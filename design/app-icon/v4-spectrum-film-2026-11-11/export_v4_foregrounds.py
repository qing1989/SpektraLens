#!/usr/bin/env python3
"""Export SpektraLens v4 adaptive foregrounds from the approved 1024 master.

Maps icon-master-1024.png onto the center 72dp of a 108dp layer.
Do not use scripts/export_app_icon.swift (that shrinks v1 artwork to 60dp).
"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
MASTER = HERE / "icon-master-1024.png"
OUT = HERE / "exported-foregrounds"
DENSITIES = (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432))
LAYER = 1536  # 108dp source; 72/108 * 1536 = 1024


def main() -> None:
    master = Image.open(MASTER).convert("RGB")
    if master.size != (1024, 1024):
        raise SystemExit(f"expected 1024x1024 master, got {master.size}")
    layer = Image.new("RGB", (LAYER, LAYER), (0, 0, 0))
    off = (LAYER - 1024) // 2
    layer.paste(master, (off, off))
    layer.save(HERE / "adaptive-foreground-1536.png", "PNG")
    OUT.mkdir(exist_ok=True)
    for name, px in DENSITIES:
        layer.resize((px, px), Image.Resampling.LANCZOS).save(
            OUT / f"ic_spektra_film_s_foreground-{name}-{px}.png", "PNG"
        )
        print(name, px)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
