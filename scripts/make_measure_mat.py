"""Measuring mat for the Lookout camera: US Letter, all sizes in mm.

Frame origin: the center of marker 0 (top left). +X right, +Y down.
The marker centers are at (0,0), (170,0), (170,230), (0,230): IDs 0 to 3, clockwise.

Writes assets/measure-mat.svg and assets/measure-mat.pdf. Needs rsvg-convert.
Run from the repository root:
    uv run --with opencv-python-headless python scripts/make_measure_mat.py
"""
import pathlib
import subprocess

import cv2

ASSETS = pathlib.Path(__file__).resolve().parent.parent / "assets"

PAGE_W, PAGE_H = 215.9, 279.4
OX, OY = 22.5, 22.5          # frame origin on the page
FW, FH = 170.0, 230.0        # frame size, marker center to marker center
M = 25.0                     # marker size, black border included
QUIET = 3.0                  # white space around a marker
DICT = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)

out = []
def add(s):
    out.append(s)

def P(x, y):
    """Frame mm to page mm."""
    return OX + x, OY + y

def marker(mid, cx, cy):
    bits = cv2.aruco.generateImageMarker(DICT, mid, 6)
    cell = M / 6
    x0, y0 = cx - M / 2, cy - M / 2
    add(f'<rect x="{x0 - QUIET:.3f}" y="{y0 - QUIET:.3f}" width="{M + 2 * QUIET:.3f}" '
        f'height="{M + 2 * QUIET:.3f}" fill="#fff"/>')
    for r in range(6):
        for c in range(6):
            if bits[r][c] == 0:
                add(f'<rect x="{x0 + c * cell:.3f}" y="{y0 + r * cell:.3f}" '
                    f'width="{cell + 0.02:.3f}" height="{cell + 0.02:.3f}" fill="#000"/>')

def text(x, y, s, size=2.5, anchor="middle", weight="normal", fill="#000", rot=0):
    t = f' transform="rotate({rot} {x:.3f} {y:.3f})"' if rot else ""
    add(f'<text x="{x:.3f}" y="{y:.3f}" font-size="{size}" text-anchor="{anchor}" '
        f'font-weight="{weight}" fill="{fill}" font-family="Helvetica, Arial"{t}>{s}</text>')

def line(x1, y1, x2, y2, w=0.15, color="#000"):
    add(f'<line x1="{x1:.3f}" y1="{y1:.3f}" x2="{x2:.3f}" y2="{y2:.3f}" '
        f'stroke="{color}" stroke-width="{w}"/>')

add(f'<rect width="{PAGE_W}" height="{PAGE_H}" fill="#fff"/>')

# Grid: 5 mm light, 10 mm medium, 50 mm dark. Clear of the markers.
clear = M / 2 + QUIET
for v in range(0, int(FW) + 1, 5):
    w, col = (0.35, "#777") if v % 50 == 0 else (0.2, "#aaa") if v % 10 == 0 else (0.1, "#d4d4d4")
    y_a = clear if v in (0, FW) or v < clear or v > FW - clear else 0
    x, ya = P(v, y_a)
    _, yb = P(v, FH - y_a)
    line(x, ya, x, yb, w, col)
for v in range(0, int(FH) + 1, 5):
    w, col = (0.35, "#777") if v % 50 == 0 else (0.2, "#aaa") if v % 10 == 0 else (0.1, "#d4d4d4")
    x_a = clear if v in (0, FH) or v < clear or v > FH - clear else 0
    xa, y = P(x_a, v)
    xb, _ = P(FW - x_a, v)
    line(xa, y, xb, y, w, col)

# Rulers: 1 mm ticks on the four frame lines, outside the work area, numbers every 10.
def ruler_h(yf, sign):
    for v in range(int(clear) + 1, int(FW - clear)):
        L = 4 if v % 10 == 0 else 2.5 if v % 5 == 0 else 1.5
        x, y = P(v, yf)
        line(x, y, x, y + sign * L, 0.15)
        if v % 10 == 0:
            text(x, y + sign * 6.5 + (0.9 if sign > 0 else 0), str(v), 2.4)
