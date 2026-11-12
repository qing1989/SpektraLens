#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Create an APK plus complete corresponding source, excluding local tools/data."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path
from validate_film_icons import validate

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release-apk', type=Path, help='Already signed release APK used for latest.json')
args = parser.parse_args()
if args.release_apk and not args.release_apk.is_file():
    parser.error('--release-apk must point to an existing signed APK')
OUT = ROOT / 'deliverables'
OUT.mkdir(exist_ok=True)
apk = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
gradle = (ROOT / 'android/app/build.gradle').read_text()
version_code = int(re.search(r'versionCode\s+(\d+)', gradle).group(1))
version = re.search(r"versionName\s+'([^']+)'", (ROOT / 'android/app/build.gradle').read_text()).group(1)
for suffix in ('debug.apk', '完整源码.zip', '交付包.zip'):
    target = OUT / f'SpektraLens-v{version}-{suffix}'
    if target.exists():
        raise SystemExit(f'Refusing to overwrite an existing delivery: {target}')
validate(apk)
with zipfile.ZipFile(apk) as z:
    names = z.namelist()
    assert 'lib/arm64-v8a/libspektra.so' in names
    assert 'lib/arm64-v8a/libvk_swiftshader.so' in names
    # x86_64 is only packaged for emulator builds (-Pabis=arm64-v8a,x86_64); a delivery APK may
    # legitimately contain arm64 only.
    if any(n.startswith('lib/x86_64/') for n in names):
        assert 'lib/x86_64/libspektra.so' in names
        assert 'lib/x86_64/libvk_swiftshader.so' in names
    assert len([n for n in names if n.startswith('assets/engine/profiles/') and n.endswith('.json')]) == 28
    assert len([n for n in names if n.startswith('assets/engine/vulkan/') and n.endswith('.spv')]) == 35
shutil.copy2(apk, OUT / f'SpektraLens-v{version}-debug.apk')
# The fixed Release resource name is independent of this script's debug delivery name.
# For a signed release pass --release-apk; never publish a debug checksum for another APK.
manifest_apk = args.release_apk if args.release_apk else apk
manifest_sha256 = hashlib.sha256(manifest_apk.read_bytes()).hexdigest()
latest = {
    'versionCode': version_code,
    'versionName': version,
    'apkUrl': f'https://github.com/qing1989/SpektraLens/releases/download/v{version}/SpektraLens-v{version}.apk',
    'sha256': manifest_sha256,
    'sizeBytes': manifest_apk.stat().st_size,
    'minSdk': int(re.search(r'minSdk\s+(\d+)', gradle).group(1)),
}
(OUT / 'latest.json').write_text(json.dumps(latest, ensure_ascii=False, indent=2) + '\n')

guide = (ROOT / 'README.md').read_text().replace(f'deliverables/SpektraLens-v{version}-debug.apk',f'SpektraLens-v{version}-debug.apk').replace('(docs/验证记录.md)','(验证记录.md)').replace('(docs/图标映射.md)','(图标映射.md)').replace('(docs/兼容显影实现.md)','(兼容显影实现.md)').replace('(docs/PhotonCam编辑页设计.md)','(编辑页设计与映射.md)').replace('(docs/导出性能排查.md)','(导出性能排查.md)').replace('(docs/界面图标检查.md)','(界面图标检查.md)').replace('(docs/移动GPU移植.md)','(移动GPU移植.md)').replace('(THIRD_PARTY_NOTICES.md)','(来源与许可.md)')
guide = guide.replace('(design/app-icon/v1/README.md)', f'(SpektraLens-v{version}-完整源码.zip)')
current_docs = ('C方案界面.md', '滑杆界面.md', '照片预览与资源校验.md', '胶片显影对照.md', '胶片显影对照.json', '历史胶卷图标来源.json')
for name in current_docs:
    guide = guide.replace(f'(docs/{name})', f'({name})')
(OUT / '安装与使用说明.md').write_text(guide)
shutil.copy2(ROOT / 'docs/验证记录.md', OUT / '验证记录.md')
shutil.copy2(ROOT / 'THIRD_PARTY_NOTICES.md', OUT / '来源与许可.md')
shutil.copy2(ROOT / 'UPSTREAMS.md', OUT / 'UPSTREAMS.md')
shutil.copy2(ROOT / 'NOTICE', OUT / 'NOTICE')
shutil.copy2(ROOT / 'docs/图标映射.md', OUT / '图标映射.md')
shutil.copy2(ROOT / 'docs/兼容显影实现.md', OUT / '兼容显影实现.md')
shutil.copy2(ROOT / 'docs/移动GPU移植.md', OUT / '移动GPU移植.md')
shutil.copy2(ROOT / 'docs/界面图标检查.md', OUT / '界面图标检查.md')
shutil.copy2(ROOT / 'docs/PhotonCam编辑页设计.md', OUT / '编辑页设计与映射.md')
shutil.copy2(ROOT / 'docs/导出性能排查.md', OUT / '导出性能排查.md')
for name in current_docs:
    shutil.copy2(ROOT / 'docs' / name, OUT / name)

