#!/usr/bin/env python3
"""
Rasterise the dancer preview to PNG.

Reuses the exact geometry from preview_dancer.py (which speaks SVG path
strings), flattens the quadratic segments and paints them with PIL:
a wide translucent pass for the neon bloom, a crisp pass for the filament
and a thin white pass for the hot core.

Usage:  python test/preview_dancer_png.py [out.png]
"""

import pathlib
import re
import sys

from PIL import Image, ImageDraw, ImageFilter

import preview_dancer as pd

SCALE = 2
W, H = 600, 900
BG = (11, 14, 20)

# neon ramp, matching the Android LinearGradient stops
STOPS = [
    (0.00, (0x22, 0xD3, 0xEE)),
    (0.42, (0xEC, 0x48, 0x99)),
    (0.62, (0xFA, 0xCC, 0x15)),
    (1.00, (0x22, 0xD3, 0xEE)),
]


def ramp(t):
    t = max(0.0, min(1.0, t))
    for i in range(len(STOPS) - 1):
        t0, c0 = STOPS[i]
        t1, c1 = STOPS[i + 1]
        if t0 <= t <= t1:
            k = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(int(c0[j] + (c1[j] - c0[j]) * k) for j in range(3))
    return STOPS[-1][1]


def parse_path(d):
    """Turn an SVG path string into a list of polylines (one per subpath)."""
    tokens = re.findall(r"([MLQZ])([^MLQZ]*)", d)
    polys, cur = [], []
    cx = cy = 0.0
    for cmd, raw in tokens:
        nums = [float(v) for v in re.findall(r"-?\d+(?:\.\d+)?", raw)]
        if cmd == "M":
            if len(cur) > 1:
                polys.append(cur)
            cx, cy = nums[0], nums[1]
            cur = [(cx, cy)]
        elif cmd == "L":
            for i in range(0, len(nums), 2):
                cx, cy = nums[i], nums[i + 1]
                cur.append((cx, cy))
        elif cmd == "Q":
            for i in range(0, len(nums), 4):
                qx, qy, x, y = nums[i:i + 4]
                for s in range(1, 13):
                    u = s / 12.0
                    iu = 1.0 - u
                    px = iu * iu * cx + 2 * iu * u * qx + u * u * x
                    py = iu * iu * cy + 2 * iu * u * qy + u * u * y
                    cur.append((px, py))
                cx, cy = x, y
        elif cmd == "Z":
            if len(cur) > 1:
                polys.append(cur)
            cur = [(cx, cy)]
    if len(cur) > 1:
        polys.append(cur)
    return polys


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "preview_dancer.png"
    h = 480.0
    ox, oy = 300.0, 560.0

    pose = dict(lean=-6.0, headTilt=6.0, crouch=0.05,
                shoulderL=248.0, elbowL=264.0, shoulderR=98.0, elbowR=82.0,
                hipL=350.0, kneeL=358.0, hipR=68.0, kneeR=84.0)
    fig = pd.build(pose, h)
    shapes = pd.render(fig, h, ox, oy)

    img = Image.new("RGB", (W * SCALE, H * SCALE), BG)
    glow = Image.new("RGBA", (W * SCALE, H * SCALE), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    d = ImageDraw.Draw(img)

    y0, y1 = -0.62 * h, 0.60 * h

    def col(y):
        return ramp((y - y0) / (y1 - y0))

    for dpath, width, opacity, hot in shapes:
        polys = parse_path(dpath)
        sw = max(1, int(width * SCALE))
        # bloom
        for poly in polys:
            pts = [((x + ox) * SCALE, (y + oy) * SCALE) for x, y in poly]
            if len(pts) < 2:
                continue
            c = col(oy + sum(p[1] for p in poly) / len(poly) - oy)
            gd.line(pts, fill=(c[0], c[1], c[2], int(70 * opacity)), width=int(sw * 2.9), joint="curve")
        glow = glow.filter(ImageFilter.GaussianBlur(radius=4 * SCALE))
        img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")
        glow = Image.new("RGBA", (W * SCALE, H * SCALE), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        d = ImageDraw.Draw(img)

        # crisp filament, coloured per segment so the ramp runs down the body
        for poly in polys:
            if len(poly) < 2:
                continue
            for i in range(len(poly) - 1):
                x0, y0p = poly[i]
                x1, y1p = poly[i + 1]
                c = col(y0p)
                d.line(
                    [((x0 + ox) * SCALE, (y0p + oy) * SCALE),
                     ((x1 + ox) * SCALE, (y1p + oy) * SCALE)],
                    fill=c, width=sw
                )
            if hot:
                hw = max(1, int(sw * 0.38))
                d.line([((x + ox) * SCALE, (y + oy) * SCALE) for x, y in poly],
                       fill=(255, 255, 255), width=hw, joint="curve")

    img.save(out)
    print(f"已保存 {out} ({W * SCALE}x{H * SCALE})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
