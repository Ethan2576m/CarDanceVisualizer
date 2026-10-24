#!/usr/bin/env python3
"""
Offline preview of the dancer geometry - realistic figure, line-only rendering.

Ports the exact numbers from the Kotlin renderer (Kinematics + DancerRenderer)
into SVG, so the figure can be judged without building the APK.

Round 3 target: **写实人体 + 线条画法** - the body itself has to read as a
real young woman, but the medium stays line art (no shading fills):
  - proportions retuned to ~7.2 heads (was ~6.4, too stylised);
  - the skull is a real cranium: forehead -> temple -> brow -> cheekbone ->
    jaw angle -> chin, still with no facial features;
  - anatomy lines added: sternum, iliac crest, patella + popliteal crease,
    ankle joint crease, palm crease;
  - hair grouped finer: parting, temple hairlines, 5+4 strands per side with
    staggered tips;
  - clothes differentiate tension (over the bust / hip) from drape (hem,
    waistband) instead of one uniform pleat set.

Usage:  python test/preview_dancer.py [out.svg]
"""

import math
import sys

# ---------------------------------------------------------------- skeleton
# Proportions retuned to ~7.0 heads tall with realistic circumferences:
# shoulder : chest : waist : hip = 1.06 : 1 : 0.70 : 1.08.
TORSO = 0.282
NECK = 0.078
HEAD_R = 0.067
UPPER_ARM = 0.140
FOREARM = 0.130
THIGH = 0.248
SHIN = 0.240
FOOT = 0.048
SHOULDER_HALF = 0.080
CHEST_HALF = 0.076
HIP_HALF = 0.082
WAIST_HALF = 0.053


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


def lerp(a, b, t):
    return a + (b - a) * t


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


# ------------------------------------------------------------ line weights
W_CONTOUR = 0.0160
W_LIMB = 0.0130
W_STRUCT = 0.0104
W_FOLD = 0.0068
W_HAIR = 0.0074
W_DETAIL = 0.0046

A_CONTOUR = 0.93
A_LIMB = 0.88
A_STRUCT = 0.76
A_FOLD = 0.60
A_HAIR = 0.70
A_DETAIL = 0.46


def add(out, p, w, a, bloom=False, hot=False):
    out.append((p.svg(), w, a, bloom, hot))


