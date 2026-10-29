#!/usr/bin/env python3
"""Generate a laser focus calibration G-code (GRBL, laser mode $32=1).

Start with the laser at its current focus height, over the bottom-left corner
of the test area. The head lifts Z_LIFT mm, then burns vertical lines spaced
X_STEP mm apart, lowering Z by Z_STEP after each line. Every 5th line is longer
so the lines are easy to count. All moves are relative, so the file works from
wherever the head is parked.
"""

Z_LIFT = 6.0        # mm, initial lift
Z_STEP = 0.2        # mm, descent after each line
X_STEP = 1.5        # mm, spacing between lines
LEN_SHORT = 10.0    # mm
LEN_LONG = 15.0     # mm, every 5th line
LONG_EVERY = 5
LINES = int(round(Z_LIFT / Z_STEP)) * 2 + 1   # 61 lines: Z +3.0 down to -3.0
POWER = 1000        # S value (max is GRBL $30, usually 1000)
FEED = 600          # mm/min burn speed
TRAVEL = 1500       # mm/min travel speed
PASSES = 3          # burns per line (repeats go back and forth along the line)

out = [
    "; Laser focus calibration",
    f"; {LINES} lines, {X_STEP} mm apart, Z from {Z_LIFT:+.1f} to "
    f"{Z_LIFT - (LINES - 1) * Z_STEP:+.1f} in {Z_STEP} mm steps",
    f"; {PASSES} pass(es) per line, S{POWER}, F{FEED}",
    f"; line n (starting at 1) is burned at Z = +{Z_LIFT} - {Z_STEP}*(n-1) above start",
    f"; every {LONG_EVERY}th line is {LEN_LONG} mm long, others {LEN_SHORT} mm",
    "G21 ; mm",
    "G91 ; relative moves",
    "M5",
    f"G0 Z{Z_LIFT:.3f} ; lift",
]

for i in range(LINES):
    n = i + 1
    z = Z_LIFT - i * Z_STEP
    length = LEN_LONG if n % LONG_EVERY == 0 else LEN_SHORT
    out.append(f"; line {n}  Z={z:+.1f}  len={length:g}")
    out.append(f"M3 S{POWER}")
    for p in range(PASSES):
        dy = length if p % 2 == 0 else -length
        out.append(f"G1 Y{dy:.3f} F{FEED}")
    out.append("M5")
    # odd number of passes ends at the top of the line, even ends at the bottom
    back = -length if PASSES % 2 == 1 else 0.0
    if n < LINES:
        move = f"G0 X{X_STEP:.3f}" + (f" Y{back:.3f}" if back else "")
        out.append(f"{move} F{TRAVEL}")
        out.append(f"G0 Z{-Z_STEP:.3f}")
    elif back:
        out.append(f"G0 Y{back:.3f} F{TRAVEL}")

# return to start position
out += [
    f"G0 X{-(LINES - 1) * X_STEP:.3f}",
    f"G0 Z{-(Z_LIFT - (LINES - 1) * Z_STEP):.3f}",
    "G90 ; back to absolute",
    "M5",
    "M2",
]

with open("focus_test.gcode", "w") as f:
    f.write("\n".join(out) + "\n")
print(f"wrote focus_test.gcode ({LINES} lines, {PASSES} passes each)")
