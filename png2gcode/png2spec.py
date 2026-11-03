#!/usr/bin/env python3
"""
png2spec - print pixel rows of a black/white PNG as run-length specs for
tests, e.g. "4b5w6b7w" = 4 black, 5 white, 6 black, 7 white pixels.

Rows are counted from the top of the image, starting at 0, like in an
image editor. Output is one quoted string per row, ready to paste into
gcode(...) in test_png2gcode.py.

    ./png2spec.py board.png 100 101 102
    ./png2spec.py board.png 100-110 --cols 2000-2100
"""

import argparse

from png2gcode import die, load_mask


def spec(row):
    out, n, cur = [], 0, row[0]
    for v in row:
        if v == cur:
            n += 1
        else:
            out.append(f"{n}{'w' if cur else 'b'}")
            n, cur = 1, v
    out.append(f"{n}{'w' if cur else 'b'}")
    return "".join(out)


def span(text, limit, what):
    """'5' -> (5, 6), '5-9' -> (5, 10); end inclusive on the command line."""
    try:
        a, _, b = text.partition("-")
        start, end = int(a), int(b or a) + 1
    except ValueError:
        die(f"bad {what} {text!r}, use N or N-M")
    if not 0 <= start < end <= limit:
        die(f"{what} {text} out of range 0-{limit - 1}")
    return start, end


def main():
    p = argparse.ArgumentParser(description="Print PNG pixel rows as test specs like '4b5w6b7w'.")
    p.add_argument("input", help="PNG file (pure black/white only)")
    p.add_argument("rows", nargs="+", help="row numbers from the top, N or N-M (inclusive)")
    p.add_argument("--cols", help="only columns N-M (inclusive), default all")
    a = p.parse_args()

    mask, _ = load_mask(a.input, invert=False)
    h, w = mask.shape
    c0, c1 = span(a.cols, w, "columns") if a.cols else (0, w)

    for r in a.rows:
        r0, r1 = span(r, h, "rows")
        for y in range(r0, r1):
            print(f'"{spec(mask[y, c0:c1])}",  # row {y}')


if __name__ == "__main__":
    main()
