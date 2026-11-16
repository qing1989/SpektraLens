#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Verify public builds omit sponsor artwork and private builds inject exact JPEG bytes."""
import filecmp
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'android/app/build'
SOURCE = ROOT / '.local/sponsor/wechat-appreciation.jpg'
GENERATED = BUILD / 'generated/sponsor-res/raw/sponsor_wechat_qr.jpg'
SYMBOLS = (
    BUILD
    / 'intermediates/symbol_list_with_package_name/debug/processDebugResources'
    / 'package-aware-r.txt'
)


def build(*arguments):
    subprocess.run(
        [sys.executable, str(ROOT / 'scripts/build.py'), *arguments, '--offline'],
        cwd=ROOT,
        check=True,
    )


build(
    '-PpublicBuild=true',
    ':app:processDebugResources',
)
if GENERATED.exists():
    raise SystemExit(f'public build leaked sponsor artwork: {GENERATED}')
if SYMBOLS.is_file() and 'sponsor_wechat_qr' in SYMBOLS.read_text(encoding='utf-8'):
    raise SystemExit('public build leaked sponsor resource symbol')

build(':app:prepareSponsorAsset', ':app:processDebugResources')
if SOURCE.is_file():
    if not GENERATED.is_file() or not filecmp.cmp(SOURCE, GENERATED, shallow=False):
        raise SystemExit('private sponsor artwork bytes do not match the source')
    if not SYMBOLS.is_file() or 'sponsor_wechat_qr' not in SYMBOLS.read_text(encoding='utf-8'):
        raise SystemExit('private build did not export the sponsor resource symbol')
    print('public build omitted sponsor artwork; private build injected exact JPEG bytes')
else:
    if GENERATED.exists():
        raise SystemExit('sponsor artwork was generated without a private source')
    print('no private sponsor artwork configured; sponsor entry will be hidden')
