#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
"""Small adb UI helper for reviewable device smoke tests (no private APIs)."""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/sdk')) / 'platform-tools/adb'

def adb(*args):
    return subprocess.check_output([str(ADB), *args])

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/spektra-smoke.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/spektra-smoke.xml')).iter('node'))

def tap(text=None, desc=None, timeout=15):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        for node in nodes():
            if ((text is not None and node.get('text') == text) or
                (desc is not None and node.get('content-desc') == desc)):
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                if x2 > x1 and y2 > y1 and node.get('enabled') == 'true':
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    print('Tapped', text or desc, flush=True)
                    time.sleep(.35)
                    return
        time.sleep(.25)
    raise RuntimeError('Cannot find enabled control: ' + str(text or desc))

def wait_render(timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if any(n.get('text') == '导出照片' and n.get('enabled') == 'true' for n in nodes()):
            return
    raise RuntimeError('Render not complete')

def settings():
    root = ET.fromstring(adb('shell', 'run-as', 'app.spektrafilm', 'cat', 'shared_prefs/darkroom.xml'))
    return json.loads(root.find("string[@name='settings']").text)

def screenshot(name):
    target = ROOT / 'artifacts' / (name + '.png')
    target.parent.mkdir(exist_ok=True)
    target.write_bytes(adb('exec-out', 'screencap', '-p'))
    print('Screenshot', target.name, flush=True)

if __name__ == '__main__':
    command = sys.argv[1]
    if command == 'dump':
        for n in nodes():
            if n.get('text') or n.get('content-desc'):
                print(n.get('text'), '|', n.get('content-desc'), '|', n.get('bounds'))
    elif command == 'tap': tap(text=sys.argv[2])
    elif command == 'desc': tap(desc=sys.argv[2])
    elif command == 'shot': screenshot(sys.argv[2])
    elif command == 'prefs': print(json.dumps(settings(), ensure_ascii=False, indent=2))
