"""Freeze pack manifests and prepare their complete contents without a launcher."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import shutil
import re
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from contextlib import nullcontext
from progress import Activity, copy_stream, size, status


def safe_path(name):
    parts = name.split('/') if isinstance(name, str) else []
    if not parts or any(not part or part in ('.', '..') or part.endswith((' ', '.'))
                        or any(char in part for char in '\\:<>"|?*')
                        or any(ord(char) < 32 for char in part)
                        or re.fullmatch(r'(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?', part)
                        for part in parts):
        raise ValueError('Unsafe pack path: ' + repr(name))
    return name


def safe_url(url, allow_http=False):
    parsed = urllib.parse.urlsplit(url)
    try:
        loopback = ipaddress.ip_address(parsed.hostname or '').is_loopback
    except ValueError:
        loopback = parsed.hostname == 'localhost'
    if parsed.username or parsed.password or not parsed.hostname or not (
            parsed.scheme == 'https' or (parsed.scheme == 'http' and (loopback or allow_http))):
        raise ValueError('Only HTTPS or loopback HTTP download URLs are supported')
    return url


def validate_manifest(manifest, locked=False, allow_http=False):
    if not isinstance(manifest, dict) or manifest.get('version') != 1 or set(manifest) - {'version', 'files', 'archives', 'textFiles'}:
        raise ValueError('Unsupported pack manifest version or fields')
    explicit_paths = set()
    for kind, allowed in [('files', {'path', 'url', 'owner', 'maven', 'sha256', 'sha1'}),
                          ('archives', {'url', 'exclude', 'keepExisting', 'stripPrefix', 'sha256', 'sha1'})]:
        if not isinstance(manifest.get(kind, []), list):
            raise ValueError(kind + ' must be a list')
        for entry in manifest.get(kind, []):
            if not isinstance(entry, dict) or set(entry) - allowed:
                raise ValueError('Unsupported ' + kind + ' fields')
            safe_url(entry['url'], allow_http)
            if kind == 'files':
                safe_path(entry['path'])
                key = entry['path'].casefold()
                if key in explicit_paths:
                    raise ValueError('Duplicate explicit file path: ' + entry['path'])
                explicit_paths.add(key)
            else:
                if not isinstance(entry.get('exclude', []), list) or not isinstance(entry.get('keepExisting', False), bool):
                    raise ValueError('Invalid archive exclusion/overwrite settings')
                for excluded in entry.get('exclude', []):
                    safe_path(excluded)
                if entry.get('stripPrefix'):
                    safe_path(entry['stripPrefix'])
            for algorithm, length in [('sha256', 64), ('sha1', 40)]:
                if algorithm in entry and not re.fullmatch('[0-9a-f]{' + str(length) + '}', entry[algorithm]):
                    raise ValueError('Invalid ' + algorithm)
            if locked and 'sha256' not in entry:
                raise ValueError('Unpinned artifact in lock')
    if not isinstance(manifest.get('textFiles', {}), dict):
        raise ValueError('textFiles must be an object')
    for name, value in manifest.get('textFiles', {}).items():
        safe_path(name)
        if not isinstance(value, str):
            raise ValueError('textFiles values must be strings')


def disjoint(first, second):
    first, second = first.resolve(), second.resolve()
    if first == second or first in second.parents or second in first.parents:
        raise ValueError('Cache and output must not overlap')


def digest(path, algorithm='sha256', progress=None, label=None):
    value = hashlib.new(algorithm)
    read = 0
    total = path.stat().st_size if progress else 0
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
            if progress:
                read += len(block)
                progress.update(f'Checking {label or path.name}: {size(read)} / {size(total)}')
    return value.hexdigest()


def download(url, cache, expected=None, allow_http=False, progress=None):
    label = urllib.parse.unquote(urllib.parse.urlsplit(url).path.rsplit('/', 1)[-1]) or 'artifact'
    with nullcontext(progress) if progress else Activity('Fetching ' + label) as activity:
        return download_artifact(url, cache, expected, allow_http, activity, label)


def download_artifact(url, cache, expected, allow_http, progress, label):
    safe_url(url, allow_http)
    if expected:
        cached = cache / expected
        if cached.is_file() and digest(cached, progress=progress, label=label) == expected:
            progress.update('Using cached ' + label)
            return cached
    cache.mkdir(parents=True, exist_ok=True)
    progress.update('Downloading ' + label, force=True)
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            for attempt in range(3):
                try:
                    request = urllib.request.Request(url, headers={'User-Agent': 'AE2WI-fullpack/1.0'})
                    with urllib.request.urlopen(request, timeout=60) as response:
                        safe_url(response.url, allow_http)
                        length = response.headers.get('Content-Length')
                        copy_stream(response, stream, progress, 'Downloading ' + label,
                                    int(length) if length is not None else None)
                        if length is not None and stream.tell() != int(length):
                            raise OSError('Incomplete download: response shorter than Content-Length')
                    break
                except (OSError, urllib.error.URLError) as error:
                    if attempt == 2 or (isinstance(error, urllib.error.HTTPError)
                                        and error.code < 500 and error.code != 429):
                        raise
                    stream.seek(0)
                    stream.truncate()
                    progress.update(f'Retrying {label}: attempt {attempt + 2}/3 ({type(error).__name__})', force=True)
                    time.sleep(attempt + 1)
        except BaseException:
            stream.close()
            temporary.unlink(missing_ok=True)
            raise
    try:
        actual = digest(temporary, progress=progress, label=label)
        if expected and actual != expected:
            raise ValueError('Downloaded content does not match locked SHA256')
        target = cache / actual
        temporary.replace(target)
        return target
    finally:
        temporary.unlink(missing_ok=True)


def write_json(output, payload):
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=output.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(payload, stream, indent=2)
            stream.write('\n')
            stream.close()
            temporary.replace(output)
        finally:
            temporary.unlink(missing_ok=True)


def lock_pack(source, output, cache, source_provenance=None, allow_http=False):
    disjoint(output, cache)
    raw = urllib.request.urlopen(safe_url(source, allow_http), timeout=60).read() if '://' in source else Path(source).read_bytes()
    manifest = json.loads(raw)
    validate_manifest(manifest, allow_http=allow_http)

    def acquire(entry):
        label = entry.get('path', urllib.parse.urlsplit(entry['url']).path)
        try:
            path = download(entry['url'], cache, entry.get('sha256'), allow_http)
            if entry.get('sha1') and digest(path, 'sha1') != entry['sha1']:
                raise ValueError('SHA1 mismatch')
            entry['sha256'] = path.name
            print('Cached ' + label, file=sys.stderr)
        except (OSError, ValueError) as error:
            raise ValueError('Could not acquire ' + label + ': ' + str(error)) from error

    with ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(acquire, manifest.get('files', []) + manifest.get('archives', [])))
    payload = {'lockVersion': 1, 'source': source_provenance or (source if '://' in source else Path(source).name),
               'sourceSha256': hashlib.sha256(raw).hexdigest(), 'allowHttp': allow_http, 'manifest': manifest}
    write_json(output, payload)


def prepare(lock, output, cache):
    with Activity('Preparing modpack files') as progress:
        return prepare_contents(lock, output, cache, progress)


def prepare_contents(lock, output, cache, progress):
    disjoint(output, cache)
    payload = json.loads(lock.read_text(encoding='utf-8'))
    if payload.get('lockVersion') != 1:
        raise ValueError('Unsupported lock version')
    manifest = payload['manifest']
    allow_http = payload.get('allowHttp', False)
    validate_manifest(manifest, locked=True, allow_http=allow_http)
    if output.exists():
        raise ValueError('Output already exists; choose a fresh directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.fullpack-', dir=output.parent) as directory:
        stage = Path(directory) / 'pack'
        stage.mkdir()
        names = {}

        def destination(name, directory=False):
            safe_path(name)
            # Translation archives use differently cased parent directories. Preserve the
            # native filesystem semantics, but reject actual file aliases and type conflicts.
            parts = name.split('/')
            for index in range(1, len(parts) + 1):
                partial = '/'.join(parts[:index])
                is_directory = directory or index < len(parts)
                previous, was_directory = names.setdefault(partial.casefold(), (partial, is_directory))
                if is_directory != was_directory:
                    raise ValueError('Pack file/directory conflict: ' + partial)
                if not is_directory and previous != partial:
                    raise ValueError('Case-colliding pack files: ' + partial + ' and ' + previous)
            return stage / name

        files = manifest.get('files', [])
        archives = manifest.get('archives', [])
        status(f'Modpack contains {len(files)} files and {len(archives)} archives; checking the download cache')
        for index, entry in enumerate(files, 1):
            progress.update(f'File {index}/{len(files)}: {entry["path"]}')
            target = destination(entry['path'])
            target.parent.mkdir(parents=True, exist_ok=True)
            cached = download(entry['url'], cache, entry['sha256'], allow_http, progress)
            label = f'Copying file {index}/{len(files)} from cache: {entry["path"]}'
            progress.update(label, force=index in (1, len(files)))
            with cached.open('rb') as source, target.open('wb') as sink:
                copy_stream(source, sink, progress, label, cached.stat().st_size)
        for index, entry in enumerate(archives, 1):
            archive = download(entry['url'], cache, entry['sha256'], allow_http, progress)
            label = urllib.parse.unquote(urllib.parse.urlsplit(entry['url']).path.rsplit('/', 1)[-1])
            progress.update(f'Extracting archive {index}/{len(archives)}: {label}', force=True)
            with zipfile.ZipFile(archive) as zipped:
                members = zipped.infolist()
                for member, info in enumerate(members, 1):
                    progress.update(f'Archive {index}/{len(archives)}, entry {member}/{len(members)}: {info.filename}')
                    name = safe_path(info.filename.rstrip('/'))
                    if stat.S_ISLNK(info.external_attr >> 16):
                        raise ValueError('Archive symlinks are not supported')
                    prefix = entry.get('stripPrefix', '')
                    if prefix:
                        if not name.startswith(prefix + '/'):
                            continue
                        name = name[len(prefix) + 1:]
                    if any(name == path or name.startswith(path + '/') for path in entry.get('exclude', [])):
                        continue
                    target = destination(name, directory=info.is_dir())
                    if info.is_dir():
                        target.mkdir(parents=True, exist_ok=True)
                    elif not (entry.get('keepExisting', False) and target.exists()):
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with zipped.open(info) as source, target.open('wb') as sink:
                            copy_stream(source, sink, progress, f'Extracting {info.filename}', info.file_size)
                        if os.name != 'nt' and info.create_system == 3:
                            target.chmod(target.stat().st_mode | ((info.external_attr >> 16) & 0o111))
        for name, content in manifest.get('textFiles', {}).items():
            target = destination(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8', newline='')
        stage.rename(output)
        status(f'Modpack ready: {len(files)} files, {len(archives)} archives')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('lock', 'prepare'):
        command = commands.add_parser(name)
        command.add_argument('--manifest' if name == 'lock' else '--lock', required=True)
        command.add_argument('--output', required=True, type=Path)
        command.add_argument('--cache', required=True, type=Path)
        if name == 'lock':
            command.add_argument('--allow-http', action='store_true', help='Allow legacy upstream HTTP files; recorded in lock')
    args = parser.parse_args()
    try:
        if args.command == 'lock':
            lock_pack(args.manifest, args.output, args.cache, allow_http=args.allow_http)
        else:
            prepare(Path(args.lock), args.output, args.cache)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print('Preparation failed: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
