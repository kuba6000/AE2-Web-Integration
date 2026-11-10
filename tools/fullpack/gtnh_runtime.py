"""Java 17 GTNH bootstrap from the pinned pack and lwjgl3ify launcher metadata."""
import json
import importlib.metadata
import os
from pathlib import Path
import signal
import subprocess
import sys
import zipfile

import fullpack
from minecraft_runtime import (LaunchPlan, check_java, cache_lock, clean_environment,
                               java_arguments, offline_options)
from process_containment import ProcessGroup
from progress import Activity, installer_callbacks, status, wait_for_installation


def preflight(java, runtime):
    if (runtime.get('kind'), runtime.get('minecraft'), runtime.get('loader'), runtime.get('java')) != (
            'gtnh', '1.7.10', '10.13.4.1614', 17):
        raise ValueError('GTNH runtime requires Minecraft 1.7.10 / Forge 10.13.4.1614 / Java 17')
    return check_java(java)


def validate_server(game, runtime):
    patch = game / 'lwjgl3ify-forgePatches.jar'
    if fullpack.digest(patch, 'sha1') != runtime['forgePatchesSha1']:
        raise ValueError('Bundled lwjgl3ify bootstrap does not match runtime profile')
    with zipfile.ZipFile(patch) as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r', '')
    unfolded = manifest.split('\n\n', 1)[0].replace('\n ', '')
    values = dict(line.split(': ', 1) for line in unfolded.splitlines() if ': ' in line)
    if values.get('Implementation-Version') != runtime['lwjgl3ify']:
        raise ValueError('Bundled lwjgl3ify version differs from the pinned runtime')
    if not (game / 'java9args.txt').is_file():
        raise ValueError('Bundled Java 17 server arguments are missing')
    for name in values['Class-Path'].split():
        fullpack.safe_path(name)
        if not (game / name).is_file():
            raise ValueError('Bundled server bootstrap dependency is missing: ' + name)


def prepare_runtime(role, pack_directory, runtime_cache, java, offline_user, *, runtime,
                    quick_play_multiplayer=None, memory_mib=8192):
    java = preflight(java, runtime)
    game = Path(pack_directory).resolve(strict=True)
    if role == 'server':
        with Activity('Checking bundled GTNH server runtime'):
            validate_server(game, runtime)
        return LaunchPlan([str(java), f'-Xmx{memory_mib}M', '-Dfml.readTimeout=180', '-Duser.language=en',
                           '@java9args.txt', '-jar', 'lwjgl3ify-forgePatches.jar', 'nogui'], game)
    if role != 'client':
        raise ValueError('Unknown GTNH runtime role')
    if importlib.metadata.version('minecraft-launcher-lib') != '8.0':
        raise ValueError('Install the pinned minecraft-launcher-lib 8.0 dependency')
    options = offline_options(java, game, offline_user, memory_mib)
    patch = game / '.gtnh/launcher/lwjgl3ify-forgePatches.jar'
    if fullpack.digest(patch, 'sha1') != runtime['forgePatchesSha1']:
        raise ValueError('Client pack lwjgl3ify bootstrap differs from runtime metadata')
    root = Path(runtime_cache).resolve()
    fullpack.disjoint(root, game)
    root.mkdir(parents=True, exist_ok=True)
    cache = root / ('client-gtnh-' + runtime['clientMetadataSha256'][:16])
    with cache_lock(root / (cache.name + '.lock')) as lease:
        owner = {'kind': 'gtnh', 'metadataSha256': runtime['clientMetadataSha256'], 'library': '8.0'}
        ownership = cache / 'runtime-owner.json'
        if cache.exists():
            if not ownership.is_file() or json.loads(ownership.read_text()) != owner:
                raise ValueError('Refusing an unowned GTNH runtime cache')
        else:
            cache.mkdir()
            fullpack.write_json(ownership, owner)
        source = fullpack.download(runtime['clientMetadataUrl'], root / 'downloads', runtime['clientMetadataSha256'])
        metadata = json.loads(source.read_text())
        expected_id = '1.7.10-Forge10.13.4.1614-1.7.10-lwjgl3ify-' + runtime['lwjgl3ify']
        if metadata['id'] != expected_id:
            raise ValueError('Pinned launcher metadata has the wrong version identity')
        # We explicitly run Java 17; the publisher's optional bundled Java 25 is unnecessary.
        metadata.pop('javaVersion', None)
        for library in metadata['libraries']:
            artifact = library.get('downloads', {}).get('artifact')
            if artifact:
                artifact.setdefault('path', library_path(library['name']))
        version = cache / 'versions' / metadata['id'] / (metadata['id'] + '.json')
        fullpack.write_json(version, metadata)
        marker = cache / 'complete.json'
        if not marker.exists():
            status('GTNH client runtime is not cached; installation log: ' + str(cache / 'installation.log'))
            install_client(cache, metadata['id'], lease)
            with Activity('Verifying new GTNH runtime installation'):
                validate_client(cache, metadata)
            fullpack.write_json(marker, owner)
        elif json.loads(marker.read_text()) != owner:
            raise ValueError('GTNH runtime completion identity does not match')
        status('Checking prepared GTNH client runtime')
        with Activity('Verifying GTNH assets and libraries'):
            validate_client(cache, metadata)
        import minecraft_launcher_lib.command
        command = minecraft_launcher_lib.command.get_minecraft_command(metadata['id'], cache, options)
        if '--userType' in command:
            command[command.index('--userType') + 1] = 'legacy'
        # Native-only vanilla entries have no ordinary artifact. Java ignores these
        # nonexistent classpath entries; omit them after validating all real inputs.
        if '-cp' in command:
            index = command.index('-cp') + 1
            command[index] = os.pathsep.join(path for path in command[index].split(os.pathsep) if Path(path).is_file())
        if quick_play_multiplayer:
            from server_readiness import endpoint
            host, port = endpoint(quick_play_multiplayer)
            command.extend(['--server', host, '--port', str(port)])
        if any('${' in item for item in command):
            raise ValueError('Unresolved GTNH launch argument placeholder')
        arguments = game / 'fullpack-java.args'
        arguments.write_text(java_arguments(command[1:]), encoding='utf-8', newline='')
        return LaunchPlan([str(java), '@' + str(arguments)], game)


