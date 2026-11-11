#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Run update policy JVM tests without Android SDK or third-party test dependencies."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory(prefix='spektra-update-tests-') as output:
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', output,
                    str(root / 'android/app/src/main/java/app/spektrafilm/UpdateManifest.java'),
                    str(root / 'android/app/src/test/java/app/spektrafilm/UpdateManifestTest.java')], check=True)
    subprocess.run(['java', '-cp', output, 'app.spektrafilm.UpdateManifestTest'], check=True)
