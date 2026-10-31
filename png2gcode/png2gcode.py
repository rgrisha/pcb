#!/usr/bin/env python3
"""
png2gcode - turn a strictly black/white PNG into raster G-code for a 3018
laser engraver (GRBL 1.1 in laser mode, $32=1).

White pixels burn, black pixels don't. --invert swaps that.

The image is scanned in horizontal lines, alternating direction. Each run of
burn pixels becomes one G1 move at full power. The head runs --overscan mm
past each run with the laser at S0, so it is at full speed while burning.

Origin: X0 Y0 is the bottom-left corner of the image.
"""

import argparse
import sys

import numpy as np
from PIL import Image


def die(msg):
    print(f"png2gcode: error: {msg}", file=sys.stderr)
    sys.exit(1)


def load_mask(path, invert):
    """Return (burn mask, dpi or None). Exit if the image isn't pure black/white."""
    try:
        img = Image.open(path)
        img.load()
    except (OSError, ValueError) as e:
        die(f"cannot read {path}: {e}")

    dpi = img.info.get("dpi")
    rgba = np.asarray(img.convert("RGBA"))
    rgb, alpha = rgba[..., :3], rgba[..., 3]

    white = (rgb == 255).all(axis=-1)
    black = (rgb == 0).all(axis=-1)
    bad = ~(white | black) | (alpha != 255)
    if bad.any():
        y, x = np.argwhere(bad)[0]
        die(
            f"{path} is not strictly black/white: {int(bad.sum())} pixels are gray, "
            f"colored or transparent (first at x={x}, y={y}, RGBA={tuple(int(v) for v in rgba[y, x])})"
        )

    return (~white if invert else white), dpi


def runs(row):
    """(start, end_exclusive) column pairs for each run of True in a 1D bool array."""
    edges = np.flatnonzero(np.diff(np.concatenate(([0], row.astype(np.int8), [0]))))
    return list(zip(edges[0::2], edges[1::2]))


def num(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def generate(mask, px, a):
    h, w = mask.shape
    width, height = w * px, h * px
    n_lines = max(1, int(round(height / a.line_pitch)))

    g = [
        f"; png2gcode {a.input}",
        f"; {w}x{h} px, pixel {num(px)} mm, size {num(width)} x {num(height)} mm",
        f"; burn {'black' if a.invert else 'white'}, line pitch {num(a.line_pitch)} mm, "
        f"S{a.power} F{a.feed}, overscan {num(a.overscan)} mm, {'M3' if a.m3 else 'M4'}",
        "; origin X0 Y0 = bottom-left of image; needs GRBL laser mode ($32=1)",
        "G21",
        "G90",
        "M5",
        f"G0 F{a.travel}",
        f"{'M3' if a.m3 else 'M4'} S0",
    ]

    forward = True
    burned_lines = 0
    for i in range(n_lines):
        y = (i + 0.5) * a.line_pitch          # centre of this line, from bottom
        row = mask[h - 1 - min(h - 1, int(y / px))]
        segs = [(s * px, e * px) for s, e in runs(row)]
        if not segs:
            continue
        burned_lines += 1

        if not forward:
            segs = [(e, s) for s, e in reversed(segs)]
        d = 1 if forward else -1

        # Group runs whose gap is short enough to cross at burn speed with S0.
        groups = [[segs[0]]]
        for seg in segs[1:]:
            if abs(seg[0] - groups[-1][-1][1]) > 2 * a.overscan:
                groups.append([])
            groups[-1].append(seg)

        for grp in groups:
            g.append(f"G0 X{num(grp[0][0] - d * a.overscan)} Y{num(y)}")
            g.append(f"G1 X{num(grp[0][0])} S0 F{a.feed}")
            for j, (s, e) in enumerate(grp):
                if j:
                    g.append(f"G1 X{num(s)} S0")
                g.append(f"G1 X{num(e)} S{a.power}")
            g.append(f"G1 X{num(grp[-1][1] + d * a.overscan)} S0")

        forward = not forward

    g += ["M5", "G0 X0 Y0", "M2"]
    return g, burned_lines, width, height


def main():
    p = argparse.ArgumentParser(
        description="Raster a strictly black/white PNG into laser G-code for a 3018 (GRBL).")
    p.add_argument("input", help="PNG file (pure black/white only)")
    p.add_argument("-o", "--output", help="output G-code file (default: stdout)")
    p.add_argument("--invert", action="store_true", help="burn black pixels instead of white")
    size = p.add_mutually_exclusive_group()
    size.add_argument("--dpi", type=float, help="image resolution (default: read from PNG)")
    size.add_argument("--width", type=float, help="image width in mm; pixel size = width / pixel columns")
    p.add_argument("--line-pitch", type=float, default=0.1, help="mm between scan lines (default 0.1)")
    p.add_argument("--power", type=int, default=1000, help="laser S value while burning (default 1000, GRBL $30)")
    p.add_argument("--feed", type=int, default=1000, help="burn feed mm/min (default 1000)")
    p.add_argument("--travel", type=int, default=3000, help="travel feed mm/min (default 3000)")
    p.add_argument("--m3", action="store_true",
                   help="constant power M3 instead of dynamic M4 (M4 scales power with speed)")
    p.add_argument("--overscan", type=float, default=2.0, help="mm of laser-off run-up on each side (default 2)")
    a = p.parse_args()

    if a.line_pitch <= 0 or a.feed <= 0 or a.power < 0 or a.overscan < 0:
        die("line pitch and feed must be > 0, power and overscan >= 0")
    if (a.width is not None and a.width <= 0) or (a.dpi is not None and a.dpi <= 0):
        die("width and dpi must be > 0")

    mask, png_dpi = load_mask(a.input, a.invert)
    if a.width:
        px = a.width / mask.shape[1]
    else:
        dpi = a.dpi or (png_dpi[0] if png_dpi else None)
        if not dpi or dpi <= 0:
            die("PNG has no DPI info, pass --dpi or --width")
        px = 25.4 / dpi

    lines, burned, width, height = generate(mask, px, a)
    text = "\n".join(lines) + "\n"
    if a.output:
        with open(a.output, "w") as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    print(f"png2gcode: {num(width)} x {num(height)} mm, {burned} burn lines", file=sys.stderr)


if __name__ == "__main__":
    main()
