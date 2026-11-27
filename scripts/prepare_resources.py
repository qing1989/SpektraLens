#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Package upstream data unchanged and compile its GLSL kernels for Android."""
import json
import os
import shutil
import subprocess
from pathlib import Path
from prepare_engine import prepare

ROOT = Path(__file__).resolve().parent.parent
ENGINE = prepare()
ASSETS = ROOT / 'android/app/src/main/assets'
SDK = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/sdk'))
NDK = SDK / 'ndk/27.2.12479018'
glslc = next((NDK / 'shader-tools').glob('*/glslc'))
destination = ASSETS / 'engine'
shutil.copytree(ENGINE / 'resources', destination, dirs_exist_ok=True)
product = ROOT / 'SpektraLab/engine/resources_product'
product_profiles = (
    'kodak_tri_x_400', 'kodak_tmax_100', 'fujifilm_neopan_acros_100_ii',
    'ilford_hp5_plus_400', 'ilford_multigrade_iv_rc', 'cinestill_800t',
)
base_profile_names = [
    source.name for source in sorted((ENGINE / 'resources/profiles').glob('*.json'))
]
product_profile_names = [stock + '.json' for stock in sorted(product_profiles)]
expected_profiles = set(base_profile_names + product_profile_names)
for source in (destination / 'profiles').glob('*.json'):
    if source.name not in expected_profiles:
        source.unlink()
for stock in product_profiles:
    shutil.copy2(product / 'profiles' / (stock + '.json'), destination / 'profiles')
canisters_src = ROOT / 'android/canisters'
if canisters_src.is_dir():
    shutil.copytree(canisters_src, destination / 'canisters', dirs_exist_ok=True)
(destination / 'vulkan').mkdir(exist_ok=True)
for source in sorted((ENGINE / 'src/shaders/vulkan').glob('*.comp')):
    subprocess.run([str(glslc), '--target-env=vulkan1.0', '-O0',
                    '-I' + str(source.parent), str(source),
                    '-o', str(destination / 'vulkan' / (source.stem + '.spv'))], check=True)

def profile_info(source, seen=None):
    seen = set() if seen is None else seen
    if source in seen:
        raise RuntimeError('Cyclic profile alias: ' + str(source))
    seen.add(source)
    data = json.loads(source.read_text())
    base = data.get('derived_from')
    if base:
        inherited = profile_info(source.parent / (base + '.json'), seen)
    else:
        inherited = {}
    info = dict(inherited)
    info.update(data.get('info', {}))
    info['stock'] = source.stem
    return info


catalog = {'films': [], 'papers': []}
# Preserve the 0.4.6 order and its first-film default. New product profiles are appended even
# though "cinestill_800t.json" sorts before the original Fujifilm/Kodak catalog.
for profile_name in base_profile_names + product_profile_names:
    source = destination / 'profiles' / profile_name
    info = profile_info(source)
    item = {k: info.get(k, '') for k in ('stock', 'name', 'type', 'target_print')}
    if info.get('channel_model') == 'bw':
        item['monochrome'] = True
    if info.get('antihalation') == 'no':
        item['antihalation'] = 'no'
    catalog['films' if info.get('stage') == 'filming' else 'papers'].append(item)
(ASSETS / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2))
licenses = ASSETS / 'licenses'
licenses.mkdir(exist_ok=True)
for name, source in {
    'NOTICE.txt': ROOT / 'NOTICE',
    'CITATION.cff': ROOT / 'CITATION.cff',
    'LibRaw-LGPL-2.1.txt': ROOT / 'third_party/LibRaw-0.22.2/LICENSE.LGPL',
    'LibRaw-COPYRIGHT.txt': ROOT / 'third_party/LibRaw-0.22.2/COPYRIGHT',
    'stb-LICENSE.txt': ENGINE / 'third_party/stb/LICENSE',
    'GPL-3.0.txt': ROOT / 'SpektraLab/LICENSE.txt',
    'Profiles-CC-BY-SA-4.0.txt': ROOT / 'SpektraLab/license/SPEKTRAFILM_LICENSE.txt',
    'SpektraLab-CITATION.cff': ROOT / 'SpektraLab/CITATION.cff',
    'SpektraLab-Product-Data-NOTICE.txt': product / 'NOTICE.md',
    'Volk-LICENSE.md': ENGINE / 'third_party/volk/LICENSE.md',
    'Vulkan-Headers-LICENSE.md': ROOT / 'third_party/Vulkan-Headers/LICENSE.md',
}.items():
    if source.exists(): shutil.copy2(source, licenses / name)
for source in (ROOT / 'third_party/Vulkan-Headers/LICENSES').glob('*'):
    shutil.copy2(source, licenses / ('Vulkan-Headers-' + source.name))
print('Resources ready:', len(catalog['films']), 'films,', len(catalog['papers']), 'papers')
