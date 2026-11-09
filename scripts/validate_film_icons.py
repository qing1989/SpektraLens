#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors.
"""Check complete project-drawn stock coverage and keep archived PNGs out of built apps."""
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android/app/src/main/assets'


def validate(apk=None):
    source = (ASSETS / 'film_icons/manifest.json').read_bytes()
    manifest = json.loads(source)
    assert manifest['schema_version'] == 3
    films = {f['stock']: f for f in json.loads((ASSETS / 'catalog.json').read_text())['films']}
    assert manifest['stocks'].keys() == films.keys(), 'Every catalog entry needs a cartridge'
    for stock, recipe in manifest['stocks'].items():
        assert set(recipe) == {'family', 'mark', 'coat', 'accent', 'series'} | (
            {'push'} if '_push' in stock else set()), stock
        assert recipe['family'] and recipe['mark'].upper() in films[stock]['name'].upper(), stock
        for color in ('coat', 'accent'):
            assert re.fullmatch('#[0-9A-Fa-f]{6}', recipe[color]), stock
        assert recipe['series'] in ('negative', 'reversal', 'cine'), stock
        assert (recipe['series'] == 'reversal') == (films[stock]['type'] == 'positive'), stock
        assert recipe.get('push', 0) == (int(stock[-1]) if '_push' in stock else 0), stock
    if apk:
        archived = json.loads((ROOT / 'docs/历史胶卷图标来源.json').read_text())
        excluded = {v['sha256'] for v in archived['archived_artwork'].values()}
        with zipfile.ZipFile(apk) as z:
            assert z.read('assets/film_icons/manifest.json') == source
            files = {n for n in z.namelist() if n.startswith('assets/film_icons/')}
            assert files == {'assets/film_icons/README.txt', 'assets/film_icons/manifest.json'}, files
            for path in z.namelist():
                if path.lower().endswith('.png'):
                    assert hashlib.sha256(z.read(path)).hexdigest() not in excluded, path
    return {'project_drawn_configurations': len(films), 'apk_verified': bool(apk)}


if __name__ == '__main__':
    print(json.dumps(validate(sys.argv[1] if len(sys.argv) > 1 else None)))
