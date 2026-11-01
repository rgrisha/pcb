"""Tests for png2gcode. Run with: python3 -m pytest

Pixel rows are written as run-length specs: "4b5w6b7w" means 4 black,
5 white, 6 black, 7 white pixels. Tests use 1 mm pixels and a 1 mm line
pitch, so X coordinates equal pixel columns and line n sits at Y n+0.5
counted from the bottom.
"""

import re
from argparse import Namespace

import numpy as np
import pytest
from PIL import Image

import png2gcode


def row(spec):
    """'4b5w' -> array of 9 pixels (0 = black, 255 = white)."""
    parts = re.fullmatch(r"(?:\d+[bw])+", spec) and re.findall(r"(\d+)([bw])", spec)
    assert parts, f"bad row spec {spec!r}"
    return np.concatenate([np.full(int(n), 255 if c == "w" else 0, np.uint8) for n, c in parts])


def image(*specs):
    """Rows listed top to bottom, as in the PNG."""
    rows = [row(s) for s in specs]
    assert len({len(r) for r in rows}) == 1, "all rows must have the same width"
    return np.stack(rows)


def args(**kw):
    a = dict(input="test.png", invert=False, line_pitch=1.0, power=1000, feed=1000,
             travel=3000, overscan=2.0, m3=False)
    a.update(kw)
    return Namespace(**a)


def gcode(*specs, **kw):
    """Generate from rows and return only the motion lines (no header/footer)."""
    a = args(**kw)
    mask = image(*specs) == 255
    if a.invert:
        mask = ~mask
    lines, _, _, _ = png2gcode.generate(mask, 1.0, a)
    start = lines.index("M3 S0" if a.m3 else "M4 S0") + 1
    end = lines.index("M5", start) - 1      # drop the return-to-origin move
    return lines[start:end]


# --- generated G commands -------------------------------------------------

def test_two_runs_far_apart_are_separate_burns():
    # white at columns 4-9 and 15-22; 6 mm gap > 2 x overscan, so travel between
    assert gcode("4b5w6b7w") == [
        "G1 X2 Y0.5 S0 F3000",
        "G1 X4 S0 F1000",
        "G1 X9 S1000",
        "G1 X11 S0",
        "G1 X13 Y0.5 S0 F3000",
        "G1 X15 S0 F1000",
        "G1 X22 S1000",
        "G1 X24 S0",
    ]


def test_short_gap_is_crossed_with_laser_off():
    # 3 mm gap <= 2 x overscan: stay at burn feed, S0 across the gap
    assert gcode("2w3b2w") == [
        "G1 X-2 Y0.5 S0 F3000",
        "G1 X0 S0 F1000",
        "G1 X2 S1000",
        "G1 X5 S0",
        "G1 X7 S1000",
        "G1 X9 S0",
    ]


def test_all_black_row_produces_no_moves():
    assert gcode("10b") == []


def test_full_white_row():
    assert gcode("5w") == [
        "G1 X-2 Y0.5 S0 F3000",
        "G1 X0 S0 F1000",
        "G1 X5 S1000",
        "G1 X7 S0",
    ]


def test_rows_alternate_direction_bottom_up():
    # bottom row (listed last) burns left to right, the one above right to left
    assert gcode("1b3w", "3w1b") == [
        "G1 X-2 Y0.5 S0 F3000",
        "G1 X0 S0 F1000",
        "G1 X3 S1000",
        "G1 X5 S0",
        "G1 X6 Y1.5 S0 F3000",
        "G1 X4 S0 F1000",
        "G1 X1 S1000",
        "G1 X-1 S0",
    ]


def test_empty_row_does_not_flip_direction():
    assert gcode("2w", "2b", "2w") == [
        "G1 X-2 Y0.5 S0 F3000",
        "G1 X0 S0 F1000",
        "G1 X2 S1000",
        "G1 X4 S0",
        "G1 X4 Y2.5 S0 F3000",
        "G1 X2 S0 F1000",
        "G1 X0 S1000",
        "G1 X-2 S0",
    ]


def test_invert_burns_black():
    assert gcode("2w3b2w", invert=True) == [
        "G1 X0 Y0.5 S0 F3000",
        "G1 X2 S0 F1000",
        "G1 X5 S1000",
        "G1 X7 S0",
    ]


def test_power_feed_travel_overscan_options():
    assert gcode("1b2w", power=300, feed=500, travel=1200, overscan=0.5) == [
        "G1 X0.5 Y0.5 S0 F1200",
        "G1 X1 S0 F500",
        "G1 X3 S300",
        "G1 X3.5 S0",
    ]


def test_line_pitch_samples_rows():
    # 4 rows at 2 mm pitch -> lines at Y1 and Y3, sampling rows 2 and 0 from top
    assert gcode("1w3b", "4b", "3b1w", "4b", line_pitch=2.0) == [
        "G1 X1 Y1 S0 F3000",
        "G1 X3 S0 F1000",
        "G1 X4 S1000",
        "G1 X6 S0",
        "G1 X3 Y3 S0 F3000",
        "G1 X1 S0 F1000",
        "G1 X0 S1000",
        "G1 X-2 S0",
    ]


def test_header_and_footer():
    lines, burned, width, height = png2gcode.generate(image("2w") == 255, 1.0, args())
    assert lines[4:8] == ["G21", "G90", "M5", "M4 S0"]
    assert lines[-3:] == ["G1 X0 Y0 S0 F3000", "M5", "M2"]
    assert (burned, width, height) == (1, 2.0, 1.0)


def test_m3_option():
    lines, _, _, _ = png2gcode.generate(image("2w") == 255, 1.0, args(m3=True))
    assert "M3 S0" in lines and "M4 S0" not in lines


def test_no_g0_moves():
    # GRBL ignores F on G0, so travel must be G1
    lines, _, _, _ = png2gcode.generate(image("3w2b3w", "1b6w1b") == 255, 1.0, args())
    assert not [l for l in lines if l.startswith("G0")]


# --- PNG loading ----------------------------------------------------------

def save(tmp_path, pixels, mode="L", **info):
    path = tmp_path / "in.png"
    Image.fromarray(pixels, mode).save(path, **info)
    return str(path)


def test_load_black_white_png(tmp_path):
    mask, dpi = png2gcode.load_mask(save(tmp_path, image("2b3w"), dpi=(254, 254)), invert=False)
    assert mask.tolist() == [[False, False, True, True, True]]
    assert dpi == pytest.approx((254, 254))


def test_load_invert(tmp_path):
    mask, _ = png2gcode.load_mask(save(tmp_path, image("2b3w")), invert=True)
    assert mask.tolist() == [[True, True, False, False, False]]


@pytest.mark.parametrize("pixels, mode", [
    (np.array([[0, 128, 255]], np.uint8), "L"),                            # gray
    (np.array([[[255, 0, 0], [0, 0, 0]]], np.uint8), "RGB"),              # color
    (np.array([[[255, 255, 255, 0], [0, 0, 0, 255]]], np.uint8), "RGBA"),  # transparent
])
def test_load_rejects_non_black_white(tmp_path, pixels, mode, capsys):
    with pytest.raises(SystemExit) as e:
        png2gcode.load_mask(save(tmp_path, pixels, mode), invert=False)
    assert e.value.code == 1
    assert "not strictly black/white" in capsys.readouterr().err
