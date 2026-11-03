#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
"""Verify stock mappings, original PNG hashes and the copies packaged in the APK."""
import hashlib
import json
import struct
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android/app/src/main/assets'

def validate(apk=None):
    manifest_bytes = (ASSETS / 'film_icons/manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    catalog = {f['stock']:f for f in json.loads((ASSETS / 'catalog.json').read_text())['films']}
    films = set(catalog)
    mapped = set(manifest['stocks'])
    fallback = set(manifest['vector_fallback_stocks'])
    assert mapped.isdisjoint(fallback)
    assert mapped | fallback == films
    assert len(mapped) == 20 and not fallback
    assert len(manifest['artworks']) == 18
    assert set(manifest['stocks'].values()) == set(manifest['artworks'])
    verified = {}
    missing_private = []
    vectors = set()
    for name, art in manifest['artworks'].items():
        if art['mode'] == 'vector_label':
            stocks = [stock for stock,value in manifest['stocks'].items() if value == name]
            assert len(stocks) == 1
            stock = stocks[0]
            drawing = art['drawing']
            assert (art['logical_width'],art['logical_height']) == (120,120)
            speeds = [m.upper() for m in re.findall(r'\d+[a-zA-Z]?',catalog[stock]['name']) if int(re.sub(r'\D','',m)) >= 50]
            display = re.sub(r'^[A-Z]+','',drawing['display'])
            assert display in speeds, (stock,drawing['display'],speeds)
            assert drawing['family'].replace('-','').upper() in catalog[stock]['name'].replace('-','').replace(' ','').upper()
            for key in ('background','ink','spine','separator','rail_ink'):
                assert re.fullmatch(r'#[0-9A-Fa-f]{6}',drawing[key])
            vectors.add(stock)
            continue
        path = ASSETS / 'film_icons' / art['asset']
        assert path.resolve().is_relative_to((ASSETS / 'film_icons').resolve())
        if not path.exists():
            missing_private.append(name)
            continue
        data = path.read_bytes()
        assert data[:8] == b'\x89PNG\r\n\x1a\n', name
        assert struct.unpack('>II', data[16:24]) == (120, 120), name
        assert hashlib.sha256(data).hexdigest() == art['sha256'], name
        assert art['mode'] == 'label'
        verified['assets/film_icons/' + art['asset']] = data
    assert len(verified) in (0, 11) and len(verified) + len(missing_private) == 11
    assert vectors == set(manifest['authored_vector_stocks']) and len(vectors) == 7
    if apk:
        with zipfile.ZipFile(apk) as z:
            assert z.read('assets/film_icons/manifest.json') == manifest_bytes
            packaged = set(verified) & set(z.namelist())
            assert len(packaged) in (0, 11)
            for path in packaged:
                assert z.read(path) == verified[path], path
    return {'original_pngs': len(verified), 'mapped_stocks': len(mapped), 'authored_vector_stocks': sorted(vectors),
            'vector_fallback_stocks': sorted(fallback), 'apk_verified': bool(apk)}

if __name__ == '__main__':
    print(json.dumps(validate(sys.argv[1] if len(sys.argv) > 1 else None), ensure_ascii=False, indent=2))
