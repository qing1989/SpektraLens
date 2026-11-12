#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Install and test real APKs. Optional camera fixture download is hash checked and local only."""
import argparse
import hashlib
import os
import re
from pathlib import Path
import subprocess
import urllib.request
ROOT = Path(__file__).resolve().parent.parent
ADB = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/sdk')) / 'platform-tools/adb'
URL = 'https://raw.githubusercontent.com/letmaik/rawpy/2705f5b223c162c3cbc48474fe1a01c3cf09fa4f/test/iss030e122639.NEF'
SHA = '5922721d13f11795557d97fdeb0a60b900086c402bc82a848ff280d15b99ffd4'
p = argparse.ArgumentParser()
p.add_argument('--fetch-camera-fixture', action='store_true', help='Download a rawpy test NEF to .tooling only')
p.add_argument('--test-update-install', action='store_true',
               help='Also test the system update confirmation UI on an isolated emulator')
args = p.parse_args()
if args.test_update_install:
    devices = subprocess.check_output([str(ADB), 'devices'], text=True).splitlines()[1:]
    connected = [line.split()[0] for line in devices if line.endswith('\tdevice')]
    if len(connected) != 1 or not connected[0].startswith('emulator-'):
        raise SystemExit('--test-update-install requires exactly one connected emulator')
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
def instrument(*selector):
    result = subprocess.check_output([
        str(ADB), 'shell', 'am', 'instrument', '-w', *selector,
        'app.spektrafilm.test/android.test.InstrumentationTestRunner'], text=True)
    print(result, flush=True)
    if not re.search(r'OK \(\d+ tests?\)', result) or 'FAILURES!!!' in result or 'INSTRUMENTATION_FAILED' in result:
        raise SystemExit('Device tests failed')

previous_mode = None
operation = 'REQUEST_INSTALL_PACKAGES'
if args.test_update_install:
    status = subprocess.check_output([str(ADB), 'shell', 'appops', 'get', 'app.spektrafilm', operation], text=True)
    match = re.search(r'REQUEST_INSTALL_PACKAGES:\s*(\w+)', status)
    previous_mode = match.group(1) if match else 'default'
try:
    if args.test_update_install:
        adb('shell', 'appops', 'set', 'app.spektrafilm', operation, 'deny')
    test_sources = ROOT / 'android/app/src/androidTest/java/app/spektrafilm'
    classes = sorted('app.spektrafilm.' + path.stem
                     for path in test_sources.glob('*InstrumentedTest.java')
                     if path.stem != 'UpdateInstallInstrumentedTest')
    instrument('-e', 'class', ','.join(classes))
    if args.test_update_install:
        adb('shell', 'am', 'force-stop', 'app.spektrafilm')
        adb('shell', 'appops', 'set', 'app.spektrafilm', operation, 'allow')
        instrument('-e', 'class', 'app.spektrafilm.UpdateInstallInstrumentedTest')
finally:
    if previous_mode is not None:
        # Revoking this app-op may kill the app, so restore it after instrumentation exits.
        adb('shell', 'am', 'force-stop', 'app.spektrafilm')
        adb('shell', 'appops', 'set', 'app.spektrafilm', operation, previous_mode)