# ------------------------------------------------------------------ render
def render(fig, h, ox, oy):
    """Return a list of (path_svg, width, opacity, bloom, hot) tuples."""
    out = []
    r = fig["r"]
    hx, hy = fig["head"]

    rx, ry = unit(fig["shoulder_r"][0] - fig["shoulder_l"][0],
                  fig["shoulder_r"][1] - fig["shoulder_l"][1])
    ux, uy = unit(fig["chest"][0] - fig["pelvis"][0], fig["chest"][1] - fig["pelvis"][1])
    down_y = -uy

    base_x = fig["pelvis"][0] + (fig["chest"][0] - fig["pelvis"][0]) * 0.50
    base_y = fig["pelvis"][1] + (fig["chest"][1] - fig["pelvis"][1]) * 0.50
    ch = fig["chest_half"]
    wa = fig["waist_half"]
    sh = fig["shoulder_half"]

    chest_rx = fig["chest"][0] + rx * ch
    chest_ry = fig["chest"][1] + ry * ch
    chest_lx = fig["chest"][0] - rx * ch
    chest_ly = fig["chest"][1] - ry * ch
    waist_rx, waist_ry = base_x + rx * wa, base_y + ry * wa
    waist_lx, waist_ly = base_x - rx * wa, base_y - ry * wa
    bust = ch * 0.42

    wC = W_CONTOUR
    wL = W_LIMB
    wS = W_STRUCT
    wF = W_FOLD
    wH = W_HAIR
    wD = W_DETAIL

    # ============================================================ echo
    echo_a = 0.10
    dx, dy = -h * 0.004, h * 0.008
    p = P()
    p.move(fig["shoulder_r"][0] + dx, fig["shoulder_r"][1] + dy)
    p.quad(fig["chest"][0] + (fig["shoulder_r"][0] - fig["shoulder_l"][0]) * 0.44 + dx,
           fig["chest"][1] + (fig["shoulder_r"][1] - fig["shoulder_l"][1]) * 0.44 + h * 0.030 + dy,
           fig["hip_r"][0] + dx, fig["hip_r"][1] + dy)
    add(out, p, wL * 0.85, echo_a)
    for hip, knee, ankle in ((fig["hip_l"], fig["knee_l"], fig["ankle_l"]),
                             (fig["hip_r"], fig["knee_r"], fig["ankle_r"])):
        lw = h * 0.0270
        add(out, edge_path((hip[0] + dx, hip[1] + dy), (knee[0] + dx, knee[1] + dy),
                           (ankle[0] + dx, ankle[1] + dy),
                           lw, lw * 0.72, lw * 0.42, lw * 0.20, 1),
            wL * 0.62, echo_a)

    # ======================================================= back hair
    hair_back(out, fig, h)

    # =========================================================== legs
    for hip, knee, ankle, foot in ((fig["hip_l"], fig["knee_l"], fig["ankle_l"], fig["foot_l"]),
                                   (fig["hip_r"], fig["knee_r"], fig["ankle_r"], fig["foot_r"])):
        thigh, calf, anklew = h * 0.0300, h * 0.0200, h * 0.0108
        bend = thigh * 0.42
        add(out, edge_path(hip, knee, foot, thigh, calf, anklew, bend, 1), wL, A_LIMB, bloom=True)
        add(out, edge_path(hip, knee, foot, thigh, calf, anklew, bend, -1), wL, A_LIMB, bloom=True)

        # knee: patella + popliteal crease behind it
        kux, kuy = unit(knee[0] - hip[0], knee[1] - hip[1])
        knx, kny = -kuy, kux
        add(out, oval_path(knee[0] + kux * calf * 0.10, knee[1] + kuy * calf * 0.10,
                           calf * 0.42, calf * 0.54), wD * 1.7, A_DETAIL + 0.14)
        p = P()
        p.move(knee[0] - kux * calf * 0.28 + knx * calf * 0.40,
               knee[1] - kuy * calf * 0.28 + kny * calf * 0.40)
        p.quad(knee[0] - kux * calf * 0.46, knee[1] - kuy * calf * 0.46,
               knee[0] - kux * calf * 0.28 - knx * calf * 0.40,
               knee[1] - kuy * calf * 0.28 - kny * calf * 0.40)
        add(out, p, wD, A_DETAIL + 0.10)

        # ankle: joint crease across
        aux, auy = unit(ankle[0] - knee[0], ankle[1] - knee[1])
        anx, any_ = -auy, aux
        p = P()
        p.move(ankle[0] + anx * anklew * 0.92, ankle[1] + any_ * anklew * 0.92)
        p.line(ankle[0] - anx * anklew * 0.92, ankle[1] - any_ * anklew * 0.92)
        add(out, p, wD, A_DETAIL + 0.14)

        # shoe: a wedge with a toe crease
        add(out, oval_path(foot[0], foot[1], thigh * 0.44, thigh * 0.66), wS * 0.92, A_STRUCT + 0.06)
        fux, fuy = unit(foot[1] - ankle[1], -(foot[0] - ankle[0]))
        p = P()
        p.move(foot[0] - fux * thigh * 0.30, foot[1] + fuy * thigh * 0.52)
        p.line(foot[0] + fux * thigh * 0.30, foot[1] + fuy * thigh * 0.52)
        add(out, p, wD, A_DETAIL + 0.10)

    # ========================================================= skirt
    hem_y = fig["pelvis"][1] + h * 0.135
    lx, rhx = fig["hip_l"][0], fig["hip_r"][0]
    flare = 1.95
    hem_lx, hem_rx = lx * flare, rhx * flare
    p = P()
    p.move(lx * 1.05, fig["hip_l"][1] - h * 0.008)
    p.quad(lx * 1.48, fig["pelvis"][1] + h * 0.072, hem_lx, hem_y)
    seg = (hem_rx - hem_lx) / 3
    for i in range(3):
        x0, x1 = hem_lx + seg * i, hem_lx + seg * (i + 1)
        p.quad((x0 + x1) * 0.5, hem_y + h * 0.013, x1, hem_y)
    p.quad(rhx * 1.48, fig["pelvis"][1] + h * 0.072, rhx * 1.05, fig["hip_r"][1] - h * 0.008)
    p.close()
    add(out, p, wC * 0.88, A_CONTOUR, bloom=True, hot=True)

    # waistband
    p = P()
    p.move(lx * 1.02, fig["hip_l"][1] - h * 0.012)
    p.quad(fig["pelvis"][0], fig["pelvis"][1] - h * 0.020, rhx * 1.02, fig["hip_r"][1] - h * 0.012)
    add(out, p, wS, A_STRUCT, bloom=True)

    # pleats - heavy/light/heavy/light/heavy, the light ones stop short, and
    # the control point dips so the fabric hangs under gravity
    pleats = ((0.10, 1.15, 1.00), (0.30, 0.80, 0.72), (0.50, 1.20, 1.00),
              (0.70, 0.80, 0.72), (0.90, 1.15, 1.00))
    sy = fig["hip_l"][1] + h * 0.012
    for t, wmul, span in pleats:
        sx = lx + (rhx - lx) * t
        ex = hem_lx + (hem_rx - hem_lx) * t
        ey = hem_y - h * 0.008 - (1.0 - span) * (hem_y - sy) * 0.92
        p = P()
        p.move(sx, sy)
        p.quad(sx * 1.28, fig["pelvis"][1] + h * 0.082, ex, ey)
        add(out, p, wF * wmul, A_FOLD * (1.05 if wmul > 1 else 0.82))

    # hem inner line - fabric thickness
    p = P()
    p.move(hem_lx * 0.98, hem_y - h * 0.014)
    p.quad((hem_lx + hem_rx) * 0.5, hem_y - h * 0.004, hem_rx * 0.98, hem_y - h * 0.014)
    add(out, p, wF * 0.9, A_FOLD * 0.85)

    # =============================================== torso + crop top
    # Right side of the body: shoulder -> deltoid -> bust -> waist -> hip.
    p = P()
    p.move(*fig["shoulder_r"])
    p.quad(chest_rx + rx * bust * 0.55, chest_ry + ry * bust * 0.35 + down_y * 0.012 * h,
           chest_rx + rx * bust * 0.85, chest_ry + ry * bust * 0.80 + down_y * 0.026 * h)
    p.quad(chest_rx + rx * bust, chest_ry + ry * bust + down_y * 0.034 * h, chest_rx, chest_ry)
    p.quad(waist_rx - rx * 0.016 * h, waist_ry - 0.008 * h, waist_rx, waist_ry)
    p.quad(fig["hip_r"][0] + rx * 0.012 * h, fig["hip_r"][1] - 0.048 * h, *fig["hip_r"])
    add(out, p, wC, A_CONTOUR, bloom=True, hot=True)

    # Left side, mirrored.
    p = P()
    p.move(*fig["shoulder_l"])
    p.quad(chest_lx - rx * bust * 0.55, chest_ly - ry * bust * 0.35 + down_y * 0.012 * h,
           chest_lx - rx * bust * 0.85, chest_ly - ry * bust * 0.80 + down_y * 0.026 * h)
    p.quad(chest_lx - rx * bust, chest_ly - ry * bust + down_y * 0.034 * h, chest_lx, chest_ly)
    p.quad(waist_lx + rx * 0.016 * h, waist_ly - 0.008 * h, waist_lx, waist_ly)
    p.quad(fig["hip_l"][0] - rx * 0.012 * h, fig["hip_l"][1] - 0.048 * h, *fig["hip_l"])
    add(out, p, wC, A_CONTOUR, bloom=True, hot=True)

    # shoulder caps - the deltoid mass over each shoulder joint
    for sgn, shd in ((-1, fig["shoulder_l"]), (1, fig["shoulder_r"])):
        p = P()
        p.move(fig["neck"][0] + sgn * r * 0.24, fig["neck"][1] + r * 0.06)
        p.quad(shd[0] + sgn * r * 0.10, shd[1] - r * 0.32, shd[0] + sgn * r * 0.26, shd[1] + r * 0.18)
        add(out, p, wS, A_STRUCT, bloom=True)

    # collarbones
    for sgn, shd in ((1, fig["shoulder_r"]), (-1, fig["shoulder_l"])):
        p = P()
        p.move(*fig["neck"])
        p.quad((fig["neck"][0] + shd[0]) * 0.5, (fig["neck"][1] + shd[1]) * 0.5 + down_y * 0.010 * h, *shd)
        add(out, p, wS * 0.8, A_STRUCT * 0.72)

    # sternum - a light centre line from the neckline to the underbust
    p = P()
    p.move(fig["chest"][0], fig["chest"][1] + down_y * 0.020 * h)
    p.quad(fig["chest"][0], fig["chest"][1] + down_y * 0.040 * h,
           fig["chest"][0], fig["chest"][1] + down_y * 0.054 * h)
    add(out, p, wD, A_DETAIL + 0.06)

    # spine - two broken segments (断线)
    p = P()
    p.move(fig["chest"][0], fig["chest"][1] + down_y * 0.030 * h)
    p.quad(fig["chest"][0], fig["chest"][1] + down_y * 0.085 * h, base_x, base_y - down_y * 0.012 * h)
    add(out, p, wD, A_DETAIL + 0.06)
    p = P()
    p.move(base_x, base_y - down_y * 0.004 * h)
    p.quad(waist_lx + (waist_rx - waist_lx) * 0.30, waist_ly + down_y * 0.006 * h,
           waist_lx + (waist_rx - waist_lx) * 0.60, waist_ly + down_y * 0.002 * h)
    add(out, p, wD, A_DETAIL)

    # bust curve: one line under each bust
    for sgn in (-1, 1):
        p = P()
        p.move(fig["chest"][0] + sgn * ch * 0.06, fig["chest"][1] + down_y * 0.030 * h)
        p.quad(fig["chest"][0] + sgn * ch * 0.86, fig["chest"][1] + down_y * 0.058 * h,
               fig["chest"][0] + sgn * ch * 1.00, fig["chest"][1] + down_y * 0.026 * h)
        add(out, p, wS * 0.8, A_STRUCT * 0.8)

    # iliac crest - the top of the pelvis, visible above the skirt waistband
    p = P()
    p.move(fig["hip_l"][0], fig["hip_l"][1] - h * 0.006)
    p.quad(fig["pelvis"][0], fig["pelvis"][1] - h * 0.032, fig["hip_r"][0], fig["hip_r"][1] - h * 0.006)
    add(out, p, wD, A_DETAIL + 0.08)

    # navel
    p = P()
    p.move(waist_lx, waist_ly)
    p.quad(base_x, base_y + down_y * 0.016 * h, waist_rx, waist_ry)
    add(out, p, wD, A_DETAIL + 0.14)

    # --- the crop top: neckline, straps, side seams, hem, armpit folds

    # neckline
    p = P()
    p.move(fig["shoulder_l"][0] + rx * sh * 0.52, fig["shoulder_l"][1])
    p.quad(fig["chest"][0] - rx * ch * 0.42, fig["chest"][1] + down_y * 0.004 * h,
           fig["chest"][0], fig["chest"][1] + down_y * 0.020 * h)
    p.quad(fig["chest"][0] + rx * ch * 0.42, fig["chest"][1] + down_y * 0.004 * h,
           fig["shoulder_r"][0] - rx * sh * 0.52, fig["shoulder_r"][1])
    add(out, p, wF, A_FOLD + 0.10)

    # straps over the shoulders
    for sgn, shd in ((-1, fig["shoulder_l"]), (1, fig["shoulder_r"])):
        p = P()
        p.move(shd[0] + sgn * rx * sh * 0.52, shd[1])
        p.quad(shd[0] + sgn * rx * sh * 0.26, shd[1] - r * 0.24, shd[0] + sgn * rx * sh * 0.58, shd[1] - r * 0.04)
        add(out, p, wF * 0.9, A_FOLD)

    # side seams, ending just under the bust
    for sgn in (-1, 1):
        p = P()
        p.move(fig["chest"][0] + sgn * ch * 0.92, fig["chest"][1] + down_y * 0.030 * h)
        p.quad(fig["chest"][0] + sgn * ch * 1.02, fig["chest"][1] + down_y * 0.044 * h,
               fig["chest"][0] + sgn * ch * 0.98, fig["chest"][1] + down_y * 0.058 * h)
        add(out, p, wF, A_FOLD + 0.06)
    # hem of the top
    p = P()
    p.move(fig["chest"][0] - ch * 0.98, fig["chest"][1] + down_y * 0.058 * h)
    p.quad(fig["chest"][0], fig["chest"][1] + down_y * 0.068 * h,
           fig["chest"][0] + ch * 0.98, fig["chest"][1] + down_y * 0.058 * h)
    add(out, p, wF, A_FOLD + 0.08)

    # armpit folds - two per side, heavy then light (衣纹朝关节集中)
    for sgn, shd in ((-1, fig["shoulder_l"]), (1, fig["shoulder_r"])):
        p = P()
        p.move(shd[0] + sgn * sh * 0.70, shd[1] + down_y * 0.012 * h)
        p.quad(fig["chest"][0] + sgn * ch * 0.60, fig["chest"][1] + down_y * 0.036 * h,
               fig["chest"][0] + sgn * ch * 0.34, fig["chest"][1] + down_y * 0.056 * h)
        add(out, p, wF, A_FOLD + 0.06)
        p = P()
        p.move(shd[0] + sgn * sh * 0.78, shd[1] + down_y * 0.020 * h)
        p.quad(fig["chest"][0] + sgn * ch * 0.76, fig["chest"][1] + down_y * 0.046 * h,
               fig["chest"][0] + sgn * ch * 0.54, fig["chest"][1] + down_y * 0.062 * h)
        add(out, p, wF * 0.78, A_FOLD * 0.82)

    # ========================================================= arms
    for shd, el, ha in ((fig["shoulder_l"], fig["elbow_l"], fig["hand_l"]),
                        (fig["shoulder_r"], fig["elbow_r"], fig["hand_r"])):
        upper, fore = h * 0.0200, h * 0.0120
        bend = upper * 0.62
        add(out, edge_path(shd, el, ha, upper, fore, fore * 0.62, bend, 1), wL * 0.88, A_LIMB, bloom=True)
        add(out, edge_path(shd, el, ha, upper, fore, fore * 0.62, bend, -1), wL * 0.88, A_LIMB, bloom=True)
        # elbow: olecranon
        add(out, oval_path(el[0], el[1], fore * 0.90, fore * 0.90), wD * 1.4, A_DETAIL + 0.08)
        # hand: palm + finger crease
        add(out, oval_path(ha[0], ha[1], upper * 0.58, upper * 0.68), wS * 0.78, A_STRUCT + 0.10)
        p = P()
        p.move(ha[0] - upper * 0.42, ha[1] + upper * 0.16)
        p.quad(ha[0], ha[1] + upper * 0.30, ha[0] + upper * 0.42, ha[1] + upper * 0.16)
        add(out, p, wD, A_DETAIL + 0.14)
        # wrist crease
        fux, fuy = unit(ha[0] - el[0], ha[1] - el[1])
        nx, ny = -fuy, fux
        wx = el[0] + (ha[0] - el[0]) * 0.82
        wy = el[1] + (ha[1] - el[1]) * 0.82
        p = P()
        p.move(wx + nx * fore * 0.88, wy + ny * fore * 0.88)
        p.line(wx - nx * fore * 0.88, wy - ny * fore * 0.88)
        add(out, p, wD, A_DETAIL + 0.10)

    # ========================================================= head
    # A real cranium: crown -> temple -> brow -> cheekbone -> jaw -> chin.
    # Still no facial features anywhere.
    p = P()
    p.move(hx, hy - r * 1.00)
    p.quad(hx + r * 0.99, hy - r * 0.90, hx + r * 0.90, hy - r * 0.50)
    p.quad(hx + r * 1.00, hy - r * 0.16, hx + r * 0.96, hy + r * 0.04)
    p.quad(hx + r * 0.92, hy + r * 0.32, hx + r * 0.80, hy + r * 0.52)
    p.quad(hx + r * 0.66, hy + r * 0.76, hx + r * 0.54, hy + r * 0.90)
    p.quad(hx + r * 0.36, hy + r * 1.04, hx + r * 0.12, hy + r * 1.07)
    p.quad(hx - r * 0.36, hy + r * 1.04, hx - r * 0.54, hy + r * 0.90)
    p.quad(hx - r * 0.66, hy + r * 0.76, hx - r * 0.80, hy + r * 0.52)
    p.quad(hx - r * 0.92, hy + r * 0.32, hx - r * 0.96, hy + r * 0.04)
    p.quad(hx - r * 1.00, hy - r * 0.16, hx - r * 0.90, hy - r * 0.50)
    p.quad(hx - r * 0.99, hy - r * 0.90, hx, hy - r * 1.00)
    p.close()
    add(out, p, wC, A_CONTOUR, bloom=True, hot=True)

    # neck: two converging lines from the jaw down to the sternum
    for sgn in (-1, 1):
        p = P()
        p.move(hx + sgn * r * 0.34, hy + r * 0.92)
        p.quad(fig["neck"][0] + sgn * r * 0.46, fig["neck"][1] + r * 0.30,
               fig["neck"][0] + sgn * r * 0.42, fig["neck"][1])
        add(out, p, wS * 0.60, A_STRUCT * 0.82)

    # ==================================================== front hair
    hair_bangs(out, fig, h)
    hair_face_locks(out, fig, h)
    hair_strands(out, fig, h, front=True)

    return out


