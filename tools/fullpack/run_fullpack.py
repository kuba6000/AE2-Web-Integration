"""Prepare fresh owned fullpack slots or execute a local trusted launch plan."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import sys
import zipfile
from fullpack import disjoint, prepare, safe_path, write_json
from fullpack_lifecycle import owned_slot, reject_links, reset_game, run_process
from progress import Activity, status


def mod_ids(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        identities = set()
        if 'mcmod.info' in names:
            try:
                metadata = json.loads(jar.read('mcmod.info').decode('utf-8-sig'))
                mods = metadata.get('modList', []) if isinstance(metadata, dict) else metadata
                if isinstance(mods, list):
                    identities.update(mod['modid'] for mod in mods if isinstance(mod, dict) and isinstance(mod.get('modid'), str))
            except (ValueError, UnicodeError):
                pass  # Unreadable third-party metadata is never a reason to delete its JAR.
        descriptor = next((name for name in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml') if name in names), None)
        if descriptor is None:
            return identities
        try:
            import tomllib
        except ImportError:
            try:
                import tomli as tomllib
            except ImportError as error:
                raise ValueError('Install runtime-requirements.txt to inspect Forge mod identities on Python 3.10') from error
        metadata = tomllib.loads(jar.read(descriptor).decode('utf-8'))
        identities.update(mod['modId'] for mod in metadata.get('mods', []) if isinstance(mod.get('modId'), str))
        return identities


def check_world(world, runtime_root):
    if world is None:
        return
    disjoint(world, runtime_root)
    if world.is_file() and world.suffix.lower() == '.zip':
        with zipfile.ZipFile(world) as archive:
            for entry in archive.infolist():
                safe_path(entry.filename.removesuffix('/'))
                if stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError('World archive contains a symbolic link: ' + entry.filename)
            if 'level.dat' not in archive.namelist():
                raise ValueError('World ZIP must contain level.dat at its root')
            damaged = archive.testzip()
            if damaged:
                raise ValueError('Damaged world ZIP entry: ' + damaged)
        return
    if not world.is_dir() or not (world / 'level.dat').is_file():
        raise ValueError('--world must be a directory or ZIP containing level.dat at its root')
    for current, directories, files in os.walk(world, followlinks=False):
        reject_links(Path(current))
        for name in directories + files:
            reject_links(Path(current) / name)


def mute_client(game):
    options = game / 'options.txt'
    reject_links(options)
    previous = options.read_text(encoding='utf-8') if options.exists() else ''
    lines = [line for line in previous.splitlines() if line.split(':', 1)[0] != 'soundCategory_master']
    lines.append('soundCategory_master:0.0')
    options.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='')


def write_eula(path):
    reject_links(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('eula=true\n', encoding='utf-8', newline='')


def require_eula(runtime_root, accept):
    path = runtime_root.absolute().parent / 'eula.txt'
    reject_links(path)
    if path.is_file():
        agreed = False
        for line in path.read_text(encoding='utf-8').splitlines():
            key, _, value = line.partition('=')
            if key.strip() == 'eula':
                agreed = value.strip().lower() == 'true'
        if agreed:
            return
    if not accept:
        try:
            accept = input('Do you accept the Minecraft EULA (https://www.minecraft.net/eula)? [y/N] ').strip().lower() in ('y', 'yes')
        except EOFError:
            raise ValueError('Accept Minecraft EULA with --accept-eula or in ' + str(path)) from None
    if not accept:
        raise ValueError('Minecraft EULA was not accepted')
    write_eula(path)


def prepare_slot(args, slot):
    old_plan = slot / 'launch.json'
    reject_links(old_plan)
    old_plan.unlink(missing_ok=True)
    with Activity('Resetting the previous ' + args.role + ' instance'):
        game = reset_game(slot)
    prepare(args.lock, game, args.cache)
    if args.world:
        destination = game / ('world' if args.role == 'server' else 'saves/fullpack')
        if destination.exists():
            raise ValueError('Pack contains a world at the fixture destination')
        with Activity('Restoring fixture world from ' + str(args.world)):
            if args.world.is_file():
                with zipfile.ZipFile(args.world) as archive:
                    archive.extractall(destination)
            else:
                shutil.copytree(args.world, destination)
    artifacts = {}
    if args.mod_jar:
        artifacts['ae2webintegration'] = (args.mod_jar, 'ae2webintegration-local.jar')
    if args.icon_generator_jar:
        artifacts['ae2webintegration_icon_generator'] = (args.icon_generator_jar, 'ae2webintegration-icon-generator-local.jar')
    if artifacts:
        status('Installing locally built mod artifacts')
        mods = game / 'mods'
        mods.mkdir(exist_ok=True)
        mod_directories = [mods]
        minecraft = getattr(args, 'runtime', {}).get('minecraft')
        if minecraft:
            mod_directories.append(mods / minecraft)
        for directory in mod_directories:
            for candidate in directory.glob('*.jar'):
                if mod_ids(candidate).intersection(artifacts):
                    candidate.unlink()
        for source, name in artifacts.values():
            shutil.copyfile(source, mods / name)
    if args.mod_jar:
        config = game / 'config/ae2webintegration/config.toml'
        if config.exists():
            raise ValueError('Pack already supplies AE2 Web Integration config; refusing to overwrite its settings')
        config.parent.mkdir(parents=True, exist_ok=True)
        web_port = args.web_port if args.web_port is not None else (23240 if args.role == 'server' else 23241)
        password = secrets.token_urlsafe(32)
        config.write_text('[general]\n'
                          f'port = {web_port}\n'
                          f'password = "{password}"\n'
                          'public_mode = false\n'
                          'allow_no_password_on_localhost = true\n'
                          'check_for_updates = false\n\n'
                          '[telemetry]\n'
                          'enabled = false\n', encoding='utf-8', newline='')
    if args.role == 'server':
        properties = game / 'server.properties'
        settings = {'server-ip': '127.0.0.1', 'server-port': str(args.port), 'online-mode': 'false',
                    'white-list': 'false', 'enforce-whitelist': 'false',
                    'level-name': 'world', 'enable-rcon': 'false', 'enable-query': 'false', 'enable-status': 'true'}
        previous = properties.read_text(encoding='utf-8') if properties.exists() else ''
        lines = [line for line in previous.splitlines() if line.split('=', 1)[0].strip() not in settings]
        lines.extend(key + '=' + value for key, value in settings.items())
        properties.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='')
        write_eula(game / 'eula.txt')
    else:
        mute_client(game)
    if args.content_only:
        return None
    status('Preparing Minecraft ' + args.runtime['minecraft'] + ' / ' + args.runtime['kind'] + ' ' + args.runtime.get('loader', ''))
    if args.runtime['kind'] == 'gtnh':
        from gtnh_runtime import prepare_runtime
    else:
        from minecraft_runtime import prepare_runtime
    launch = prepare_runtime(args.role, game, args.runtime_cache, args.java, args.offline_user,
                             runtime=args.runtime,
                             quick_play_multiplayer=args.join_server, memory_mib=args.memory_mib)
    payload = {'command': launch.command, 'role': args.role, 'joinServer': args.join_server,
               'protocol': args.runtime['protocol']}
    write_json(slot / 'launch.json', payload)
    return payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'run', 'execute'])
    parser.add_argument('--plan', type=Path, help='Trusted local command JSON; execute only')
    parser.add_argument('--lock', type=Path)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--runtime-cache', type=Path)
    parser.add_argument('--java', type=Path)
    parser.add_argument('--runtime-config', type=Path, help='Pinned runtime JSON; defaults beside the pack lock')
    parser.add_argument('--protocol', type=int, help='Minecraft status protocol for an external execute plan')
    parser.add_argument('--offline-user')
    parser.add_argument('--join-server', help='Optional loopback multiplayer address for the test client')
    parser.add_argument('--server-timeout', type=float, default=300)
    parser.add_argument('--world', type=Path)
    parser.add_argument('--mod-jar', type=Path)
    parser.add_argument('--icon-generator-jar', type=Path, help='Standalone icon generator; client role only')
    parser.add_argument('--port', type=int, default=25585)
    parser.add_argument('--web-port', type=int, help='AE2WI HTTP port; default23240 server /23241 client')
    parser.add_argument('--memory-mib', type=int, default=8192)
    parser.add_argument('--content-only', action='store_true')
    parser.add_argument('--runtime-root', type=Path, required=True)
    parser.add_argument('--profile', required=True)
    parser.add_argument('--role', choices=['client', 'server'], required=True)
    parser.add_argument('--accept-eula', action='store_true', help='Remember Minecraft EULA acceptance for dedicated servers')
    parser.add_argument('--max-seconds', type=float)
    parser.add_argument('--stop-timeout', type=float, default=20)
    args = parser.parse_args()
    try:
        status(f'{args.command}: {args.profile} ({args.role})')
        if args.role == 'server':
            require_eula(args.runtime_root, args.accept_eula)
        if not math.isfinite(args.server_timeout) or args.server_timeout <= 0:
            raise ValueError('--server-timeout must be positive and finite')
        if args.join_server:
            from server_readiness import endpoint
            endpoint(args.join_server)
            if args.role != 'client':
                raise ValueError('--join-server requires the client role')
        if args.max_seconds is not None and args.max_seconds <= 0 or args.stop_timeout <= 0:
            raise ValueError('Process deadlines must be positive')
        if not 1024 <= args.memory_mib <= 1048576:
            raise ValueError('--memory-mib must be between 1024 and 1048576')
        if args.command != 'execute':
            if args.lock is None or args.cache is None:
                raise ValueError('--lock and --cache are required')
            disjoint(args.runtime_root, args.cache)
            disjoint(args.runtime_root, args.lock)
            check_world(args.world, args.runtime_root)
            if args.icon_generator_jar and args.role != 'client':
                raise ValueError('--icon-generator-jar requires the client role')
            for path, identity in ((args.mod_jar, 'ae2webintegration'),
                                   (args.icon_generator_jar, 'ae2webintegration_icon_generator')):
                if path:
                    disjoint(args.runtime_root, path)
                    if identity not in mod_ids(path):
                        raise ValueError('Overlay JAR must declare ' + identity + ' in loader metadata')
            if not 1024 <= args.port <= 65535:
                raise ValueError('--port must be between 1024 and 65535')
            if args.web_port is not None and not 1024 <= args.web_port <= 65535:
                raise ValueError('--web-port must be between 1024 and 65535')
            if args.content_only and args.command != 'prepare':
                raise ValueError('--content-only is supported by prepare only')
            if not args.content_only:
                if not args.java or not args.runtime_cache:
                    raise ValueError('--java and --runtime-cache are required')
                disjoint(args.runtime_root, args.runtime_cache)
                if args.role == 'client' and not args.offline_user:
                    raise ValueError('--offline-user is required for the isolated test client')
                if args.role == 'client' and not re.fullmatch('[A-Za-z0-9_]{1,16}', args.offline_user):
                    raise ValueError('--offline-user must contain 1-16 letters, numbers or underscores')
                runtime_file = args.runtime_config or args.lock.parent / 'runtime.json'
                args.runtime = json.loads(runtime_file.read_text(encoding='utf-8'))
                if args.runtime.get('kind') not in ('forge', 'neoforge', 'gtnh') or type(args.runtime.get('protocol')) is not int:
                    raise ValueError('Unsupported runtime profile kind or protocol')
                metadata = json.loads(args.lock.read_text(encoding='utf-8')).get('provider', {}).get('minecraft')
                if metadata and (metadata.get('version') != args.runtime['minecraft'] or
                                 not any(item.get('id') == args.runtime['kind'] + '-' + args.runtime['loader'] for item in metadata.get('modLoaders', []))):
                    raise ValueError('Runtime profile does not match the pinned pack metadata')
                if args.runtime['kind'] == 'gtnh':
                    from gtnh_runtime import preflight
                else:
                    from minecraft_runtime import preflight
                args.java = preflight(args.java, args.runtime)
        with owned_slot(args.runtime_root, args.profile, args.role) as (slot, lease_fd):
            game = slot / 'game'
            if args.command == 'execute':
                reject_links(game)
                game.mkdir(exist_ok=True)
                plan_path = args.plan or slot / 'launch.json'
                plan = json.loads(plan_path.read_text(encoding='utf-8'))
                if plan.get('role', args.role) != args.role:
                    raise ValueError('Launch plan belongs to a different role')
                if 'joinServer' in plan and args.join_server is not None and args.join_server != plan['joinServer']:
                    raise ValueError('Join address differs from the prepared command; prepare the client again')
                if args.role == 'client':
                    mute_client(game)
                else:
                    write_eula(game / 'eula.txt')
            else:
                plan = prepare_slot(args, slot)
            if args.command == 'prepare':
                print('Prepared ' + str(game), flush=True)
                return 0
            join_server = plan.get('joinServer', args.join_server)
            if join_server:
                if args.role != 'client':
                    raise ValueError('Prepared join address requires the client role')
                from server_readiness import wait_for_server
                print('Waiting for Minecraft server readiness at ' + join_server, flush=True)
                protocol = plan.get('protocol', args.protocol)
                if type(protocol) is not int or protocol < 0:
                    raise ValueError('Join launch plan must declare its Minecraft protocol; prepare it again')
                with Activity('Waiting for Minecraft server readiness'):
                    wait_for_server(join_server, args.server_timeout, protocol)
            status('Starting Minecraft ' + args.role + '; game output follows')
            return run_process(plan['command'], game, args.role, args.stop_timeout, args.max_seconds, lease_fd)
    except KeyboardInterrupt:
        print('Fullpack launch cancelled', file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print('Fullpack launch failed: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
