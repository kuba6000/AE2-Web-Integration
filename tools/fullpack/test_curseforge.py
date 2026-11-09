"""Public CLI integration: an exported pack becomes a verified complete installation."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
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


HERE = Path(__file__).resolve().parent


class CurseForgeCliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.routes = {}
        self.requests = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests.append((self.path, self.headers.get('x-api-key')))
                data = owner.routes.get(self.path)
                if isinstance(data, tuple):
                    self.send_response(302)
                    self.send_header('Location', data[1])
                    self.end_headers()
                    return
                self.send_response(200 if data is not None else 404)
                self.end_headers()
                self.wfile.write(data or b'absent')

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.routes['/mod.jar'] = b'fixture mod bytes'
        self.routes['/v1/mods/42'] = json.dumps({'data': {'id': 42, 'classId': 6}}).encode()
        self.routes['/v1/mods/42/files/99'] = json.dumps({'data': {
            'id': 99, 'modId': 42, 'fileName': 'example.jar',
            'downloadUrl': self.base + '/mod.jar',
            'hashes': [{'algo': 1, 'value': hashlib.sha1(b'fixture mod bytes').hexdigest()}]
        }}).encode()
        self.pack()

    def pack(self, extra_files=()):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as z:
            z.writestr('manifest.json', json.dumps({
                'manifestType': 'minecraftModpack', 'manifestVersion': 1,
                'name': 'Fixture', 'version': 'pinned',
                'minecraft': {'version': '1.20.1', 'modLoaders': [{'id': 'forge-47.4.0', 'primary': True}]},
                'overrides': 'overrides',
                'files': [{'projectID': 42, 'fileID': 99, 'required': True}, *extra_files]
            }))
            z.writestr('overrides/config/game.cfg', 'keep this config')
            z.writestr('overrides/kubejs/server_scripts/recipe.js', 'keep this recipe')
        self.routes['/pack.zip'] = data.getvalue()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def pin(self, *extra):
        env = dict(os.environ, CURSEFORGE_API_KEY='production-secret', CURSEFORGE_TEST_API_KEY='must-not-leak')
        return subprocess.run([sys.executable, str(HERE / 'curseforge.py'),
            '--archive', self.base + '/pack.zip', '--api-base', self.base,
            '--output', str(self.root / 'pack.lock.json'), '--cache', str(self.root / 'cache'),
            *extra], env=env, capture_output=True, text=True)

    def test_pinned_client_preserves_overrides_and_never_sends_key_to_downloads(self):
        result = self.pin()
        self.assertEqual(result.returncode, 0, result.stderr)
        prepared = self.root / 'instance'
        result = subprocess.run([sys.executable, str(HERE / 'fullpack.py'), 'prepare',
            '--lock', str(self.root / 'pack.lock.json'), '--output', str(prepared),
            '--cache', str(self.root / 'cache')], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((prepared / 'mods/example.jar').read_bytes(), b'fixture mod bytes')
        self.assertEqual((prepared / 'config/game.cfg').read_text(), 'keep this config')
        self.assertEqual((prepared / 'kubejs/server_scripts/recipe.js').read_text(), 'keep this recipe')
        self.assertTrue(any(key == 'must-not-leak' for path, key in self.requests if '/v1/' in path))
        self.assertTrue(all(key is None for path, key in self.requests if '/v1/' not in path))
        self.assertFalse(any(key == 'production-secret' for _, key in self.requests))
        self.assertNotIn('must-not-leak', (self.root / 'pack.lock.json').read_text())

    def test_lock_records_original_pack_and_explicit_optional_selection(self):
        self.pack([{'projectID': 23, 'fileID': 24, 'required': False}])
        result = self.pin()
        self.assertEqual(result.returncode, 0, result.stderr)
        lock = json.loads((self.root / 'pack.lock.json').read_text())
        self.assertEqual(lock['source'], self.base + '/pack.zip')
        self.assertEqual(lock['provider']['optionalMods'], 'required-only')
        self.assertEqual(lock['provider']['minecraft']['version'], '1.20.1')
        self.assertFalse(any('/mods/23/' in path for path, _ in self.requests))

    def test_unavailable_mod_reports_exact_ids_without_partial_lock(self):
        metadata = json.loads(self.routes['/v1/mods/42/files/99'])
        metadata['data']['downloadUrl'] = None
        self.routes['/v1/mods/42/files/99'] = json.dumps(metadata).encode()
        result = self.pin()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('42', result.stderr)
        self.assertIn('99', result.stderr)
        self.assertFalse((self.root / 'pack.lock.json').exists())

    def test_corrupt_download_cannot_be_locked(self):
        self.routes['/mod.jar'] = b'wrong contents'
        result = self.pin()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'pack.lock.json').exists())

    def test_api_redirect_never_forwards_credentials(self):
        self.routes['/v1/mods/42/files/99'] = ('redirect', self.base + '/unexpected')
        self.routes['/unexpected'] = b'{}'
        result = self.pin()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(path == '/unexpected' for path, _ in self.requests))
        self.assertNotIn('must-not-leak', result.stderr)
        self.assertFalse((self.root / 'pack.lock.json').exists())

    def test_explicit_keyless_source_preserves_file_identity_and_never_sends_credentials(self):
        result = self.pin('--metadata-source', 'curse-tools')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(any(path == '/v1/mods/42/files/99' for path, _ in self.requests))
        self.assertTrue(all(key is None for _, key in self.requests))
        lock = json.loads((self.root / 'pack.lock.json').read_text())
        self.assertEqual(lock['provider']['metadataSource'], 'curse-tools')
        self.assertEqual(lock['provider']['metadataApi'], self.base)
        self.assertEqual(lock['manifest']['files'][0]['sha1'],
                         hashlib.sha1(b'fixture mod bytes').hexdigest())

    def test_keyless_source_does_not_bypass_missing_downloads_or_wrong_ids(self):
        original = json.loads(self.routes['/v1/mods/42/files/99'])
        for replacement in ({'downloadUrl': None}, {'id': 100}):
            with self.subTest(replacement=replacement):
                metadata = json.loads(json.dumps(original))
                metadata['data'].update(replacement)
                self.routes['/v1/mods/42/files/99'] = json.dumps(metadata).encode()
                result = self.pin('--metadata-source', 'curse-tools')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / 'pack.lock.json').exists())
                self.assertTrue(all(key is None for _, key in self.requests))

    def test_resourcepack_is_installed_by_project_class_and_ids_are_preserved(self):
        self.routes['/v1/mods/42'] = json.dumps({'data': {'id': 42, 'classId': 12}}).encode()
        metadata = json.loads(self.routes['/v1/mods/42/files/99'])
        metadata['data']['fileName'] = 'textures.zip'
        self.routes['/v1/mods/42/files/99'] = json.dumps(metadata).encode()
        result = self.pin('--metadata-source', 'curse-tools')
        self.assertEqual(result.returncode, 0, result.stderr)
        lock = json.loads((self.root / 'pack.lock.json').read_text())
        self.assertEqual(lock['provider']['files'], [{'projectID': 42, 'fileID': 99,
            'classId': 12, 'path': 'resourcepacks/textures.zip',
            'sha1': hashlib.sha1(b'fixture mod bytes').hexdigest()}])
        prepared = self.root / 'instance'
        result = subprocess.run([sys.executable, str(HERE / 'fullpack.py'), 'prepare',
            '--lock', str(self.root / 'pack.lock.json'), '--output', str(prepared),
            '--cache', str(self.root / 'cache')], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((prepared / 'resourcepacks/textures.zip').read_bytes(), b'fixture mod bytes')
        self.assertFalse((prepared / 'mods/textures.zip').exists())
        self.assertEqual((prepared / 'config/game.cfg').read_text(), 'keep this config')

    def test_unknown_project_class_and_mismatched_project_are_not_guessed(self):
        for project in ({'id': 42, 'classId': 17}, {'id': 43, 'classId': 6}):
            with self.subTest(project=project):
                self.routes['/v1/mods/42'] = json.dumps({'data': project}).encode()
                result = self.pin('--metadata-source', 'curse-tools')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / 'pack.lock.json').exists())


if __name__ == '__main__':
    unittest.main()
