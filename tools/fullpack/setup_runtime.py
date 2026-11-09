"""Install the pinned Python launcher dependencies into an owned local environment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import venv

from fullpack import write_json
from fullpack_lifecycle import reject_links
from minecraft_runtime import cache_lock, clean_environment
from process_containment import ProcessGroup


def install(command, lease):
    options = {} if os.name == 'nt' else {'start_new_session': True, 'pass_fds': (lease,)}
    with ProcessGroup() as group:
        process = group.start(command, env=clean_environment(), **options)
        try:
            if process.wait(timeout=600) != 0:
                raise RuntimeError('Could not install fullpack launcher dependencies')
        finally:
            if process.poll() is None:
                if os.name == 'nt':
                    group.close()
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', required=True, type=Path)
    args = parser.parse_args()
    try:
        if sys.version_info < (3, 10):
            raise ValueError('Fullpack launching requires Python 3.10 or newer')
        home = args.home.absolute()
        environment = home / 'python'
        reject_links(environment)
        home.mkdir(parents=True, exist_ok=True)
        requirements = Path(__file__).with_name('runtime-requirements.txt')
        expected = {'version': 1, 'requirements': hashlib.sha256(requirements.read_bytes()).hexdigest(),
                    'python': str(Path(sys.executable).resolve()), 'pythonVersion': sys.version}
        with cache_lock(home / '.python.lock') as lease:
            ownership = environment / '.fullpack-python.json'
            marker = environment / '.complete.json'
            reject_links(ownership)
            reject_links(marker)
            if environment.exists():
                if not ownership.is_file() or json.loads(ownership.read_text()) != {'version': 1}:
                    raise ValueError('Refusing foreign Python environment: ' + str(environment))
            else:
                environment.mkdir()
                write_json(ownership, {'version': 1})
            python = environment / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
            if marker.is_file() and python.is_file() and json.loads(marker.read_text()) == expected:
                return 0
            venv.EnvBuilder(with_pip=False).create(environment)
            install([str(python), '-m', 'ensurepip', '--upgrade'], lease)
            install([str(python), '-m', 'pip', 'install', '--require-hashes',
                     '--only-binary=:all:', '-r', str(requirements)], lease)
            write_json(marker, expected)
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print('Fullpack setup failed: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
