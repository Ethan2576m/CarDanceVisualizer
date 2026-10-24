#!/usr/bin/env python3
"""
Rasterise the dancer preview to PNG.

Reuses the exact geometry from preview_dancer.py (which speaks SVG path
strings), flattens the quadratic segments and paints them with PIL.

Two modes:
  - neon (default): wide translucent bloom pass + crisp gradient filament
    + thin white hot core, on the dark stage - the app look;
  - mono:           ink lines on paper, light pencil fills on the hair
    masses, no glow - the reference-image look.

Usage:
  python test/preview_dancer_png.py [out.png] [--mono]
"""

import pathlib
import re
import sys

from PIL import Image, ImageDraw, ImageFilter

import preview_dancer as pd

SCALE = 2
W, H = 600, 900
BG_NEON = (11, 14, 20)
BG_MONO = (246, 244, 239)
INK = (34, 34, 42)

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
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    mono = "--mono" in sys.argv
    crop = None
    if "--crop" in sys.argv:
        i = sys.argv.index("--crop")
        crop = tuple(float(v) for v in sys.argv[i + 1].split(","))
    out = args[0] if args else "preview_dancer.png"
    h = 480.0
    ox, oy = 300.0, 560.0

    pose = dict(lean=-6.0, headTilt=6.0, crouch=0.05,
                shoulderL=248.0, elbowL=264.0, shoulderR=98.0, elbowR=82.0,
                hipL=350.0, kneeL=358.0, hipR=68.0, kneeR=84.0)
    fig = pd.build(pose, h)
    shapes = pd.render(fig, h, ox, oy, mode="mono" if mono else "neon")

    bg = BG_MONO if mono else BG_NEON
    img = Image.new("RGB", (W * SCALE, H * SCALE), bg)
    glow = Image.new("RGBA", (W * SCALE, H * SCALE), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    d = ImageDraw.Draw(img)

    # framing: full figure, or a zoomed crop of geometry coords (x0,y0,x1,y1)
    if crop:
        cx0, cy0, cx1, cy1 = crop
        s = min(W * SCALE / (cx1 - cx0), H * SCALE / (cy1 - cy0))
        ccx, ccy = (cx0 + cx1) * 0.5, (cy0 + cy1) * 0.5

        def T(x, y):
            return ((x - ccx) * s + W * SCALE * 0.5, (y - ccy) * s + H * SCALE * 0.5)
        sc = s
    else:
        def T(x, y):
            return ((x + ox) * SCALE, (y + oy) * SCALE)
        sc = SCALE

    y0, y1 = -0.62 * h, 0.60 * h

    def col(y):
        return ramp((y - y0) / (y1 - y0))

    def flatten_glow():
        nonlocal glow, gd, d, img
        glow = glow.filter(ImageFilter.GaussianBlur(radius=4 * SCALE))
        img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")
        glow = Image.new("RGBA", (W * SCALE, H * SCALE), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        d = ImageDraw.Draw(img)

    for shape in shapes:
        dpath, width, opacity, bloom, hot, fill = shape
        polys = parse_path(dpath)
        sw = max(1, int(width * sc))

        # pencil fill (hair masses, eyes) - blended over the paper
        if fill is not None:
            fr, fg, fb, fa = fill
            for poly in polys:
                if len(poly) < 3:
                    continue
                pts = [T(x, y) for x, y in poly]
                d.polygon(pts, fill=(int(bg[0] + (fr - bg[0]) * fa),
                                     int(bg[1] + (fg - bg[1]) * fa),
                                     int(bg[2] + (fb - bg[2]) * fa)))

        if mono:
            # ink pass only - crisp pencil lines, no glow, no hot core;
            # the ink is lifted toward full black so the page reads as a
            # drawing, not a whisper
            for poly in polys:
                if len(poly) < 2:
                    continue
                pts = [T(x, y) for x, y in poly]
                ia = min(1.0, 0.30 + opacity * 0.75)
                ink = (int(bg[0] + (INK[0] - bg[0]) * ia),
                       int(bg[1] + (INK[1] - bg[1]) * ia),
                       int(bg[2] + (INK[2] - bg[2]) * ia))
                d.line(pts, fill=ink, width=sw, joint="curve")
            continue

        # bloom - only on contour/structure tiers, so folds and hair stay
        # crisp ink lines instead of everything glowing equally
        if bloom:
            for poly in polys:
                pts = [T(x, y) for x, y in poly]
                if len(pts) < 2:
                    continue
                c = col(oy + sum(p[1] for p in poly) / len(poly) - oy)
                gd.line(pts, fill=(c[0], c[1], c[2], int(70 * opacity)), width=int(sw * 2.9), joint="curve")
            flatten_glow()

        # crisp filament, coloured per segment so the ramp runs down the body
        for poly in polys:
            if len(poly) < 2:
                continue
            for i in range(len(poly) - 1):
                x0, y0p = poly[i]
                x1, y1p = poly[i + 1]
                c = col(y0p)
                d.line(
                    [T(x0, y0p),
                     T(x1, y1p)],
                    fill=c, width=sw
                )
            if hot:
                hw = max(1, int(sw * 0.38))
                d.line([T(x, y) for x, y in poly],
                       fill=(255, 255, 255), width=hw, joint="curve")

    img.save(out)
    print(f"已保存 {out} ({W * SCALE}x{H * SCALE}){'  [mono]' if mono else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
