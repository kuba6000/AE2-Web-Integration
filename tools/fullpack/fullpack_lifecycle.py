"""Ownership and foreground process lifecycle for disposable local pack instances."""
from contextlib import contextmanager
import codecs
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time
from process_containment import ProcessGroup


def reject_links(path):
    for part in (path, *path.parents):
        if part.is_symlink() or (part.exists() and getattr(part.stat(), 'st_file_attributes', 0)
                                 & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 1024)):
            raise ValueError('Runtime paths must not traverse links/reparse points: ' + str(part))


@contextmanager
def owned_slot(root, profile, role):
    if not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_.-]*', profile):
        raise ValueError('Profile must be a simple directory name')
    root = root.absolute()
    slot = root / profile / role
    reject_links(slot)
    slot.parent.mkdir(parents=True, exist_ok=True)
    lease_path = slot.parent / ('.' + role + '.lock')
    reject_links(lease_path)
    with lease_path.open('a+b') as lease:
        lease.seek(0)
        if not lease.read(1):
            lease.write(b'0')
            lease.flush()
        lease.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(lease.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lease.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError('Runtime slot is in use: ' + str(slot)) from error
        marker = slot / '.fullpack-owned.json'
        reject_links(marker)
        expected = {'version': 1, 'profile': profile, 'role': role}
        if slot.exists():
            if not marker.is_file() or json.loads(marker.read_text()) != expected:
                raise ValueError('Refusing foreign runtime slot: ' + str(slot))
        else:
            slot.mkdir()
            marker.write_text(json.dumps(expected), encoding='utf-8')
        yield slot, lease.fileno()


def reset_game(slot):
    game = slot / 'game'
    reject_links(game)
    if game.exists():
        # Refuse all links before deletion, including nested world junctions on Windows.
        for current, directories, files in os.walk(game, followlinks=False):
            for name in directories + files:
                reject_links(Path(current) / name)
        if game.resolve().parent != slot.resolve():
            raise ValueError('Game path escaped owned slot')
        shutil.rmtree(game)
    return game


def stop_process(process, role, timeout, group):
    if process.poll() is not None:
        return
    if role == 'server':
        try:
            process.stdin.write('stop\n')
            process.stdin.flush()
            process.wait(timeout=timeout)
            return
        except (OSError, subprocess.TimeoutExpired):
            pass
    if os.name == 'nt':
        group.close()
    else:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=10)


def run_process(command, cwd, role, stop_timeout=20, max_seconds=None, lease_fd=None):
    if not isinstance(command, list) or not command or any(not isinstance(value, str) for value in command):
        raise ValueError('Launch plan command must be a nonempty string array')
    stopping = threading.Event()
    signals = [signal.SIGINT, signal.SIGTERM]
    if hasattr(signal, 'SIGBREAK'):
        signals.append(signal.SIGBREAK)
    old_handlers = {number: signal.signal(number, lambda *_: stopping.set()) for number in signals}
    process = None
    group = ProcessGroup()
    try:
        from minecraft_runtime import clean_environment
        process = group.start(command, cwd=cwd, stdin=subprocess.PIPE, text=True,
                                   creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == 'nt' else 0,
                                   start_new_session=os.name != 'nt', env=clean_environment(),
                                   pass_fds=(lease_fd,) if os.name != 'nt' and lease_fd is not None else ())

        def console():
            try:
                decoder = codecs.getincrementaldecoder(sys.stdin.encoding or 'utf-8')(errors='replace')
                while True:
                    data = os.read(sys.stdin.fileno(), 4096)
                    if not data:
                        break
                    if process.poll() is not None:
                        break
                    process.stdin.write(decoder.decode(data))
                    process.stdin.flush()
            except (OSError, ValueError):
                pass

        if role == 'server':
            threading.Thread(target=console, daemon=True).start()
        deadline = time.monotonic() + max_seconds if max_seconds else None
        while process.poll() is None:
            expired = deadline is not None and time.monotonic() >= deadline
            if stopping.is_set() or expired:
                stop_process(process, role, stop_timeout, group)
                return 124 if expired else 130
            stopping.wait(0.05)
        return process.returncode
    finally:
        try:
            if process is not None:
                stop_process(process, role, stop_timeout, group)
                process.stdin.close()
        finally:
            group.close()
            for number, handler in old_handlers.items():
                signal.signal(number, handler)
