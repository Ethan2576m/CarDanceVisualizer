#!/usr/bin/env python3
"""
Offline preview of the dancer geometry.

Ports the exact numbers from the Kotlin renderer (Kinematics + DancerRenderer)
into SVG, so the figure can be judged without building the APK. The neon look
is reproduced with an SVG gradient + gaussian blur filter.

Usage:  python test/preview_dancer.py [out.svg]
"""

import math
import sys

# ---------------------------------------------------------------- skeleton
TORSO = 0.272
NECK = 0.082
HEAD_R = 0.072
UPPER_ARM = 0.140
FOREARM = 0.130
THIGH = 0.238
SHIN = 0.234
FOOT = 0.050
SHOULDER_HALF = 0.088
CHEST_HALF = 0.076
HIP_HALF = 0.086
WAIST_HALF = 0.058


def point(fx, fy, length, angle_deg):
    a = math.radians(angle_deg)
    return (fx + math.sin(a) * length, fy + math.cos(a) * length)


def build(pose, h):
    lean, head_tilt, crouch = pose["lean"], pose["headTilt"], pose["crouch"]
    up = 180.0 + lean
    left_ang, right_ang = up + 90.0, up - 90.0

    torso_len = TORSO * (1.0 - crouch * 0.16) * h
    pelvis = (0.0, 0.0)
    chest = point(*pelvis, torso_len, up)
    shoulder_l = point(*chest, SHOULDER_HALF * h, left_ang)
    shoulder_r = point(*chest, SHOULDER_HALF * h, right_ang)
    hip_l = point(*pelvis, HIP_HALF * h, left_ang)
    hip_r = point(*pelvis, HIP_HALF * h, right_ang)
    neck = point(*chest, NECK * h * 0.20, up + head_tilt)
    head = point(*chest, NECK * h, up + head_tilt)

    elbow_l = point(*shoulder_l, UPPER_ARM * h, pose["shoulderL"])
    hand_l = point(*elbow_l, FOREARM * h, pose["elbowL"])
    elbow_r = point(*shoulder_r, UPPER_ARM * h, pose["shoulderR"])
    hand_r = point(*elbow_r, FOREARM * h, pose["elbowR"])

    extra = crouch * 26.0
    knee_l = point(*hip_l, THIGH * h, pose["hipL"])
    knee_r = point(*hip_r, THIGH * h, pose["hipR"])
    ankle_l = point(*knee_l, SHIN * h * 0.90, pose["kneeL"] + extra)
    ankle_r = point(*knee_r, SHIN * h * 0.90, pose["kneeR"] + extra)
    foot_l = point(*ankle_l, FOOT * h, 0.0)
    foot_r = point(*ankle_r, FOOT * h, 0.0)

    return dict(
        pelvis=pelvis, chest=chest, neck=neck, head=head, r=HEAD_R * h,
        shoulder_half=SHOULDER_HALF * h, chest_half=CHEST_HALF * h,
        waist_half=WAIST_HALF * h, hip_half=HIP_HALF * h,
        shoulder_l=shoulder_l, shoulder_r=shoulder_r, hip_l=hip_l, hip_r=hip_r,
        elbow_l=elbow_l, hand_l=hand_l, elbow_r=elbow_r, hand_r=hand_r,
        knee_l=knee_l, ankle_l=ankle_l, foot_l=foot_l,
        knee_r=knee_r, ankle_r=ankle_r, foot_r=foot_r,
    )


def unit(x, y):
    l = math.hypot(x, y)
    return (0.0, 0.0) if l < 1e-4 else (x / l, y / l)


# ------------------------------------------------------------------- paths
class P:
    def __init__(self):
        self.d = []

    def move(self, x, y):
        self.d.append(f"M{x:.2f},{y:.2f}")

    def line(self, x, y):
        self.d.append(f"L{x:.2f},{y:.2f}")

    def quad(self, cx, cy, x, y):
        self.d.append(f"Q{cx:.2f},{cy:.2f} {x:.2f},{y:.2f}")

    def close(self):
        self.d.append("Z")

    def svg(self):
        return " ".join(self.d)


