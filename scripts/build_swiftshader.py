#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Rebuild the bundled headless CPU Vulkan driver with Android NDK 27.

No source edits or OS configuration changes are required. The generated Ninja
adapter selects Android's anonymous JIT mapping, avoiding Linux memfd execmod.
"""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
COMMIT = 'cddb5c91b23ff70a1f607ea31610aa0d7f4b5ad5'
ARCHIVE = ROOT / 'third_party/SwiftShader-cddb5c91-source.zip'
ARCHIVE_SHA = '6d1e0f0b373979429ab15adc706087d6c447190bb18946de5192690bcc460033'


def adapt_ninja(path, source):
    text = path.read_text()
    script = str(source / 'src/Vulkan/vk_swiftshader.lds')
    text = text.replace('-Wl,--version-script=' + script,
                        '-Wl,--version-script=' + shlex.quote(script))
    # Bionic supplies pthread in libc. The Linux headless target adds -lpthread.
    text = text.replace(' -lpthread ', ' ')
    lines = text.splitlines()
    active = False
    found = False
    for i, line in enumerate(lines):
        if line.startswith('build '):
            active = 'ReactorLLVM.dir/ExecutableMemory.cpp.o:' in line
        if active and line.startswith('  FLAGS ='):
            # Restore this source's existing Android mmap/prctl implementation;
            # the other files use POSIX headless code instead of Android HAL WSI.
            lines[i] = line + ' -D__ANDROID__=1 -UANDROID_NDK_BUILD'
            found = True
    if not found:
        raise RuntimeError('Pinned Reactor memory translation unit not found')
    path.write_text('\n'.join(lines) + '\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, help='Use an existing SwiftShader source checkout')
    p.add_argument('--abis', nargs='+', choices=['arm64-v8a', 'x86_64'], default=['arm64-v8a', 'x86_64'])
    p.add_argument('--jobs', type=int, default=min(6, os.cpu_count() or 2))
    args = p.parse_args()
    if hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() != ARCHIVE_SHA:
        raise SystemExit('Vendored SwiftShader source checksum mismatch')
    source = args.source.resolve() if args.source else ROOT / '.tooling/SwiftShader-vendor/SwiftShader'
    if not args.source and not (source.parent / '.source-ready').exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(ARCHIVE) as z:
            if z.testzip() is not None:
                raise SystemExit('SwiftShader source ZIP is damaged')
            z.extractall(source.parent)
        (source.parent / '.source-ready').write_text(ARCHIVE_SHA)
    sdk = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/sdk')).resolve()
    ndk = Path(os.environ.get('ANDROID_NDK_HOME', sdk / 'ndk/27.2.12479018')).resolve()
    cmake = Path(os.environ.get('CMAKE', sdk / 'cmake/3.22.1/bin/cmake'))
    ninja = cmake.with_name('ninja')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}[platform.system()]
    strip = ndk / 'toolchains/llvm/prebuilt' / host / 'bin/llvm-strip'
    for abi in args.abis:
        build = ROOT / '.tooling' / ('swiftshader-build-' + abi)
        subprocess.run([
            str(cmake), '-S', str(source), '-B', str(build), '-G', 'Ninja',
            '-DCMAKE_MAKE_PROGRAM=' + str(ninja),
            '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
            '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=29', '-DANDROID_STL=c++_static',
            '-DCMAKE_BUILD_TYPE=Release', '-DSWIFTSHADER_BUILD_TESTS=OFF',
            '-DSWIFTSHADER_BUILD_BENCHMARKS=OFF', '-DSWIFTSHADER_WARNINGS_AS_ERRORS=OFF',
            '-DSWIFTSHADER_ENABLE_ASTC=OFF', '-DREACTOR_BACKEND=LLVM',
            '-DSWIFTSHADER_LLVM_VERSION=10.0', '-DLINUX=TRUE',
            '-DSWIFTSHADER_BUILD_WSI_XCB=OFF', '-DSWIFTSHADER_BUILD_WSI_WAYLAND=OFF',
            '-DSWIFTSHADER_BUILD_WSI_DIRECTFB=OFF',
            '-DCMAKE_CXX_FLAGS_RELEASE=-O2 -DNDEBUG -U__ANDROID__',
            '-DCMAKE_C_FLAGS_RELEASE=-O2 -DNDEBUG -U__ANDROID__',
            '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--undefined-version',
        ], check=True)
        adapt_ninja(build / 'build.ninja', source)
        subprocess.run([str(cmake), '--build', str(build), '--target', 'vk_swiftshader',
                        '--parallel', str(max(1, args.jobs))], check=True)
        target = ROOT / 'android/app/src/main/jniLibs' / abi / 'libvk_swiftshader.so'
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix('.pending')
        shutil.copy2(build / 'libvk_swiftshader.so', temporary)
        subprocess.run([str(strip), '--strip-unneeded', str(temporary)], check=True)
        temporary.replace(target)
        print(abi, hashlib.sha256(target.read_bytes()).hexdigest(), flush=True)


if __name__ == '__main__':
    main()
