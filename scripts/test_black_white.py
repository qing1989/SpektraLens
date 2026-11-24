#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Validate bundled black-and-white data and Java policy; optionally render with upstream Metal."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android/app/src/main/assets"
UPSTREAM = ROOT / "SpektraLab/engine"
FILMS = {
    "kodak_tri_x_400", "kodak_tmax_100", "fujifilm_neopan_acros_100_ii", "ilford_hp5_plus_400",
}
PAPER = "ilford_multigrade_iv_rc"
CINESTILL = "cinestill_800t"


def resources_check():
    catalog = json.loads((ASSETS / "catalog.json").read_text())
    assert len(catalog["films"]) == 25 and len(catalog["papers"]) == 9
    assert catalog["films"][0]["stock"] == "fujifilm_c200", "0.4.6 default film order changed"
    assert {item["stock"] for item in catalog["films"] if item.get("monochrome")} == FILMS
    assert {item["stock"] for item in catalog["papers"] if item.get("monochrome")} == {PAPER}
    cinestill = next(item for item in catalog["films"] if item["stock"] == CINESTILL)
    assert cinestill["name"] == "CineStill 800T"
    assert cinestill["type"] == "negative"
    assert cinestill["target_print"] == "kodak_2383"
    assert cinestill["antihalation"] == "no"
    expected = {item["stock"] + ".json" for item in catalog["films"] + catalog["papers"]}
    assert {path.name for path in (ASSETS / "engine/profiles").glob("*.json")} == expected
    for item in catalog["films"]:
        if item["stock"] in FILMS:
            assert item["target_print"] == PAPER
    for source in (UPSTREAM / "resources/profiles").glob("*.json"):
        assert (ASSETS / "engine/profiles" / source.name).read_bytes() == source.read_bytes()
    for stock in FILMS | {PAPER, CINESTILL}:
        source = UPSTREAM / "resources_product/profiles" / (stock + ".json")
        assert (ASSETS / "engine/profiles" / source.name).read_bytes() == source.read_bytes()
    assert (ASSETS / "engine/neutral_print_filters.json").read_bytes() == (
        UPSTREAM / "resources/neutral_print_filters.json").read_bytes()
    assert (ASSETS / "licenses/SpektraLab-Product-Data-NOTICE.txt").read_bytes() == (
        UPSTREAM / "resources_product/NOTICE.md").read_bytes()
    assert (ASSETS / "engine/vulkan/spk_highlight_headroom.spv").is_file()
    from validate_film_icons import validate
    assert validate()["project_drawn_configurations"] == 25
    print("PASS resources: 25 films / 9 papers; upstream bytes, attribution and artwork intact", flush=True)


def java_checks(temporary):
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
    subprocess.run([
        javac, "-encoding", "UTF-8", "-d", str(temporary),
        str(ROOT / "android/app/src/main/java/app/spektrafilm/FilmStockPolicy.java"),
        str(ROOT / "android/app/src/test/java/app/spektrafilm/FilmStockPolicyTest.java"),
    ], check=True)
    fixtures = temporary / "wire.json"
    subprocess.run([
        java, "-cp", str(temporary), "app.spektrafilm.FilmStockPolicyTest", str(fixtures),
    ], check=True)
    return json.loads(fixtures.read_text())