sources = []
# Only the integrated launcher artwork belongs to the corresponding application source.
# Parallel design proposals are separate deliverables and may still be under review.
for directory in ('android', 'scripts', 'docs', 'design/app-icon/v4-spectrum-film-2026-11-11', 'third_party', 'patches'):
    for path in (ROOT / directory).rglob('*'):
        rel = path.relative_to(ROOT)
        if not path.is_file() or any(p in ('build', '.gradle', '.cxx', '__pycache__') for p in rel.parts): continue
        if path.name in ('local.properties', '.DS_Store') or path.name.startswith('emulator-'): continue
        if rel.parts[:7] == ('android','app','src','main','assets','film_icons','grlife'): continue
        if rel.parts[:6] == ('android','app','src','main','assets','engine'): continue
        sources.append(path)
for name in ('README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md', 'UPSTREAMS.md', 'NOTICE', 'CITATION.cff', 'CHANGELOG.md', '.gitignore', '.gitmodules', '.gitattributes'):
    sources.append(ROOT / name)
if (ROOT / 'SpektraLab/.git').exists():
    tracked = subprocess.check_output(['git', '-C', str(ROOT / 'SpektraLab'), 'ls-files', '-z']).decode().split('\0')
else:
    tracked = [p.relative_to(ROOT / 'SpektraLab').as_posix() for p in (ROOT / 'SpektraLab').rglob('*')
               if p.is_file() and not any(part in ('build', '.git', '__pycache__', '.DS_Store') for part in p.parts)]
sources.extend(ROOT / 'SpektraLab' / name for name in tracked if name and (ROOT / 'SpektraLab' / name).is_file())
archive = OUT / f'SpektraLens-v{version}-完整源码.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in sorted(set(sources)):
        z.write(path, 'SpektraLens/' + path.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert 'SpektraLens/SpektraLab/engine/src/gpu/vulkan_gpu.cpp' in z.namelist()
    assert 'SpektraLens/android/gradle/wrapper/gradle-wrapper.jar' in z.namelist()
    assert 'SpektraLens/third_party/SwiftShader-cddb5c91-source.zip' in z.namelist()
    assert 'SpektraLens/scripts/build_swiftshader.py' in z.namelist()
    assert 'SpektraLens/patches/android-gpu-v0.2.3.patch' in z.namelist()
    assert 'SpektraLens/scripts/prepare_engine.py' in z.namelist()
    assert 'SpektraLens/third_party/SpektraLab-Android-v1.3.3j-source.zip' in z.namelist()

bundle_files = [OUT / f'SpektraLens-v{version}-debug.apk', archive, OUT / 'latest.json']
bundle_files += [OUT / name for name in ('安装与使用说明.md', '验证记录.md', '来源与许可.md', '图标映射.md', '兼容显影实现.md', '移动GPU移植.md', '界面图标检查.md', '编辑页设计与映射.md', '导出性能排查.md', 'UPSTREAMS.md', 'NOTICE')]
bundle_files += [OUT / name for name in current_docs]
for directory in (OUT / 'verification' / f'v{version}', OUT / f'screenshots-v{version}'):
    if directory.exists(): bundle_files += [p for p in directory.rglob('*') if p.is_file()]
bundle = OUT / f'SpektraLens-v{version}-交付包.zip'
with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    checksums = []
    for path in sorted(bundle_files):
        name = path.relative_to(OUT).as_posix()
        z.write(path, name)
        checksums.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {name}\n')
    z.writestr('SHA256SUMS.txt', ''.join(checksums))
with zipfile.ZipFile(bundle) as z:
    assert z.testzip() is None

sha = {}
for path in OUT.rglob('*'):
    if path.is_file() and path.suffix in ('.apk','.zip','.md','.png','.mp4','.json'):
        sha[path.relative_to(OUT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
(OUT / 'SHA256SUMS.txt').write_text(''.join(f'{value}  {name}\n' for name,value in sorted(sha.items())))
print(json.dumps({p.name:p.stat().st_size for p in OUT.iterdir() if p.is_file()},ensure_ascii=False,indent=2))
