#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Build with an existing JDK 17/Android SDK; paths containing spaces are supported."""
import glob
import os
import subprocess
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
# Toolchain lives in .tooling/ (or SPEKTRA_TOOLING). The sibling-project path below is a
# convenience for this machine only; without either, pass JAVA_HOME and ANDROID_HOME.
TOOLING = Path(os.environ.get('SPEKTRA_TOOLING') or ROOT / '.tooling')
SIBLING = ROOT.parent / '听书 App/.tooling'
ROOTS = [TOOLING] + ([SIBLING] if SIBLING.exists() else [])

sdk = Path(os.environ.get('ANDROID_HOME') or next(
    (r / 'sdk' for r in ROOTS if (r / 'sdk').exists()), ROOT / '.tooling/sdk'))
jdk = os.environ.get('JAVA_HOME')
if not jdk:
    candidates = [p for r in ROOTS for p in glob.glob(str(r / 'jdk/*/Contents/Home'))]
    if not candidates:
        sys.exit(
            'Set JAVA_HOME to JDK 17, or point SPEKTRA_TOOLING at a directory holding '
            'jdk/ and sdk/.\nLooked in: '
            + ', '.join(str(r / 'jdk') for r in ROOTS))
    jdk = candidates[0]
cache = next((r / 'gradle-home' for r in ROOTS if (r / 'gradle-home').exists()),
             ROOT / '.tooling/gradle-home')
env = dict(os.environ, JAVA_HOME=jdk, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
           GRADLE_USER_HOME=os.environ.get('GRADLE_USER_HOME', str(cache)))
(ROOT / 'android/local.properties').write_text('sdk.dir=' + str(sdk) + '\n')
subprocess.run([sys.executable, str(ROOT / 'scripts/prepare_resources.py')], env=env, check=True)
subprocess.run([sys.executable, str(ROOT / 'scripts/validate_film_icons.py')], env=env, check=True)
gradle = next((r / 'gradle-8.11.1/bin/gradle' for r in ROOTS
               if (r / 'gradle-8.11.1/bin/gradle').exists()), None)
if gradle is None: gradle = ROOT / 'android/gradlew'
cmd = [str(gradle)] + (sys.argv[1:] or [':app:assembleDebug', ':app:lintDebug']) + ['--console=plain']
build_proxy = os.environ.get('SPEKTRALENS_BUILD_PROXY') or os.environ.get('SPEKTRA_BUILD_PROXY')
if build_proxy:
    from urllib.parse import urlparse
    proxy = urlparse(build_proxy)
    for protocol in ('http', 'https'):
        cmd += [f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 80}']
    cmd += ['-Dhttp.nonProxyHosts=localhost|127.*|repo.maven.apache.org']
sys.exit(subprocess.call(cmd, cwd=ROOT / 'android', env=env))