def edge_path(a, b, c, wa, wb, wc, bend, sign):
    u1x, u1y = unit(b[0] - a[0], b[1] - a[1])
    n1x, n1y = -u1y, u1x
    u2x, u2y = unit(c[0] - b[0], c[1] - b[1])
    n2x, n2y = -u2y, u2x
    bx, by = n1x + n2x, n1y + n2y
    bl = math.hypot(bx, by)
    if bl > 1e-4:
        bx, by = bx / bl, by / bl
    else:
        bx, by = n1x, n1y
    ax, ay = a[0] + n1x * wa * sign, a[1] + n1y * wa * sign
    jx, jy = b[0] + bx * wb * sign, b[1] + by * wb * sign
    cx, cy = c[0] + n2x * wc * sign, c[1] + n2y * wc * sign
    p = P()
    p.move(ax, ay)
    p.quad((ax + jx) * 0.5 + bx * bend, (ay + jy) * 0.5 + by * bend, jx, jy)
    p.quad((jx + cx) * 0.5 + bx * bend, (jy + cy) * 0.5 + by * bend, cx, cy)
    return p


def oval_path(cx, cy, rx, ry):
    """An ellipse built from four quadratic segments (keeps the parser simple)."""
    k = 0.5523
    p = P()
    p.move(cx, cy - ry)
    p.quad(cx + rx * k, cy - ry, cx + rx, cy)
    p.quad(cx + rx, cy + ry * k, cx + rx, cy + ry)
    p.quad(cx + rx * k, cy + ry, cx, cy + ry)
    p.quad(cx - rx * k, cy + ry, cx - rx, cy)
    p.quad(cx - rx, cy + ry * k, cx - rx, cy)
    p.quad(cx - rx * k, cy - ry, cx, cy - ry)
    p.close()
    return p


