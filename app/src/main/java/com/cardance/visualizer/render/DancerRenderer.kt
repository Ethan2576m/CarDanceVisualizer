package com.cardance.visualizer.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import com.cardance.visualizer.dance.Dancer
import com.cardance.visualizer.dance.Figure
import com.cardance.visualizer.dance.HairStyle
import com.cardance.visualizer.util.lerp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws one dancer as **neon line art of a real young woman**.
 *
 * Earlier revisions were abstract silhouettes: a filled torso, a round head,
 * straight limbs. This version is built from anatomy instead:
 *
 * - a face with a tapered chin, brow, almond eyes, a nose and lips;
 * - a neck, collarbones and a neckline;
 * - a torso that actually has a bust line, a waist and a hip;
 * - arms and legs drawn as tapered contours with a slight curve, not sticks;
 * - a short flared skirt, because a dancer's silhouette is half the costume;
 * - hair that follows the skull and falls in many independent strands.
 *
 * The look is still neon: the drawing is carried by thin glowing contours on
 * a black stage rather than by solid fills.
 *
 * Bloom is faked with a wide low-alpha stroke under the filament, because a
 * hardware canvas ignores `setShadowLayer` on shapes.
 */
class DancerRenderer {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }

    /** Hot white filament drawn on top of the coloured strokes. */
    private val filament = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val p = Path()
    private val mtx = Matrix()

    /** Global brightness multiplier - other dancers dim while one performs. */
    private var alphaScale = 1f

    fun draw(
        canvas: Canvas,
        dancer: Dancer,
        fig: Figure,
        originX: Float,
        originY: Float,
        heightPx: Float,
        timeSec: Float
    ) {
        val h = heightPx
        if (h <= 2f) return

        alphaScale = dancer.dim
        buildShader(dancer, h, timeSec)

        canvas.save()
        canvas.translate(originX, originY)

        drawEcho(canvas, fig, dancer, h)
        drawHairBack(canvas, fig, dancer, h, timeSec)
        drawLegs(canvas, fig, dancer, h)
        drawSkirt(canvas, fig, h)
        drawTorso(canvas, fig, h)
        drawArms(canvas, fig, dancer, h)
        drawHead(canvas, fig, h)
        drawFace(canvas, fig, h)
        drawHairFront(canvas, fig, dancer, h, timeSec)

        canvas.restore()
    }

    // ---------------------------------------------------------------- shader

    /**
     * A vertical neon sweep. The dancer's own hue sits in the middle of the
     * ramp so each of the three keeps its identity, while the ramp still
     * travels through cyan / magenta / gold like a light painting.
     */
    private fun buildShader(dancer: Dancer, h: Float, timeSec: Float) {
        val base = dancer.color
        val colors = intArrayOf(
            Color.parseColor("#22D3EE"),
            base,
            Color.parseColor("#EC4899"),
            Color.parseColor("#FACC15"),
            base,
            Color.parseColor("#22D3EE")
        )
        val positions = floatArrayOf(0f, 0.20f, 0.42f, 0.62f, 0.82f, 1f)
        val shader = LinearGradient(
            0f, -h * 0.62f, 0f, h * 0.60f,
            colors, positions, Shader.TileMode.MIRROR
        )
        val scroll = (timeSec * h * 0.055f) % (h * 1.10f)
        mtx.setTranslate(0f, scroll)
        shader.setLocalMatrix(mtx)
        stroke.shader = shader
    }

    // ------------------------------------------------------------ primitives

    /** Wide soft halo + crisp filament. */
    private fun neon(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        val a = (alpha * alphaScale).toInt().coerceIn(0, 255)
        stroke.alpha = (a * 0.16f).toInt().coerceIn(0, 255)
        stroke.strokeWidth = w * 2.9f
        canvas.drawPath(path, stroke)

        stroke.alpha = a
        stroke.strokeWidth = w
        canvas.drawPath(path, stroke)
    }

    /** Same, but with a hot white core - used on the main silhouette lines. */
    private fun neonHot(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        neon(canvas, path, w, alpha)
        filament.color = Color.WHITE
        filament.alpha = (alpha * alphaScale * 0.40f).toInt().coerceIn(0, 255)
        filament.strokeWidth = w * 0.38f
        canvas.drawPath(path, filament)
    }

    /** Single pass, no bloom - for the very fine detail lines. */
    private fun fine(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        stroke.alpha = (alpha * alphaScale).toInt().coerceIn(0, 255)
        stroke.strokeWidth = w
        canvas.drawPath(path, stroke)
    }

    private fun unit(x: Float, y: Float): Pair<Float, Float> {
        val l = sqrt(x * x + y * y)
        return if (l < 0.0001f) Pair(0f, 0f) else Pair(x / l, y / l)
    }

    /** An ellipse drawn as four quadratic segments. */
    private fun ovalPath(cx: Float, cy: Float, rx: Float, ry: Float): Path {
        val k = 0.5523f
        p.reset()
        p.moveTo(cx, cy - ry)
        p.quadTo(cx + rx * k, cy - ry, cx + rx, cy)
        p.quadTo(cx + rx, cy + ry * k, cx + rx, cy + ry)
        p.quadTo(cx + rx * k, cy + ry, cx, cy + ry)
        p.quadTo(cx - rx * k, cy + ry, cx - rx, cy)
        p.quadTo(cx - rx, cy + ry * k, cx - rx, cy)
        p.quadTo(cx - rx * k, cy - ry, cx, cy - ry)
        p.close()
        return p
    }

    // ------------------------------------------------------------- geometry

    /**
     * One edge of a tapered limb. [a] - [b] - [c] are the joints, the widths
     * are the half-widths at each joint, and [bend] pushes the middle of the
     * curve outwards so the limb reads as flesh rather than as a stick.
     */
    private fun edgePath(
        a: PointF,
        b: PointF,
        c: PointF,
        wa: Float,
        wb: Float,
        wc: Float,
        bend: Float,
        sign: Float
    ): Path {
        val (u1x, u1y) = unit(b.x - a.x, b.y - a.y)
        val n1x = -u1y
        val n1y = u1x
        val (u2x, u2y) = unit(c.x - b.x, c.y - b.y)
        val n2x = -u2y
        val n2y = u2x

        var bx = n1x + n2x
        var by = n1y + n2y
        val bl = sqrt(bx * bx + by * by)
        if (bl > 0.0001f) {
            bx /= bl
            by /= bl
        } else {
            bx = n1x
            by = n1y
        }

        val ax = a.x + n1x * wa * sign
        val ay = a.y + n1y * wa * sign
        val jx = b.x + bx * wb * sign
        val jy = b.y + by * wb * sign
        val cx = c.x + n2x * wc * sign
        val cy = c.y + n2y * wc * sign

        p.reset()
        p.moveTo(ax, ay)
        val m1x = (ax + jx) * 0.5f
        val m1y = (ay + jy) * 0.5f
        p.quadTo(m1x + bx * bend, m1y + by * bend, jx, jy)
        val m2x = (jx + cx) * 0.5f
        val m2y = (jy + cy) * 0.5f
        p.quadTo(m2x + bx * bend, m2y + by * bend, cx, cy)
        return p
    }

    // ---------------------------------------------------------------- torso

    private fun drawTorso(canvas: Canvas, fig: Figure, h: Float) {
        val (rx, ry) = unit(fig.shoulderR.x - fig.shoulderL.x, fig.shoulderR.y - fig.shoulderL.y)
        val (ux, uy) = unit(fig.chest.x - fig.pelvis.x, fig.chest.y - fig.pelvis.y)
        val downY = -uy

        val baseX = fig.pelvis.x + (fig.chest.x - fig.pelvis.x) * 0.50f
        val baseY = fig.pelvis.y + (fig.chest.y - fig.pelvis.y) * 0.50f

        val chestRx = fig.chest.x + rx * fig.chestHalf
        val chestRy = fig.chest.y + ry * fig.chestHalf
        val chestLx = fig.chest.x - rx * fig.chestHalf
        val chestLy = fig.chest.y - ry * fig.chestHalf
        val waistRx = baseX + rx * fig.waistHalf
        val waistRy = baseY + ry * fig.waistHalf
        val waistLx = baseX - rx * fig.waistHalf
        val waistLy = baseY - ry * fig.waistHalf

        val bust = fig.chestHalf * 0.42f
        val w = h * 0.0165f

        // Right side of the body: shoulder -> bust -> waist -> hip.
        p.reset()
        p.moveTo(fig.shoulderR.x, fig.shoulderR.y)
        p.quadTo(
            chestRx + rx * bust,
            chestRy + ry * bust + downY * 0.030f * h,
            chestRx, chestRy
        )
        p.quadTo(
            waistRx - rx * 0.014f * h,
            waistRy - 0.006f * h,
            waistRx, waistRy
        )
        p.quadTo(
            fig.hipR.x + rx * 0.010f * h,
            fig.hipR.y - 0.045f * h,
            fig.hipR.x, fig.hipR.y
        )
        neonHot(canvas, p, w, 232)

        // Left side, mirrored.
        p.reset()
        p.moveTo(fig.shoulderL.x, fig.shoulderL.y)
        p.quadTo(
            chestLx - rx * bust,
            chestLy - ry * bust + downY * 0.030f * h,
            chestLx, chestLy
        )
        p.quadTo(
            waistLx + rx * 0.014f * h,
            waistLy - 0.006f * h,
            waistLx, waistLy
        )
        p.quadTo(
            fig.hipL.x - rx * 0.010f * h,
            fig.hipL.y - 0.045f * h,
            fig.hipL.x, fig.hipL.y
        )
        neonHot(canvas, p, w, 232)

        // Neckline: a shallow scoop, not a deep V.
        p.reset()
        p.moveTo(fig.shoulderL.x + rx * fig.shoulderHalf * 0.52f, fig.shoulderL.y)
        p.quadTo(
            fig.chest.x - rx * fig.chestHalf * 0.42f,
            fig.chest.y + downY * 0.004f * h,
            fig.chest.x, fig.chest.y + downY * 0.020f * h
        )
        p.quadTo(
            fig.chest.x + rx * fig.chestHalf * 0.42f,
            fig.chest.y + downY * 0.004f * h,
            fig.shoulderR.x - rx * fig.shoulderHalf * 0.52f, fig.shoulderR.y
        )
        fine(canvas, p, w * 0.55f, 180)

        // Collarbones.
        p.reset()
        p.moveTo(fig.neck.x, fig.neck.y)
        p.quadTo(
            (fig.neck.x + fig.shoulderR.x) * 0.5f,
            (fig.neck.y + fig.shoulderR.y) * 0.5f + downY * 0.010f * h,
            fig.shoulderR.x, fig.shoulderR.y
        )
        fine(canvas, p, w * 0.5f, 165)

        p.reset()
        p.moveTo(fig.neck.x, fig.neck.y)
        p.quadTo(
            (fig.neck.x + fig.shoulderL.x) * 0.5f,
            (fig.neck.y + fig.shoulderL.y) * 0.5f + downY * 0.010f * h,
            fig.shoulderL.x, fig.shoulderL.y
        )
        fine(canvas, p, w * 0.5f, 165)

        // Bust curve: one line under each bust.
        for (side in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(
                fig.chest.x + side * fig.chestHalf * 0.06f,
                fig.chest.y + downY * 0.030f * h
            )
            p.quadTo(
                fig.chest.x + side * fig.chestHalf * 0.86f,
                fig.chest.y + downY * 0.058f * h,
                fig.chest.x + side * fig.chestHalf * 1.00f,
                fig.chest.y + downY * 0.026f * h
            )
            fine(canvas, p, w * 0.5f, 158)
        }

        // Navel.
        p.reset()
        p.moveTo(waistLx, waistLy)
        p.quadTo(baseX, baseY + downY * 0.016f * h, waistRx, waistRy)
        fine(canvas, p, w * 0.45f, 140)
    }

    /** A flared A-line skirt with a gently wavy hem - half of what makes a dancer. */
    private fun drawSkirt(canvas: Canvas, fig: Figure, h: Float) {
        val hemY = fig.pelvis.y + h * 0.135f
        val lx = fig.hipL.x
        val rx = fig.hipR.x
        val flare = 1.95f
        val hemLx = lx * flare
        val hemRx = rx * flare
        val w = h * 0.0142f

        p.reset()
        p.moveTo(lx * 1.05f, fig.hipL.y - h * 0.008f)
        p.quadTo(lx * 1.48f, fig.pelvis.y + h * 0.072f, hemLx, hemY)
        // Scalloped hem.
        val seg = (hemRx - hemLx) / 3f
        for (i in 0 until 3) {
            val x0 = hemLx + seg * i
            val x1 = x0 + seg
            p.quadTo((x0 + x1) * 0.5f, hemY + h * 0.013f, x1, hemY)
        }
        p.quadTo(rx * 1.48f, fig.pelvis.y + h * 0.072f, rx * 1.05f, fig.hipR.y - h * 0.008f)
        p.close()
        neonHot(canvas, p, w, 225)

        // Pleat lines.
        for (i in 1 until 4) {
            val t = i / 4f
            val sx = lx + (rx - lx) * t
            val ex = hemLx + (hemRx - hemLx) * t
            p.reset()
            p.moveTo(sx, fig.hipL.y + h * 0.012f)
            p.quadTo(sx * 1.30f, fig.pelvis.y + h * 0.070f, ex, hemY - h * 0.008f)
            fine(canvas, p, h * 0.0068f, 128)
        }
    }

    // ---------------------------------------------------------------- limbs

    private fun drawLegs(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * 0.0130f
        val thigh = h * 0.0270f
        val calf = h * 0.0185f
        val ankle = h * 0.0105f

        drawLeg(canvas, fig.hipL, fig.kneeL, fig.ankleL, fig.footL, w, thigh, calf, ankle, dancer)
        drawLeg(canvas, fig.hipR, fig.kneeR, fig.ankleR, fig.footR, w, thigh, calf, ankle, dancer)
    }

    private fun drawLeg(
        canvas: Canvas,
        hip: PointF,
        knee: PointF,
        ankle: PointF,
        foot: PointF,
        w: Float,
        thigh: Float,
        calf: Float,
        ankleW: Float,
        dancer: Dancer
    ) {
        // The ribbon runs all the way to the foot so nothing floats.
        val bend = thigh * 0.40f * (1f + dancer.energy * 0.35f)
        neon(canvas, edgePath(hip, knee, foot, thigh, calf, ankleW, bend, 1f), w, 225)
        neon(canvas, edgePath(hip, knee, foot, thigh, calf, ankleW, bend, -1f), w, 225)

        // Knee and ankle hinges.
        fine(canvas, ovalPath(knee.x, knee.y, calf * 0.92f, calf * 0.92f), w * 0.6f, 128)
        fine(canvas, ovalPath(ankle.x, ankle.y, ankleW * 1.05f, ankleW * 1.05f), w * 0.56f, 115)

        // Shoe.
        fine(canvas, ovalPath(foot.x, foot.y, w * 1.08f, w * 1.32f), w * 0.79f, 208)
    }

    private fun drawArms(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * 0.0112f
        val upper = h * 0.0195f
        val fore = h * 0.0118f

        drawArm(canvas, fig.shoulderL, fig.elbowL, fig.handL, w, upper, fore, dancer)
        drawArm(canvas, fig.shoulderR, fig.elbowR, fig.handR, w, upper, fore, dancer)
    }

    private fun drawArm(
        canvas: Canvas,
        shoulder: PointF,
        elbow: PointF,
        hand: PointF,
        w: Float,
        upper: Float,
        fore: Float,
        dancer: Dancer
    ) {
        val bend = upper * 0.60f * (1f + dancer.energy * 0.35f)
        neon(canvas, edgePath(shoulder, elbow, hand, upper, fore, fore * 0.62f, bend, 1f), w, 228)
        neon(canvas, edgePath(shoulder, elbow, hand, upper, fore, fore * 0.62f, bend, -1f), w, 228)

        // Elbow hinge and hand.
        fine(canvas, ovalPath(elbow.x, elbow.y, fore * 0.95f, fore * 0.95f), w * 0.60f, 122)
        fine(canvas, ovalPath(hand.x, hand.y, w * 0.84f, w * 1.00f), w * 0.71f, 198)
    }

    // ----------------------------------------------------------------- head

    private fun drawHead(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val w = h * 0.0155f
        val hx = fig.head.x
        val hy = fig.head.y

        // Neck: two converging lines from the base of the neck up to the jaw.
        p.reset()
        p.moveTo(fig.neck.x - r * 0.42f, fig.neck.y)
        p.quadTo(hx - r * 0.46f, hy + r * 0.92f, hx - r * 0.34f, hy + r * 0.74f)
        fine(canvas, p, w * 0.62f, 185)

        p.reset()
        p.moveTo(fig.neck.x + r * 0.42f, fig.neck.y)
        p.quadTo(hx + r * 0.46f, hy + r * 0.92f, hx + r * 0.34f, hy + r * 0.74f)
        fine(canvas, p, w * 0.62f, 185)

        // Face: an oval that tapers into a soft chin.
        p.reset()
        p.moveTo(hx - r * 0.86f, hy)
        p.quadTo(hx - r * 0.95f, hy - r * 0.86f, hx, hy - r)
        p.quadTo(hx + r * 0.95f, hy - r * 0.86f, hx + r * 0.86f, hy)
        p.quadTo(hx + r * 0.80f, hy + r * 0.52f, hx + r * 0.30f, hy + r * 0.96f)
        p.quadTo(hx, hy + r * 1.05f, hx - r * 0.30f, hy + r * 0.96f)
        p.quadTo(hx - r * 0.80f, hy + r * 0.52f, hx - r * 0.86f, hy)
        p.close()
        neonHot(canvas, p, w, 228)
    }

    /** Brows, almond eyes, a nose and lips - small marks, big readability win. */
    private fun drawFace(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val w = h * 0.0078f
        val hx = fig.head.x
        val hy = fig.head.y

        val eyeY = hy + r * 0.10f
        val eyeDx = r * 0.36f

        for (side in intArrayOf(-1, 1)) {
            val ex = hx + side * eyeDx

            // Upper lid - a long, slightly lifted almond.
            p.reset()
            p.moveTo(ex - r * 0.20f, eyeY + r * 0.02f)
            p.quadTo(ex - r * 0.02f, eyeY - r * 0.14f, ex + r * 0.20f, eyeY - r * 0.01f)
            fine(canvas, p, w, 225)

            // Lower lid.
            p.reset()
            p.moveTo(ex - r * 0.16f, eyeY + r * 0.07f)
            p.quadTo(ex + r * 0.01f, eyeY + r * 0.15f, ex + r * 0.17f, eyeY + r * 0.04f)
            fine(canvas, p, w * 0.8f, 170)

            // Pupil.
            p.reset()
            p.addCircle(ex + side * r * 0.01f, eyeY + r * 0.02f, r * 0.050f, Path.Direction.CW)
            fine(canvas, p, w * 1.1f, 210)

            // Brow.
            p.reset()
            p.moveTo(ex - r * 0.19f, eyeY - r * 0.30f)
            p.quadTo(ex + side * r * 0.02f, eyeY - r * 0.40f, ex + r * 0.20f, eyeY - r * 0.26f)
            fine(canvas, p, w * 0.9f, 195)
        }

        // Nose.
        p.reset()
        p.moveTo(hx + r * 0.02f, eyeY + r * 0.10f)
        p.quadTo(hx + r * 0.10f, eyeY + r * 0.28f, hx - r * 0.04f, eyeY + r * 0.32f)
        fine(canvas, p, w * 0.75f, 165)

        // Lips - a small cupid's bow.
        val mouthY = eyeY + r * 0.52f
        p.reset()
        p.moveTo(hx - r * 0.13f, mouthY)
        p.quadTo(hx - r * 0.05f, mouthY - r * 0.05f, hx, mouthY - r * 0.01f)
        p.quadTo(hx + r * 0.05f, mouthY - r * 0.05f, hx + r * 0.13f, mouthY)
        p.quadTo(hx, mouthY + r * 0.11f, hx - r * 0.13f, mouthY)
        fine(canvas, p, w * 0.9f, 200)
    }

    // ----------------------------------------------------------------- hair

    private fun drawHairBack(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        hairMass(canvas, fig, dancer, h, timeSec)
        when (dancer.style) {
            HairStyle.STRAIGHT -> hairStrands(canvas, fig, dancer, h, timeSec, false)
            HairStyle.PONYTAIL -> hairPonyTail(canvas, fig, dancer, h, timeSec)
            HairStyle.WAVES -> hairWaves(canvas, fig, dancer, h, timeSec)
        }
    }

    private fun drawHairFront(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        when (dancer.style) {
            HairStyle.STRAIGHT -> {
                faceLocks(canvas, fig, h)
                bangWedges(canvas, fig, h)
                hairStrands(canvas, fig, dancer, h, timeSec, true)
            }
            HairStyle.PONYTAIL -> sweptFringe(canvas, fig, h)
            HairStyle.WAVES -> {
                faceLocks(canvas, fig, h)
                bangWedges(canvas, fig, h)
            }
        }
    }

    /** The teardrop of hair sitting behind the head. */
    private fun hairMass(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val baseOff = fig.shoulderHalf * 1.00f
        val botY = fig.pelvis.y + r * 2.4f
        val sway = sin(timeSec * 1.4f + dancer.phase) * r * 0.10f

        p.reset()
        p.moveTo(hx - r * 1.00f, hy - r * 0.34f)
        p.quadTo(hx - baseOff * 1.02f + sway, hy + r * 1.30f, hx - baseOff * 0.55f + sway, botY)
        p.quadTo(hx + sway, botY + r * 0.55f, hx + baseOff * 0.55f + sway, botY)
        p.quadTo(hx + baseOff * 1.02f + sway, hy + r * 1.30f, hx + r * 1.00f, hy - r * 0.34f)
        p.close()
        neon(canvas, p, h * 0.0086f, 148)
    }

    /** Two tapered locks framing the face. */
    private fun faceLocks(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        for (side in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(hx + side * r * 0.96f, hy - r * 0.62f)
            p.quadTo(hx + side * r * 1.24f, hy + r * 0.30f, hx + side * r * 0.92f, hy + r * 1.30f)
            p.quadTo(hx + side * r * 0.74f, hy + r * 0.40f, hx + side * r * 0.72f, hy - r * 0.20f)
            p.quadTo(hx + side * r * 0.78f, hy - r * 0.48f, hx + side * r * 0.96f, hy - r * 0.62f)
            p.close()
            fine(canvas, p, h * 0.0086f, 184)
        }
    }

    /** Three soft wedges of fringe across the forehead. */
    private fun bangWedges(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val lockW = r * 0.66f
        for (i in 0 until 3) {
            val t = i / 2f
            val cx = hx + (t - 0.5f) * r * 1.30f
            p.reset()
            p.moveTo(cx - lockW * 0.5f, hy - r * 0.82f)
            p.quadTo(cx, hy - r * 1.02f, cx + lockW * 0.5f, hy - r * 0.82f)
            p.quadTo(cx + lockW * 0.34f, hy - r * 0.50f, cx + lockW * 0.06f, hy - r * 0.22f)
            p.quadTo(cx - lockW * 0.36f, hy - r * 0.48f, cx - lockW * 0.5f, hy - r * 0.82f)
            p.close()
            fine(canvas, p, h * 0.0084f, 168)
        }
    }

    /** Long straight strands falling past the waist. */
    private fun hairStrands(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float,
        front: Boolean
    ) {
        val r = fig.headRadius
        val topY = fig.head.y - r * 0.40f
        val botY = fig.pelvis.y + r * 2.4f
        val baseOff = fig.shoulderHalf * 1.00f
        val w = h * 0.0076f
        val amp = r * (0.13f + dancer.energy * 0.10f) * (1f + dancer.bounceRatio * 1.6f)
        val count = if (front) 2 else 3

        for (side in intArrayOf(-1, 1)) {
            for (i in 0 until count) {
                val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
                val ph = dancer.phase + side * 0.65f + i * 0.83f
                val swing = sin(timeSec * 1.7f + ph) * amp
                val topX = fig.head.x + side * (baseOff * 0.90f - r * 0.30f + t * r * 0.22f)
                val midX = fig.head.x + side * (baseOff * 0.95f + t * r * 0.26f) + swing
                val endX = fig.head.x + side * (baseOff * 0.60f - r * 0.20f + t * r * 0.26f) +
                    swing * 1.70f

                p.reset()
                p.moveTo(topX, topY)
                p.quadTo(midX, lerp(topY, botY, 0.44f), endX, botY)
                fine(canvas, p, w * (1f - t * 0.20f), 184)
            }
        }
    }

    /** A single tail, split into strands that fan out and swing together. */
    private fun hairPonyTail(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        val r = fig.headRadius
        val w = h * 0.0094f
        val dir = -1f
        val swingBase = sin(timeSec * 2.1f + dancer.phase) * (0.30f + dancer.energy * 0.35f)

        for (i in 0 until 3) {
            val t = i / 2f
            val spread = (t - 0.5f) * 1.25f
            val ph = dancer.phase + i * 0.70f
            val swing = swingBase + sin(timeSec * 2.6f + ph) * 0.16f

            val startX = fig.head.x + dir * r * (0.60f + t * 0.20f)
            val startY = fig.head.y - r * (0.26f - t * 0.12f)
            val reach = r * (2.85f + t * 1.15f + swing * 0.80f)
            val midX = fig.head.x + dir * reach * 0.92f + spread * r * 0.30f
            val midY = fig.head.y - r * (0.35f + swing * 0.42f) - spread * r * 0.20f
            val endX = fig.head.x + dir * reach * 1.02f + spread * r * 0.55f
            val endY = fig.head.y + r * (2.35f + t * 0.95f + swing * 0.55f)

            p.reset()
            p.moveTo(startX, startY)
            p.quadTo(midX, midY, endX, endY)
            fine(canvas, p, w * (1.12f - t * 0.30f), 205)
        }
    }

    /** A swept fringe tied on one side. */
    private fun sweptFringe(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        p.reset()
        p.moveTo(hx - r * 1.02f, hy - r * 0.16f)
        p.quadTo(hx - r * 0.55f, hy - r * 1.02f, hx + r * 0.18f, hy - r * 0.92f)
        p.quadTo(hx + r * 0.86f, hy - r * 0.80f, hx + r * 1.00f, hy - r * 0.10f)
        p.quadTo(hx + r * 0.40f, hy - r * 0.42f, hx - r * 0.28f, hy - r * 0.20f)
        p.quadTo(hx - r * 0.72f, hy - r * 0.06f, hx - r * 1.02f, hy - r * 0.16f)
        p.close()
        neon(canvas, p, h * 0.0118f, 205)
    }

    /** Big S-waves down both sides, each strand on its own ripple phase. */
    private fun hairWaves(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        val r = fig.headRadius
        val topY = fig.head.y - r * 0.26f
        val botY = fig.pelvis.y + r * 2.6f
        val baseOff = fig.shoulderHalf * 1.10f
        val w = h * 0.0088f
        val steps = 18

        for (side in intArrayOf(-1, 1)) {
            for (i in 0 until 3) {
                val t = i / 2f
                val ph = dancer.phase + side * 1.05f + i * 0.77f
                val rippleAmp = r * (0.26f + dancer.energy * 0.16f) * (1f - t * 0.28f)

                p.reset()
                for (k in 0..steps) {
                    val u = k.toFloat() / steps
                    val y = lerp(topY, botY, u)
                    val taper = 1f - u * 0.20f
                    val ripple = sin(u * 6.6f + timeSec * 2.5f + ph)
                    val x = fig.head.x +
                        side * (baseOff * taper * 0.92f + r * (0.40f + t * 0.22f)) +
                        ripple * rippleAmp * side
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                fine(canvas, p, w * (1f - t * 0.24f), 198)
            }
        }
    }
    // ----------------------------------------------------------------- echo

    /** One ghost contour trailing the body - motion blur, and it reads as life. */
    private fun drawEcho(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val a = (30 * alphaScale).toInt().coerceIn(0, 255)
        val dx = -dancer.sway * h * 0.0018f
        val dy = h * 0.008f
        val legW = h * 0.0270f

        canvas.save()
        canvas.translate(dx, dy)
        p.reset()
        p.moveTo(fig.shoulderR.x, fig.shoulderR.y)
        p.quadTo(
            fig.chest.x + (fig.shoulderR.x - fig.shoulderL.x) * 0.44f,
            fig.chest.y + (fig.shoulderR.y - fig.shoulderL.y) * 0.44f + h * 0.030f,
            fig.hipR.x, fig.hipR.y
        )
        fine(canvas, p, h * 0.0110f, a)
        fine(
            canvas,
            edgePath(fig.hipL, fig.kneeL, fig.ankleL, legW, legW * 0.72f, legW * 0.42f, legW * 0.20f, 1f),
            h * 0.0080f, a
        )
        fine(
            canvas,
            edgePath(fig.hipR, fig.kneeR, fig.ankleR, legW, legW * 0.72f, legW * 0.42f, legW * 0.20f, 1f),
            h * 0.0080f, a
        )
        canvas.restore()
    }
}
