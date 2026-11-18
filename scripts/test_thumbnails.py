#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Run host-JVM thumbnail scheduling, publication-race and disk-cache simulations."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
jdk = os.environ.get("JAVA_HOME")
if not jdk:
    jdk = next(root.glob(".tooling/jdk/*/Contents/Home"), None)
bin_dir = Path(jdk) / "bin" if jdk else Path("/usr/bin")
main = root / "android/app/src/main/java/app/spektrafilm"
test = root / "android/app/src/test/java/app/spektrafilm/ThumbnailPerformanceTest.java"
with tempfile.TemporaryDirectory(prefix="spektra-thumbnail-tests-") as output:
    subprocess.run([str(bin_dir / "javac"), "-encoding", "UTF-8", "-d", output,
                    *(str(main / name) for name in (
                        "ThumbnailWorkQueue.java", "ThumbnailWriteGate.java", "ThumbnailDiskCache.java")),
                    str(test)], check=True)
    subprocess.run([str(bin_dir / "java"), "-cp", output,
                    "app.spektrafilm.ThumbnailPerformanceTest"], check=True)