# ------------------------------------------------------------------- hair
def hair_back(out, fig, h):
    """Teardrop mass behind the head + grouped inner flow lines."""
    r = fig["r"]
    hx, hy = fig["head"]
    base_off = fig["shoulder_half"]
    bot_y = fig["pelvis"][1] + r * 2.6

    p = P()
    p.move(hx - r * 1.00, hy - r * 0.34)
    p.quad(hx - base_off * 1.02, hy + r * 1.30, hx - base_off * 0.55, bot_y)
    p.quad(hx, bot_y + r * 0.55, hx + base_off * 0.55, bot_y)
    p.quad(hx + base_off * 1.02, hy + r * 1.30, hx + r * 1.00, hy - r * 0.34)
    p.quad(hx + r * 1.15, hy - r * 1.20, hx, hy - r * 1.30)
    p.quad(hx - r * 1.15, hy - r * 1.20, hx - r * 1.00, hy - r * 0.34)
    p.close()
    add(out, p, W_STRUCT * 0.82, A_STRUCT * 0.70, bloom=True)

    # inner flow lines, three per side, sweeping from the crown to the ends
    for sgn in (-1, 1):
        for i in range(3):
            t = i / 2.0
            sx = hx + sgn * r * (0.55 - t * 0.25)
            sy = hy - r * (0.34 - t * 0.10)
            ex = hx + sgn * base_off * (0.42 + t * 0.30)
            ey = bot_y - r * (0.05 + t * 0.12)
            mx = hx + sgn * base_off * (0.72 + t * 0.30)
            my = hy + r * (1.15 + t * 0.35)
            p = P()
            p.move(sx, sy)
            p.quad(mx, my, ex, ey)
            add(out, p, W_HAIR * (1 - t * 0.2), A_HAIR * (0.85 - t * 0.18))


