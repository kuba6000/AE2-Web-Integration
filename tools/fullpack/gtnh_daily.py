"""Pin the current GTNH daily client/server pair and cache all referenced files.

Published daily assets can be pruned upstream; retain the verified cache for later
preparation or verified import on another machine.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import urllib.request

import fullpack

DEFAULT_BASE = 'https://github.com/Pxx500/DreamAssemblerXXL/releases/download/fullpack-daily'
NAMES = {'client': 'daily.json', 'server': 'daily-server.json'}


def fetch_pair(base, allow_http):
    pair = {}
    for role, name in NAMES.items():
        url = base.rstrip('/') + '/' + name
        with urllib.request.urlopen(fullpack.safe_url(url, allow_http), timeout=60) as response:
            pair[role] = response.read(4 * 1024 * 1024 + 1)
        if len(pair[role]) > 4 * 1024 * 1024:
            raise ValueError('Daily manifest exceeds size limit')
    return pair


def check_pair(pair, allow_http):
    manifests = {role: json.loads(raw) for role, raw in pair.items()}
    labels = []
    for manifest in manifests.values():
        fullpack.validate_manifest(manifest, allow_http=allow_http)
        properties = manifest.get('textFiles', {}).get('config/DreamCoreMod.properties', '')
        values = [line.split('=', 1)[1].strip() for line in properties.splitlines()
                  if line.strip().startswith('displayedModpackVersion=')]
        if len(values) != 1 or not values[0]:
            raise ValueError('Daily display label is missing or ambiguous')
        labels.append(values[0])
    client, server = manifests['client'], manifests['server']
    if labels[0] != labels[1]:
        raise ValueError('Client and server daily labels differ')
    if not client.get('archives') or not server.get('archives') or (
            client['archives'][0]['url'] != server['archives'][0]['url']):
        raise ValueError('Client and server configuration archives differ')
    owners = []
    for manifest in (client, server):
        grouped = {}
        for entry in manifest.get('files', []):
            if entry.get('owner'):
                grouped.setdefault(entry['owner'].casefold(), []).append(
                    (entry['path'], entry['url'], entry.get('maven', '')))
        owners.append(grouped)
    for owner in owners[0].keys() & owners[1].keys():
        if sorted(owners[0][owner]) != sorted(owners[1][owner]):
            raise ValueError('Client and server disagree on shared mod: ' + owner)
    return labels[0]


def pin(base, output, cache, allow_http):
    fullpack.disjoint(output, cache)
    if output.exists() or output.is_symlink():
        raise ValueError('Profile destination already exists')
    pair = fetch_pair(base, allow_http)
    label = check_pair(pair, allow_http)
    if fetch_pair(base, allow_http) != pair:
        raise ValueError('Daily manifests changed during selection; retry pinning')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.gtnh-pin-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'profile'
        stage.mkdir()
        provenance = {'label': label, 'pinnedAt': datetime.now(timezone.utc).isoformat(), 'sources': {}}
        for role, raw in pair.items():
            source = stage / (role + '.manifest.json')
            source.write_bytes(raw)
            url = base.rstrip('/') + '/' + NAMES[role]
            fullpack.lock_pack(str(source), stage / (role + '.lock.json'), cache,
                               source_provenance=url, allow_http=allow_http)
            provenance['sources'][role] = {'url': url, 'sha256': hashlib.sha256(raw).hexdigest()}
        if fetch_pair(base, allow_http) != pair:
            raise ValueError('Daily manifests changed during download; retry pinning')
        (stage / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
        if output.exists() or output.is_symlink():
            raise ValueError('Profile destination appeared while pinning')
        stage.rename(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default=DEFAULT_BASE)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--allow-http', action='store_true')
    args = parser.parse_args()
    try:
        pin(args.base_url, args.output, args.cache, args.allow_http)
    except (OSError, ValueError, KeyError) as error:
        print('Daily pin failed: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
