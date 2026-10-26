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
import com.cardance.visualizer.dance.Outfit
import com.cardance.visualizer.util.lerp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws one dancer as **realistic figure, line-only rendering**.
 *
 * The medium stays neon line art on a black stage - no shading fills - but
 * the body itself is drawn to read as a real young woman:
 *
 * - proportions retuned to ~7 heads with realistic circumferences
 *   (shoulder : chest : waist : hip = 1.06 : 1 : 0.70 : 1.08), so the
 *   silhouette no longer reads as a stylised croquis;
 * - the skull is a real cranium - crown, temple, brow, cheekbone, jaw angle,
 *   chin - and now carries a clean anime portrait: eyes with an iris ring and
 *   a white catch-light, thin brows, a small nose, soft lips and an ear. The
 *   face is drawn on top of the hair so it always reads as a face, and it
 *   rotates with the head tilt like the skull does;
 * - the torso and limbs are a smooth silhouette plus clothing only - the old
 *   anatomy-study lines (collarbones, sternum, broken spine, bust curve,
 *   iliac crest, navel, and the knee / ankle / elbow / wrist joint creases)
 *   are gone, so the figure reads as a dressed young woman, not a croquis;
 * - hair is grouped with a **side parting** that differs per dancer, an
 *   uneven fringe edge and left/right strands on different rhythms - nothing
 *   mirrors, because real hair never mirrors;
 * - clothes separate tension (over the bust, over the hips) from drape (the
 *   hem, the waistband).
 *
 * Line weight is a five-tier hierarchy (contour / limb / structure / fold /
 * hair / detail) so the picture has the 疏密 contrast a sketch lives on.
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
    private val wContour = 0.0160f
    private val wLimb = 0.0130f
    private val wStruct = 0.0104f
    private val wFold = 0.0068f
    private val wHair = 0.0074f
    private val wDetail = 0.0046f

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
        drawLowerGarment(canvas, fig, dancer, h)
        drawTorso(canvas, fig, h)
        drawArms(canvas, fig, dancer, h)
        drawHead(canvas, fig, h)
        drawHairFront(canvas, fig, dancer, h, timeSec)
        drawFace(canvas, fig, h)

        canvas.restore()
    }

    // ---------------------------------------------------------------- shader

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

    /** Detail tiers: a single crisp pass, no bloom. */
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

    /** A small filled dot - eye catch-lights and ear studs. */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, rad: Float, alpha: Int) {
        dotPaint.color = Color.WHITE
        dotPaint.alpha = (alpha * alphaScale).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, rad, dotPaint)
    }

    // ------------------------------------------------------------- geometry

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

        // Right side of the body: shoulder -> deltoid -> bust -> waist -> hip.
        // Four segments, so the contour bulges over the deltoid before it
        // sweeps into the bust instead of one straight dive.
        p.reset()
        p.moveTo(fig.shoulderR.x, fig.shoulderR.y)
        p.quadTo(
            chestRx + rx * bust * 0.55f,
            chestRy + ry * bust * 0.35f + downY * 0.012f * h,
            chestRx + rx * bust * 0.85f,
            chestRy + ry * bust * 0.80f + downY * 0.026f * h
        )
        p.quadTo(
            chestRx + rx * bust,
            chestRy + ry * bust + downY * 0.034f * h,
            chestRx, chestRy
        )
        p.quadTo(waistRx - rx * 0.016f * h, waistRy - 0.008f * h, waistRx, waistRy)
        p.quadTo(fig.hipR.x + rx * 0.012f * h, fig.hipR.y - 0.048f * h, fig.hipR.x, fig.hipR.y)
        contour(canvas, p, wC, aContour)

        // Left side, mirrored.
        p.reset()
        p.moveTo(fig.shoulderL.x, fig.shoulderL.y)
        p.quadTo(
            chestLx - rx * bust * 0.55f,
            chestLy - ry * bust * 0.35f + downY * 0.012f * h,
            chestLx - rx * bust * 0.85f,
            chestLy - ry * bust * 0.80f + downY * 0.026f * h
        )
        p.quadTo(
            chestLx - rx * bust,
            chestLy - ry * bust + downY * 0.034f * h,
            chestLx, chestLy
        )
        p.quadTo(waistLx + rx * 0.016f * h, waistLy - 0.008f * h, waistLx, waistLy)
        p.quadTo(fig.hipL.x - rx * 0.012f * h, fig.hipL.y - 0.048f * h, fig.hipL.x, fig.hipL.y)
        contour(canvas, p, wC, aContour)

        // Shoulder caps - the deltoid mass over each shoulder joint.
        for (sgn in intArrayOf(-1, 1)) {
            val shd = if (sgn < 0) fig.shoulderL else fig.shoulderR
            p.reset()
            p.moveTo(fig.neck.x + sgn * fig.headRadius * 0.24f, fig.neck.y + fig.headRadius * 0.06f)
            p.quadTo(
                shd.x + sgn * fig.headRadius * 0.10f,
                shd.y - fig.headRadius * 0.32f,
                shd.x + sgn * fig.headRadius * 0.26f,
                shd.y + fig.headRadius * 0.18f
            )
            structure(canvas, p, wS, aStruct)
        }

        // (collarbones / sternum / spine / bust curve / iliac crest / navel
        //  removed in v8.1 - the smooth silhouette + clothing imply the body;
        //  the figure now reads as a dressed young woman, not an anatomy study)

        // Navel placeholder removed.

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
                shd.y - fig.headRadius * 0.24f,
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

    /** Dispatches the lower garment by [Dancer.outfit]. */
    private fun drawLowerGarment(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        when (dancer.outfit) {
            Outfit.SHORT_SKIRT -> drawShortSkirt(canvas, fig, h)
            Outfit.LONG_DRESS -> drawLongDress(canvas, fig, h)
            Outfit.JEANS -> drawJeans(canvas, fig, dancer, h)
        }
    }

    /**
     * A flared A-line mini skirt - the lightest of the three garments, so the
     * legs stay bare below it.
     */
    private fun drawShortSkirt(canvas: Canvas, fig: Figure, h: Float) {
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

        // Pleats - heavy/light/heavy/light/heavy, the light ones stop short,
        // and the control point dips so the fabric hangs under gravity.
        val pleats = floatArrayOf(0.10f, 0.30f, 0.50f, 0.70f, 0.90f)
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
            p.quadTo(sx * 1.28f, fig.pelvis.y + h * 0.082f, ex, ey)
            val a = if (wmul[i] > 1f) (aFold * 1.05f).toInt() else (aFold * 0.82f).toInt()
            line(canvas, p, wF * wmul[i], a)
        }

        // Hem inner line - fabric thickness.
        p.reset()
        p.moveTo(hemLx * 0.98f, hemY - h * 0.014f)
        p.quadTo((hemLx + hemRx) * 0.5f, hemY - h * 0.004f, hemRx * 0.98f, hemY - h * 0.014f)
        line(canvas, p, wF * 0.9f, (aFold * 0.85f).toInt())
    }

    /**
     * A floor-length gown. The silhouette hangs from the hips to just above
     * the ankles and is drawn over the covered legs, with soft vertical drape
     * lines so the column reads as fabric, not a tube.
     */
    private fun drawLongDress(canvas: Canvas, fig: Figure, h: Float) {
        val lx = fig.hipL.x
        val rx = fig.hipR.x
        val hemY = fig.pelvis.y + h * 0.40f
        val flare = 1.32f
        val hemLx = lx * flare
        val hemRx = rx * flare
        val wC = h * wContour * 0.88f
        val wS = h * wStruct
        val wF = h * wFold

        p.reset()
        p.moveTo(lx * 1.05f, fig.hipL.y - h * 0.008f)
        p.quadTo(lx * 1.34f, fig.pelvis.y + h * 0.22f, hemLx, hemY)
        p.lineTo(hemRx, hemY)
        p.quadTo(rx * 1.34f, fig.pelvis.y + h * 0.22f, rx * 1.05f, fig.hipR.y - h * 0.008f)
        p.close()
        contour(canvas, p, wC, aContour)

        // Waist seam where the bodice meets the skirt.
        p.reset()
        p.moveTo(lx * 1.02f, fig.hipL.y - h * 0.012f)
        p.quadTo(fig.pelvis.x, fig.pelvis.y - h * 0.020f, rx * 1.02f, fig.hipR.y - h * 0.012f)
        structure(canvas, p, wS, aStruct)

        // Soft vertical drape lines.
        val drape = floatArrayOf(0.18f, 0.38f, 0.50f, 0.62f, 0.82f)
        val sy = fig.hipL.y + h * 0.012f
        for (i in drape.indices) {
            val t = drape[i]
            val sx = lx + (rx - lx) * t
            val ex = hemLx + (hemRx - hemLx) * t
            p.reset()
            p.moveTo(sx, sy)
            p.quadTo(sx * 1.12f, fig.pelvis.y + h * 0.22f, ex, hemY - h * 0.006f)
            line(canvas, p, wF * 0.9f, (aFold * 0.85f).toInt())
        }

        // Hem inner line.
        p.reset()
        p.moveTo(hemLx * 0.99f, hemY - h * 0.012f)
        p.quadTo((hemLx + hemRx) * 0.5f, hemY - h * 0.004f, hemRx * 0.99f, hemY - h * 0.012f)
        line(canvas, p, wF * 0.9f, (aFold * 0.85f).toInt())
    }

    /**
     * Denim jeans. Two tapered trouser legs wrap the thigh and shin on the
     * same joints as the (now hidden) bare legs, plus an inseam, a knee
     * crease, a hip pocket and a topstitched outer seam. A waistband and a
     * short fly close the top.
     */
    private fun drawJeans(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * wLimb
        val thighW = h * 0.044f
        val calfW = h * 0.032f
        val ankleW = h * 0.020f

        for (sgn in intArrayOf(-1, 1)) {
            val hip = if (sgn < 0) fig.hipL else fig.hipR
            val knee = if (sgn < 0) fig.kneeL else fig.kneeR
            val ankle = if (sgn < 0) fig.ankleL else fig.ankleR
            val bend = thighW * 0.30f * (1f + dancer.energy * 0.25f)

            // Outer + inner trouser contours.
            structure(canvas, edgePath(hip, knee, ankle, thighW, calfW, ankleW, bend, 1f), w, aLimb)
            structure(canvas, edgePath(hip, knee, ankle, thighW, calfW, ankleW, bend, -1f), w, aLimb)

            // Topstitch - a parallel line just inside the outer seam.
            structure(
                canvas,
                edgePath(hip, knee, ankle, thighW * 0.74f, calfW * 0.74f, ankleW * 0.74f, bend, 1f),
                w * 0.40f, (aLimb * 0.58f).toInt()
            )

            // Inseam down the front of the leg.
            p.reset()
            p.moveTo(hip.x * 0.55f, hip.y + h * 0.010f)
            p.quadTo((hip.x + ankle.x) * 0.5f, (hip.y + ankle.y) * 0.5f, ankle.x * 0.40f, ankle.y - h * 0.006f)
            line(canvas, p, w * 0.50f, (aLimb * 0.70f).toInt())

            // Knee crease across the joint.
            val (kux, kuy) = unit(knee.x - hip.x, knee.y - hip.y)
            val knx = -kuy
            val kny = kux
            p.reset()
            p.moveTo(
                knee.x - kux * calfW * 0.5f + knx * calfW * 0.9f,
                knee.y - kuy * calfW * 0.5f + kny * calfW * 0.9f
            )
            p.quadTo(
                knee.x + kux * calfW * 0.2f,
                knee.y + kuy * calfW * 0.2f,
                knee.x - kux * calfW * 0.5f - knx * calfW * 0.9f,
                knee.y - kuy * calfW * 0.5f - kny * calfW * 0.9f
            )
            line(canvas, p, w * 0.44f, (aLimb * 0.66f).toInt())

            // Front pocket - a small arc near the hip.
            p.reset()
            p.moveTo(hip.x * 0.82f, hip.y + h * 0.014f)
            p.quadTo(hip.x * 0.55f, hip.y + h * 0.032f, hip.x * 0.30f, hip.y + h * 0.020f)
            line(canvas, p, w * 0.50f, (aLimb * 0.58f).toInt())
        }

        // Waistband across the hips.
        p.reset()
        p.moveTo(fig.hipL.x * 1.02f, fig.hipL.y - h * 0.012f)
        p.quadTo(fig.pelvis.x, fig.pelvis.y - h * 0.022f, fig.hipR.x * 1.02f, fig.hipR.y - h * 0.012f)
        structure(canvas, p, w, aLimb)

        // Fly.
        p.reset()
        p.moveTo(fig.pelvis.x, fig.pelvis.y - h * 0.010f)
        p.lineTo(fig.pelvis.x, fig.pelvis.y + h * 0.040f)
        line(canvas, p, w * 0.50f, (aLimb * 0.58f).toInt())
    }

    // ---------------------------------------------------------------- limbs

    private fun drawLegs(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * wLimb
        val thigh = h * 0.0300f
        val calf = h * 0.0200f
        val ankle = h * 0.0108f
        // Jeans and the floor-length dress cover the legs; only the feet show.
        val bare = dancer.outfit == Outfit.SHORT_SKIRT

        drawLeg(canvas, fig.hipL, fig.kneeL, fig.ankleL, fig.footL, w, thigh, calf, ankle, dancer, bare)
        drawLeg(canvas, fig.hipR, fig.kneeR, fig.ankleR, fig.footR, w, thigh, calf, ankle, dancer, bare)
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
        dancer: Dancer,
        bare: Boolean
    ) {
        if (bare) {
            // The limb runs all the way to the foot so nothing floats.
            val bend = thigh * 0.42f * (1f + dancer.energy * 0.35f)
            structure(canvas, edgePath(hip, knee, foot, thigh, calf, ankleW, bend, 1f), w, aLimb)
            structure(canvas, edgePath(hip, knee, foot, thigh, calf, ankleW, bend, -1f), w, aLimb)
        }

        // Shoe: a wedge with a toe crease (always drawn - the foot peeks out
        // below a skirt, a dress hem or a jean cuff).
        line(canvas, ovalPath(foot.x, foot.y, thigh * 0.44f, thigh * 0.66f), w * 0.74f, aStruct + 16)
        val (tux, tuy) = unit(foot.y - ankle.y, -(foot.x - ankle.x))
        p.reset()
        p.moveTo(foot.x - tux * thigh * 0.30f, foot.y + tuy * thigh * 0.52f)
        p.lineTo(foot.x + tux * thigh * 0.30f, foot.y + tuy * thigh * 0.52f)
        line(canvas, p, w * 0.36f, aDetail + 26)
    }

    private fun drawArms(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * wLimb * 0.88f
        val upper = h * 0.0200f
        val fore = h * 0.0120f

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
        val bend = upper * 0.62f * (1f + dancer.energy * 0.35f)
        structure(canvas, edgePath(shoulder, elbow, hand, upper, fore, fore * 0.62f, bend, 1f), w, aLimb)
        structure(canvas, edgePath(shoulder, elbow, hand, upper, fore, fore * 0.62f, bend, -1f), w, aLimb)

        // Hand: the palm plus a finger crease. Joint creases (elbow, wrist)
        // are omitted so the arm reads smooth, not as an anatomical diagram.
        line(canvas, ovalPath(hand.x, hand.y, upper * 0.58f, upper * 0.68f), w * 0.60f, aStruct + 26)
        p.reset()
        p.moveTo(hand.x - upper * 0.42f, hand.y + upper * 0.16f)
        p.quadTo(hand.x, hand.y + upper * 0.30f, hand.x + upper * 0.42f, hand.y + upper * 0.16f)
        line(canvas, p, w * 0.42f, aDetail + 36)
    }

    // ----------------------------------------------------------------- head

    /**
     * A real cranium - crown, temple, brow, cheekbone, jaw angle, chin - and
     * **no facial features anywhere**. The hair veils the upper half, so the
     * featureless contour below still reads as a portrait.
     */
    private fun drawHead(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val w = h * wContour
        val wS = h * wStruct * 0.60f
        val hx = fig.head.x
        val hy = fig.head.y

        // Neck: two converging lines from the jaw down to the sternum.
        for (sgn in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(hx + sgn * r * 0.34f, hy + r * 0.92f)
            p.quadTo(
                fig.neck.x + sgn * r * 0.46f,
                fig.neck.y + r * 0.30f,
                fig.neck.x + sgn * r * 0.42f,
                fig.neck.y
            )
            line(canvas, p, wS, (aStruct * 0.82f).toInt())
        }

        p.reset()
        p.moveTo(hx, hy - r * 1.00f)
        p.quadTo(hx + r * 0.99f, hy - r * 0.90f, hx + r * 0.90f, hy - r * 0.50f)
        p.quadTo(hx + r * 1.00f, hy - r * 0.16f, hx + r * 0.96f, hy + r * 0.04f)
        p.quadTo(hx + r * 0.92f, hy + r * 0.32f, hx + r * 0.80f, hy + r * 0.52f)
        p.quadTo(hx + r * 0.66f, hy + r * 0.76f, hx + r * 0.54f, hy + r * 0.90f)
        p.quadTo(hx + r * 0.36f, hy + r * 1.04f, hx + r * 0.12f, hy + r * 1.07f)
        p.quadTo(hx - r * 0.36f, hy + r * 1.04f, hx - r * 0.54f, hy + r * 0.90f)
        p.quadTo(hx - r * 0.66f, hy + r * 0.76f, hx - r * 0.80f, hy + r * 0.52f)
        p.quadTo(hx - r * 0.92f, hy + r * 0.32f, hx - r * 0.96f, hy + r * 0.04f)
        p.quadTo(hx - r * 1.00f, hy - r * 0.16f, hx - r * 0.90f, hy - r * 0.50f)
        p.quadTo(hx - r * 0.99f, hy - r * 0.90f, hx, hy - r * 1.00f)
        p.close()
        contour(canvas, p, w, aContour)
    }

    /**
     * The face - a clean anime line portrait drawn **on top of the hair** so
     * the features never get veiled by the fringe. A head-local (right, up)
     * frame lets every feature rotate with the head tilt, the way the skull
     * already does, instead of staying screen-fixed.
     *
     * No shading fills: a thick lash line, a thin lower lid, an iris ring and
     * one white catch-light read as an eye; thin brows, a small nose, soft
     * lips and an ear finish the portrait.
     */
    private fun drawFace(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val wD = h * wDetail
        val hx = fig.head.x
        val hy = fig.head.y

        // lx = right, ly = up, both in units of r; ly > 0 pushes toward the crown.
        val (ux, uy) = unit(fig.head.x - fig.neck.x, fig.head.y - fig.neck.y)
        val rx = -uy
        val ry = ux
        fun fp(lx: Float, ly: Float): PointF =
            PointF(hx + (lx * rx + ly * ux) * r, hy + (lx * ry + ly * uy) * r)

        for (sgn in intArrayOf(1, -1)) {
            val ex = sgn * 0.36f
            val ey = 0.04f

            // Upper lash line - thick, flicked up at the outer corner.
            var a = fp(ex - sgn * 0.135f, ey + 0.012f)
            var b = fp(ex, ey + 0.118f)
            var c = fp(ex + sgn * 0.135f, ey + 0.052f)
            p.reset(); p.moveTo(a.x, a.y); p.quadTo(b.x, b.y, c.x, c.y)
            line(canvas, p, wD * 2.6f, aContour + 18)

            // Lower lid - thin.
            a = fp(ex + sgn * 0.125f, ey + 0.018f)
            b = fp(ex, ey - 0.074f)
            c = fp(ex - sgn * 0.115f, ey)
            p.reset(); p.moveTo(a.x, a.y); p.quadTo(b.x, b.y, c.x, c.y)
            line(canvas, p, wD * 1.4f, aDetail + 24)

            // Iris ring.
            val ic = fp(ex, ey + 0.004f)
            line(canvas, ovalPath(ic.x, ic.y, 0.090f * r, 0.094f * r), wD * 0.9f, aDetail + 34)

            // Catch-light - a small white dot.
            val hl = fp(ex - sgn * 0.040f, ey + 0.052f)
            dot(canvas, hl.x, hl.y, 0.046f * r, aDetail + 70)

            // Eyebrow - thin arc.
            a = fp(ex - sgn * 0.155f, ey + 0.265f)
            b = fp(ex, ey + 0.315f)
            c = fp(ex + sgn * 0.155f, ey + 0.252f)
            p.reset(); p.moveTo(a.x, a.y); p.quadTo(b.x, b.y, c.x, c.y)
            line(canvas, p, wD * 1.5f, aDetail + 26)

            // Ear.
            a = fp(sgn * 0.80f, 0.16f)
            b = fp(sgn * 1.00f, -0.02f)
            c = fp(sgn * 0.82f, -0.18f)
            p.reset(); p.moveTo(a.x, a.y); p.quadTo(b.x, b.y, c.x, c.y)
            line(canvas, p, wD * 1.4f, aDetail + 14)
        }

        // Nose - a small side curve.
        var a = fp(-0.028f, -0.235f)
        var b = fp(0.004f, -0.300f)
        var c = fp(0.034f, -0.210f)
        p.reset(); p.moveTo(a.x, a.y); p.quadTo(b.x, b.y, c.x, c.y)
        line(canvas, p, wD * 1.3f, aDetail + 18)

        // Mouth - closed, faint smile.
        a = fp(-0.055f, -0.495f)
        b = fp(0.0f, -0.468f)
        c = fp(0.055f, -0.495f)
        p.reset(); p.moveTo(a.x, a.y); p.quadTo(b.x, b.y, c.x, c.y)
        line(canvas, p, wD * 1.6f, aDetail + 34)
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
            HairStyle.RIBBON -> hairRibbonBack(canvas, fig, dancer, h, timeSec)
            HairStyle.HALFUP -> hairHalfUpBack(canvas, fig, dancer, h, timeSec)
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
                hairBangs(canvas, fig, dancer, h)
                faceLocks(canvas, fig, h)
                hairStrands(canvas, fig, dancer, h, timeSec, true)
            }
            HairStyle.PONYTAIL -> sweptFringe(canvas, fig, dancer, h)
            HairStyle.WAVES -> {
                hairBangs(canvas, fig, dancer, h)
                faceLocks(canvas, fig, h)
            }
            HairStyle.RIBBON -> {
                hairBangs(canvas, fig, dancer, h)
                faceLocks(canvas, fig, h)
                hairRibbonFront(canvas, fig, dancer, h, timeSec)
            }
            HairStyle.HALFUP -> {
                hairBangs(canvas, fig, dancer, h)
                faceLocks(canvas, fig, h)
                hairHalfUpFront(canvas, fig, h)
            }
        }
    }

    /**
     * The teardrop of hair sitting behind the head.  It arcs *over* the skull
     * so the crown carries volume instead of a bald cap, and it stays tight
     * enough to read as a real hairstyle rather than a helmet.
     */
    private fun hairMass(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val baseOff = fig.shoulderHalf * 1.00f
        val botY = fig.pelvis.y + r * 2.6f
        val wS = h * wStruct * 0.82f
        val wH = h * wHair

        p.reset()
        p.moveTo(hx - r * 1.00f, hy - r * 0.34f)
        p.quadTo(hx - baseOff * 1.02f, hy + r * 1.30f, hx - baseOff * 0.55f, botY)
        p.quadTo(hx, botY + r * 0.55f, hx + baseOff * 0.55f, botY)
        p.quadTo(hx + baseOff * 1.02f, hy + r * 1.30f, hx + r * 1.00f, hy - r * 0.34f)
        p.quadTo(hx + r * 1.15f, hy - r * 1.20f, hx, hy - r * 1.30f)
        p.quadTo(hx - r * 1.15f, hy - r * 1.20f, hx - r * 1.00f, hy - r * 0.34f)
        p.close()
        structure(canvas, p, wS, (aStruct * 0.70f).toInt())

        // Inner flow lines, three per side, sweeping from the crown to the
        // ends - the "分组刻画" that makes hair read as hair.
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
     * One grouped fringe with a **side parting**.  The parting offset is
     * derived from the dancer's phase, so the three dancers never part their
     * hair the same way, and the lower edge of the fringe is deliberately
     * uneven - a real fringe never mirrors.
     */
    private fun hairBangs(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val wH = h * wHair
        val po = sin(dancer.phase * 1.7f) * r * 0.18f

        p.reset()
        p.moveTo(hx + po - r * 0.95f, hy - r * 0.30f)
        p.quadTo(hx + po - r * 0.55f, hy - r * 0.78f, hx + po, hy - r * 0.84f)
        p.quadTo(hx + po + r * 0.55f, hy - r * 0.78f, hx + po + r * 0.95f, hy - r * 0.30f)
        p.quadTo(hx + po + r * 0.70f, hy + r * 0.22f, hx + po + r * 0.45f, hy + r * 0.10f)
        p.quadTo(hx + po + r * 0.22f, hy + r * 0.26f, hx + po, hy + r * 0.12f)
        p.quadTo(hx + po - r * 0.22f, hy + r * 0.36f, hx + po - r * 0.45f, hy + r * 0.20f)
        p.quadTo(hx + po - r * 0.70f, hy + r * 0.30f, hx + po - r * 0.95f, hy - r * 0.30f)
        p.close()
        line(canvas, p, wH, aHair)

        // The parting running back from the crown through the fringe.
        p.reset()
        p.moveTo(hx + po, hy - r * 0.82f)
        p.quadTo(hx + po + r * 0.10f, hy - r * 0.40f, hx + po + r * 0.06f, hy + r * 0.06f)
        line(canvas, p, wH * 0.72f, (aHair * 0.62f).toInt())

        // Separation lines inside the mass, one per lock boundary.
        for (sx in floatArrayOf(hx + po - r * 0.45f, hx + po + r * 0.45f)) {
            p.reset()
            p.moveTo(sx, hy - r * 0.58f)
            p.quadTo(
                sx + (sx - hx - po) * 0.10f,
                hy - r * 0.12f,
                sx + (sx - hx - po) * 0.06f,
                hy + r * 0.10f
            )
            line(canvas, p, wH * 0.72f, (aHair * 0.62f).toInt())
        }

        // Temple hairlines - where the fringe meets the skin at the sides.
        for (sgn in intArrayOf(-1, 1)) {
            p.reset()
            p.moveTo(hx + sgn * r * 0.86f, hy - r * 0.20f)
            p.quadTo(hx + sgn * r * 0.97f, hy - r * 0.04f, hx + sgn * r * 0.90f, hy + r * 0.10f)
            line(canvas, p, wH * 0.70f, (aHair * 0.55f).toInt())
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

    /**
     * Falling strands, tips staggered so the ends never line up in a row.
     * The left and right sides run on **different** stagger/lean rhythms -
     * mirrored hair is the fastest way to make a figure look drawn.
     */
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
        val botY = fig.pelvis.y + r * 2.6f
        val baseOff = fig.shoulderHalf * 1.00f
        val wH = h * wHair
        val amp = r * (0.13f + dancer.energy * 0.10f) * (1f + dancer.bounceRatio * 1.6f)
        val count = if (front) 4 else 5
        val staggerL = floatArrayOf(0.00f, 0.30f, 0.55f, 0.78f, 0.40f)
        val leanL = floatArrayOf(0.04f, -0.05f, 0.03f, -0.06f, 0.05f)
        val staggerR = floatArrayOf(0.12f, 0.44f, 0.68f, 0.92f, 0.52f)
        val leanR = floatArrayOf(-0.06f, 0.05f, -0.04f, 0.06f, -0.05f)

        for (sgn in intArrayOf(-1, 1)) {
            val stagger = if (sgn < 0) staggerL else staggerR
            val lean = if (sgn < 0) leanL else leanR
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

    /** A swept fringe tied on one side, parted off-centre. */
    private fun sweptFringe(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val po = sin(dancer.phase * 1.7f) * r * 0.18f
        p.reset()
        p.moveTo(hx + po - r * 1.02f, hy - r * 0.16f)
        p.quadTo(hx + po - r * 0.55f, hy - r * 1.02f, hx + po + r * 0.18f, hy - r * 0.92f)
        p.quadTo(hx + po + r * 0.86f, hy - r * 0.80f, hx + po + r * 1.00f, hy - r * 0.10f)
        p.quadTo(hx + po + r * 0.40f, hy - r * 0.42f, hx + po - r * 0.28f, hy - r * 0.20f)
        p.quadTo(hx + po - r * 0.72f, hy - r * 0.06f, hx + po - r * 1.02f, hy - r * 0.16f)
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

    /** A bow ornament - two loops, a knot, and two trailing tails. */
    private fun drawBow(canvas: Canvas, cx: Float, cy: Float, s: Float, wH: Float, alpha: Int) {
        // Left / right loops and the knot - open ovals, line art only.
        line(canvas, ovalPath(cx - s * 0.95f, cy, s * 0.75f, s * 0.52f), wH, alpha)
        line(canvas, ovalPath(cx + s * 0.95f, cy, s * 0.75f, s * 0.52f), wH, alpha)
        line(canvas, ovalPath(cx, cy, s * 0.32f, s * 0.34f), wH, alpha)
        // Tails flowing down.
        p.reset()
        p.moveTo(cx - s * 0.18f, cy + s * 0.20f)
        p.quadTo(cx - s * 0.55f, cy + s * 1.40f, cx - s * 0.32f, cy + s * 2.30f)
        line(canvas, p, wH * 0.80f, (alpha * 0.85f).toInt())
        p.reset()
        p.moveTo(cx + s * 0.18f, cy + s * 0.20f)
        p.quadTo(cx + s * 0.60f, cy + s * 1.30f, cx + s * 0.36f, cy + s * 2.05f)
        line(canvas, p, wH * 0.80f, (alpha * 0.85f).toInt())
    }

    /**
     * 蝴蝶结 back: the mass sits behind the head and the length is gathered to
     * one side, falling as a swinging bundle - the signature of the ribbon
     * look. Motion comes from [timeSec] and [Dancer.energy].
     */
    private fun hairRibbonBack(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val wH = h * wHair
        // Gather point at the right side of the head.
        val gx = hx + r * 1.05f
        val gy = hy + r * 0.25f
        val botY = fig.pelvis.y + r * 2.4f
        val amp = r * (0.12f + dancer.energy * 0.10f) * (1f + dancer.bounceRatio * 1.5f)
        for (i in 0 until 4) {
            val t = i / 3f
            val ph = dancer.phase + i * 0.7f
            val swing = sin(timeSec * 1.7f + ph) * amp
            val topX = gx
            val topY = gy
            val endX = gx + r * (0.50f + t * 0.32f) + swing
            val endY = botY - r * (0.10f + t * 0.16f)
            p.reset()
            p.moveTo(topX, topY)
            p.quadTo(gx + r * 0.60f, topY + (endY - topY) * 0.40f, endX, endY)
            line(canvas, p, wH * (1f - t * 0.20f), (aHair * (0.90f - t * 0.15f)).toInt())
        }
    }

    /**
     * 蝴蝶结 front: a bow tied at the side gather, plus a few front strands on
     * the gathered side so the face stays framed.
     */
    private fun hairRibbonFront(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val wH = h * wHair
        val amp = r * (0.10f + dancer.energy * 0.08f) * (1f + dancer.bounceRatio * 1.4f)
        for (i in 0 until 3) {
            val t = i / 2f
            val ph = dancer.phase + i * 0.9f
            val swing = sin(timeSec * 1.9f + ph) * amp
            val topX = hx + r * (0.55f + t * 0.25f)
            val endX = hx + r * (1.10f + t * 0.30f) + swing
            val endY = hy + r * (1.50f + t * 0.40f) - r * 0.10f
            p.reset()
            p.moveTo(topX, hy - r * 0.20f)
            p.quadTo(hx + r * 0.95f, hy + r * 0.70f, endX, endY)
            line(canvas, p, wH * (0.90f - t * 0.20f), (aHair * (0.80f - t * 0.15f)).toInt())
        }
        // The bow at the side gather.
        drawBow(canvas, hx + r * 1.0f, hy - r * 0.05f, r * 0.42f, wH, aHair)
    }

    /**
     * 半扎发 back: a small bun gathered at the crown, and the lower half falls
     * as loose strands - the "half up, half down" silhouette.
     */
    private fun hairHalfUpBack(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float
    ) {
        val r = fig.headRadius
        val hx = fig.head.x
        val hy = fig.head.y
        val wH = h * wHair
        // Small bun at the back crown.
        val bunX = hx
        val bunY = hy - r * 1.05f
        p.reset()
        p.moveTo(bunX - r * 0.45f, bunY)
        p.quadTo(bunX, bunY - r * 0.50f, bunX + r * 0.45f, bunY)
        p.quadTo(bunX + r * 0.50f, bunY + r * 0.42f, bunX, bunY + r * 0.45f)
        p.quadTo(bunX - r * 0.50f, bunY + r * 0.42f, bunX - r * 0.45f, bunY)
        p.close()
        line(canvas, p, wH, aHair)
        // Lower falling strands.
        val topY = hy + r * 0.45f
        val botY = fig.pelvis.y + r * 2.6f
        val baseOff = fig.shoulderHalf * 1.0f
        val amp = r * (0.15f + dancer.energy * 0.10f) * (1f + dancer.bounceRatio * 1.4f)
        for (sgn in intArrayOf(-1, 1)) {
            for (i in 0 until 3) {
                val t = i / 2f
                val ph = dancer.phase + sgn * 0.8f + i * 0.7f
                val swing = sin(timeSec * 1.7f + ph) * amp
                val topX = hx + sgn * (baseOff * (0.50f + 0.30f * t) + r * 0.10f)
                val midX = hx + sgn * (baseOff * (0.92f + 0.20f * t) + r * 0.15f) + swing
                val endX = hx + sgn * (baseOff * (0.45f + 0.35f * t) + r * 0.10f) + swing * 1.5f
                val endY = botY - r * (0.10f + t * 0.15f)
                p.reset()
                p.moveTo(topX, topY)
                p.quadTo(midX, topY + (endY - topY) * 0.45f, endX, endY)
                line(canvas, p, wH * (1f - t * 0.18f), (aHair * (0.90f - t * 0.18f)).toInt())
            }
        }
    }

    /** 半扎发 front: a ribbon bow tied at the crown gather. */
    private fun hairHalfUpFront(
        canvas: Canvas,
        fig: Figure,
        h: Float
    ) {
        val r = fig.headRadius
        val wH = h * wHair
        drawBow(canvas, fig.head.x, fig.head.y - r * 1.02f, r * 0.40f, wH, aHair)
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
        // The leg ghosts only make sense when the legs are bare (mini skirt).
        if (dancer.outfit == Outfit.SHORT_SKIRT) {
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
        }
        canvas.restore()
    }
}