def hair_bangs(out, fig, h):
    """One grouped fringe across the forehead + a parting + separation lines."""
    r = fig["r"]
    hx, hy = fig["head"]

    # side parting: the whole fringe sits off-centre, and the lower edge is
    # deliberately uneven - a real fringe never mirrors.
    po = -r * 0.16
    p = P()
    p.move(hx + po - r * 0.95, hy - r * 0.30)
    p.quad(hx + po - r * 0.55, hy - r * 0.78, hx + po, hy - r * 0.84)
    p.quad(hx + po + r * 0.55, hy - r * 0.78, hx + po + r * 0.95, hy - r * 0.30)
    p.quad(hx + po + r * 0.70, hy + r * 0.22, hx + po + r * 0.45, hy + r * 0.10)
    p.quad(hx + po + r * 0.22, hy + r * 0.26, hx + po, hy + r * 0.12)
    p.quad(hx + po - r * 0.22, hy + r * 0.36, hx + po - r * 0.45, hy + r * 0.20)
    p.quad(hx + po - r * 0.70, hy + r * 0.30, hx + po - r * 0.95, hy - r * 0.30)
    p.close()
    add(out, p, W_HAIR, A_HAIR)

    # parting running back from the crown through the fringe
    p = P()
    p.move(hx + po, hy - r * 0.82)
    p.quad(hx + po + r * 0.10, hy - r * 0.40, hx + po + r * 0.06, hy + r * 0.06)
    add(out, p, W_HAIR * 0.72, A_HAIR * 0.62)

    # separation lines inside the mass, one per lock boundary
    for sx in (hx + po - r * 0.45, hx + po + r * 0.45):
        p = P()
        p.move(sx, hy - r * 0.58)
        p.quad(sx + (sx - hx - po) * 0.10, hy - r * 0.12, sx + (sx - hx - po) * 0.06, hy + r * 0.10)
        add(out, p, W_HAIR * 0.72, A_HAIR * 0.62)

    # temple hairlines - where the fringe meets the skin at the sides
    for sgn in (-1, 1):
        p = P()
        p.move(hx + sgn * r * 0.86, hy - r * 0.20)
        p.quad(hx + sgn * r * 0.97, hy - r * 0.04, hx + sgn * r * 0.90, hy + r * 0.10)
        add(out, p, W_HAIR * 0.7, A_HAIR * 0.55)


