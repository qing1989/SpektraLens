#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Run export option and actual image-writer regressions without building an APK."""
from pathlib import Path
import os
import platform
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
engine = root / ".tooling/android-engine-source/engine"
if not engine.is_dir():
    engine = root / "SpektraLab/engine"
native = root / "android/app/src/main/cpp"
with tempfile.TemporaryDirectory(prefix="spektra-export-tests-") as temporary:
    output = Path(temporary)
    subprocess.run(
        ["javac", "-encoding", "UTF-8", "-d", str(output),
         str(root / "android/app/src/main/java/app/spektrafilm/ExportOptions.java"),
         str(root / "android/app/src/test/java/app/spektrafilm/ExportOptionsTest.java")],
        check=True,
    )
    subprocess.run(["java", "-cp", str(output), "app.spektrafilm.ExportOptionsTest"], check=True)
    executable = output / "export_quality_test"
    command = [
        os.environ.get("CXX", "c++"), "-std=c++20", "-O2", "-ffunction-sections", "-fdata-sections",
        "-I" + str(native), "-I" + str(engine / "include"), "-I" + str(engine / "src"),
        "-I" + str(engine / "src/core"), "-I" + str(engine / "host"),
        "-I" + str(engine / "third_party/stb"),
        str(root / "android/app/src/test/cpp/export_quality_test.cpp"),
        str(native / "edit_pipeline.cpp"), str(native / "output_spaces.cpp"),
        str(engine / "src/core/json.cpp"),
        str(engine / "host/output.cpp"), str(engine / "host/platform.cpp"),
        str(engine / "host/stb_impl.cpp"), "-lz",
        "-Wl,-dead_strip" if platform.system() == "Darwin" else "-Wl,--gc-sections",
        "-o", str(executable),
    ]
    subprocess.run(command, check=True)
    subprocess.run(
        [str(executable), str(root / "android/app/src/main/assets/engine"), str(output / "images")],
        check=True,
    )
