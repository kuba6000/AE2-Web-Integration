"""Flushed preparation activity, including waits that produce no intermediate output."""
from contextlib import AbstractContextManager
import subprocess
import sys
import threading
import time


def status(message):
    line = ''.join(character if character.isprintable() else ' ' for character in str(message))
    print('[fullpack] ' + line[:500], file=sys.stderr, flush=True)


def size(value):
    return f'{value / (1024 * 1024):.1f} MiB'


class Activity(AbstractContextManager):
    def __init__(self, label):
        self.label = label
        self.detail = ''
        self.started = time.monotonic()
        self.last_output = self.started
        self.stopped = threading.Event()
        self.lock = threading.Lock()
        self.thread = threading.Thread(target=self._heartbeat, daemon=True)

    def __enter__(self):
        status(self.label)
        self.thread.start()
        return self

    def update(self, detail, *, force=False):
        with self.lock:
            self.detail = detail
            now = time.monotonic()
            if force or now - self.last_output >= 2:
                self.last_output = now
                status(f'{self.label}: {detail} [{now - self.started:.0f}s elapsed]')

    def _heartbeat(self):
        while not self.stopped.wait(5):
            with self.lock:
                now = time.monotonic()
                if now - self.last_output >= 5:
                    self.last_output = now
                    status(f'{self.label}: {self.detail or "working"} [{now - self.started:.0f}s elapsed]')

    def __exit__(self, kind, value, traceback):
        self.stopped.set()
        self.thread.join()
        outcome = 'finished' if kind is None else 'failed'
        status(f'{self.label}: {outcome} in {time.monotonic() - self.started:.1f}s')


def copy_stream(source, target, progress, label, total=None):
    copied = 0
    progress.update(f'{label}: 0 bytes' + (f' / {size(total)}' if total is not None else ''))
    while block := source.read(1024 * 1024):
        target.write(block)
        copied += len(block)
        progress.update(f'{label}: {size(copied)}' + (f' / {size(total)}' if total is not None else ''))
    return copied


def installer_callbacks(activity):
    state = {'status': 'Preparing installer', 'maximum': None}

    def set_status(value):
        state['status'] = value
        activity.update(value)

    def set_maximum(value):
        state['maximum'] = value

    def set_progress(value):
        maximum = state['maximum']
        counts = f'{value}/{maximum} steps' if maximum else f'{value} steps'
        activity.update(f'{state["status"]} ({counts})')

    return {'setStatus': set_status, 'setMax': set_maximum, 'setProgress': set_progress}


def wait_for_installation(process, log_path, label, timeout=1800):
    deadline = time.monotonic() + timeout
    previous_size = -1
    detail = 'Waiting for installer output'
    with Activity(label) as activity:
        while True:
            log_size = log_path.stat().st_size
            if log_size != previous_size:
                previous_size = log_size
                with log_path.open('rb') as log:
                    log.seek(max(0, log_size - 65536))
                    lines = log.read().decode('utf-8', errors='replace').splitlines()
                updates = [line[len('[fullpack] '):] for line in lines if line.startswith('[fullpack] ')]
                changed = bool(updates and updates[-1] != detail)
                if updates:
                    detail = updates[-1]
                elif log_size:
                    detail = 'Installer is writing output to its log'
                activity.update(f'{detail}; installer log {log_size:,} bytes', force=changed)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(process.args, timeout)
            try:
                return process.wait(timeout=min(1, remaining))
            except subprocess.TimeoutExpired:
                pass