def hair_face_locks(out, fig, h):
    """Two tapered locks framing the face."""
    r = fig["r"]
    hx, hy = fig["head"]
    for sgn in (-1, 1):
        p = P()
        p.move(hx + sgn * r * 0.96, hy - r * 0.62)
        p.quad(hx + sgn * r * 1.24, hy + r * 0.30, hx + sgn * r * 0.92, hy + r * 1.30)
        p.quad(hx + sgn * r * 0.74, hy + r * 0.40, hx + sgn * r * 0.72, hy - r * 0.20)
        p.quad(hx + sgn * r * 0.78, hy - r * 0.48, hx + sgn * r * 0.96, hy - r * 0.62)
        p.close()
        add(out, p, W_HAIR, A_HAIR * 0.9)
        p = P()
        p.move(hx + sgn * r * 0.98, hy - r * 0.50)
        p.quad(hx + sgn * r * 1.10, hy + r * 0.35, hx + sgn * r * 0.86, hy + r * 1.16)
        add(out, p, W_HAIR * 0.7, A_HAIR * 0.55)


def hair_strands(out, fig, h, front):
    """Falling strands, tips staggered so the ends never line up in a row."""
    r = fig["r"]
    hx, hy = fig["head"]
    base_off = fig["shoulder_half"]
    top_y = hy - r * 0.40
    bot_y = fig["pelvis"][1] + r * 2.6
    count = 4 if front else 5
    stagger = (0.00, 0.30, 0.55, 0.78, 0.40)
    lean = (0.04, -0.05, 0.03, -0.06, 0.05)
    # the right side of the head gets its own rhythm - real hair never mirrors
    stagger_r = (0.12, 0.44, 0.68, 0.92, 0.52)
    lean_r = (-0.06, 0.05, -0.04, 0.06, -0.05)
    for sgn in (-1, 1):
        stag, ln = (stagger, lean) if sgn < 0 else (stagger_r, lean_r)
        for i in range(count):
            t = i / (count - 1)
            top_x = hx + sgn * (base_off * (0.60 + 0.30 * t) + r * 0.06)
            mid_x = hx + sgn * (base_off * (0.92 + 0.20 * t) + r * 0.16) + sgn * ln[i] * r
            end_x = hx + sgn * (base_off * (0.42 + 0.34 * t) + r * 0.10) + sgn * ln[i] * r * 1.7
            end_y = bot_y - r * stag[i]
            p = P()
            p.move(top_x, top_y)
            p.quad(mid_x, top_y + (bot_y - top_y) * 0.44, end_x, end_y)
            add(out, p, W_HAIR * (1 - t * 0.18), A_HAIR * (0.92 - t * 0.20))


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
    for d, w, op, bloom, hot in shapes:
        if bloom:
            parts.append(
                f'<path d="{d}" fill="none" stroke="url(#neon)" stroke-width="{w * 2.9:.2f}" '
                f'stroke-linecap="round" stroke-linejoin="round" opacity="{op * 0.16:.2f}"/>'
            )
        parts.append(
            f'<path d="{d}" fill="none" stroke="url(#neon)" stroke-width="{w:.2f}" '
            f'stroke-linecap="round" stroke-linejoin="round" opacity="{op:.2f}"/>'
        )
        if hot:
            parts.append(
                f'<path d="{d}" fill="none" stroke="#FFFFFF" stroke-width="{w * 0.38:.2f}" '
                f'stroke-linecap="round" stroke-linejoin="round" opacity="{op * 0.40:.2f}"/>'
            )

    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 900" width="600" height="900">
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
  <rect width="600" height="900" fill="#0B0E14"/>
  <g transform="translate({ox},{oy})" filter="url(#glow)">
    {chr(10).join('    ' + p for p in parts)}
  </g>
</svg>'''

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(svg)
    print(f"已生成 {out_path}（{len(shapes)} 条线）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
