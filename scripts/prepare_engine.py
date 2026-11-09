#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Prepare the pinned Android GPU overlay without editing the upstream checkout."""
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def prepare():
    patch = ROOT / 'patches/android-gpu-v0.2.3.patch'
    manifest = ROOT / 'patches/android-gpu-base.json'
    data = json.loads(manifest.read_text())
    for name, expected in data['files'].items():
        path = ROOT / 'SpektraLab' / name
        actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
        if actual != expected:
            raise RuntimeError('Android GPU patch baseline changed: ' + name)
    fingerprint = hashlib.sha256(patch.read_bytes() + manifest.read_bytes()).hexdigest()
    stage = ROOT / '.tooling/android-engine-source'
    stamp = stage / '.android-gpu-ready'
    if stamp.exists() and stamp.read_text() == fingerprint:
        return stage / 'engine'
    temporary = ROOT / '.tooling/android-engine-pending'
    if temporary.exists():
        shutil.rmtree(temporary)
    temporary.mkdir(parents=True)
    shutil.copytree(ROOT / 'SpektraLab/engine', temporary / 'engine',
                    ignore=shutil.ignore_patterns('build', '__pycache__', '.DS_Store'))
    patch_env = dict(os.environ, GIT_CEILING_DIRECTORIES=str(ROOT / '.tooling'))
    patch_env.pop('GIT_DIR', None)
    patch_env.pop('GIT_WORK_TREE', None)
    subprocess.run(['git', 'apply', '--whitespace=nowarn', str(patch)],
                   cwd=temporary, env=patch_env, check=True)
    (temporary / '.android-gpu-ready').write_text(fingerprint)
    if stage.exists():
        shutil.rmtree(stage)
    temporary.replace(stage)
    return stage / 'engine'


if __name__ == '__main__':
    print(prepare())
