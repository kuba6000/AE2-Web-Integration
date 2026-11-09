import json
import hashlib
import io
import os
from pathlib import Path
import subprocess
import socketserver
import sys
import tempfile
import time
import threading
import unittest
import zipfile


SCRIPT = Path(__file__).with_name('run_fullpack.py')


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def plan(self, code):
        path = self.root / 'launch.json'
        path.write_text(json.dumps({'command': [sys.executable, '-c', code]}))
        return path

    def command(self, plan, *extra):
        return [sys.executable, str(SCRIPT), 'execute', '--plan', str(plan),
                '--runtime-root', str(self.root / 'runtimes'), '--profile', 'fixture',
                '--role', 'server', '--accept-eula', *extra]

    def status_fixture(self, protocol=763):
        ready = threading.Event()
        queried = threading.Event()

        def varint(value):
            result = bytearray()
            while value > 127:
                result.append((value & 127) | 128)
                value >>= 7
            result.append(value)
            return bytes(result)

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                self.connection.settimeout(5)
                length, shift = 0, 0
                while True:
                    byte = self.rfile.read(1)[0]
                    length |= (byte & 127) << shift
                    if not byte & 128:
                        break
                    shift += 7
                handshake = self.rfile.read(length)
                if handshake[-1:] != b'\x01' or self.rfile.read(2) != b'\x01\x00':
                    return
                if not handshake.startswith(b'\x00' + varint(protocol)):
                    return
                queried.set()
                value = {'version': {'name': 'fixture', 'protocol': protocol}, 'players': {'online': 0, 'max': 20}} if ready.is_set() else {'text': 'Server is still starting'}
                data = json.dumps(value).encode()
                payload = b'\x00' + varint(len(data)) + data
                self.wfile.write(varint(len(payload)) + payload)

        server = socketserver.ThreadingTCPServer(('127.0.0.1', 0), Handler)
        server.daemon_threads = True
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return '127.0.0.1:' + str(server.server_address[1]), ready, queried

    def test_client_waits_for_real_status_not_startup_disconnect(self):
        address, ready, queried = self.status_fixture()
        plan = self.plan("from pathlib import Path; Path('started').touch()")
        payload = json.loads(plan.read_text())
        payload['joinServer'] = address
        payload['role'] = 'client'
        payload['protocol'] = 763
        plan.write_text(json.dumps(payload))
        command = self.command(plan, '--server-timeout', '5')
        command[command.index('--role') + 1] = 'client'
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertTrue(queried.wait(3), 'Launcher did not request server status')
            marker = self.root / 'runtimes/fixture/client/game/started'
            self.assertFalse(marker.exists())
            ready.set()
            output, error = process.communicate(timeout=10)
            self.assertEqual(0, process.returncode, error)
            self.assertTrue(marker.exists())
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate()

    def test_status_timeout_does_not_launch_client(self):
        address, _, _ = self.status_fixture()
        command = self.command(self.plan("from pathlib import Path; Path('started').touch()"),
                               '--join-server', address, '--server-timeout', '0.2', '--protocol', '763')
        command[command.index('--role') + 1] = 'client'
        result = subprocess.run(command, capture_output=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'runtimes/fixture/client/game/started').exists())

    def test_client_uses_selected_legacy_protocol_before_joining(self):
        address, ready, _ = self.status_fixture(protocol=5)
        ready.set()
        command = self.command(self.plan("from pathlib import Path; Path('started').touch()"),
                               '--join-server', address, '--server-timeout', '2', '--protocol', '5')
        command[command.index('--role') + 1] = 'client'
        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / 'runtimes/fixture/client/game/started').exists())

    def test_execute_runs_owned_slot_and_propagates_child_exit(self):
        plan = self.plan("from pathlib import Path; Path('result').write_text('ran'); raise SystemExit(7)")
        result = subprocess.run(self.command(plan), capture_output=True, text=True, timeout=15)
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual('ran', (self.root / 'runtimes/fixture/server/game/result').read_text())

    def test_foreign_slot_is_not_used_or_modified(self):
        slot = self.root / 'runtimes/fixture/server'
        slot.mkdir(parents=True)
        (slot / 'user-file').write_text('keep')
        result = subprocess.run(self.command(self.plan("raise SystemExit(0)")), capture_output=True, timeout=15)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('keep', (slot / 'user-file').read_text())
        self.assertFalse((slot / 'game').exists())

    def test_deadline_requests_graceful_server_stop(self):
        code = "from pathlib import Path; import sys; Path('stop').write_text(sys.stdin.readline().strip())"
        result = subprocess.run(self.command(self.plan(code), '--max-seconds', '0.3'), capture_output=True, timeout=15)
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertEqual('stop', (self.root / 'runtimes/fixture/server/game/stop').read_text())

    def test_unresponsive_server_is_terminated_after_grace_period(self):
        code = "from pathlib import Path; import sys,time; Path('stop').write_text(sys.stdin.readline().strip()); time.sleep(60)"
        result = subprocess.run(self.command(self.plan(code), '--max-seconds', '0.3', '--stop-timeout', '0.2'),
                                capture_output=True, timeout=15)
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertEqual('stop', (self.root / 'runtimes/fixture/server/game/stop').read_text())

    def test_active_role_is_exclusive_but_other_role_can_run(self):
        plan = self.plan("from pathlib import Path; import sys; Path('ready').touch(); sys.stdin.readline()")
        parent = subprocess.Popen(self.command(plan), stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, text=True)
        try:
            ready = self.root / 'runtimes/fixture/server/game/ready'
            deadline = time.monotonic() + 5
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists())
            simple = self.plan('raise SystemExit(0)')
            competing = subprocess.run(self.command(simple), capture_output=True, timeout=10)
            self.assertNotEqual(0, competing.returncode)
            client = self.command(simple)
            client[client.index('--role') + 1] = 'client'
            other = subprocess.run(client, capture_output=True, timeout=10)
            self.assertEqual(0, other.returncode, other.stderr)
            parent.communicate('stop\n', timeout=10)
            self.assertEqual(0, parent.returncode)
            retry = subprocess.run(self.command(simple), capture_output=True, timeout=10)
            self.assertEqual(0, retry.returncode, retry.stderr)
        finally:
            if parent.poll() is None:
                parent.kill()
                parent.wait()

    def test_fresh_prepare_resets_only_owned_game_and_copies_world(self):
        lock = self.root / 'pack.lock.json'
        lock.write_text(json.dumps({'lockVersion': 1, 'manifest': {'version': 1,
                                       'textFiles': {'config/test.cfg': 'original',
                                           'server.properties': 'white-list=true\nenforce-whitelist=true\nonline-mode=true\nmotd=Fixture\n'}}}))
        world = self.root / 'fixture-world'
        world.mkdir()
        (world / 'level.dat').write_bytes(b'fixture world')
        command = [sys.executable, str(SCRIPT), 'prepare', '--lock', str(lock), '--content-only',
                   '--cache', str(self.root / 'cache'), '--runtime-root', str(self.root / 'runtimes'),
                   '--profile', 'fixture', '--role', 'server', '--world', str(world), '--accept-eula']
        for index in range(2):
            result = subprocess.run(command, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            game = self.root / 'runtimes/fixture/server/game'
            self.assertFalse((game / 'old-world').exists())
            self.assertEqual(b'fixture world', (game / 'world/level.dat').read_bytes())
            self.assertIn('server-ip=127.0.0.1', (game / 'server.properties').read_text())
            properties = dict(line.split('=', 1) for line in (game / 'server.properties').read_text().splitlines())
            self.assertEqual('false', properties['white-list'])
            self.assertEqual('false', properties['enforce-whitelist'])
            self.assertEqual('false', properties['online-mode'])
            self.assertEqual('Fixture', properties['motd'])
            (game / 'old-world').write_text('discard next run')
        self.assertEqual(b'fixture world', (world / 'level.dat').read_bytes())

    def test_client_is_muted_on_prepare_and_persistent_launch(self):
        lock = self.root / 'pack.lock.json'
        lock.write_text(json.dumps({'lockVersion': 1, 'manifest': {'version': 1,
            'textFiles': {'options.txt': 'soundCategory_master:0.8\nsoundCategory_music:0.4\nfov:0.5\n'}}}))
        command = [sys.executable, str(SCRIPT), 'prepare', '--lock', str(lock), '--content-only',
                   '--cache', str(self.root / 'cache'), '--runtime-root', str(self.root / 'runtimes'),
                   '--profile', 'fixture', '--role', 'client', '--accept-eula']
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        options = self.root / 'runtimes/fixture/client/game/options.txt'
        settings = dict(line.split(':', 1) for line in options.read_text().splitlines())
        self.assertEqual(0, float(settings['soundCategory_master']))
        self.assertEqual('0.4', settings['soundCategory_music'])
        self.assertEqual('0.5', settings['fov'])
        options.write_text('soundCategory_master:1.0\nfov:0.7\n')
        plan = self.plan("from pathlib import Path; import json; Path('observed.json').write_text(json.dumps(dict(line.split(':',1) for line in Path('options.txt').read_text().splitlines())))")
        command = self.command(plan)
        command[command.index('--role') + 1] = 'client'
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        observed = json.loads((options.parent / 'observed.json').read_text())
        self.assertEqual(0, float(observed['soundCategory_master']))
        self.assertEqual('0.7', observed['fov'])

    def test_killed_launcher_cannot_leave_an_unprotected_running_game(self):
        code = "import os,time; from pathlib import Path; Path('pid').write_text(str(os.getpid())); time.sleep(60)"
        parent = subprocess.Popen(self.command(self.plan(code)), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        pid_file = self.root / 'runtimes/fixture/server/game/pid'
        try:
            deadline = time.monotonic() + 5
            while not pid_file.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(pid_file.exists())
            pid = int(pid_file.read_text())
            parent.kill()
            parent.wait(timeout=5)
            if os.name == 'nt':
                import ctypes
                from ctypes import wintypes
                kernel = ctypes.WinDLL('kernel32', use_last_error=True)
                kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
                kernel.OpenProcess.restype = wintypes.HANDLE
                kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
                kernel.CloseHandle.argtypes = [wintypes.HANDLE]
                handle = kernel.OpenProcess(0x100000, False, pid)
                if handle:
                    try:
                        self.assertEqual(0, kernel.WaitForSingleObject(handle, 2000), 'Orphan game survived launcher death')
                    finally:
                        kernel.CloseHandle(handle)
            else:
                command = self.command(self.plan('raise SystemExit(0)'))
                result = subprocess.run(command, capture_output=True, timeout=5)
                self.assertNotEqual(0, result.returncode, 'Live child lost exclusive slot ownership')
        finally:
            if parent.poll() is None:
                parent.kill()
                parent.wait()
            if pid_file.exists():
                pid = int(pid_file.read_text())
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(pid), '/T', '/F'], capture_output=True)
                else:
                    try:
                        os.kill(pid, 9)
                    except ProcessLookupError:
                        pass

    def test_legacy_mod_overlay_is_recognized_without_forge_toml(self):
        cache = self.root / 'cache'
        cache.mkdir()
        lock = self.root / 'pack.lock.json'
        lock.write_text(json.dumps({'lockVersion': 1, 'manifest': {'version': 1}}))
        replacement = self.root / 'legacy.jar'
        with zipfile.ZipFile(replacement, 'w') as archive:
            archive.writestr('mcmod.info', json.dumps([{'modid': 'ae2webintegration', 'version': 'test'}]))
        result = subprocess.run([sys.executable, str(SCRIPT), 'prepare', '--content-only',
            '--lock', str(lock), '--cache', str(cache), '--runtime-root', str(self.root / 'runtimes'),
            '--profile', 'legacy', '--role', 'server', '--mod-jar', str(replacement), '--accept-eula'],
            capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(replacement.read_bytes(),
            (self.root / 'runtimes/legacy/server/game/mods/ae2webintegration-local.jar').read_bytes())

    def test_client_replaces_main_and_generator_by_declared_mod_identity(self):
        def jar(mod_id, version):
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                archive.writestr('mcmod.info', json.dumps([{'modid': mod_id, 'version': version}]))
            return data.getvalue()
        original = io.BytesIO()
        with zipfile.ZipFile(original, 'w') as archive:
            archive.writestr('mods/renamed-main.jar', jar('ae2webintegration', 'old'))
            archive.writestr('mods/renamed-generator.jar', jar('ae2webintegration_icon_generator', 'old'))
            archive.writestr('mods/unrelated.jar', jar('other', 'keep'))
        cache = self.root / 'cache'
        cache.mkdir()
        digest = hashlib.sha256(original.getvalue()).hexdigest()
        (cache / digest).write_bytes(original.getvalue())
        lock = self.root / 'pack.lock.json'
        lock.write_text(json.dumps({'lockVersion': 1, 'manifest': {'version': 1, 'archives': [
            {'url': 'https://fixture.invalid/pack.zip', 'sha256': digest}]}}))
        main = self.root / 'main.jar'
        generator = self.root / 'generator.jar'
        main.write_bytes(jar('ae2webintegration', 'new'))
        generator.write_bytes(jar('ae2webintegration_icon_generator', 'new'))
        command = [sys.executable, str(SCRIPT), 'prepare', '--content-only', '--lock', str(lock),
            '--cache', str(cache), '--runtime-root', str(self.root / 'runtimes'), '--profile', 'fixture',
            '--role', 'client', '--mod-jar', str(main), '--icon-generator-jar', str(generator), '--accept-eula']
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        mods = self.root / 'runtimes/fixture/client/game/mods'
        self.assertEqual({'ae2webintegration-local.jar', 'ae2webintegration-icon-generator-local.jar', 'unrelated.jar'},
                         {path.name for path in mods.glob('*.jar')})
        self.assertEqual(main.read_bytes(), (mods / 'ae2webintegration-local.jar').read_bytes())
        self.assertEqual(generator.read_bytes(), (mods / 'ae2webintegration-icon-generator-local.jar').read_bytes())

    def test_mod_overlay_keeps_addons_that_only_depend_on_our_mod(self):
        def jar(metadata):
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                archive.writestr('META-INF/mods.toml', metadata)
            return data.getvalue()
        old_mod = jar('[[mods]]\nmodId="ae2webintegration"\nversion="old"\n')
        addon = jar('[[mods]]\nmodId="addon"\n[[dependencies.addon]]\nmodId="ae2webintegration"\n')
        archive_data = io.BytesIO()
        with zipfile.ZipFile(archive_data, 'w') as archive:
            archive.writestr('mods/old-name.jar', old_mod)
            archive.writestr('mods/addon.jar', addon)
        cache = self.root / 'cache'
        cache.mkdir()
        digest = hashlib.sha256(archive_data.getvalue()).hexdigest()
        (cache / digest).write_bytes(archive_data.getvalue())
        lock = self.root / 'pack.lock.json'
        lock.write_text(json.dumps({'lockVersion': 1, 'manifest': {'version': 1, 'archives': [
            {'url': 'https://fixture.invalid/pack.zip', 'sha256': digest}]}}))
        replacement = self.root / 'new.jar'
        replacement.write_bytes(jar('[[mods]]\nmodId="ae2webintegration"\nversion="new"\n'))
        result = subprocess.run([sys.executable, str(SCRIPT), 'prepare', '--content-only',
            '--lock', str(lock), '--cache', str(cache), '--runtime-root', str(self.root / 'runtimes'),
            '--profile', 'fixture', '--role', 'server', '--mod-jar', str(replacement), '--web-port', '23245', '--accept-eula'],
            capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        mods = self.root / 'runtimes/fixture/server/game/mods'
        self.assertEqual(addon, (mods / 'addon.jar').read_bytes())
        self.assertFalse((mods / 'old-name.jar').exists())
        self.assertEqual(replacement.read_bytes(), (mods / 'ae2webintegration-local.jar').read_bytes())
        try:
            import tomllib
        except ImportError:
            import tomli as tomllib
        config = tomllib.loads((mods.parent / 'config/ae2webintegration/config.toml').read_text())
        self.assertEqual(23245, config['general']['port'])
        self.assertGreaterEqual(len(config['general']['password']), 32)
        self.assertFalse(config['general']['public_mode'])
        self.assertFalse(config['general']['allow_no_password_on_localhost'])
        self.assertFalse(config['general']['check_for_updates'])
        self.assertFalse(config['telemetry']['enabled'])
        self.assertNotIn(config['general']['password'], result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