def render_checks(library, metal_resources, temporary, wires, output, photo):
    import numpy as np
    from PIL import Image
    sys.path.insert(0, str(UPSTREAM / "tests"))
    from spk_ctypes import Engine

    resources = temporary / "resources"
    shutil.copytree(ASSETS / "engine", resources)
    shaders = list(metal_resources.glob("*.metallib*"))
    assert shaders, "Pass the resources directory belonging to the Metal reference library"
    for shader in shaders:
        shutil.copy2(shader, resources / shader.name)
    colors = np.array([
        [.184, .184, .184], [.45, .04, .03], [.08, .25, .06], [.15, .28, .55], [.03, .05, .35],
    ], dtype=np.float32)
    chart = np.repeat(np.repeat(colors[None, :, :], 48, axis=1), 48, axis=0)
    levels = np.geomspace(.184 / 8, .184 * 8, 9).astype(np.float32)
    ramp = np.tile(np.repeat(levels, 32)[None, :, None], (48, 1, 3))
    photograph = None
    if photo:
        with Image.open(photo) as source:
            sample = source.convert("RGB")
            sample.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
            photograph = np.asarray(sample, dtype=np.float32) / 255
    common = dict(
        input_color_space="sRGB", input_cctf_decoding=False,
        output_color_space="sRGB", output_cctf_encoding=True, auto_exposure=False,
        film_format_mm=36, preview_long_edge=1024, scan_film=False,
        print_exposure=1, exposure_compensation_ev=0,
        m_filter_shift=0, y_filter_shift=0, halation_amount=1, glare_amount=1,
        dir_couplers_amount=1, halation_scatter_amount=1, density_curve_gamma=1,
        scene_latitude_active=False, contrast_mask_active=False,
        overscan_active=False, date_imprint_active=False,
    )
    quiet = dict(grain_active=False, glare_active=False, halation_active=False, dir_couplers_active=False)
    results = []
    with Engine(library=library, resources=resources) as engine:
        backend = engine.capabilities()["backend"]
        assert backend["render_core"] == "native-metal", backend
        print("Reference backend:", json.dumps(backend), flush=True)
        for film in sorted(FILMS):
            params = {**common, "film_stock": film, **wires[film], **quiet}
            with engine.open(chart, {**params, "product_defaults": True}) as session:
                full = session.render("full")[0]
                live = session.render("live")[0]
            spread = float(np.ptp(full[:, :, :3].astype(float), axis=2).max() / 65535)
            assert spread < .002, (film, "non-neutral print", spread)
            mid = float(full[8:-8, 8:40, 1].mean() / 65535)
            linear = ((mid + .055) / 1.055) ** 2.4 if mid > .04045 else mid / 12.92
            density = float(-np.log10(max(linear, 1e-6)))
            assert .55 < density < .95, (film, "misplaced mid-grey", density)
            delta = int(np.abs(full.astype(np.int32) - live.astype(np.int32)).max())
            assert delta <= 1, (film, "live/full mismatch at equal dimensions", delta)
            with engine.open(ramp, {**params, "product_defaults": True}) as session:
                tones = session.render("full")[0][24, 16::32, 1].astype(np.int32)
            assert np.all(np.diff(tones) > 0), (film, "collapsed/inverted grey steps", tones)
            defaults = {**common, "film_stock": film, **wires[film],
                        "halation_active": True, "glare_active": True, "dir_couplers_active": True}
            with engine.open(chart, {**defaults, "product_defaults": True}) as session:
                effected = session.render("full")[0]
                readback = session.get_params()
            assert readback["grain_sublayers_active"] is False
            assert abs(readback["grain_amount"] - wires[film]["grain_amount"]) < 1e-9
            assert readback["y_filter_neutral"] == 68
            effected_spread = float(np.ptp(effected[:, :, :3].astype(float), axis=2).max() / 65535)
            assert effected_spread < .01, (film, "default effects introduce a color cast", effected_spread)
            grain_source = np.full((384, 384, 3), .184, dtype=np.float32)
            grain_frames = []
            for amount in (0, wires[film]["grain_amount"], min(2, 2 * wires[film]["grain_amount"])):
                grain_params = {**params, "grain_amount": amount, "grain_active": amount > 0}
                with engine.open(grain_source, {**grain_params, "product_defaults": True}) as session:
                    grain_frames.append(session.render("full")[0])
            assert not np.array_equal(grain_frames[0], grain_frames[1]), (film, "grain has no effect")
            assert not np.array_equal(grain_frames[1], grain_frames[2]), (film, "grain is saturated")
            results.append(dict(film=film, mid_density=density, neutral_spread=spread,
                                default_spread=effected_spread, live_full_max_u16=delta,
                                grey_steps=tones.tolist(),
                                grain_std_u16=[float(frame[16:-16, 16:-16, 1].std())
                                               for frame in grain_frames]))
            if output:
                Image.fromarray(((effected[:, :, :3].astype(np.uint32) + 128) // 257).astype(
                    np.uint8)).save(output / (film + ".png"))
                if photograph is not None:
                    with engine.open(photograph, {**defaults, "input_cctf_decoding": True,
                                                  "product_defaults": True}) as session:
                        image = session.render("full")[0]
                    Image.fromarray(((image[:, :, :3].astype(np.uint32) + 128) // 257).astype(
                        np.uint8)).save(output / (film + "-photo.png"))
            print("PASS", json.dumps(results[-1]), flush=True)
        color = {**common, "film_stock": "kodak_portra_400",
                 **wires["kodak_portra_400"], **quiet}
        with engine.open(chart, {**color, "product_defaults": True}) as session:
            before = session.render("full")[0]
            for film in sorted(FILMS):
                session.set_params({**common, "film_stock": film, **wires[film], **quiet})
                session.render("full")
                session.set_params(color)
                after = session.render("full")[0]
                assert np.array_equal(before, after), (film, "color round trip changed pixels")
        print("PASS four black-and-white/color round trips: byte-identical restored color", flush=True)
    if output:
        (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path)
    parser.add_argument("--metal-resources", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--photo", type=Path)
    args = parser.parse_args()
    if args.library and not args.metal_resources:
        parser.error("--library requires --metal-resources")
    if args.photo and (not args.library or not args.output):
        parser.error("--photo requires --library and --output")
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
    resources_check()
    with tempfile.TemporaryDirectory(prefix="spektra-black-white-") as directory:
        temporary = Path(directory)
        wires = java_checks(temporary)
        if args.library:
            render_checks(args.library.resolve(), args.metal_resources.resolve(), temporary,
                          wires, args.output, args.photo)
    if not args.library:
        print("Native render checks not run; pass --library and --metal-resources to enable.")


if __name__ == "__main__":
    main()
