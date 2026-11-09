"""Prepare pinned Forge and NeoForge runtimes without a launcher installation."""
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import importlib.metadata
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import urllib.request
import uuid
import zipfile

import fullpack
from process_containment import ProcessGroup

LIBRARY = '8.0'


@dataclass
class LaunchPlan:
    command: list[str]
    cwd: Path


def clean_environment():
    environment = os.environ.copy()
    for name in ('MOD_CLASSES', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'):
        environment.pop(name, None)
    return environment


def java_arguments(arguments):
    # Java launcher argument-file syntax, not shell escaping.
    if any('\n' in value or '\r' in value or '\x00' in value for value in arguments):
        raise ValueError('Unsupported control character in Java argument')
    return '\n'.join('"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
                     for value in arguments) + '\n'


def check_java(java, major=17):
    java = Path(java).resolve(strict=True)
    release = (java.parent.parent / 'release').read_text(encoding='utf-8')
    prefix = '1.8' if major == 8 else str(major)
    if not re.search(r'^JAVA_VERSION="' + re.escape(prefix) + r'(?:\.|\")', release, re.MULTILINE):
        raise ValueError('This profile requires Java ' + str(major))
    return java


def preflight(java, runtime):
    supported = {('forge', '1.12.2'): 8, ('forge', '1.20.1'): 17, ('neoforge', '1.21.1'): 21}
    major = supported.get((runtime.get('kind'), runtime.get('minecraft')))
    if major is None or runtime.get('java') != major or not re.fullmatch(r'\d+(?:\.\d+)+', runtime.get('loader', '')):
        raise ValueError('Unsupported Minecraft, loader or Java runtime profile')
    if importlib.metadata.version('minecraft-launcher-lib') != LIBRARY:
        raise ValueError('Install the pinned minecraft-launcher-lib 8.0 dependency')
    return check_java(java, major)


def bundled_legacy_server(game, java, runtime, memory_mib):
    bootstrap = game / ('forge-' + runtime['minecraft'] + '-' + runtime['loader'] + '.jar')
    with zipfile.ZipFile(bootstrap) as jar:
        main_section = jar.read('META-INF/MANIFEST.MF').decode().replace('\r', '').split('\n\n', 1)[0]
    values = dict(line.split(': ', 1) for line in main_section.replace('\n ', '').splitlines() if ': ' in line)
    if values.get('Main-Class') != 'net.minecraftforge.fml.relauncher.ServerLaunchWrapper':
        raise ValueError('Bundled Forge server has an unexpected bootstrap')
    dependencies = values.get('Class-Path', '').split()
    if not dependencies or 'minecraft_server.' + runtime['minecraft'] + '.jar' not in dependencies:
        raise ValueError('Bundled Forge server has an incomplete bootstrap classpath')
    for dependency in dependencies:
        fullpack.safe_path(dependency)
        if not (game / dependency).is_file():
            raise ValueError('Bundled Forge server dependency is missing: ' + dependency)
    return LaunchPlan([str(java), f'-Xmx{memory_mib}M', '-jar', bootstrap.name, 'nogui'], game)


def offline_options(java, game, username, memory_mib=8192):
    if not re.fullmatch(r'[A-Za-z0-9_]{1,16}', username):
        raise ValueError('Offline test username must contain 1-16 letters, numbers or underscores')
    identity = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + username).encode()).digest(), version=3)
    return {'username': username, 'uuid': str(identity), 'token': '0',
            'executablePath': str(java), 'gameDirectory': str(game),
            'jvmArguments': [f'-Xmx{memory_mib}M'], 'launcherName': 'AE2WI-fullpack-test',
            'launcherVersion': '1', 'enableLoggingConfig': True}


def client_command(cache, game, java, username, runtime, quick_play_multiplayer=None, memory_mib=8192):
    import minecraft_launcher_lib.command
    import minecraft_launcher_lib.mod_loader
    options = offline_options(java, game, username, memory_mib)
    if quick_play_multiplayer:
        host, port = quick_play_multiplayer.rsplit(':', 1)
        if not 1 <= int(port) <= 65535 or (host != 'localhost' and not ipaddress.ip_address(host.strip('[]')).is_loopback):
            raise ValueError('Offline multiplayer must use a loopback host and valid port')
        if runtime['java'] == 8:
            options.update(server=host, port=port)
        else:
            options['quickPlayMultiplayer'] = quick_play_multiplayer
            options['quickPlayPath'] = str(game / 'quick-play.json')
    version = minecraft_launcher_lib.mod_loader.get_mod_loader(runtime['kind']).get_installed_version(
        runtime['minecraft'], runtime['loader'])
    command = minecraft_launcher_lib.command.get_minecraft_command(
        version, cache, options)
    if runtime['java'] == 8:
        metadata = json.loads((cache / 'versions' / runtime['minecraft'] / (runtime['minecraft'] + '.json')).read_text())
        native_only = {str(library_path(cache, entry['name'])) for entry in metadata['libraries']
                       if 'natives' in entry and 'downloads' in entry and 'artifact' not in entry['downloads']}
        # Launcher-lib lists ordinary JARs even for native-only Maven entries.
        index = command.index('-cp') + 1
        command[index] = os.pathsep.join(entry for entry in command[index].split(os.pathsep)
                                        if entry not in native_only or Path(entry).is_file())
    # Minecraft 1.20.1 defaults both optional account fields to empty; offline
    # tests have no Microsoft client ID or Xbox identity to supply.
    for flag in ('--clientId', '--xuid'):
        if flag in command:
            index = command.index(flag)
            del command[index:index + 2]
    if '--userType' in command:
        command[command.index('--userType') + 1] = 'legacy'
    if any('${' in argument for argument in command):
        raise ValueError('Unresolved Minecraft launch argument placeholder')
    return command


