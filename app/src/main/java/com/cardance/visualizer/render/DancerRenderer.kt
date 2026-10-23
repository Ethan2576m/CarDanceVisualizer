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
 * Draws one dancer as **sketch line art of a young woman**.
 *
 * The drawing language follows 速写 (gesture sketch) conventions rather than
 * the earlier "anatomy lesson" revision:
 *
 * - **no face at all** - the head carries only its outer contour, partly
 *   veiled by the hair; there are no eyes, brows, nose or lips;
 * - **contour lines** carry the silhouette and are the heaviest strokes;
 * - **structure lines** mark the anatomy underneath (collarbones, shoulder
 *   caps, a broken spine line, the bust curve);
 * - **fold lines** gather at the joints on the clothes - two from each
 *   armpit onto the crop top, pleats on the skirt alternating heavy/light;
 * - **hair is grouped**: one silhouette mass per section with internal flow
 *   lines along the growth direction, tips staggered so the ends never line
 *   up in a row.
 *
 * Line weight is a five-tier hierarchy (contour / limb / structure / fold /
 * hair / detail) so the picture has the 疏密 contrast a sketch lives on:
 * dense at the hair and the skirt, sparse on the bare midriff and limbs.
 *
 * The look is still neon: the drawing is carried by glowing contours on a
 * black stage. Bloom is faked with a wide low-alpha stroke under the
 * filament, because a hardware canvas ignores `setShadowLayer` on shapes.
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

    // ------------------------------------------------------- line weights
    // Fractions of the figure height. The hierarchy is the whole point:
    // silhouette > limbs > anatomy > garment folds > hair > detail.
    private val wContour = 0.0162f
    private val wLimb = 0.0132f
    private val wStruct = 0.0106f
    private val wFold = 0.0070f
    private val wHair = 0.0078f
    private val wDetail = 0.0048f

    private val aContour = 237
    private val aLimb = 224
    private val aStruct = 194
    private val aFold = 153
    private val aHair = 179
    private val aDetail = 117

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

    /** Silhouette tier: wide soft halo + crisp filament + hot white core. */
    private fun contour(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        val a = (alpha * alphaScale).toInt().coerceIn(0, 255)
        stroke.alpha = (a * 0.16f).toInt().coerceIn(0, 255)
        stroke.strokeWidth = w * 2.9f
        canvas.drawPath(path, stroke)

        stroke.alpha = a
        stroke.strokeWidth = w
        canvas.drawPath(path, stroke)

        filament.color = Color.WHITE
        filament.alpha = (a * 0.40f).toInt().coerceIn(0, 255)
        filament.strokeWidth = w * 0.38f
        canvas.drawPath(path, filament)
    }

    /** Anatomy tier: halo + filament, no hot core. */
    private fun structure(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        val a = (alpha * alphaScale).toInt().coerceIn(0, 255)
        stroke.alpha = (a * 0.16f).toInt().coerceIn(0, 255)
        stroke.strokeWidth = w * 2.9f
        canvas.drawPath(path, stroke)

        stroke.alpha = a
        stroke.strokeWidth = w
        canvas.drawPath(path, stroke)
    }

    /** Detail tiers: a single crisp pass, no bloom - keeps folds and hair
     *  reading as ink lines instead of everything glowing equally. */
    private fun line(canvas: Canvas, path: Path, w: Float, alpha: Int) {
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
        val shHalf = fig.shoulderHalf
        val ch = fig.chestHalf
        val wC = h * wContour
        val wS = h * wStruct
        val wF = h * wFold
        val wD = h * wDetail

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
        contour(canvas, p, wC, aContour)

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
        contour(canvas, p, wC, aContour)

        // Shoulder caps - the deltoid mass over each shoulder joint.
        for (sgn in intArrayOf(-1, 1)) {
            val shd = if (sgn < 0) fig.shoulderL else fig.shoulderR
            p.reset()
            p.moveTo(fig.neck.x + sgn * fig.headRadius * 0.26f, fig.neck.y + fig.headRadius * 0.04f)
            p.quadTo(
                shd.x + sgn * fig.headRadius * 0.12f,
                shd.y - fig.headRadius * 0.30f,
                shd.x + sgn * fig.headRadius * 0.24f,
                shd.y + fig.headRadius * 0.16f
            )
            structure(canvas, p, wS, aStruct)
        }

        // Collarbones.
        for (sgn in intArrayOf(1, -1)) {
            val shd = if (sgn > 0) fig.shoulderR else fig.shoulderL
            p.reset()
            p.moveTo(fig.neck.x, fig.neck.y)
            p.quadTo(
                (fig.neck.x + shd.x) * 0.5f,
                (fig.neck.y + shd.y) * 0.5f + downY * 0.010f * h,
                shd.x, shd.y
            )
            line(canvas, p, wS * 0.8f, (aStruct * 0.85f).toInt())
        }

        // Spine - two broken segments (断线), lighter in the middle: 虚实.
        p.reset()
        p.moveTo(fig.chest.x, fig.chest.y + downY * 0.030f * h)
        p.quadTo(fig.chest.x, fig.chest.y + downY * 0.085f * h, baseX, baseY - downY * 0.012f * h)
        line(canvas, p, wD, aDetail + 15)
        p.reset()
        p.moveTo(baseX, baseY - downY * 0.004f * h)
        p.quadTo(
            waistLx + (waistRx - waistLx) * 0.30f,
            waistLy + downY * 0.006f * h,
            waistLx + (waistRx - waistLx) * 0.60f,
            waistLy + downY * 0.002f * h
        )
        line(canvas, p, wD, aDetail)

        // Bust curve: one line under each bust.
        for (sgn in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(fig.chest.x + sgn * ch * 0.06f, fig.chest.y + downY * 0.030f * h)
            p.quadTo(
                fig.chest.x + sgn * ch * 0.86f,
                fig.chest.y + downY * 0.058f * h,
                fig.chest.x + sgn * ch * 1.00f,
                fig.chest.y + downY * 0.026f * h
            )
            line(canvas, p, wS * 0.8f, (aStruct * 0.80f).toInt())
        }

        // Navel.
        p.reset()
        p.moveTo(waistLx, waistLy)
        p.quadTo(baseX, baseY + downY * 0.016f * h, waistRx, waistRy)
        line(canvas, p, wD, aDetail + 20)

        // --- the crop top: neckline, straps, side seams, hem, armpit folds

        // Neckline: a shallow scoop, not a deep V.
        p.reset()
        p.moveTo(fig.shoulderL.x + rx * shHalf * 0.52f, fig.shoulderL.y)
        p.quadTo(
            fig.chest.x - rx * ch * 0.42f,
            fig.chest.y + downY * 0.004f * h,
            fig.chest.x, fig.chest.y + downY * 0.020f * h
        )
        p.quadTo(
            fig.chest.x + rx * ch * 0.42f,
            fig.chest.y + downY * 0.004f * h,
            fig.shoulderR.x - rx * shHalf * 0.52f, fig.shoulderR.y
        )
        line(canvas, p, wF, aFold + 26)

        // Straps over the shoulders.
        for (sgn in intArrayOf(-1, 1)) {
            val shd = if (sgn < 0) fig.shoulderL else fig.shoulderR
            p.reset()
            p.moveTo(shd.x + sgn * rx * shHalf * 0.52f, shd.y)
            p.quadTo(
                shd.x + sgn * rx * shHalf * 0.26f,
                shd.y - fig.headRadius * 0.22f,
                shd.x + sgn * rx * shHalf * 0.58f,
                shd.y - fig.headRadius * 0.04f
            )
            line(canvas, p, wF * 0.9f, aFold)
        }

        // Side seams, ending just under the bust.
        for (sgn in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(fig.chest.x + sgn * ch * 0.92f, fig.chest.y + downY * 0.030f * h)
            p.quadTo(
                fig.chest.x + sgn * ch * 1.02f,
                fig.chest.y + downY * 0.044f * h,
                fig.chest.x + sgn * ch * 0.98f,
                fig.chest.y + downY * 0.058f * h
            )
            line(canvas, p, wF, aFold + 15)
        }
        // Hem of the top.
        p.reset()
        p.moveTo(fig.chest.x - ch * 0.98f, fig.chest.y + downY * 0.058f * h)
        p.quadTo(
            fig.chest.x,
            fig.chest.y + downY * 0.068f * h,
            fig.chest.x + ch * 0.98f,
            fig.chest.y + downY * 0.058f * h
        )
        line(canvas, p, wF, aFold + 20)

        // Armpit folds - two per side, heavy then light (衣纹朝关节集中).
        for (sgn in intArrayOf(-1, 1)) {
            val shd = if (sgn < 0) fig.shoulderL else fig.shoulderR
            p.reset()
            p.moveTo(shd.x + sgn * shHalf * 0.70f, shd.y + downY * 0.012f * h)
            p.quadTo(
                fig.chest.x + sgn * ch * 0.60f,
                fig.chest.y + downY * 0.036f * h,
                fig.chest.x + sgn * ch * 0.34f,
                fig.chest.y + downY * 0.056f * h
            )
            line(canvas, p, wF, aFold + 15)
            p.reset()
            p.moveTo(shd.x + sgn * shHalf * 0.78f, shd.y + downY * 0.020f * h)
            p.quadTo(
                fig.chest.x + sgn * ch * 0.76f,
                fig.chest.y + downY * 0.046f * h,
                fig.chest.x + sgn * ch * 0.54f,
                fig.chest.y + downY * 0.062f * h
            )
            line(canvas, p, wF * 0.78f, (aFold * 0.82f).toInt())
        }
    }

    /** A flared A-line skirt - half of what makes a dancer. */
    private fun drawSkirt(canvas: Canvas, fig: Figure, h: Float) {
        val hemY = fig.pelvis.y + h * 0.135f
        val lx = fig.hipL.x
        val rx = fig.hipR.x
        val flare = 1.95f
        val hemLx = lx * flare
        val hemRx = rx * flare
        val wC = h * wContour * 0.88f
        val wS = h * wStruct
        val wF = h * wFold

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
        contour(canvas, p, wC, aContour)

        // Waistband.
        p.reset()
        p.moveTo(lx * 1.02f, fig.hipL.y - h * 0.012f)
        p.quadTo(fig.pelvis.x, fig.pelvis.y - h * 0.020f, rx * 1.02f, fig.hipR.y - h * 0.012f)
        structure(canvas, p, wS, aStruct)

        // Pleats - 一疏一密: heavy/light/heavy/light/heavy, and the light
        // ones stop short of the hem (取舍).
        val pleats = floatArrayOf(0.12f, 0.32f, 0.50f, 0.68f, 0.88f)
        val wmul = floatArrayOf(1.15f, 0.80f, 1.20f, 0.80f, 1.15f)
        val span = floatArrayOf(1.00f, 0.72f, 1.00f, 0.72f, 1.00f)
        val sy = fig.hipL.y + h * 0.012f
        for (i in pleats.indices) {
            val t = pleats[i]
            val sx = lx + (rx - lx) * t
            val ex = hemLx + (hemRx - hemLx) * t
            val ey = hemY - h * 0.008f - (1f - span[i]) * (hemY - sy) * 0.92f
            p.reset()
            p.moveTo(sx, sy)
            p.quadTo(sx * 1.28f, fig.pelvis.y + h * 0.070f, ex, ey)
            val a = if (wmul[i] > 1f) (aFold * 1.05f).toInt() else (aFold * 0.82f).toInt()
            line(canvas, p, wF * wmul[i], a)
        }

        // Hem inner line - fabric thickness, a second line above the scallops.
        p.reset()
        p.moveTo(hemLx * 0.98f, hemY - h * 0.014f)
        p.quadTo((hemLx + hemRx) * 0.5f, hemY - h * 0.004f, hemRx * 0.98f, hemY - h * 0.014f)
        line(canvas, p, wF * 0.9f, (aFold * 0.85f).toInt())
    }

    // ---------------------------------------------------------------- limbs

    private fun drawLegs(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * wLimb
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
        structure(canvas, edgePath(hip, knee, foot, thigh, calf, ankleW, bend, 1f), w, aLimb)
        structure(canvas, edgePath(hip, knee, foot, thigh, calf, ankleW, bend, -1f), w, aLimb)

        // Knee and ankle hinges.
        line(canvas, ovalPath(knee.x, knee.y, calf * 0.92f, calf * 0.92f), w * 0.36f, aDetail + 26)
        line(canvas, ovalPath(ankle.x, ankle.y, ankleW * 1.05f, ankleW * 1.05f), w * 0.36f, aDetail + 16)

        // Shoe.
        line(canvas, ovalPath(foot.x, foot.y, thigh * 0.50f, thigh * 0.62f), w * 0.74f, aStruct + 20)
    }

    private fun drawArms(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * wLimb * 0.88f
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
        structure(canvas, edgePath(shoulder, elbow, hand, upper, fore, fore * 0.62f, bend, 1f), w, aLimb)
        structure(canvas, edgePath(shoulder, elbow, hand, upper, fore, fore * 0.62f, bend, -1f), w, aLimb)

        // Elbow hinge and hand.
        line(canvas, ovalPath(elbow.x, elbow.y, fore * 0.95f, fore * 0.95f), w * 0.42f, aDetail + 20)
        line(canvas, ovalPath(hand.x, hand.y, upper * 0.56f, upper * 0.66f), w * 0.60f, aStruct + 16)

        // Wrist line - separates the forearm from the hand.
        val (fx, fy) = unit(hand.x - elbow.x, hand.y - elbow.y)
        val nx = -fy
        val ny = fx
        val wx = elbow.x + (hand.x - elbow.x) * 0.82f
        val wy = elbow.y + (hand.y - elbow.y) * 0.82f
        p.reset()
        p.moveTo(wx + nx * fore * 0.88f, wy + ny * fore * 0.88f)
        p.lineTo(wx - nx * fore * 0.88f, wy - ny * fore * 0.88f)
        line(canvas, p, w * 0.42f, aDetail + 26)
    }

    // ----------------------------------------------------------------- head

    /**
     * Outer contour only - there is deliberately no face anywhere: no eyes,
     * brows, nose or lips. The hair veils the upper half of the head, so the
     * featureless contour below reads as a sketch portrait.
     */
    private fun drawHead(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val w = h * wContour
        val wS = h * wStruct * 0.62f
        val hx = fig.head.x
        val hy = fig.head.y

        // Neck: two converging lines from the base of the neck up to the jaw.
        for (sgn in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(fig.neck.x + sgn * r * 0.42f, fig.neck.y)
            p.quadTo(hx + sgn * r * 0.46f, hy + r * 0.92f, hx + sgn * r * 0.34f, hy + r * 0.74f)
            line(canvas, p, wS, (aStruct * 0.82f).toInt())
        }

        // Face: an oval that tapers into a soft chin.
        p.reset()
        p.moveTo(hx - r * 0.86f, hy)
        p.quadTo(hx - r * 0.95f, hy - r * 0.86f, hx, hy - r)
        p.quadTo(hx + r * 0.95f, hy - r * 0.86f, hx + r * 0.86f, hy)
        p.quadTo(hx + r * 0.80f, hy + r * 0.52f, hx + r * 0.30f, hy + r * 0.96f)
        p.quadTo(hx, hy + r * 1.05f, hx - r * 0.30f, hy + r * 0.96f)
        p.quadTo(hx - r * 0.80f, hy + r * 0.52f, hx - r * 0.86f, hy)
        p.close()
        contour(canvas, p, w, aContour)
    }

    // ----------------------------------------------------------------- hair

    private fun drawHairBack(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        hairMass(canvas, fig, h)
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
                hairBangs(canvas, fig, h)
                faceLocks(canvas, fig, h)
                hairStrands(canvas, fig, dancer, h, timeSec, true)
            }
            HairStyle.PONYTAIL -> sweptFringe(canvas, fig, h)
            HairStyle.WAVES -> {
                hairBangs(canvas, fig, h)
                faceLocks(canvas, fig, h)
            }
        }
    }

    /** The teardrop of hair sitting behind the head - now arcing *over* the
     *  skull so the crown carries volume instead of a bald cap. */
    private fun hairMass(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val baseOff = fig.shoulderHalf * 1.00f
        val botY = fig.pelvis.y + r * 2.4f
        val wS = h * wStruct * 0.82f
        val wH = h * wHair

        p.reset()
        p.moveTo(hx - r * 1.00f, hy - r * 0.34f)
        p.quadTo(hx - baseOff * 1.02f, hy + r * 1.30f, hx - baseOff * 0.55f, botY)
        p.quadTo(hx, botY + r * 0.55f, hx + baseOff * 0.55f, botY)
        p.quadTo(hx + baseOff * 1.02f, hy + r * 1.30f, hx + r * 1.00f, hy - r * 0.34f)
        p.quadTo(hx + r * 1.28f, hy - r * 1.34f, hx, hy - r * 1.46f)
        p.quadTo(hx - r * 1.28f, hy - r * 1.34f, hx - r * 1.00f, hy - r * 0.34f)
        p.close()
        structure(canvas, p, wS, (aStruct * 0.70f).toInt())

        // Inner flow lines, three per side, sweeping from the crown to the
        // ends - this is the "分组刻画" that makes hair read as hair.
        for (sgn in intArrayOf(-1, 1)) {
            for (i in 0 until 3) {
                val t = i / 2f
                val sx = hx + sgn * r * (0.55f - t * 0.25f)
                val sy = hy - r * (0.34f - t * 0.10f)
                val ex = hx + sgn * baseOff * (0.42f + t * 0.30f)
                val ey = botY - r * (0.05f + t * 0.12f)
                val mx = hx + sgn * baseOff * (0.72f + t * 0.30f)
                val my = hy + r * (1.15f + t * 0.35f)
                p.reset()
                p.moveTo(sx, sy)
                p.quadTo(mx, my, ex, ey)
                line(canvas, p, wH * (1f - t * 0.20f), (aHair * (0.85f - t * 0.18f)).toInt())
            }
        }
    }

    /**
     * One grouped fringe across the forehead + internal separation lines.
     * Sketch bangs are a single mass with a fringed lower edge, not four
     * separate wedges - the lock divisions live *inside* the silhouette.
     */
    private fun hairBangs(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val wH = h * wHair

        p.reset()
        p.moveTo(hx - r * 0.95f, hy - r * 0.30f)
        p.quadTo(hx - r * 0.55f, hy - r * 0.78f, hx, hy - r * 0.84f)
        p.quadTo(hx + r * 0.55f, hy - r * 0.78f, hx + r * 0.95f, hy - r * 0.30f)
        p.quadTo(hx + r * 0.70f, hy + r * 0.18f, hx + r * 0.45f, hy + r * 0.10f)
        p.quadTo(hx + r * 0.22f, hy + r * 0.30f, hx, hy + r * 0.16f)
        p.quadTo(hx - r * 0.22f, hy + r * 0.28f, hx - r * 0.45f, hy + r * 0.10f)
        p.quadTo(hx - r * 0.70f, hy + r * 0.20f, hx - r * 0.95f, hy - r * 0.30f)
        p.close()
        line(canvas, p, wH, aHair)

        for (sx in floatArrayOf(hx - r * 0.45f, hx, hx + r * 0.45f)) {
            p.reset()
            p.moveTo(sx, hy - r * 0.58f)
            p.quadTo(sx + (sx - hx) * 0.10f, hy - r * 0.12f, sx + (sx - hx) * 0.06f, hy + r * 0.10f)
            line(canvas, p, wH * 0.72f, (aHair * 0.62f).toInt())
        }
    }

    /** Two tapered locks framing the face, each with an inner flow line. */
    private fun faceLocks(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val wH = h * wHair
        for (sgn in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(hx + sgn * r * 0.96f, hy - r * 0.62f)
            p.quadTo(hx + sgn * r * 1.24f, hy + r * 0.30f, hx + sgn * r * 0.92f, hy + r * 1.30f)
            p.quadTo(hx + sgn * r * 0.74f, hy + r * 0.40f, hx + sgn * r * 0.72f, hy - r * 0.20f)
            p.quadTo(hx + sgn * r * 0.78f, hy - r * 0.48f, hx + sgn * r * 0.96f, hy - r * 0.62f)
            p.close()
            line(canvas, p, wH, (aHair * 0.90f).toInt())

            p.reset()
            p.moveTo(hx + sgn * r * 0.98f, hy - r * 0.50f)
            p.quadTo(hx + sgn * r * 1.10f, hy + r * 0.35f, hx + sgn * r * 0.86f, hy + r * 1.16f)
            line(canvas, p, wH * 0.70f, (aHair * 0.55f).toInt())
        }
    }

    /** Falling strands, tips staggered so the ends never line up in a row. */
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
        val wH = h * wHair
        val amp = r * (0.13f + dancer.energy * 0.10f) * (1f + dancer.bounceRatio * 1.6f)
        val count = if (front) 3 else 4
        val stagger = floatArrayOf(0.00f, 0.34f, 0.62f, 0.26f)
        val lean = floatArrayOf(0.04f, -0.05f, 0.03f, -0.06f)

        for (sgn in intArrayOf(-1, 1)) {
            for (i in 0 until count) {
                val t = i.toFloat() / (count - 1)
                val ph = dancer.phase + sgn * 0.65f + i * 0.83f
                val swing = sin(timeSec * 1.7f + ph) * amp
                val topX = fig.head.x + sgn * (baseOff * (0.60f + 0.30f * t) + r * 0.06f)
                val midX = fig.head.x + sgn * (baseOff * (0.92f + 0.20f * t) + r * 0.16f) +
                    sgn * lean[i] * r + swing
                val endX = fig.head.x + sgn * (baseOff * (0.42f + 0.34f * t) + r * 0.10f) +
                    sgn * lean[i] * r * 1.7f + swing * 1.70f
                val endY = botY - r * stagger[i]

                p.reset()
                p.moveTo(topX, topY)
                p.quadTo(midX, topY + (botY - topY) * 0.44f, endX, endY)
                line(canvas, p, wH * (1f - t * 0.18f), (aHair * (0.92f - t * 0.20f)).toInt())
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
        val wH = h * wHair
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
            line(canvas, p, wH * (1.12f - t * 0.30f), (aHair * 1.05f).toInt().coerceAtMost(255))

            // Inner flow line, slightly offset - the second edge of the lock.
            p.reset()
            p.moveTo(startX + dir * r * 0.10f, startY + r * 0.06f)
            p.quadTo(
                midX + dir * r * 0.16f,
                midY + r * 0.10f,
                endX + dir * r * 0.20f,
                endY - r * 0.10f
            )
            line(canvas, p, wH * 0.72f, (aHair * 0.60f).toInt())
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
        structure(canvas, p, h * wStruct * 0.9f, aStruct)
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
        val wH = h * wHair
        val steps = 18

        for (sgn in intArrayOf(-1, 1)) {
            for (i in 0 until 3) {
                val t = i / 2f
                val ph = dancer.phase + sgn * 1.05f + i * 0.77f
                val rippleAmp = r * (0.26f + dancer.energy * 0.16f) * (1f - t * 0.28f)

                p.reset()
                for (k in 0..steps) {
                    val u = k.toFloat() / steps
                    val y = lerp(topY, botY, u)
                    val taper = 1f - u * 0.20f
                    val ripple = sin(u * 6.6f + timeSec * 2.5f + ph)
                    val x = fig.head.x +
                        sgn * (baseOff * taper * 0.92f + r * (0.40f + t * 0.22f)) +
                        ripple * rippleAmp * sgn
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                line(canvas, p, wH * (1f - t * 0.24f), (aHair * 0.95f).toInt())
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
        line(canvas, p, h * wLimb * 0.85f, a)
        line(
            canvas,
            edgePath(fig.hipL, fig.kneeL, fig.ankleL, legW, legW * 0.72f, legW * 0.42f, legW * 0.20f, 1f),
            h * wLimb * 0.62f, a
        )
        line(
            canvas,
            edgePath(fig.hipR, fig.kneeR, fig.ankleR, legW, legW * 0.72f, legW * 0.42f, legW * 0.20f, 1f),
            h * wLimb * 0.62f, a
        )
        canvas.restore()
    }
}
