import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).with_name('run_fullpack.py')


class MinecraftRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.java = self.root / 'jdk/bin/java'
        self.java.parent.mkdir(parents=True)
        self.java.touch()
        (self.java.parent.parent / 'release').write_text('JAVA_VERSION="1.8.0_442"\n')
        self.runtime = self.root / 'runtime.json'
        self.runtime.write_text(json.dumps({'kind': 'forge', 'minecraft': '1.12.2',
            'loader': '14.23.5.2860', 'java': 8, 'protocol': 340}))
        self.game = self.root / 'pack'
        self.game.mkdir()
        with zipfile.ZipFile(self.game / 'forge-1.12.2-14.23.5.2860.jar', 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n'
                         'Main-Class: net.minecraftforge.fml.relauncher.ServerLaunchWrapper\r\n'
                         'Class-Path: libraries/dependency.jar minecraft_server.1.12.2.jar\r\n\r\n')
        (self.game / 'libraries').mkdir()
        (self.game / 'libraries/dependency.jar').write_bytes(b'pinned library')
        (self.game / 'minecraft_server.1.12.2.jar').write_bytes(b'pinned vanilla')

    def prepare(self):
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
        return subprocess.run([sys.executable, str(SCRIPT), 'prepare', '--role', 'server',
            '--profile', 'fixture', '--lock', str(lock), '--cache', str(cache),
            '--runtime-root', str(self.root / 'runtimes'), '--runtime-cache', str(self.root / 'minecraft'),
            '--java', str(self.java), '--runtime-config', str(self.runtime), '--memory-mib', '2048',
            '--accept-eula'], capture_output=True, text=True, timeout=15)

    def test_legacy_server_uses_complete_bundled_bootstrap_without_installer(self):
        result = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads((self.root / 'runtimes/fixture/server/launch.json').read_text())
        self.assertIn('-Xmx2048M', plan['command'])
        self.assertEqual(['-jar', 'forge-1.12.2-14.23.5.2860.jar', 'nogui'], plan['command'][-3:])
        self.assertFalse(any(argument.startswith('@') for argument in plan['command']))

    def test_missing_bundled_library_refuses_a_launch_plan(self):
        (self.game / 'libraries/dependency.jar').unlink()
        result = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'runtimes/fixture/server/launch.json').exists())

    def test_wrong_java_preserves_existing_instance_before_reset(self):
        result = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        sentinel = self.root / 'runtimes/fixture/server/game/world/player-data'
        sentinel.parent.mkdir()
        sentinel.write_text('saved player progress')
        (self.java.parent.parent / 'release').write_text('JAVA_VERSION="21.0.1"\n')
        result = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('saved player progress', sentinel.read_text())


if __name__ == '__main__':
    unittest.main()
