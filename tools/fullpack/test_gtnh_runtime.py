import hashlib
import json
import http.server
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile


SCRIPT = Path(__file__).with_name('run_fullpack.py')


class GtnhRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game = self.root / 'game'
        self.game.mkdir()
        self.java = self.root / 'jdk/bin/java'
        self.java.parent.mkdir(parents=True)
        self.java.touch()
        (self.java.parent.parent / 'release').write_text('JAVA_VERSION="17.0.1"\n')
        self.patch = self.game / 'lwjgl3ify-forgePatches.jar'
        with zipfile.ZipFile(self.patch, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n'
                         'Implementation-Version: 3.0.42\r\n'
                         'Class-Path: libraries/dependency.jar minecraft_server.1.7.10.jar\r\n\r\n'
                         'Name: bundled/library/\r\nImplementation-Version: 9.10.1\r\n')
        (self.game / 'libraries').mkdir()
        (self.game / 'libraries/dependency.jar').write_bytes(b'pinned library')
        (self.game / 'minecraft_server.1.7.10.jar').write_bytes(b'pinned vanilla')
        (self.game / 'java9args.txt').write_text('-Dfile.encoding=UTF-8\n')
        self.runtime = self.root / 'runtime.json'
        self.runtime.write_text(json.dumps({'kind': 'gtnh', 'minecraft': '1.7.10',
            'loader': '10.13.4.1614', 'java': 17, 'protocol': 5, 'lwjgl3ify': '3.0.42',
            'forgePatchesSha1': hashlib.sha1(self.patch.read_bytes()).hexdigest()}))

    def command(self):
        archive = self.root / 'pack.zip'
        with zipfile.ZipFile(archive, 'w') as zipped:
            for path in self.game.rglob('*'):
                if path.is_file():
                    zipped.write(path, path.relative_to(self.game).as_posix())
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        cache = self.root / 'cache'
        cache.mkdir(exist_ok=True)
        (cache / digest).write_bytes(archive.read_bytes())
        lock = self.root / 'pack.lock.json'
        lock.write_text(json.dumps({'lockVersion': 1, 'manifest': {'version': 1, 'archives': [
            {'url': 'https://fixture.invalid/server.zip', 'sha256': digest}]}}))
        return [sys.executable, str(SCRIPT), 'prepare', '--role', 'server', '--profile', 'fixture',
                '--lock', str(lock), '--cache', str(cache), '--runtime-root', str(self.root / 'runtimes'),
                '--runtime-cache', str(self.root / 'minecraft'), '--java', str(self.java),
                '--runtime-config', str(self.runtime), '--memory-mib', '4096', '--accept-eula']

    def test_server_plan_uses_pinned_bundled_java17_bootstrap(self):
        result = subprocess.run(self.command(), capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads((self.root / 'runtimes/fixture/server/launch.json').read_text())
        self.assertIn('-Xmx4096M', plan['command'])
        self.assertIn('@java9args.txt', plan['command'])
        self.assertIn('lwjgl3ify-forgePatches.jar', plan['command'])
        self.assertEqual('nogui', plan['command'][-1])

    def test_missing_bootstrap_dependency_refuses_launch_plan(self):
        (self.game / 'libraries/dependency.jar').unlink()
        result = subprocess.run(self.command(), capture_output=True, text=True, timeout=15)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'runtimes/fixture/server/launch.json').exists())
        self.assertIn('libraries/dependency.jar', result.stderr)

    def test_overlay_removes_active_version_mod_but_keeps_other_version_folders(self):
        def mod(path, identity):
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, 'w') as jar:
                jar.writestr('mcmod.info', json.dumps([{'modid': identity}]))
        mod(self.game / 'mods/1.7.10/old-web.jar', 'ae2webintegration')
        mod(self.game / 'mods/1.7.10/addon.jar', 'another-addon')
        mod(self.game / 'mods/1.12.2/unrelated-version.jar', 'ae2webintegration')
        replacement = self.root / 'replacement.jar'
        mod(replacement, 'ae2webintegration')
        result = subprocess.run(self.command() + ['--mod-jar', str(replacement)],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        mods = self.root / 'runtimes/fixture/server/game/mods'
        self.assertFalse((mods / '1.7.10/old-web.jar').exists())
        self.assertTrue((mods / '1.7.10/addon.jar').is_file())
        self.assertTrue((mods / '1.12.2/unrelated-version.jar').is_file())
        self.assertEqual(replacement.read_bytes(), (mods / 'ae2webintegration-local.jar').read_bytes())

    def client_fixture(self, hold_library=False):
        routes = {}
        started = threading.Event()
        release = threading.Event()

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                data = routes.get(self.path)
                self.send_response(200 if data is not None else 404)
                self.end_headers()
                if hold_library and self.path == '/bootstrap.jar':
                    started.set()
                    release.wait(20)
                if data is not None:
                    self.wfile.write(data)

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = 'http://127.0.0.1:' + str(server.server_port)
        routes['/client.jar'] = self.patch.read_bytes()
        routes['/bootstrap.jar'] = self.patch.read_bytes()
        routes['/assets.json'] = b'{"objects": {}}'

        def download(path):
            return {'url': base + path, 'sha1': hashlib.sha1(routes[path]).hexdigest(), 'size': len(routes[path])}

        metadata = {'id': '1.7.10-Forge10.13.4.1614-1.7.10-lwjgl3ify-3.0.42', 'type': 'release',
                    'mainClass': 'com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread',
                    'libraries': [{'name': 'fixture:bootstrap:1.0', 'url': base,
                                   'downloads': {'artifact': download('/bootstrap.jar')}}],
                    'arguments': {'jvm': ['-cp', '${classpath}'],
                                                 'game': ['--username', '${auth_player_name}']},
                    'assetIndex': {'id': 'fixture', **download('/assets.json')}, 'assets': 'fixture',
                    'downloads': {'client': download('/client.jar')}}
        routes['/metadata.json'] = json.dumps(metadata).encode()
        runtime = json.loads(self.runtime.read_text())
        runtime.update(clientMetadataUrl=base + '/metadata.json',
                       clientMetadataSha256=hashlib.sha256(routes['/metadata.json']).hexdigest())
        self.runtime.write_text(json.dumps(runtime))
        client_patch = self.game / '.gtnh/launcher/lwjgl3ify-forgePatches.jar'
        client_patch.parent.mkdir(parents=True)
        client_patch.write_bytes(self.patch.read_bytes())
        command = self.command()
        command[command.index('--role') + 1] = 'client'
        command.extend(['--offline-user', 'FullpackTest', '--join-server', '127.0.0.1:25585'])
        return command, started, release

    def test_client_prepares_from_pinned_launcher_metadata_without_prism(self):
        command, _, _ = self.client_fixture()
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        slot = self.root / 'runtimes/fixture/client'
        plan = json.loads((slot / 'launch.json').read_text())
        arguments = (slot / 'game/fullpack-java.args').read_text()
        self.assertEqual('127.0.0.1:25585', plan['joinServer'])
        self.assertIn('MainStartOnFirstThread', arguments)
        self.assertIn('"-Xmx4096M"', arguments)
        self.assertIn('"--server"', arguments)
        self.assertIn('"127.0.0.1"', arguments)

    def test_installer_reports_current_library_before_its_download_finishes(self):
        command, started, release = self.client_fixture(hold_library=True)
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        reported = threading.Event()
        messages = []

        def read_output():
            for line in process.stdout:
                messages.append(line)
                if 'bootstrap-1.0.jar' in line:
                    reported.set()

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        try:
            self.assertTrue(started.wait(8), 'Installer did not request the library')
            self.assertTrue(reported.wait(12), 'Installer progress was hidden while the download waited')
            self.assertIsNone(process.poll())
        finally:
            release.set()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=2)
            process.stdout.close()
        self.assertEqual(0, process.returncode, ''.join(messages))


if __name__ == '__main__':
    unittest.main()