def render(fig, h, ox, oy):
    """Return a list of (path_svg, width, opacity, hot) tuples."""
    out = []
    r = fig["r"]

    rx, ry = unit(fig["shoulder_r"][0] - fig["shoulder_l"][0],
                  fig["shoulder_r"][1] - fig["shoulder_l"][1])
    ux, uy = unit(fig["chest"][0] - fig["pelvis"][0], fig["chest"][1] - fig["pelvis"][1])
    down_y = -uy

    base_x = fig["pelvis"][0] + (fig["chest"][0] - fig["pelvis"][0]) * 0.50
    base_y = fig["pelvis"][1] + (fig["chest"][1] - fig["pelvis"][1]) * 0.50
    ch = fig["chest_half"]
    wa = fig["waist_half"]

    chest_rx = fig["chest"][0] + rx * ch
    chest_ry = fig["chest"][1] + ry * ch
    chest_lx = fig["chest"][0] - rx * ch
    chest_ly = fig["chest"][1] - ry * ch
    waist_rx, waist_ry = base_x + rx * wa, base_y + ry * wa
    waist_lx, waist_ly = base_x - rx * wa, base_y - ry * wa
    bust = ch * 0.42

    # torso right
    p = P()
    p.move(*fig["shoulder_r"])
    p.quad(chest_rx + rx * bust, chest_ry + ry * bust + down_y * 0.030 * h, chest_rx, chest_ry)
    p.quad(waist_rx - rx * 0.014 * h, waist_ry - 0.006 * h, waist_rx, waist_ry)
    p.quad(fig["hip_r"][0] + rx * 0.010 * h, fig["hip_r"][1] - 0.045 * h, *fig["hip_r"])
    out.append((p.svg(), h * 0.0165, 0.91, True))

    # torso left
    p = P()
    p.move(*fig["shoulder_l"])
    p.quad(chest_lx - rx * bust, chest_ly - ry * bust + down_y * 0.030 * h, chest_lx, chest_ly)
    p.quad(waist_lx + rx * 0.014 * h, waist_ly - 0.006 * h, waist_lx, waist_ly)
    p.quad(fig["hip_l"][0] - rx * 0.010 * h, fig["hip_l"][1] - 0.045 * h, *fig["hip_l"])
    out.append((p.svg(), h * 0.0165, 0.91, True))

    # neckline: a shallow scoop, not a deep V
    p = P()
    p.move(fig["shoulder_l"][0] + rx * fig["shoulder_half"] * 0.52, fig["shoulder_l"][1])
    p.quad(fig["chest"][0] - rx * ch * 0.42, fig["chest"][1] + down_y * 0.004 * h,
           fig["chest"][0], fig["chest"][1] + down_y * 0.020 * h)
    p.quad(fig["chest"][0] + rx * ch * 0.42, fig["chest"][1] + down_y * 0.004 * h,
           fig["shoulder_r"][0] - rx * fig["shoulder_half"] * 0.52, fig["shoulder_r"][1])
    out.append((p.svg(), h * 0.0091, 0.70, False))

    # collarbones
    for sgn, sh in ((1, fig["shoulder_r"]), (-1, fig["shoulder_l"])):
        p = P()
        p.move(*fig["neck"])
        p.quad((fig["neck"][0] + sh[0]) * 0.5, (fig["neck"][1] + sh[1]) * 0.5 + down_y * 0.010 * h, *sh)
        out.append((p.svg(), h * 0.0083, 0.65, False))

    # bust curve: one line under each bust
    for sgn in (-1, 1):
        p = P()
        p.move(fig["chest"][0] + sgn * ch * 0.06, fig["chest"][1] + down_y * 0.030 * h)
        p.quad(fig["chest"][0] + sgn * ch * 0.86, fig["chest"][1] + down_y * 0.058 * h,
               fig["chest"][0] + sgn * ch * 1.00, fig["chest"][1] + down_y * 0.026 * h)
        out.append((p.svg(), h * 0.0083, 0.62, False))

    # navel
    p = P()
    p.move(waist_lx, waist_ly)
    p.quad(base_x, base_y + down_y * 0.016 * h, waist_rx, waist_ry)
    out.append((p.svg(), h * 0.0074, 0.55, False))

    # skirt: a flared A-line with a gently wavy hem
    hem_y = fig["pelvis"][1] + h * 0.135
    lx, rxh = fig["hip_l"][0], fig["hip_r"][0]
    flare = 1.95
    hem_lx, hem_rx = lx * flare, rxh * flare
    p = P()
    p.move(lx * 1.05, fig["hip_l"][1] - h * 0.008)
    p.quad(lx * 1.48, fig["pelvis"][1] + h * 0.072, hem_lx, hem_y)
    seg = (hem_rx - hem_lx) / 3
    for i in range(3):
        x0, x1 = hem_lx + seg * i, hem_lx + seg * (i + 1)
        p.quad((x0 + x1) * 0.5, hem_y + h * 0.013, x1, hem_y)
    p.quad(rxh * 1.48, fig["pelvis"][1] + h * 0.072, rxh * 1.05, fig["hip_r"][1] - h * 0.008)
    p.close()
    out.append((p.svg(), h * 0.0142, 0.88, True))

    # pleat lines
    for i in range(1, 4):
        t = i / 4.0
        sx = lx + (rxh - lx) * t
        ex = hem_lx + (hem_rx - hem_lx) * t
        p = P()
        p.move(sx, fig["hip_l"][1] + h * 0.012)
        p.quad(sx * 1.30, fig["pelvis"][1] + h * 0.070, ex, hem_y - h * 0.008)
        out.append((p.svg(), h * 0.0068, 0.50, False))

    # waistband
    p = P()
    p.move(lx * 1.02, fig["hip_l"][1] - h * 0.012)
    p.quad(fig["pelvis"][0], fig["pelvis"][1] - h * 0.020, rxh * 1.02, fig["hip_r"][1] - h * 0.012)
    out.append((p.svg(), h * 0.0105, 0.71, False))

    # legs: the ribbon runs all the way to the foot so nothing floats
    for hip, knee, ankle, foot in ((fig["hip_l"], fig["knee_l"], fig["ankle_l"], fig["foot_l"]),
                                    (fig["hip_r"], fig["knee_r"], fig["ankle_r"], fig["foot_r"])):
        bend = h * 0.027 * 0.40
        out.append((edge_path(hip, knee, foot, h * 0.0270, h * 0.0185, h * 0.0105, bend, 1).svg(),
                    h * 0.0126, 0.88, False))
        out.append((edge_path(hip, knee, foot, h * 0.0270, h * 0.0185, h * 0.0105, bend, -1).svg(),
                    h * 0.0126, 0.88, False))
        p = oval_path(knee[0], knee[1], h * 0.0175, h * 0.0175)
        out.append((p.svg(), h * 0.0076, 0.50, False))
        p = oval_path(ankle[0], ankle[1], h * 0.0115, h * 0.0115)
        out.append((p.svg(), h * 0.0072, 0.45, False))
        p = oval_path(foot[0], foot[1], h * 0.0135, h * 0.0165)
        out.append((p.svg(), h * 0.0100, 0.82, False))

    # arms
    for sh, el, ha in ((fig["shoulder_l"], fig["elbow_l"], fig["hand_l"]),
                        (fig["shoulder_r"], fig["elbow_r"], fig["hand_r"])):
        bend = h * 0.0195 * 0.60
        out.append((edge_path(sh, el, ha, h * 0.0195, h * 0.0118, h * 0.0072, bend, 1).svg(),
                    h * 0.0112, 0.90, False))
        out.append((edge_path(sh, el, ha, h * 0.0195, h * 0.0118, h * 0.0072, bend, -1).svg(),
                    h * 0.0112, 0.90, False))
        p = oval_path(el[0], el[1], h * 0.0110, h * 0.0110)
        out.append((p.svg(), h * 0.0068, 0.48, False))
        p = oval_path(ha[0], ha[1], h * 0.0105, h * 0.0125)
        out.append((p.svg(), h * 0.0080, 0.78, False))

    # neck
    hx, hy = fig["head"]
    for sgn in (-1, 1):
        p = P()
        p.move(fig["neck"][0] + sgn * r * 0.42, fig["neck"][1])
        p.quad(hx + sgn * r * 0.46, hy + r * 0.92, hx + sgn * r * 0.34, hy + r * 0.74)
        out.append((p.svg(), h * 0.0096, 0.73, False))

    # face
    p = P()
    p.move(hx - r * 0.86, hy)
    p.quad(hx - r * 0.95, hy - r * 0.86, hx, hy - r)
    p.quad(hx + r * 0.95, hy - r * 0.86, hx + r * 0.86, hy)
    p.quad(hx + r * 0.80, hy + r * 0.52, hx + r * 0.30, hy + r * 0.96)
    p.quad(hx, hy + r * 1.05, hx - r * 0.30, hy + r * 0.96)
    p.quad(hx - r * 0.80, hy + r * 0.52, hx - r * 0.86, hy)
    p.close()
    out.append((p.svg(), h * 0.0155, 0.90, True))

    # eyes / brows / nose / lips
    eye_y = hy + r * 0.10
    for sgn in (-1, 1):
        ex = hx + sgn * r * 0.36
        p = P()
        p.move(ex - r * 0.20, eye_y + r * 0.02)
        p.quad(ex - r * 0.02, eye_y - r * 0.14, ex + r * 0.20, eye_y - r * 0.01)
        out.append((p.svg(), h * 0.0078, 0.88, False))
        p = P()
        p.move(ex - r * 0.16, eye_y + r * 0.07)
        p.quad(ex + r * 0.01, eye_y + r * 0.15, ex + r * 0.17, eye_y + r * 0.04)
        out.append((p.svg(), h * 0.0062, 0.67, False))
        p = P()
        p.move(ex - r * 0.19, eye_y - r * 0.30)
        p.quad(ex + sgn * r * 0.02, eye_y - r * 0.40, ex + r * 0.20, eye_y - r * 0.26)
        out.append((p.svg(), h * 0.0070, 0.76, False))
    p = P()
    p.move(hx + r * 0.02, eye_y + r * 0.10)
    p.quad(hx + r * 0.10, eye_y + r * 0.28, hx - r * 0.04, eye_y + r * 0.32)
    out.append((p.svg(), h * 0.0059, 0.65, False))
    mouth_y = eye_y + r * 0.52
    p = P()
    p.move(hx - r * 0.13, mouth_y)
    p.quad(hx - r * 0.05, mouth_y - r * 0.05, hx, mouth_y - r * 0.01)
    p.quad(hx + r * 0.05, mouth_y - r * 0.05, hx + r * 0.13, mouth_y)
    p.quad(hx, mouth_y + r * 0.11, hx - r * 0.13, mouth_y)
    out.append((p.svg(), h * 0.0070, 0.78, False))

    # hair: a teardrop mass behind the head, face-framing locks, bang wedges
    base_off = fig["shoulder_half"] * 1.00
    top_y = hy - r * 0.40
    bot_y = fig["pelvis"][1] + r * 2.4

    p = P()
    p.move(hx - r * 1.00, hy - r * 0.34)
    p.quad(hx - base_off * 1.02, hy + r * 1.30, hx - base_off * 0.55, bot_y)
    p.quad(hx, bot_y + r * 0.55, hx + base_off * 0.55, bot_y)
    p.quad(hx + base_off * 1.02, hy + r * 1.30, hx + r * 1.00, hy - r * 0.34)
    p.close()
    out.append((p.svg(), h * 0.0086, 0.58, False))

    # face-framing side locks
    for sgn in (-1, 1):
        p = P()
        p.move(hx + sgn * r * 0.96, hy - r * 0.62)
        p.quad(hx + sgn * r * 1.24, hy + r * 0.30, hx + sgn * r * 0.92, hy + r * 1.30)
        p.quad(hx + sgn * r * 0.74, hy + r * 0.40, hx + sgn * r * 0.72, hy - r * 0.20)
        p.quad(hx + sgn * r * 0.78, hy - r * 0.48, hx + sgn * r * 0.96, hy - r * 0.62)
        p.close()
        out.append((p.svg(), h * 0.0086, 0.72, False))

    # bang wedges across the forehead - wide and soft, not spiky
    lock_w = r * 0.66
    for i in range(3):
        t = i / 2.0
        cx0 = hx + (t - 0.5) * r * 1.30
        p = P()
        p.move(cx0 - lock_w * 0.5, hy - r * 0.82)
        p.quad(cx0, hy - r * 1.02, cx0 + lock_w * 0.5, hy - r * 0.82)
        p.quad(cx0 + lock_w * 0.34, hy - r * 0.50, cx0 + lock_w * 0.06, hy - r * 0.22)
        p.quad(cx0 - lock_w * 0.36, hy - r * 0.48, cx0 - lock_w * 0.5, hy - r * 0.82)
        p.close()
        out.append((p.svg(), h * 0.0084, 0.66, False))

    # flowing strands
    for sgn in (-1, 1):
        for i in range(3):
            t = i / 2
            top_x = hx + sgn * (base_off * 0.90 - r * 0.30 + t * r * 0.22)
            mid_x = hx + sgn * (base_off * 0.95 + t * r * 0.26)
            end_x = hx + sgn * (base_off * 0.60 - r * 0.20 + t * r * 0.26)
            p = P()
            p.move(top_x, top_y)
            p.quad(mid_x, top_y + (bot_y - top_y) * 0.44, end_x, bot_y)
            out.append((p.svg(), h * 0.0076 * (1 - t * 0.20), 0.72, False))

    return out


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "preview_dancer.svg"
    h = 480.0
    ox, oy = 300.0, 560.0

    pose = dict(lean=-6.0, headTilt=6.0, crouch=0.05,
                shoulderL=248.0, elbowL=264.0, shoulderR=98.0, elbowR=82.0,
                hipL=350.0, kneeL=358.0, hipR=68.0, kneeR=84.0)
    fig = build(pose, h)
    shapes = render(fig, h, ox, oy)

    parts = []
    for d, w, op, hot in shapes:
        parts.append(
            f'<path d="{d}" fill="none" stroke="url(#neon)" stroke-width="{w:.2f}" '
            f'stroke-linecap="round" stroke-linejoin="round" opacity="{op:.2f}"/>'
        )
        if hot:
            parts.append(
                f'<path d="{d}" fill="none" stroke="#FFFFFF" stroke-width="{w*0.38:.2f}" '
                f'stroke-linecap="round" stroke-linejoin="round" opacity="{op*0.40:.2f}"/>'
            )

    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 560" width="600" height="560">
  <defs>
    <linearGradient id="neon" x1="0" y1="-0.62" x2="0" y2="0.60">
      <stop offset="0%" stop-color="#22D3EE"/>
      <stop offset="20%" stop-color="#22D3EE"/>
      <stop offset="42%" stop-color="#EC4899"/>
      <stop offset="62%" stop-color="#FACC15"/>
      <stop offset="82%" stop-color="#22D3EE"/>
      <stop offset="100%" stop-color="#22D3EE"/>
    </linearGradient>
    <filter id="glow" x="-60%" y="-60%" width="220%" height="220%">
      <feGaussianBlur stdDeviation="6" result="b"/>
      <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
  </defs>
  <rect width="600" height="560" fill="#0B0E14"/>
  <g transform="translate({ox},{oy})" filter="url(#glow)">
    {chr(10).join('    ' + p for p in parts)}
  </g>
</svg>'''

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(svg)
    print(f"已生成 {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
