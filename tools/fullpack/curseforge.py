"""Pin a CurseForge-format client export, including its mods and complete overrides."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from fullpack import download, lock_pack


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward API credentials through an unexpected redirect.
        return None


def api_connection(source, override):
    origins = {'curseforge': 'https://api.curseforge.com', 'curse-tools': 'https://api.curse.tools'}
    base = origins[source]
    if override is not None:
        parsed = urllib.parse.urlsplit(override)
        if (parsed.scheme != 'http' or parsed.hostname not in ('localhost', '127.0.0.1', '::1')
                or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
            raise ValueError('Custom API origin must be a loopback HTTP fixture')
        base = override
    key = None
    if source == 'curseforge':
        key = os.environ.get('CURSEFORGE_TEST_API_KEY' if override else 'CURSEFORGE_API_KEY')
    if source == 'curseforge' and not key:
        raise ValueError('Set CURSEFORGE_API_KEY (or CURSEFORGE_TEST_API_KEY for loopback fixtures)')
    return base, key


def api_metadata(api_base, key, path):
    headers = {'Accept': 'application/json', 'User-Agent': 'AE2WI-fullpack/1.0'}
    if key is not None:
        headers['x-api-key'] = key
    request = urllib.request.Request(f'{api_base}/v1/{path}', headers=headers)
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=60) as response:
            return json.load(response)['data']
    except urllib.error.HTTPError as error:
        raise ValueError(f'CurseForge {path}: HTTP {error.code}') from None


def pin(args):
    if args.output.exists():
        raise ValueError('Lock output already exists; choose a new pin filename')
    api_base, key = api_connection(args.metadata_source, args.api_base)
    archive = download(args.archive, args.cache)
    with zipfile.ZipFile(archive) as zipped:
        if zipped.namelist().count('manifest.json') != 1:
            raise ValueError('Client archive must contain exactly one root manifest.json')
        manifest = json.loads(zipped.read('manifest.json'))
    if manifest.get('manifestType') != 'minecraftModpack' or manifest.get('manifestVersion') != 1:
        raise ValueError('Expected a CurseForge minecraftModpack manifest version 1')
    overrides = manifest['overrides']
    files = []
    unavailable = []
    selected = []
    provenance = []
    for item in manifest['files']:
        if type(item.get('required')) is not bool:
            raise ValueError('Every manifest file must declare required true/false')
        if not item['required'] and not args.include_optional:
            continue
        project, file_id = item['projectID'], item['fileID']
        if type(project) is not int or type(file_id) is not int or min(project, file_id) < 1:
            raise ValueError('CurseForge project/file IDs must be positive integers')
        selected.append((project, file_id))

    def resolve(ids):
        project, file_id = ids
        metadata = api_metadata(api_base, key, f'mods/{project}/files/{file_id}')
        if metadata.get('id') != file_id or metadata.get('modId') != project:
            raise ValueError(f'CurseForge returned different IDs for {project}/{file_id}')
        project_metadata = api_metadata(api_base, key, f'mods/{project}')
        if project_metadata.get('id') != project:
            raise ValueError(f'CurseForge returned a different project for {project}')
        return metadata, project_metadata.get('classId')

    with ThreadPoolExecutor(max_workers=4) as executor:
        metadata_results = list(executor.map(resolve, selected))
    for (project, file_id), (metadata, class_id) in zip(selected, metadata_results):
        # CurseForge class IDs also used by Prism's FlameAPI resource-type mapping.
        directories = {6: 'mods', 12: 'resourcepacks', 6552: 'shaderpacks'}
        if class_id not in directories:
            raise ValueError(f'Unsupported CurseForge class {class_id} for project {project}')
        url = metadata.get('downloadUrl')
        if not url:
            unavailable.append(f'project {project}, file {file_id} ({metadata.get("fileName", "unknown")})')
            continue
        filename = metadata['fileName']
        if not isinstance(filename, str) or '/' in filename or '\\' in filename or ':' in filename:
            raise ValueError(f'Invalid mod filename for {project}/{file_id}')
        hashes = [h['value'] for h in metadata.get('hashes', []) if h['algo'] == 1]
        if len(hashes) != 1:
            raise ValueError(f'No unambiguous SHA1 for {project}/{file_id}')
        path = directories[class_id] + '/' + filename
        files.append({'path': path, 'url': url, 'sha1': hashes[0]})
        provenance.append({'projectID': project, 'fileID': file_id, 'classId': class_id,
                           'path': path, 'sha1': hashes[0]})
    if unavailable:
        raise ValueError('Download unavailable; no lock created. Obtain these exact files through '
                         'an authorized source:\n' + '\n'.join(unavailable))
    resolved = {'version': 1, 'files': files,
                'archives': [{'url': args.archive, 'sha256': archive.name, 'stripPrefix': overrides}],
                'textFiles': {}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.fullpack-cf-', dir=args.output.parent) as directory:
        source = Path(directory) / 'manifest.json'
        source.write_text(json.dumps(resolved), encoding='utf-8')
        locked = Path(directory) / 'pack.lock.json'
        lock_pack(str(source), locked, args.cache, source_provenance=args.archive)
        payload = json.loads(locked.read_text(encoding='utf-8'))
        payload['provider'] = {'name': 'curseforge', 'metadataSource': args.metadata_source,
                               'metadataApi': api_base, 'optionalMods':
                               'include-optional' if args.include_optional else 'required-only',
                               'minecraft': manifest['minecraft'],
                               'packName': manifest.get('name'), 'packVersion': manifest.get('version'),
                               'files': provenance}
        locked.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
        os.link(locked, args.output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True, help='Exact official client export ZIP URL')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--include-optional', action='store_true', help='Include optional manifest mods too')
    parser.add_argument('--metadata-source', choices=('curseforge', 'curse-tools'), default='curseforge',
                        help='Official CurseForge API (key required) or explicit public curse.tools service')
    parser.add_argument('--api-base', help='Loopback fixtures only')
    args = parser.parse_args()
    try:
        pin(args)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print('CurseForge preparation failed: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
