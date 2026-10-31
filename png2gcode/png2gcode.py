#!/usr/bin/env python3
"""
png2gcode - raster a PNG into laser G-code for PCB paint burning.

White pixels = laser ON (paint gets burned off -> copper gets etched).
Black pixels = laser OFF (paint stays -> copper stays).

The image is scanned row by row (serpentine by default). Each row is split
into runs of consecutive white pixels; every run becomes one G1 burn move,
gaps become G0 travel moves.

Targets GRBL 1.1+ in laser mode ($32=1): M4 dynamic power, G0 = laser off.
"""

import argparse
import sys

import numpy as np
from PIL import Image


def load_mask(path, threshold, invert):
    img = Image.open(path)
    dpi = img.info.get("dpi")

    # Transparent pixels count as black (laser off).
    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        img = img.convert("RGBA")
        bg = Image.new("RGBA", img.size, (0, 0, 0, 255))
        img = Image.alpha_composite(bg, img)

    gray = np.asarray(img.convert("L"), dtype=np.uint8)
    mask = gray >= threshold
    if invert:
        mask = ~mask
    return mask, dpi


def runs_in_row(row):
    """Return (start, end_exclusive) pairs of True runs in a 1D bool array."""
    padded = np.concatenate(([False], row, [False]))
    edges = np.flatnonzero(padded[1:] != padded[:-1])
    return list(zip(edges[0::2], edges[1::2]))


def fmt(v):
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def generate(mask, px, args):
    h, w = mask.shape
    width_mm, height_mm = w * px, h * px
    line_pitch = args.line_pitch or px
    n_lines = max(1, int(round(height_mm / line_pitch)))

    out = []
    emit = out.append
    emit(f"; png2gcode: {args.input}")
    emit(f"; image {w}x{h}px, pixel {fmt(px)}mm, size {fmt(width_mm)}x{fmt(height_mm)}mm")
    emit(f"; line pitch {fmt(line_pitch)}mm, {n_lines} lines, power S{args.power}, feed F{args.feed}")
    emit("G21 ; mm")
    emit("G90 ; absolute")
    emit("M5")
    emit(f"G0 F{args.travel_feed}")
    emit(f"M4 S0 ; dynamic laser mode, off until G1")

    burn_len = travel_len = 0.0
    cur = (0.0, 0.0)
    left_to_right = True

    for i in range(n_lines):
        # Line i sits at the centre of its band, measured from the bottom edge.
        y = (i + 0.5) * line_pitch
        if y > height_mm:
            break
        row_idx = h - 1 - min(h - 1, int(y / px))  # image row 0 is the top
        row = mask[row_idx]
        if args.mirror:
            row = row[::-1]

        runs = runs_in_row(row)
        if not runs:
            continue

        segs = [(s * px, e * px) for s, e in runs]
        if not left_to_right:
            segs = [(e, s) for s, e in reversed(segs)]

        for x0, x1 in segs:
            direction = 1 if x1 > x0 else -1
            xs = x0 - direction * args.overscan
            xs_abs, ys_abs = xs + args.x_offset, y + args.y_offset
            travel_len += np.hypot(xs_abs - cur[0], ys_abs - cur[1])
            emit(f"G0 X{fmt(xs_abs)} Y{fmt(ys_abs)}")
            if args.overscan:
                # Get up to speed with the laser off before the burn starts.
                emit(f"G1 X{fmt(x0 + args.x_offset)} S0 F{args.feed}")
            emit(f"G1 X{fmt(x1 + args.x_offset)} S{args.power} F{args.feed}")
            burn_len += abs(x1 - x0)
            cur = (x1 + args.x_offset, ys_abs)

        if args.serpentine:
            left_to_right = not left_to_right

    emit("M5")
    emit("G0 X0 Y0")
    emit("M2")

    t_min = burn_len / args.feed + travel_len / args.travel_feed
    emit(f"; burn {fmt(burn_len)}mm, travel {fmt(travel_len)}mm, ~{t_min:.1f} min (no accel)")
    return out, width_mm, height_mm, t_min


def main():
    p = argparse.ArgumentParser(description="PNG -> raster laser G-code (white = burn).")
    p.add_argument("input", help="input PNG")
    p.add_argument("-o", "--output", help="output .gcode (default: stdout)")
    size = p.add_mutually_exclusive_group()
    size.add_argument("--dpi", type=float, help="image resolution (default: from PNG metadata)")
    size.add_argument("--pixel", type=float, help="pixel size in mm")
    size.add_argument("--width", type=float, help="physical image width in mm")
    p.add_argument("--line-pitch", type=float, help="mm between scan lines (default: pixel size); set ~ laser spot size")
    p.add_argument("--power", type=int, default=1000, help="laser S value while burning (default 1000)")
    p.add_argument("--feed", type=float, default=1500, help="burn feed mm/min (default 1500)")
    p.add_argument("--travel-feed", type=float, default=3000, help="travel feed mm/min, informational for G0 (default 3000)")
    p.add_argument("--threshold", type=int, default=128, help="gray >= threshold counts as white (default 128)")
    p.add_argument("--invert", action="store_true", help="burn black pixels instead of white")
    p.add_argument("--mirror", action="store_true", help="mirror horizontally (bottom layer / toner-style)")
    p.add_argument("--no-serpentine", dest="serpentine", action="store_false", help="always scan left to right")
    p.add_argument("--overscan", type=float, default=0.0, help="mm of laser-off run-up before each burn")
    p.add_argument("--x-offset", type=float, default=0.0)
    p.add_argument("--y-offset", type=float, default=0.0)
    args = p.parse_args()

    mask, png_dpi = load_mask(args.input, args.threshold, args.invert)
    h, w = mask.shape

    if args.pixel:
        px = args.pixel
    elif args.width:
        px = args.width / w
    elif args.dpi:
        px = 25.4 / args.dpi
    elif png_dpi and png_dpi[0] > 1:
        px = 25.4 / float(png_dpi[0])
    else:
        sys.exit("error: PNG has no DPI metadata; pass --dpi, --pixel or --width")

    lines, wmm, hmm, t_min = generate(mask, px, args)
    text = "\n".join(lines) + "\n"
    if args.output:
        with open(args.output, "w") as f:
            f.write(text)
    else:
        sys.stdout.write(text)

    on = mask.mean() * 100
    print(f"{w}x{h}px -> {wmm:.2f}x{hmm:.2f}mm, {on:.1f}% burned, ~{t_min:.1f} min",
          file=sys.stderr)


if __name__ == "__main__":
    main()