def library_path(cache, coordinate):
    name, _, extension = coordinate.partition('@')
    parts = name.split(':')
    group, artifact, version = parts[:3]
    classifier = '-' + parts[3] if len(parts) == 4 else ''
    return cache / 'libraries' / group.replace('.', '/') / artifact / version / (
        artifact + '-' + version + classifier + '.' + (extension or 'jar'))


def validate_client(cache, java, runtime):
    command = client_command(cache, cache, java, 'FullpackCheck', runtime)
    for flag in ('-cp', '-classpath', '-p', '--module-path'):
        if flag in command:
            for entry in command[command.index(flag) + 1].split(os.pathsep):
                if not Path(entry).is_file():
                    raise ValueError('Required launch library is missing: ' + entry)
    minecraft = runtime['minecraft']
    metadata = json.loads((cache / 'versions' / minecraft / (minecraft + '.json')).read_text())
    index = json.loads((cache / 'assets/indexes' / (metadata['assetIndex']['id'] + '.json')).read_text())
    for asset in index['objects'].values():
        digest = asset['hash']
        if not (cache / 'assets/objects' / digest[:2] / digest).is_file():
            raise ValueError('Required Minecraft asset is missing: ' + digest)
    # Processor products are not all listed on the Java classpath.
    profile = json.loads((cache / 'forge-install-profile.json').read_text(encoding='utf-8'))
    if runtime['java'] == 8:
        return  # Legacy Forge has no patching processor pipeline.
    for key in ('MC_SRG', 'MC_EXTRA', 'PATCHED'):
        coordinate = profile['data'][key]['client']
        if not coordinate.startswith('[') or not coordinate.endswith(']'):
            raise ValueError('Unexpected Forge processor output: ' + coordinate)
        output = library_path(cache, coordinate[1:-1])
        if not output.is_file():
            raise ValueError('Forge processor output is missing: ' + str(output))


