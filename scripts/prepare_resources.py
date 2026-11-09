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
(destination / 'vulkan').mkdir(exist_ok=True)
for source in sorted((ENGINE / 'src/shaders/vulkan').glob('*.comp')):
    subprocess.run([str(glslc), '--target-env=vulkan1.0', '-O0',
                    '-I' + str(source.parent), str(source),
                    '-o', str(destination / 'vulkan' / (source.stem + '.spv'))], check=True)

catalog = {'films': [], 'papers': []}
for source in sorted((destination / 'profiles').glob('*.json')):
    data = json.loads(source.read_text())
    info = data['info']
    item = {k: info.get(k, '') for k in ('stock', 'name', 'type', 'target_print')}
    catalog['films' if info.get('stage') == 'filming' else 'papers'].append(item)
(ASSETS / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2))
licenses = ASSETS / 'licenses'
licenses.mkdir(exist_ok=True)
for name, source in {
    'LibRaw-LGPL-2.1.txt': ROOT / 'third_party/LibRaw-0.22.2/LICENSE.LGPL',
    'LibRaw-COPYRIGHT.txt': ROOT / 'third_party/LibRaw-0.22.2/COPYRIGHT',
    'stb-LICENSE.txt': ENGINE / 'third_party/stb/LICENSE',
    'GPL-3.0.txt': ROOT / 'SpektraLab/LICENSE.txt',
    'Profiles-CC-BY-SA-4.0.txt': ROOT / 'SpektraLab/license/SPEKTRAFILM_LICENSE.txt',
    'SpektraLab-CITATION.cff': ROOT / 'SpektraLab/CITATION.cff',
    'Volk-LICENSE.md': ENGINE / 'third_party/volk/LICENSE.md',
    'Vulkan-Headers-LICENSE.md': ROOT / 'third_party/Vulkan-Headers/LICENSE.md',
}.items():
    if source.exists(): shutil.copy2(source, licenses / name)
for source in (ROOT / 'third_party/Vulkan-Headers/LICENSES').glob('*'):
    shutil.copy2(source, licenses / ('Vulkan-Headers-' + source.name))
print('Resources ready:', len(catalog['films']), 'films,', len(catalog['papers']), 'papers')
