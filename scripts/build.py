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
FALLBACK = ROOT.parent / '听书 App/.tooling'
sdk = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/sdk'))
jdk = os.environ.get('JAVA_HOME')
if not jdk:
    candidates = glob.glob(str(ROOT / '.tooling/jdk/*/Contents/Home')) + glob.glob(str(FALLBACK / 'jdk/*/Contents/Home'))
    if not candidates:
        sys.exit(
            'Set JAVA_HOME to JDK 17.\n'
            f'Looked in: {ROOT / ".tooling/jdk"}  and  {FALLBACK / "jdk"}\n'
            '(the second path belongs to a sibling project and only exists on this machine)')
    jdk = candidates[0]
cache = FALLBACK / 'gradle-home' if (FALLBACK / 'gradle-home').exists() else ROOT / '.tooling/gradle-home'
env = dict(os.environ, JAVA_HOME=jdk, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
           GRADLE_USER_HOME=os.environ.get('GRADLE_USER_HOME', str(cache)))
(ROOT / 'android/local.properties').write_text('sdk.dir=' + str(sdk) + '\n')
subprocess.run([sys.executable, str(ROOT / 'scripts/prepare_resources.py')], env=env, check=True)
subprocess.run([sys.executable, str(ROOT / 'scripts/validate_film_icons.py')], env=env, check=True)
gradle = ROOT / '.tooling/gradle-8.11.1/bin/gradle'
if not gradle.exists(): gradle = FALLBACK / 'gradle-8.11.1/bin/gradle'
if not gradle.exists(): gradle = ROOT / 'android/gradlew'
cmd = [str(gradle)] + (sys.argv[1:] or [':app:assembleDebug', ':app:lintDebug']) + ['--console=plain']
build_proxy = os.environ.get('SPEKTRALENS_BUILD_PROXY') or os.environ.get('SPEKTRA_BUILD_PROXY')
if build_proxy:
    from urllib.parse import urlparse
    proxy = urlparse(build_proxy)
    for protocol in ('http', 'https'):
        cmd += [f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 80}']
    cmd += ['-Dhttp.nonProxyHosts=localhost|127.*|repo.maven.apache.org']
sys.exit(subprocess.call(cmd, cwd=ROOT / 'android', env=env))
