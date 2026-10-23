#!/usr/bin/env python3
"""
Offline preview of the dancer geometry - sketch line-art revision.

Ports the exact numbers from the Kotlin renderer (Kinematics + DancerRenderer)
into SVG, so the figure can be judged without building the APK. The neon look
is reproduced with an SVG gradient + gaussian blur filter.

Line language follows 速写 (gesture sketch) conventions:
  - contour lines carry the silhouette (heaviest, confident, unbroken)
  - structure lines mark anatomy (collarbones, shoulder caps, spine, bust)
  - fold lines gather at the joints on the clothes, alternating heavy/light
  - hair is drawn in groups along the growth direction, tips staggered
  - the face carries no features at all - only the outer contour, partly
    veiled by the hair, with a hairline where the locks meet the skin

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
# Five tiers, following the 速写 hierarchy: silhouette > anatomy > garment
# folds > hair groups > incidental detail.  Widths are fractions of h.
W_CONTOUR = 0.0162
W_LIMB = 0.0132
W_STRUCT = 0.0106
W_FOLD = 0.0070
W_HAIR = 0.0078
W_DETAIL = 0.0048

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

    # ============================================================ echo
    # Motion trail: the previous frame's contour, faint.  This is the
    # 动势线 of the sketch - it sells the dance.
    echo_a = 0.10
    dx, dy = -h * 0.004, h * 0.008
    p = P()
    p.move(fig["shoulder_r"][0] + dx, fig["shoulder_r"][1] + dy)
    p.quad(fig["chest"][0] + (fig["shoulder_r"][0] - fig["shoulder_l"][0]) * 0.44 + dx,
           fig["chest"][1] + (fig["shoulder_r"][1] - fig["shoulder_l"][1]) * 0.44 + h * 0.030 + dy,
           fig["hip_r"][0] + dx, fig["hip_r"][1] + dy)
    add(out, p, W_LIMB * 0.85, echo_a)
    for hip, knee, ankle in ((fig["hip_l"], fig["knee_l"], fig["ankle_l"]),
                             (fig["hip_r"], fig["knee_r"], fig["ankle_r"])):
        lw = h * 0.0270
        add(out, edge_path((hip[0] + dx, hip[1] + dy), (knee[0] + dx, knee[1] + dy),
                           (ankle[0] + dx, ankle[1] + dy),
                           lw, lw * 0.72, lw * 0.42, lw * 0.20, 1),
            W_LIMB * 0.62, echo_a)

    # ======================================================= back hair
    hair_back(out, fig, h)

    # =========================================================== legs
    for hip, knee, ankle, foot in ((fig["hip_l"], fig["knee_l"], fig["ankle_l"], fig["foot_l"]),
                                   (fig["hip_r"], fig["knee_r"], fig["ankle_r"], fig["foot_r"])):
        thigh, calf, anklew = h * 0.0270, h * 0.0185, h * 0.0105
        bend = thigh * 0.40
        add(out, edge_path(hip, knee, foot, thigh, calf, anklew, bend, 1), W_LIMB, A_LIMB, bloom=True)
        add(out, edge_path(hip, knee, foot, thigh, calf, anklew, bend, -1), W_LIMB, A_LIMB, bloom=True)
        # knee / ankle joint marks
        add(out, oval_path(knee[0], knee[1], calf * 0.92, calf * 0.92), W_DETAIL * 1.6, A_DETAIL + 0.10)
        add(out, oval_path(ankle[0], ankle[1], anklew * 1.05, anklew * 1.05), W_DETAIL, A_DETAIL + 0.06)
        # shoe
        add(out, oval_path(foot[0], foot[1], thigh * 0.50, thigh * 0.62), W_STRUCT * 0.95, A_STRUCT + 0.08)

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
    add(out, p, W_CONTOUR * 0.88, A_CONTOUR, bloom=True, hot=True)

    # waistband
    p = P()
    p.move(lx * 1.02, fig["hip_l"][1] - h * 0.012)
    p.quad(fig["pelvis"][0], fig["pelvis"][1] - h * 0.020, rhx * 1.02, fig["hip_r"][1] - h * 0.012)
    add(out, p, W_STRUCT, A_STRUCT, bloom=True)

    # pleats - 一疏一密: heavy/light/heavy/light/heavy, the light ones stop short
    pleats = ((0.12, 1.15, 1.00), (0.32, 0.80, 0.72), (0.50, 1.20, 1.00),
              (0.68, 0.80, 0.72), (0.88, 1.15, 1.00))
    sy = fig["hip_l"][1] + h * 0.012
    for t, wmul, span in pleats:
        sx = lx + (rhx - lx) * t
        ex = hem_lx + (hem_rx - hem_lx) * t
        ey = hem_y - h * 0.008 - (1.0 - span) * (hem_y - sy) * 0.92
        p = P()
        p.move(sx, sy)
        p.quad(sx * 1.28, fig["pelvis"][1] + h * 0.070, ex, ey)
        add(out, p, W_FOLD * wmul, A_FOLD * (1.05 if wmul > 1 else 0.82))

    # hem inner line - fabric thickness, a second line just above the scallops
    p = P()
    p.move(hem_lx * 0.98, hem_y - h * 0.014)
    p.quad((hem_lx + hem_rx) * 0.5, hem_y - h * 0.004, hem_rx * 0.98, hem_y - h * 0.014)
    add(out, p, W_FOLD * 0.9, A_FOLD * 0.85)

    # =============================================== torso + crop top
    # Right side of the body: shoulder -> bust -> waist -> hip.
    p = P()
    p.move(*fig["shoulder_r"])
    p.quad(chest_rx + rx * bust, chest_ry + ry * bust + down_y * 0.030 * h, chest_rx, chest_ry)
    p.quad(waist_rx - rx * 0.014 * h, waist_ry - 0.006 * h, waist_rx, waist_ry)
    p.quad(fig["hip_r"][0] + rx * 0.010 * h, fig["hip_r"][1] - 0.045 * h, *fig["hip_r"])
    add(out, p, W_CONTOUR, A_CONTOUR, bloom=True, hot=True)

    # Left side, mirrored.
    p = P()
    p.move(*fig["shoulder_l"])
    p.quad(chest_lx - rx * bust, chest_ly - ry * bust + down_y * 0.030 * h, chest_lx, chest_ly)
    p.quad(waist_lx + rx * 0.014 * h, waist_ly - 0.006 * h, waist_lx, waist_ly)
    p.quad(fig["hip_l"][0] - rx * 0.010 * h, fig["hip_l"][1] - 0.045 * h, *fig["hip_l"])
    add(out, p, W_CONTOUR, A_CONTOUR, bloom=True, hot=True)

    # shoulder caps - the deltoid mass over each shoulder joint
    for sgn, shd in ((-1, fig["shoulder_l"]), (1, fig["shoulder_r"])):
        p = P()
        p.move(fig["neck"][0] + sgn * r * 0.26, fig["neck"][1] + r * 0.04)
        p.quad(shd[0] + sgn * r * 0.12, shd[1] - r * 0.30, shd[0] + sgn * r * 0.24, shd[1] + r * 0.16)
        add(out, p, W_STRUCT, A_STRUCT, bloom=True)

    # collarbones
    for sgn, shd in ((1, fig["shoulder_r"]), (-1, fig["shoulder_l"])):
        p = P()
        p.move(*fig["neck"])
        p.quad((fig["neck"][0] + shd[0]) * 0.5, (fig["neck"][1] + shd[1]) * 0.5 + down_y * 0.010 * h, *shd)
        add(out, p, W_STRUCT * 0.8, A_STRUCT * 0.85)

    # spine - two broken segments (断线), lighter in the middle: 虚实
    p = P()
    p.move(fig["chest"][0], fig["chest"][1] + down_y * 0.030 * h)
    p.quad(fig["chest"][0], fig["chest"][1] + down_y * 0.085 * h, base_x, base_y - down_y * 0.012 * h)
    add(out, p, W_DETAIL, A_DETAIL + 0.06)
    p = P()
    p.move(base_x, base_y - down_y * 0.004 * h)
    p.quad(waist_lx + (waist_rx - waist_lx) * 0.30, waist_ly + down_y * 0.006 * h,
           waist_lx + (waist_rx - waist_lx) * 0.60, waist_ly + down_y * 0.002 * h)
    add(out, p, W_DETAIL, A_DETAIL)

    # bust curve: one line under each bust
    for sgn in (-1, 1):
        p = P()
        p.move(fig["chest"][0] + sgn * ch * 0.06, fig["chest"][1] + down_y * 0.030 * h)
        p.quad(fig["chest"][0] + sgn * ch * 0.86, fig["chest"][1] + down_y * 0.058 * h,
               fig["chest"][0] + sgn * ch * 1.00, fig["chest"][1] + down_y * 0.026 * h)
        add(out, p, W_STRUCT * 0.8, A_STRUCT * 0.8)

    # navel
    p = P()
    p.move(waist_lx, waist_ly)
    p.quad(base_x, base_y + down_y * 0.016 * h, waist_rx, waist_ry)
    add(out, p, W_DETAIL, A_DETAIL + 0.08)

    # --- crop top: neckline, straps, side seams, hem, armpit folds
    # neckline
    p = P()
    p.move(fig["shoulder_l"][0] + rx * sh * 0.52, fig["shoulder_l"][1])
    p.quad(fig["chest"][0] - rx * ch * 0.42, fig["chest"][1] + down_y * 0.004 * h,
           fig["chest"][0], fig["chest"][1] + down_y * 0.020 * h)
    p.quad(fig["chest"][0] + rx * ch * 0.42, fig["chest"][1] + down_y * 0.004 * h,
           fig["shoulder_r"][0] - rx * sh * 0.52, fig["shoulder_r"][1])
    add(out, p, W_FOLD, A_FOLD + 0.10)

    # straps over the shoulders
    for sgn, shd in ((-1, fig["shoulder_l"]), (1, fig["shoulder_r"])):
        p = P()
        p.move(shd[0] + sgn * rx * sh * 0.52, shd[1])
        p.quad(shd[0] + sgn * rx * sh * 0.26, shd[1] - r * 0.22, shd[0] + sgn * rx * sh * 0.58, shd[1] - r * 0.04)
        add(out, p, W_FOLD * 0.9, A_FOLD)

    # side seams + hem of the crop top (ends just under the bust)
    for sgn in (-1, 1):
        p = P()
        p.move(fig["chest"][0] + sgn * ch * 0.92, fig["chest"][1] + down_y * 0.030 * h)
        p.quad(fig["chest"][0] + sgn * ch * 1.02, fig["chest"][1] + down_y * 0.044 * h,
               fig["chest"][0] + sgn * ch * 0.98, fig["chest"][1] + down_y * 0.058 * h)
        add(out, p, W_FOLD, A_FOLD + 0.06)
    p = P()
    p.move(fig["chest"][0] - ch * 0.98, fig["chest"][1] + down_y * 0.058 * h)
    p.quad(fig["chest"][0], fig["chest"][1] + down_y * 0.068 * h,
           fig["chest"][0] + ch * 0.98, fig["chest"][1] + down_y * 0.058 * h)
    add(out, p, W_FOLD, A_FOLD + 0.08)

    # armpit folds - two per side, heavy then light (衣纹朝关节集中)
    for sgn, shd in ((-1, fig["shoulder_l"]), (1, fig["shoulder_r"])):
        p = P()
        p.move(shd[0] + sgn * sh * 0.70, shd[1] + down_y * 0.012 * h)
        p.quad(fig["chest"][0] + sgn * ch * 0.60, fig["chest"][1] + down_y * 0.036 * h,
               fig["chest"][0] + sgn * ch * 0.34, fig["chest"][1] + down_y * 0.056 * h)
        add(out, p, W_FOLD, A_FOLD + 0.06)
        p = P()
        p.move(shd[0] + sgn * sh * 0.78, shd[1] + down_y * 0.020 * h)
        p.quad(fig["chest"][0] + sgn * ch * 0.76, fig["chest"][1] + down_y * 0.046 * h,
               fig["chest"][0] + sgn * ch * 0.54, fig["chest"][1] + down_y * 0.062 * h)
        add(out, p, W_FOLD * 0.78, A_FOLD * 0.82)

    # ========================================================= arms
    for shd, el, ha in ((fig["shoulder_l"], fig["elbow_l"], fig["hand_l"]),
                        (fig["shoulder_r"], fig["elbow_r"], fig["hand_r"])):
        upper, fore = h * 0.0195, h * 0.0118
        bend = upper * 0.60
        add(out, edge_path(shd, el, ha, upper, fore, fore * 0.62, bend, 1), W_LIMB * 0.88, A_LIMB, bloom=True)
        add(out, edge_path(shd, el, ha, upper, fore, fore * 0.62, bend, -1), W_LIMB * 0.88, A_LIMB, bloom=True)
        add(out, oval_path(el[0], el[1], fore * 0.95, fore * 0.95), W_DETAIL * 1.4, A_DETAIL + 0.08)
        add(out, oval_path(ha[0], ha[1], upper * 0.56, upper * 0.66), W_STRUCT * 0.8, A_STRUCT + 0.06)
        # wrist line - separates the forearm from the hand
        fx, fy = unit(ha[0] - el[0], ha[1] - el[1])
        nx, ny = -fy, fx
        wx = el[0] + (ha[0] - el[0]) * 0.82
        wy = el[1] + (ha[1] - el[1]) * 0.82
        p = P()
        p.move(wx + nx * fore * 0.88, wy + ny * fore * 0.88)
        p.line(wx - nx * fore * 0.88, wy - ny * fore * 0.88)
        add(out, p, W_DETAIL, A_DETAIL + 0.10)

    # ========================================================= head
    # Outer contour only - no features anywhere on the face.
    p = P()
    p.move(hx - r * 0.86, hy)
    p.quad(hx - r * 0.95, hy - r * 0.86, hx, hy - r)
    p.quad(hx + r * 0.95, hy - r * 0.86, hx + r * 0.86, hy)
    p.quad(hx + r * 0.80, hy + r * 0.52, hx + r * 0.30, hy + r * 0.96)
    p.quad(hx, hy + r * 1.05, hx - r * 0.30, hy + r * 0.96)
    p.quad(hx - r * 0.80, hy + r * 0.52, hx - r * 0.86, hy)
    p.close()
    add(out, p, W_CONTOUR, A_CONTOUR, bloom=True, hot=True)

    # neck: two converging lines from the base of the neck up to the jaw
    for sgn in (-1, 1):
        p = P()
        p.move(fig["neck"][0] + sgn * r * 0.42, fig["neck"][1])
        p.quad(hx + sgn * r * 0.46, hy + r * 0.92, hx + sgn * r * 0.34, hy + r * 0.74)
        add(out, p, W_STRUCT * 0.62, A_STRUCT * 0.82)

    # ==================================================== front hair
    # No separate hairline: the crown arc of the mass and the top edges of
    # the bangs already define where hair meets skin.
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
    bot_y = fig["pelvis"][1] + r * 2.4

    # The silhouette arcs *over* the skull, so the crown carries hair volume
    # instead of a bald cap with a horizontal cut across the top.
    p = P()
    p.move(hx - r * 1.00, hy - r * 0.34)
    p.quad(hx - base_off * 1.02, hy + r * 1.30, hx - base_off * 0.55, bot_y)
    p.quad(hx, bot_y + r * 0.55, hx + base_off * 0.55, bot_y)
    p.quad(hx + base_off * 1.02, hy + r * 1.30, hx + r * 1.00, hy - r * 0.34)
    p.quad(hx + r * 1.28, hy - r * 1.34, hx, hy - r * 1.46)
    p.quad(hx - r * 1.28, hy - r * 1.34, hx - r * 1.00, hy - r * 0.34)
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
    """One grouped fringe across the forehead + internal separation lines.

    Sketch bangs are a single mass with a fringed lower edge, not four
    separate wedges - the lock divisions are drawn *inside* the silhouette.
    """
    r = fig["r"]
    hx, hy = fig["head"]

    # silhouette: temples up over the crown, back down, then a wavy lower
    # edge whose four soft bumps are the lock tips
    p = P()
    p.move(hx - r * 0.95, hy - r * 0.30)
    p.quad(hx - r * 0.55, hy - r * 0.78, hx, hy - r * 0.84)
    p.quad(hx + r * 0.55, hy - r * 0.78, hx + r * 0.95, hy - r * 0.30)
    p.quad(hx + r * 0.70, hy + r * 0.18, hx + r * 0.45, hy + r * 0.10)
    p.quad(hx + r * 0.22, hy + r * 0.30, hx, hy + r * 0.16)
    p.quad(hx - r * 0.22, hy + r * 0.28, hx - r * 0.45, hy + r * 0.10)
    p.quad(hx - r * 0.70, hy + r * 0.20, hx - r * 0.95, hy - r * 0.30)
    p.close()
    add(out, p, W_HAIR, A_HAIR)

    # separation lines inside the mass, one per lock boundary
    for sx in (hx - r * 0.45, hx, hx + r * 0.45):
        p = P()
        p.move(sx, hy - r * 0.58)
        p.quad(sx + (sx - hx) * 0.10, hy - r * 0.12, sx + (sx - hx) * 0.06, hy + r * 0.10)
        add(out, p, W_HAIR * 0.72, A_HAIR * 0.62)


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
    """Falling strands, tips staggered so the ends never line up."""
    r = fig["r"]
    hx, hy = fig["head"]
    base_off = fig["shoulder_half"]
    top_y = hy - r * 0.40
    bot_y = fig["pelvis"][1] + r * 2.4
    count = 3 if front else 4
    stagger = (0.00, 0.34, 0.62, 0.26)
    lean = (0.04, -0.05, 0.03, -0.06)
    for sgn in (-1, 1):
        for i in range(count):
            t = i / (count - 1)
            top_x = hx + sgn * (base_off * (0.60 + 0.30 * t) + r * 0.06)
            mid_x = hx + sgn * (base_off * (0.92 + 0.20 * t) + r * 0.16) + sgn * lean[i] * r
            end_x = hx + sgn * (base_off * (0.42 + 0.34 * t) + r * 0.10) + sgn * lean[i] * r * 1.7
            end_y = bot_y - r * stagger[i]
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