def install_worker(role, destination, java, installer, lease_fd, runtime):
    options = ({'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt'
               else {'start_new_session': True, 'pass_fds': (lease_fd,)})
    with (destination / 'installation.log').open('wb') as log, ProcessGroup() as group:
        process = group.start([sys.executable, str(Path(__file__).resolve()), '_install',
                               role, str(destination), str(java), str(installer),
                               runtime['kind'], runtime['minecraft'], runtime['loader']],
                              cwd=destination, env=clean_environment(), stdout=log,
                              stderr=subprocess.STDOUT, **options)
        try:
            if process.wait(timeout=1800) != 0:
                raise RuntimeError('Minecraft installation failed; see ' + str(destination / 'installation.log'))
        finally:
            if os.name == 'nt':
                group.close()
            elif process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait(timeout=30)


@contextmanager
def cache_lock(path):
    # OS locks release when a failed/interrupted installer owner exits.
    with path.open('a+b') as stream:
        stream.seek(0, os.SEEK_END)
        if stream.tell() == 0:
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise ValueError('Runtime installation is already owned: ' + str(path)) from None
        try:
            yield stream.fileno()
        finally:
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def prepare_runtime(role, pack_directory, runtime_cache, java, offline_user,
                    *, runtime, quick_play_multiplayer=None, memory_mib=8192):
    if role not in ('client', 'server'):
        raise ValueError('Unknown runtime role')
    java = preflight(java, runtime)
    if not isinstance(memory_mib, int) or isinstance(memory_mib, bool) or not 1024 <= memory_mib <= 1048576:
        raise ValueError('Memory must be between 1024 and 1048576 MiB')
    game = Path(pack_directory).resolve(strict=True)
    if role == 'server' and runtime['java'] == 8:
        return bundled_legacy_server(game, java, runtime, memory_mib)
    root = Path(runtime_cache).resolve()
    fullpack.disjoint(root, game)
    if role == 'client':
        offline_options(java, game, offline_user)
    root.mkdir(parents=True, exist_ok=True)
    kind, minecraft, loader = runtime['kind'], runtime['minecraft'], runtime['loader']
    cache = root / (role + '-' + minecraft + '-' + kind + '-' + loader)
    with cache_lock(root / (cache.name + '.lock')) as lease_fd:
        owner = {'minecraft': minecraft, kind: loader, 'library': LIBRARY, 'role': role}
        ownership = cache / 'runtime-owner.json'
        if cache.exists():
            if not ownership.is_file() or json.loads(ownership.read_text(encoding='utf-8')) != owner:
                raise ValueError('Refusing an unowned runtime cache: ' + str(cache))
        else:
            cache.mkdir()
            fullpack.write_json(ownership, owner)
        marker = cache / 'complete.json'
        if not marker.exists():
            if kind == 'neoforge':
                url = f'https://maven.neoforged.net/releases/net/neoforged/neoforge/{loader}/neoforge-{loader}-installer.jar'
            else:
                version = minecraft + '-' + loader
                url = f'https://maven.minecraftforge.net/net/minecraftforge/forge/{version}/forge-{version}-installer.jar'
            request = urllib.request.Request(url + '.sha1', headers={'User-Agent': 'AE2WI-fullpack/1.0'})
            with urllib.request.urlopen(request, timeout=60) as response:
                expected = response.read(1024).decode().strip()
            if not re.fullmatch('[0-9a-fA-F]{40}', expected):
                raise ValueError('Invalid official loader installer SHA1')
            installer = fullpack.download(url, root / 'downloads')
            if fullpack.digest(installer, 'sha1') != expected.lower():
                raise ValueError('Loader installer does not match official SHA1')
            # Keep the final path during installation: Forge metadata can retain absolute paths.
            with zipfile.ZipFile(installer) as archive:
                (cache / 'forge-install-profile.json').write_bytes(archive.read('install_profile.json'))
            install_worker(role, cache, java, installer, lease_fd, runtime)
            if role == 'client':
                validate_client(cache, java, runtime)
            else:
                for name in ('win_args.txt', 'unix_args.txt'):
                    if not (cache / server_args_directory(runtime) / name).is_file():
                        raise ValueError('Loader server installation did not create ' + name)
            fullpack.write_json(marker, {'minecraft': minecraft, kind: loader,
                                         'library': LIBRARY, 'installerSha256': installer.name})
        else:
            expected = {'minecraft': minecraft, kind: loader, 'library': LIBRARY}
            actual = json.loads(marker.read_text(encoding='utf-8'))
            if any(actual.get(key) != value for key, value in expected.items()):
                raise ValueError('Runtime cache completion metadata does not match profile')
        if role == 'client':
            validate_client(cache, java, runtime)
            command = client_command(cache, game, java, offline_user, runtime, quick_play_multiplayer, memory_mib)
            if runtime['java'] == 8:
                return LaunchPlan(short_legacy_classpath(command, game), game)
            arguments = command[1:]
        else:
            if (game / 'libraries').exists():
                raise ValueError('Prepared server already contains a libraries directory')
            shutil.copytree(cache / 'libraries', game / 'libraries')
            native_args = (server_args_directory(runtime) / ('win_args.txt' if os.name == 'nt' else 'unix_args.txt')).as_posix()
            # Java does not recursively expand @files inside another argument file.
            return LaunchPlan([str(java), f'-Xmx{memory_mib}M', '@' + native_args, 'nogui'], game)
        args_file = game / 'fullpack-java.args'
        args_file.write_text(java_arguments(arguments), encoding='utf-8')
        return LaunchPlan([str(java), '@' + str(args_file)], game)


def server_args_directory(runtime):
    if runtime['kind'] == 'neoforge':
        return Path('libraries/net/neoforged/neoforge') / runtime['loader']
    return Path('libraries/net/minecraftforge/forge') / (runtime['minecraft'] + '-' + runtime['loader'])


def short_legacy_classpath(command, game):
    # Java 8 has no @argument files. A manifest-only JAR avoids Windows' command limit.
    index = command.index('-cp') + 1
    classpath = 'Class-Path: ' + ' '.join(Path(entry).resolve().as_uri() for entry in command[index].split(os.pathsep))
    lines = [classpath[:70]]
    remainder = classpath[70:]
    while remainder:
        lines.append(' ' + remainder[:69])
        remainder = remainder[69:]
    manifest = 'Manifest-Version: 1.0\r\n' + '\r\n'.join(lines) + '\r\n\r\n'
    jar_path = game / 'fullpack-classpath.jar'
    with zipfile.ZipFile(jar_path, 'w') as jar:
        jar.writestr('META-INF/MANIFEST.MF', manifest)
    command[index] = str(jar_path)
    return command


def worker(role, destination, java, installer, kind, minecraft, loader):
    if role == 'client':
        import minecraft_launcher_lib.mod_loader
        minecraft_launcher_lib.mod_loader.get_mod_loader(kind).install(
            minecraft, str(destination), loader_version=loader, java=str(java),
            callback={'setStatus': lambda status: print(status, flush=True)})
    else:
        subprocess.run([str(java), '-jar', str(installer), '--installServer', str(destination)],
                       cwd=destination, env=clean_environment(), check=True)


if __name__ == '__main__':
    if len(sys.argv) != 9 or sys.argv[1] != '_install':
        raise SystemExit('Runtime helper is invoked by run_fullpack.py')
    worker(sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4]), Path(sys.argv[5]), *sys.argv[6:9])