def library_path(coordinate):
    parts = coordinate.split(':')
    if len(parts) not in (3, 4):
        raise ValueError('Unsupported launcher library coordinate: ' + coordinate)
    group, name, version = parts[:3]
    classifier = '-' + parts[3] if len(parts) == 4 else ''
    path = group.replace('.', '/') + '/' + name + '/' + version + '/' + name + '-' + version + classifier + '.jar'
    return fullpack.safe_path(path)


def validate_client(cache, metadata):
    # Match the pinned installer's OS rules when verifying its selected artifacts.
    from minecraft_launcher_lib._helper import parse_rule_list
    from minecraft_launcher_lib.natives import get_natives
    for library in metadata['libraries']:
        if 'rules' in library and not parse_rule_list(library['rules'], {}):
            continue
        artifact = library.get('downloads', {}).get('artifact')
        if artifact:
            path = cache / 'libraries' / artifact['path']
            if not path.is_file() or fullpack.digest(path, 'sha1') != artifact['sha1']:
                raise ValueError('Missing/corrupted GTNH library: ' + library['name'])
        native = get_natives(library)
        if native:
            artifact = library['downloads']['classifiers'][native]
            relative = artifact.get('path') or library_path(library['name'] + ':' + native)
            path = cache / 'libraries' / relative
            if not path.is_file() or fullpack.digest(path, 'sha1') != artifact['sha1']:
                raise ValueError('Missing/corrupted GTNH native library: ' + library['name'])
    client = cache / 'versions' / metadata['id'] / (metadata['id'] + '.jar')
    if not client.is_file() or fullpack.digest(client, 'sha1') != metadata['downloads']['client']['sha1']:
        raise ValueError('Missing/corrupted Minecraft client')
    index_path = cache / 'assets/indexes' / (metadata['assetIndex']['id'] + '.json')
    if fullpack.digest(index_path, 'sha1') != metadata['assetIndex']['sha1']:
        raise ValueError('Minecraft asset index does not match metadata')
    for asset in json.loads(index_path.read_text())['objects'].values():
        digest = asset['hash']
        if not (cache / 'assets/objects' / digest[:2] / digest).is_file():
            raise ValueError('Missing Minecraft asset: ' + digest)


def install_client(cache, version, lease):
    options = ({'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt'
               else {'start_new_session': True, 'pass_fds': (lease,)})
    with (cache / 'installation.log').open('wb') as log, ProcessGroup() as group:
        process = group.start([sys.executable, '-u', str(Path(__file__).resolve()), '_install', str(cache), version],
                              cwd=cache, env=clean_environment(), stdout=log, stderr=subprocess.STDOUT, **options)
        try:
            if wait_for_installation(process, cache / 'installation.log', 'Installing GTNH client runtime') != 0:
                raise RuntimeError('GTNH client runtime installation failed; see ' + str(cache / 'installation.log'))
        finally:
            if os.name == 'nt':
                group.close()
            elif process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait(timeout=30)


if __name__ == '__main__':
    if len(sys.argv) != 4 or sys.argv[1] != '_install':
        raise SystemExit('GTNH runtime helper is invoked by run_fullpack.py')
    import minecraft_launcher_lib.install
    with Activity('Minecraft/GTNH installer') as activity:
        minecraft_launcher_lib.install.install_minecraft_version(sys.argv[3], sys.argv[2],
            callback=installer_callbacks(activity))
