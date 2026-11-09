#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Install and test real APKs. Optional camera fixture download is hash checked and local only."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request
ROOT = Path(__file__).resolve().parent.parent
ADB = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/sdk')) / 'platform-tools/adb'
URL = 'https://raw.githubusercontent.com/letmaik/rawpy/2705f5b223c162c3cbc48474fe1a01c3cf09fa4f/test/iss030e122639.NEF'
SHA = '5922721d13f11795557d97fdeb0a60b900086c402bc82a848ff280d15b99ffd4'
p = argparse.ArgumentParser()
p.add_argument('--fetch-camera-fixture', action='store_true', help='Download a rawpy test NEF to .tooling only')
args = p.parse_args()
fixture = ROOT / '.tooling/raw-fixtures/iss030e122639.NEF'
if not fixture.exists() and args.fetch_camera_fixture:
    fixture.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(URL, timeout=60) as r: data = r.read()
    if hashlib.sha256(data).hexdigest() != SHA: raise SystemExit('Camera fixture checksum mismatch')
    fixture.write_bytes(data)
if not fixture.exists(): raise SystemExit('Camera fixture missing; pass --fetch-camera-fixture or supply the documented local file')
if hashlib.sha256(fixture.read_bytes()).hexdigest() != SHA: raise SystemExit('Camera fixture checksum mismatch')
def adb(*args): return subprocess.run([str(ADB), *map(str,args)], check=True)
adb('install', '-r', ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk')
adb('install', '-r', ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
adb('push', fixture, '/data/local/tmp/SpektraLens-camera-fixture.NEF')
adb('shell', 'run-as', 'app.spektrafilm', 'mkdir', '-p', 'files/test-fixtures')
adb('shell', 'run-as', 'app.spektrafilm', 'cp', '/data/local/tmp/SpektraLens-camera-fixture.NEF', 'files/test-fixtures/iss030e122639.NEF')
adb('shell', 'am', 'force-stop', 'app.spektrafilm')
result = subprocess.check_output([str(ADB), 'shell', 'am', 'instrument', '-w', 'app.spektrafilm.test/android.test.InstrumentationTestRunner'], text=True)
print(result)
if 'OK (' not in result or 'FAILURES!!!' in result or 'INSTRUMENTATION_FAILED' in result: raise SystemExit('Device tests failed')
