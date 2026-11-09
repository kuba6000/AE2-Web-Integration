import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile


SCRIPT = Path(__file__).with_name('fullpack.py')


class AcquisitionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.responses = {}
        self.requests = []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                outer.requests.append(self.path)
                body = outer.responses.get(self.path)
                self.send_response(200 if body is not None else 404)
                if self.path == '/truncated':
                    self.send_header('Content-Length', '100')
                self.end_headers()
                if body is not None:
                    self.wfile.write(body)

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.url = 'http://127.0.0.1:' + str(server.server_port)

    def cli(self, *args, success=True):
        result = subprocess.run([sys.executable, str(SCRIPT), *args,
                                 '--cache', str(self.root / 'cache')],
                                capture_output=True, text=True, timeout=20)
        if success:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
        return result

    def manifest(self, **kwargs):
        data = dict(version=1, files=[], archives=[], textFiles={})
        data.update(kwargs)
        self.responses['/daily.json'] = json.dumps(data).encode()
        return self.url + '/daily.json'

    def archive(self, entries):
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as archive:
            for name, body in entries.items():
                archive.writestr(name, body)
        self.responses['/pack.zip'] = out.getvalue()
        return self.url + '/pack.zip'

    def test_locked_daily_restores_scripts_config_and_mods_without_network(self):
        archive = self.archive({'config/test.cfg': 'original', 'scripts/recipe.zs': 'recipe'})
        self.responses['/mod.jar'] = b'mod binary'
        source = self.manifest(files=[{'path': 'mods/test.jar', 'url': self.url + '/mod.jar'}],
                               archives=[{'url': archive}],
                               textFiles={'config/test.cfg': 'daily override'})
        lock = self.root / 'daily.lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        self.responses.clear()
        self.requests.clear()
        output = self.root / 'prepared'
        self.cli('prepare', '--lock', str(lock), '--output', str(output))
        self.assertEqual('daily override', (output / 'config/test.cfg').read_text())
        self.assertEqual('recipe', (output / 'scripts/recipe.zs').read_text())
        self.assertEqual(b'mod binary', (output / 'mods/test.jar').read_bytes())
        self.assertEqual([], self.requests)

    def test_archive_overlays_preserve_existing_and_exclude_trees(self):
        archive = self.archive({'overrides/config/x': 'archive', 'overrides/scripts/x': 'recipe',
                                'overrides/skip/child': 'excluded', 'elsewhere': 'irrelevant'})
        self.responses['/config'] = b'explicit'
        source = self.manifest(files=[{'path': 'config/x', 'url': self.url + '/config'}],
                               archives=[{'url': archive, 'stripPrefix': 'overrides',
                                          'keepExisting': True, 'exclude': ['skip']}])
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        output = self.root / 'prepared'
        self.cli('prepare', '--lock', str(lock), '--output', str(output))
        self.assertEqual('explicit', (output / 'config/x').read_text())
        self.assertEqual('recipe', (output / 'scripts/x').read_text())
        self.assertFalse((output / 'skip').exists())
        self.assertFalse((output / 'elsewhere').exists())

    def test_upstream_hash_mismatch_does_not_publish_lock(self):
        self.responses['/mod'] = b'changed artifact'
        source = self.manifest(files=[{'path': 'mods/a.jar', 'url': self.url + '/mod',
                                      'sha1': hashlib.sha1(b'expected artifact').hexdigest()}])
        output = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(output), success=False)
        self.assertFalse(output.exists())

    def test_truncated_response_cannot_be_locked_as_a_complete_mod(self):
        self.responses['/truncated'] = b'cut'
        source = self.manifest(files=[{'path': 'mods/truncated.jar', 'url': self.url + '/truncated'}])
        output = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(output), success=False)
        self.assertFalse(output.exists())

    def test_text_overrides_preserve_line_endings(self):
        source = self.manifest(textFiles={'start.sh': '#!/bin/sh\necho ready\n'})
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        output = self.root / 'prepared'
        self.cli('prepare', '--lock', str(lock), '--output', str(output))
        self.assertEqual(b'#!/bin/sh\necho ready\n', (output / 'start.sh').read_bytes())

    def test_archive_cannot_escape_output_and_failed_prepare_leaves_no_output(self):
        source = self.manifest(archives=[{'url': self.archive({'../escaped': 'bad'})}])
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        output = self.root / 'prepared'
        self.cli('prepare', '--lock', str(lock), '--output', str(output), success=False)
        self.assertFalse(output.exists())
        self.assertFalse((self.root / 'escaped').exists())

    def test_failed_lock_preserves_previous_pin_and_names_missing_artifact(self):
        output = self.root / 'lock.json'
        output.write_text('previous pin')
        source = self.manifest(files=[{'path': 'mods/missing.jar', 'url': self.url + '/missing'}])
        result = self.cli('lock', '--manifest', source, '--output', str(output), success=False)
        self.assertEqual('previous pin', output.read_text())
        self.assertIn('mods/missing.jar', result.stderr)

    def test_failed_download_does_not_continue_entire_queued_pack(self):
        files = [{'path': 'mods/missing.jar', 'url': self.url + '/missing'}]
        for index in range(100):
            route = '/queued-' + str(index)
            self.responses[route] = ('queued mod ' + str(index)).encode()
            files.append({'path': 'mods/queued-' + str(index) + '.jar', 'url': self.url + route})
        source = self.manifest(files=files)
        output = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(output), success=False)
        self.assertFalse(output.exists())
        self.assertLess(sum(path.startswith('/queued-') for path in self.requests), 100)

    def test_corrupt_cache_and_changed_remote_cannot_prepare_locked_artifact(self):
        self.responses['/mod'] = b'original'
        source = self.manifest(files=[{'path': 'mods/a.jar', 'url': self.url + '/mod'}])
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        cached = self.root / 'cache' / hashlib.sha256(b'original').hexdigest()
        cached.write_bytes(b'corrupted')
        self.responses['/mod'] = b'new remote version'
        output = self.root / 'prepared'
        self.cli('prepare', '--lock', str(lock), '--output', str(output), success=False)
        self.assertFalse(output.exists())

    def test_archive_case_collisions_and_symlinks_are_rejected(self):
        for entries in ({'Config/value': 'one', 'config/value': 'two'},
                        {'config/file': 'one', 'CONFIG/file/child': 'two'},
                        {'config/dir/child': 'one', 'CONFIG/dir': 'two'},
                        {'config/aux.txt': 'reserved'}):
            source = self.manifest(archives=[{'url': self.archive(entries)}])
            lock = self.root / 'lock.json'
            self.cli('lock', '--manifest', source, '--output', str(lock))
            self.cli('prepare', '--lock', str(lock), '--output', str(self.root / 'prepared'), success=False)
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            info = zipfile.ZipInfo('linked')
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            archive.writestr(info, '../outside')
        self.responses['/pack.zip'] = buffer.getvalue()
        source = self.manifest(archives=[{'url': self.url + '/pack.zip'}])
        self.cli('lock', '--manifest', source, '--output', str(lock))
        self.cli('prepare', '--lock', str(lock), '--output', str(self.root / 'prepared'), success=False)

    def test_existing_output_is_never_modified(self):
        source = self.manifest(textFiles={'config/x': 'new'})
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        output = self.root / 'prepared'
        output.mkdir()
        (output / 'keep').write_text('user file')
        self.cli('prepare', '--lock', str(lock), '--output', str(output), success=False)
        self.assertEqual('user file', (output / 'keep').read_text())
        self.assertFalse((output / 'config').exists())

    def test_translation_archives_preserve_distinct_files_under_directory_case_aliases(self):
        first = 'config/Thaumcraft Mob Aspects/lang/de_DE.lang'
        second = 'config/Thaumcraft mob aspects/lang/fr_FR.lang'
        self.archive({first: 'German'})
        self.responses['/german.zip'] = self.responses['/pack.zip']
        self.archive({second: 'French'})
        source = self.manifest(archives=[{'url': self.url + '/german.zip', 'keepExisting': True},
                                        {'url': self.url + '/pack.zip', 'keepExisting': True}])
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        output = self.root / 'prepared'
        self.cli('prepare', '--lock', str(lock), '--output', str(output))
        self.assertEqual('German', (output / first).read_text())
        self.assertEqual('French', (output / second).read_text())

    def test_duplicate_mod_destinations_cannot_silently_drop_a_mod(self):
        self.responses['/one'] = b'one'
        self.responses['/two'] = b'two'
        source = self.manifest(files=[{'path': 'mods/a.jar', 'url': self.url + '/one'},
                                      {'path': 'mods/a.jar', 'url': self.url + '/two'}])
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock), success=False)
        self.assertFalse(lock.exists())

    @unittest.skipIf(os.name == 'nt', 'POSIX executable permission contract')
    def test_server_start_script_remains_executable_without_special_bits(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            info = zipfile.ZipInfo('start.sh')
            info.create_system = 3
            info.external_attr = 0o104755 << 16
            archive.writestr(info, '#!/bin/sh\n')
        self.responses['/pack.zip'] = buffer.getvalue()
        source = self.manifest(archives=[{'url': self.url + '/pack.zip'}])
        lock = self.root / 'lock.json'
        self.cli('lock', '--manifest', source, '--output', str(lock))
        output = self.root / 'prepared'
        self.cli('prepare', '--lock', str(lock), '--output', str(output))
        mode = (output / 'start.sh').stat().st_mode
        self.assertTrue(mode & 0o100)
        self.assertFalse(mode & 0o7000)


if __name__ == '__main__':
    unittest.main()
