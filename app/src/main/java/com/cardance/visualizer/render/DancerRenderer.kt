package com.cardance.visualizer.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import com.cardance.visualizer.dance.Dancer
import com.cardance.visualizer.dance.Figure
import com.cardance.visualizer.dance.HairStyle
import com.cardance.visualizer.util.lerp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws one dancer as **neon line art**.
 *
 * The body is not a solid silhouette any more. Every part is built from thin
 * glowing contours, the way a light-painting photograph reads on a black stage:
 *
 * - the torso is a closed contour instead of a filled shape;
 * - limbs are *ribbons* - two parallel edges with a visible gap between them;
 * - the inside of the body carries structure lines (spine, ribs, waist, hip);
 * - the hair is many independent strands, each with its own phase, so it
 *   ripples instead of moving as one sheet;
 * - one or two ghost contours trail behind the body as motion echoes.
 *
 * That is a lot more line work than a filled silhouette needs, which is the
 * point: on a car screen the *drawing* has to carry the read, not the colour.
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
    private val rect = RectF()
    private val mtx = Matrix()

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

        buildShader(dancer, h, timeSec)

        canvas.save()
        canvas.translate(originX, originY)

        drawEchoes(canvas, fig, dancer, h)
        drawHair(canvas, fig, dancer, h, timeSec, BACK)
        drawLegs(canvas, fig, dancer, h)
        drawArms(canvas, fig, dancer, h)
        drawTorso(canvas, fig, h)
        drawTorsoLines(canvas, fig, dancer, h)
        drawHead(canvas, fig, h)
        drawHair(canvas, fig, dancer, h, timeSec, FRONT)

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
            0f, -h * 0.66f, 0f, h * 0.60f,
            colors, positions, Shader.TileMode.MIRROR
        )
        // Scroll the ramp down the figure so the colour keeps moving.
        val scroll = (timeSec * h * 0.055f) % (h * 1.10f)
        mtx.setTranslate(0f, scroll)
        shader.setLocalMatrix(mtx)
        stroke.shader = shader
    }

    // ------------------------------------------------------------ primitives

    /** Wide soft halo + mid glow + crisp filament. */
    private fun neon(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        stroke.alpha = (a * 0.15f).toInt().coerceIn(0, 255)
        stroke.strokeWidth = w * 3.0f
        canvas.drawPath(path, stroke)

        stroke.alpha = (a * 0.34f).toInt().coerceIn(0, 255)
        stroke.strokeWidth = w * 1.70f
        canvas.drawPath(path, stroke)

        stroke.alpha = a
        stroke.strokeWidth = w
        canvas.drawPath(path, stroke)
    }

    /** Same, but with a hot white core - used on the main silhouette lines. */
    private fun neonHot(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        neon(canvas, path, w, alpha)
        filament.color = Color.WHITE
        filament.alpha = (alpha.coerceIn(0, 255) * 0.42f).toInt().coerceIn(0, 255)
        filament.strokeWidth = w * 0.40f
        canvas.drawPath(path, filament)
    }

    /** Single pass, no bloom - cheap enough for the ghost trails. */
    private fun flat(canvas: Canvas, path: Path, w: Float, alpha: Int) {
        stroke.alpha = alpha.coerceIn(0, 255)
        stroke.strokeWidth = w
        canvas.drawPath(path, stroke)
    }

    /**
     * A limb drawn as a narrow ribbon: the two long edges of the limb, with
     * the dark stage showing through the middle.
     */
    private fun ribbon(a: PointF, b: PointF, c: PointF, w: Float): Path {
        val d1x = b.x - a.x
        val d1y = b.y - a.y
        val l1 = sqrt(d1x * d1x + d1y * d1y).coerceAtLeast(0.0001f)
        val p1x = -d1y / l1
        val p1y = d1x / l1

        val d2x = c.x - b.x
        val d2y = c.y - b.y
        val l2 = sqrt(d2x * d2x + d2y * d2y).coerceAtLeast(0.0001f)
        val p2x = -d2y / l2
        val p2y = d2x / l2

        p.reset()
        p.moveTo(a.x + p1x * w, a.y + p1y * w)
        p.quadTo(b.x + p1x * w, b.y + p1y * w, c.x + p2x * w, c.y + p2y * w)
        p.quadTo(c.x - p2x * w, c.y - p2y * w, b.x - p1x * w, b.y - p1y * w)
        p.quadTo(a.x - p1x * w, a.y - p1y * w, a.x + p1x * w, a.y + p1y * w)
        p.close()
        return p
    }

    private fun circle(at: PointF, r: Float): Path {
        p.reset()
        p.addCircle(at.x, at.y, r, Path.Direction.CW)
        return p
    }

    // ----------------------------------------------------------------- body

    private fun torsoPath(fig: Figure): Path {
        val waistY = lerp(fig.pelvis.y, fig.chest.y, 0.55f)
        val waistLx = fig.pelvis.x - fig.waistHalf
        val waistRx = fig.pelvis.x + fig.waistHalf

        p.reset()
        p.moveTo(fig.hipL.x, fig.hipL.y)
        p.quadTo(waistLx, waistY, fig.shoulderL.x, fig.shoulderL.y)
        p.quadTo(
            fig.chest.x,
            fig.chest.y - fig.headRadius * 0.16f,
            fig.shoulderR.x,
            fig.shoulderR.y
        )
        p.quadTo(waistRx, waistY, fig.hipR.x, fig.hipR.y)
        p.quadTo(fig.pelvis.x, fig.pelvis.y + fig.hipHalf * 0.42f, fig.hipL.x, fig.hipL.y)
        p.close()
        return p
    }

    private fun drawTorso(canvas: Canvas, fig: Figure, h: Float) {
        neonHot(canvas, torsoPath(fig), h * 0.0195f, 235)
    }

    /**
     * Interior structure: spine, three rib arcs, two waist arcs, one hip arc.
     * These are what stop the body from reading as an empty outline.
     */
    private fun drawTorsoLines(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * 0.0105f
        val top = fig.chest.y
        val bottom = fig.pelvis.y
        val breathe = 1f + dancer.energy * 0.06f

        // Spine.
        p.reset()
        p.moveTo(fig.pelvis.x, fig.pelvis.y - fig.hipHalf * 0.15f)
        p.quadTo(
            fig.pelvis.x + fig.waistHalf * 0.30f * (1f + dancer.energy),
            lerp(top, bottom, 0.55f),
            fig.chest.x,
            top + fig.headRadius * 0.10f
        )
        neon(canvas, p, w, 175)

        // Rib arcs.
        for (i in 0 until 3) {
            val t = 0.74f - i * 0.17f
            val y = lerp(top, bottom, t)
            val half = lerp(fig.shoulderHalf, fig.waistHalf, 1f - t) * 0.84f
            p.reset()
            p.moveTo(fig.pelvis.x - half, y)
            p.quadTo(fig.pelvis.x, y + fig.waistHalf * 0.32f * breathe, fig.pelvis.x + half, y)
            neon(canvas, p, w * 0.88f, 150)
        }

        // Waist arcs.
        for (i in 0 until 2) {
            val y = lerp(top, bottom, 0.46f + i * 0.14f)
            val half = fig.waistHalf * (1.00f + i * 0.18f)
            p.reset()
            p.moveTo(fig.pelvis.x - half, y)
            p.quadTo(fig.pelvis.x, y + fig.waistHalf * 0.30f, fig.pelvis.x + half, y)
            neon(canvas, p, w * 0.82f, 140)
        }

        // Hip arc.
        p.reset()
        p.moveTo(fig.hipL.x, fig.hipL.y)
        p.quadTo(
            fig.pelvis.x,
            fig.pelvis.y - fig.hipHalf * 0.10f + fig.hipHalf * 0.52f,
            fig.hipR.x,
            fig.hipR.y
        )
        neon(canvas, p, w * 0.92f, 150)
    }

    private fun drawLegs(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * 0.0125f
        val half = h * (0.0295f + dancer.energy * 0.0055f)

        neon(canvas, ribbon(fig.hipL, fig.kneeL, fig.footL, half), w, 225)
        neon(canvas, ribbon(fig.hipR, fig.kneeR, fig.footR, half), w, 225)

        // Knee hinges.
        neon(canvas, circle(fig.kneeL, h * 0.0165f), w * 0.75f, 130)
        neon(canvas, circle(fig.kneeR, h * 0.0165f), w * 0.75f, 130)
    }

    private fun drawArms(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        val w = h * 0.0110f
        val half = h * (0.0225f + dancer.energy * 0.0045f)

        neon(canvas, ribbon(fig.shoulderL, fig.elbowL, fig.handL, half), w, 230)
        neon(canvas, ribbon(fig.shoulderR, fig.elbowR, fig.handR, half), w, 230)

        // Elbow hinges.
        neon(canvas, circle(fig.elbowL, h * 0.0135f), w * 0.75f, 130)
        neon(canvas, circle(fig.elbowR, h * 0.0135f), w * 0.75f, 130)
    }

    private fun drawHead(canvas: Canvas, fig: Figure, h: Float) {
        val r = fig.headRadius

        // Neck - two short lines.
        p.reset()
        p.moveTo(fig.chest.x - r * 0.34f, fig.chest.y + r * 0.12f)
        p.lineTo(fig.head.x - r * 0.26f, fig.head.y + r * 0.78f)
        neon(canvas, p, h * 0.0095f, 190)

        p.reset()
        p.moveTo(fig.chest.x + r * 0.34f, fig.chest.y + r * 0.12f)
        p.lineTo(fig.head.x + r * 0.26f, fig.head.y + r * 0.78f)
        neon(canvas, p, h * 0.0095f, 190)

        // Skull contour.
        rect.set(fig.head.x - r * 0.90f, fig.head.y - r, fig.head.x + r * 0.90f, fig.head.y + r)
        p.reset()
        p.addOval(rect)
        neonHot(canvas, p, h * 0.0175f, 228)

        // Crown parting.
        p.reset()
        p.addArc(rect, 200f, 140f)
        neon(canvas, p, h * 0.0100f, 165)

        // Face profile: forehead -> nose -> lips -> chin.
        p.reset()
        p.moveTo(fig.head.x + r * 0.16f, fig.head.y - r * 0.98f)
        p.quadTo(
            fig.head.x + r * 0.95f,
            fig.head.y - r * 0.42f,
            fig.head.x + r * 0.78f,
            fig.head.y - r * 0.06f
        )
        p.quadTo(
            fig.head.x + r * 0.64f,
            fig.head.y + r * 0.12f,
            fig.head.x + r * 0.84f,
            fig.head.y + r * 0.30f
        )
        p.quadTo(
            fig.head.x + r * 0.56f,
            fig.head.y + r * 0.66f,
            fig.head.x + r * 0.14f,
            fig.head.y + r * 0.86f
        )
        neon(canvas, p, h * 0.0115f, 195)
    }

    // ----------------------------------------------------------------- hair

    private fun drawHair(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float,
        layer: Int
    ) {
        when (dancer.style) {
            HairStyle.STRAIGHT -> hairStraight(canvas, fig, dancer, h, timeSec, layer)
            HairStyle.PONYTAIL -> hairPony(canvas, fig, dancer, h, timeSec, layer)
            HairStyle.WAVES -> hairWaves(canvas, fig, dancer, h, timeSec, layer)
        }
    }

    /** Long straight panels, each strand rippling on its own phase. */
    private fun hairStraight(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float,
        layer: Int
    ) {
        val r = fig.headRadius
        val topY = fig.head.y - r * 0.22f
        val botY = fig.pelvis.y + r * 3.5f
        val baseOff = fig.shoulderHalf * 1.10f
        val w = h * 0.0090f
        val amp = r * (0.13f + dancer.energy * 0.10f) * (1f + dancer.bounceRatio * 1.6f)

        for (side in intArrayOf(-1, 1)) {
            for (i in strandRange(layer)) {
                val t = strandT(i, layer)
                val ph = dancer.phase + side * 0.65f + i * 0.83f
                val swing = sin(timeSec * 1.7f + ph) * amp
                val topX = fig.head.x + side * (baseOff - r * 0.30f + t * r * 0.26f)
                val midX = fig.head.x + side * (baseOff + t * r * 0.34f) + swing
                val endX = fig.head.x + side * (baseOff - r * 0.24f + t * r * 0.30f) + swing * 1.75f

                p.reset()
                p.moveTo(topX, topY)
                p.quadTo(midX, lerp(topY, botY, 0.46f), endX, botY)
                neon(canvas, p, w * (1f - t * 0.26f), (205 - t * 34).toInt())
            }
        }
    }

    /** A single tail, split into strands that fan out and swing together. */
    private fun hairPony(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float,
        layer: Int
    ) {
        val r = fig.headRadius
        val w = h * 0.0102f
        val dir = -1f
        val swingBase = sin(timeSec * 2.1f + dancer.phase) * (0.30f + dancer.energy * 0.35f)

        for (i in strandRange(layer)) {
            val t = strandT(i, layer)
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
            neon(canvas, p, w * (1.12f - t * 0.34f), (210 - t * 42).toInt())
        }
    }

    /** Big S-waves down both sides, each strand on its own ripple phase. */
    private fun hairWaves(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        h: Float,
        timeSec: Float,
        layer: Int
    ) {
        val r = fig.headRadius
        val topY = fig.head.y - r * 0.18f
        val botY = fig.pelvis.y + r * 3.3f
        val baseOff = fig.shoulderHalf * 1.16f
        val w = h * 0.0096f
        val steps = 20

        for (side in intArrayOf(-1, 1)) {
            for (i in strandRange(layer)) {
                val t = strandT(i, layer)
                val ph = dancer.phase + side * 1.05f + i * 0.77f
                val rippleAmp = r * (0.24f + dancer.energy * 0.16f) * (1f - t * 0.28f)

                p.reset()
                for (k in 0..steps) {
                    val u = k.toFloat() / steps
                    val y = lerp(topY, botY, u)
                    val taper = 1f - u * 0.20f
                    val ripple = sin(u * 7.2f + timeSec * 2.5f + ph)
                    val x = fig.head.x +
                        side * (baseOff * taper * 0.92f + r * (0.44f + t * 0.24f)) +
                        ripple * rippleAmp * side
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                neon(canvas, p, w * (1f - t * 0.28f), (198 - t * 32).toInt())
            }
        }
    }

    /** Strand indices for the layer: back layer first, a couple on top of the body. */
    private fun strandRange(layer: Int): IntArray =
        if (layer == BACK) intArrayOf(0, 1, 2, 3, 4) else intArrayOf(5, 6)

    private fun strandT(i: Int, layer: Int): Float =
        if (layer == BACK) i / 4f else (i - 4f) / 2f

    // ----------------------------------------------------------------- echo

    /**
     * Two ghost contours trailing the body. They lag behind the sway, which
     * reads as motion blur and makes the figure feel alive rather than posed.
     */
    private fun drawEchoes(canvas: Canvas, fig: Figure, dancer: Dancer, h: Float) {
        for (i in 0 until 2) {
            val k = i + 1
            val dx = -dancer.sway * h * 0.0016f * k
            val dy = h * 0.009f * k
            val a = (44 - k * 13).coerceIn(12, 60)

            canvas.save()
            canvas.translate(dx, dy)
            flat(canvas, torsoPath(fig), h * 0.0125f, a)
            flat(canvas, ribbon(fig.shoulderL, fig.elbowL, fig.handL, h * 0.030f), h * 0.0090f, a)
            flat(canvas, ribbon(fig.shoulderR, fig.elbowR, fig.handR, h * 0.030f), h * 0.0090f, a)
            flat(canvas, ribbon(fig.hipL, fig.kneeL, fig.footL, h * 0.030f), h * 0.0090f, a)
            flat(canvas, ribbon(fig.hipR, fig.kneeR, fig.footR, h * 0.030f), h * 0.0090f, a)
            canvas.restore()
        }
    }

    private companion object {
        const val BACK = 0
        const val FRONT = 1
    }
}