def ruler_v(xf, sign):
    for v in range(int(clear) + 1, int(FH - clear)):
        L = 4 if v % 10 == 0 else 2.5 if v % 5 == 0 else 1.5
        x, y = P(xf, v)
        line(x, y, x + sign * L, y, 0.15)
        if v % 10 == 0:
            text(x + sign * 6.5, y + 0.85, str(v), 2.4)
ruler_h(0, -1)
ruler_h(FH, 1)
ruler_v(0, -1)
ruler_v(FW, 1)

# Markers 0..3, clockwise from top left, with their IDs
for mid, (fx, fy) in enumerate([(0, 0), (FW, 0), (FW, FH), (0, FH)]):
    cx, cy = P(fx, fy)
    marker(mid, cx, cy)
    dy = -M / 2 - QUIET - 1.5 if fy == 0 else M / 2 + QUIET + 3.5
    text(cx, cy + dy, f"ID {mid}", 2.5, weight="bold")

# Calibration bars: exactly 100 mm, in 10 mm black and white blocks, one per axis, because a
# printer can scale X and Y by different amounts. Measure each bar with a ruler.
BAR, BAR_T = 100.0, 3.0
def cal_bar(x0, y0, horizontal):
    for i in range(10):
        a = x0 + i * 10 if horizontal else x0
        b = y0 if horizontal else y0 + i * 10
        w, h = (10, BAR_T) if horizontal else (BAR_T, 10)
        add(f'<rect x="{a:.3f}" y="{b:.3f}" width="{w}" height="{h}" '
            f'fill="{"#000" if i % 2 == 0 else "#fff"}" stroke="#000" stroke-width="0.1"/>')
    # End ticks past the bar, so the ends are easy to find with a ruler
    for v in (0, BAR):
        if horizontal:
            line(x0 + v, y0 - 1.5, x0 + v, y0 + BAR_T + 1.5, 0.2)
        else:
            line(x0 - 1.5, y0 + v, x0 + BAR_T + 1.5, y0 + v, 0.2)

# Horizontal bar (checks X): under the bottom ruler, between the lower markers
hx, hy = PAGE_W / 2 - BAR / 2, OY + FH + 9.5
cal_bar(hx, hy, True)
text(hx + BAR + 2.5, hy + BAR_T - 0.4, "100 mm", 2.6, anchor="start", weight="bold")
text(hx - 2.5, hy + BAR_T - 0.4, "X check", 2.6, anchor="end", weight="bold")
# Vertical bar (checks Y): in the left margin, outside the ruler numbers
vx, vy = 9.5, OY + FH / 2 - BAR / 2
cal_bar(vx, vy, False)
text(vx - 1.8, vy + BAR / 2, "Y check  ·  100 mm", 2.6, weight="bold", rot=-90)

# Title and print check, in the top and bottom margins
text(PAGE_W / 2, 7.5, "LOOKOUT MEASURING MAT  ·  frame 170 × 230 mm  ·  ArUco 4x4_50, IDs 0–3", 3.2, weight="bold")
text(PAGE_W / 2, PAGE_H - 4.5,
     "Print at 100 % (no fit to page). Both calibration bars must measure 100.0 mm (±0.5). "
     "Scale on the paper plane only.", 2.6)
# Arrow toward the camera side, in the top margin
text(PAGE_W / 2, 13, "▲ TOP: put this edge away from you", 2.6, fill="#555")

svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{PAGE_W}mm" height="{PAGE_H}mm" '
       f'viewBox="0 0 {PAGE_W} {PAGE_H}">\n' + "\n".join(out) + "\n</svg>\n")
svg_path = ASSETS / "measure-mat.svg"
pdf_path = ASSETS / "measure-mat.pdf"
svg_path.write_text(svg)
subprocess.run(["rsvg-convert", "-f", "pdf", "-o", str(pdf_path), str(svg_path)], check=True)
print(pdf_path)
