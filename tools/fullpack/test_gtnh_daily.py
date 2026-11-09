import http.server
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('gtnh_daily.py')
ENGINE = Path(__file__).with_name('fullpack.py')


class DailyPinTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.responses = {}
        self.on_request = lambda path: None
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                outer.on_request(self.path)
                body = outer.responses.get(self.path)
                self.send_response(200 if body is not None else 404)
                self.end_headers()
                if body is not None:
                    self.wfile.write(body)

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.base = 'http://127.0.0.1:' + str(server.server_port)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as zipped:
            zipped.writestr('scripts/test.zs', 'fixed recipe')
            zipped.writestr('config/test.cfg', 'fixed config')
        self.responses['/config.zip'] = archive.getvalue()
        self.responses['/mod.jar'] = b'fixed mod'
        self.manifest = {'version': 1,
                         'files': [{'path': 'mods/test.jar', 'url': self.base + '/mod.jar',
                                    'owner': 'Example', 'maven': 'example:mod:1'}],
                         'archives': [{'url': self.base + '/config.zip'}],
                         'textFiles': {'config/DreamCoreMod.properties':
                                       'displayedModpackVersion=2.x (Daily 17) - 2026-01-02\n'}}
        self.responses['/daily.json'] = json.dumps(self.manifest).encode()
        self.responses['/daily-server.json'] = self.responses['/daily.json']

    def pin(self):
        return subprocess.run([sys.executable, str(SCRIPT), '--base-url', self.base,
                               '--output', str(self.root / 'profile'),
                               '--cache', str(self.root / 'cache')],
                              capture_output=True, text=True, timeout=20)

    def test_pin_then_offline_prepare_preserves_both_roles(self):
        result = self.pin()
        self.assertEqual(0, result.returncode, result.stderr)
        self.responses.clear()
        for role in ('client', 'server'):
            output = self.root / role
            result = subprocess.run([sys.executable, str(ENGINE), 'prepare',
                                     '--lock', str(self.root / 'profile' / (role + '.lock.json')),
                                     '--output', str(output), '--cache', str(self.root / 'cache')],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b'fixed mod', (output / 'mods/test.jar').read_bytes())
            self.assertEqual('fixed recipe', (output / 'scripts/test.zs').read_text())
            self.assertEqual('fixed config', (output / 'config/test.cfg').read_text())

    def test_inconsistent_pair_is_not_published(self):
        for mismatch in ('label', 'config', 'shared_mod'):
            with self.subTest(mismatch=mismatch):
                server = json.loads(json.dumps(self.manifest))
                if mismatch == 'label':
                    server['textFiles']['config/DreamCoreMod.properties'] = 'displayedModpackVersion=other daily'
                elif mismatch == 'config':
                    server['archives'][0]['url'] = self.base + '/other-config.zip'
                    self.responses['/other-config.zip'] = self.responses['/config.zip']
                else:
                    server['files'][0]['maven'] = 'example:mod:2'
                self.responses['/daily-server.json'] = json.dumps(server).encode()
                result = self.pin()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / 'profile').exists())

    def test_daily_change_during_download_does_not_publish_partial_profile(self):
        def move_daily(path):
            if path == '/mod.jar':
                self.responses['/daily.json'] = b'{}'
        self.on_request = move_daily
        result = self.pin()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'profile').exists())

    def test_missing_mod_does_not_publish_partial_profile(self):
        del self.responses['/mod.jar']
        result = self.pin()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'profile').exists())

    def test_existing_profile_is_never_overwritten(self):
        output = self.root / 'profile'
        output.mkdir()
        (output / 'keep.txt').write_text('user data')
        result = self.pin()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('user data', (output / 'keep.txt').read_text())


if __name__ == '__main__':
    unittest.main()
