package com.cardance.visualizer.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.cardance.visualizer.dance.Dancer
import com.cardance.visualizer.dance.Figure
import com.cardance.visualizer.dance.HairStyle
import com.cardance.visualizer.util.lerp
import kotlin.math.sin

/**
 * Draws one dancer silhouette.
 *
 * Everything is a single flat colour, so the *shape* has to carry the read:
 * hair strands are offset far enough outside the shoulders/hips that the dark
 * background shows through between hair and body. That gap is what makes the
 * hairstyle legible at car-screen size.
 */
class DancerRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun draw(
        canvas: Canvas,
        dancer: Dancer,
        fig: Figure,
        originX: Float,
        originY: Float,
        heightPx: Float,
        timeSec: Float
    ) {
        val accent = dancer.color
        val glow = Color.argb(
            165,
            Color.red(accent),
            Color.green(accent),
            Color.blue(accent)
        )
        val glowRadius = heightPx * 0.085f

        fillPaint.color = accent
        fillPaint.setShadowLayer(glowRadius, 0f, 0f, glow)
        limbPaint.color = accent
        limbPaint.setShadowLayer(glowRadius, 0f, 0f, glow)
        hairPaint.color = accent
        hairPaint.setShadowLayer(glowRadius, 0f, 0f, glow)

        canvas.save()
        canvas.translate(originX, originY)

        drawHair(canvas, fig, dancer, timeSec, heightPx)
        drawLegs(canvas, fig, heightPx)
        drawTorso(canvas, fig)
        drawArms(canvas, fig, heightPx)
        drawHead(canvas, fig)

        canvas.restore()
    }

    private fun drawTorso(canvas: Canvas, fig: Figure) {
        val waistY = lerp(fig.pelvis.y, fig.chest.y, 0.55f)
        val waistLx = fig.pelvis.x - fig.waistHalf
        val waistRx = fig.pelvis.x + fig.waistHalf

        path.reset()
        path.moveTo(fig.hipL.x, fig.hipL.y)
        // left flank, pinched at the waist
        path.quadTo(waistLx, waistY, fig.shoulderL.x, fig.shoulderL.y)
        // slight upward bow across the chest line
        path.quadTo(fig.chest.x, fig.chest.y - fig.headRadius * 0.15f, fig.shoulderR.x, fig.shoulderR.y)
        // right flank back down through the waist
        path.quadTo(waistRx, waistY, fig.hipR.x, fig.hipR.y)
        // soft hip line
        path.quadTo(fig.pelvis.x, fig.pelvis.y + fig.hipHalf * 0.45f, fig.hipL.x, fig.hipL.y)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawLegs(canvas: Canvas, fig: Figure, h: Float) {
        limbPaint.strokeWidth = h * 0.058f
        path.reset()
        path.moveTo(fig.hipL.x, fig.hipL.y)
        path.lineTo(fig.kneeL.x, fig.kneeL.y)
        path.lineTo(fig.footL.x, fig.footL.y)
        canvas.drawPath(path, limbPaint)

        path.reset()
        path.moveTo(fig.hipR.x, fig.hipR.y)
        path.lineTo(fig.kneeR.x, fig.kneeR.y)
        path.lineTo(fig.footR.x, fig.footR.y)
        canvas.drawPath(path, limbPaint)
    }

    private fun drawArms(canvas: Canvas, fig: Figure, h: Float) {
        limbPaint.strokeWidth = h * 0.044f
        path.reset()
        path.moveTo(fig.shoulderL.x, fig.shoulderL.y)
        path.lineTo(fig.elbowL.x, fig.elbowL.y)
        path.lineTo(fig.handL.x, fig.handL.y)
        canvas.drawPath(path, limbPaint)

        path.reset()
        path.moveTo(fig.shoulderR.x, fig.shoulderR.y)
        path.lineTo(fig.elbowR.x, fig.elbowR.y)
        path.lineTo(fig.handR.x, fig.handR.y)
        canvas.drawPath(path, limbPaint)
    }

    private fun drawHead(canvas: Canvas, fig: Figure) {
        canvas.drawCircle(fig.head.x, fig.head.y, fig.headRadius, fillPaint)
    }

    private fun drawHair(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        timeSec: Float,
        h: Float
    ) {
        when (dancer.style) {
            HairStyle.STRAIGHT -> drawStraightHair(canvas, fig, dancer, timeSec)
            HairStyle.PONYTAIL -> drawPonytail(canvas, fig, dancer, timeSec)
            HairStyle.WAVES -> drawWavyHair(canvas, fig, dancer, timeSec, h)
        }
    }

    /** Two long, tapered panels that fall past the waist. Minimal lateral movement. */
    private fun drawStraightHair(canvas: Canvas, fig: Figure, dancer: Dancer, timeSec: Float) {
        val topY = fig.head.y - fig.headRadius * 0.25f
        val botY = fig.pelvis.y + fig.headRadius * 3.4f
        val baseOff = fig.shoulderHalf * 1.16f
        val halfW = fig.headRadius * 0.46f
        val swayScale = dancer.bounceRatio * 22f

        for (side in intArrayOf(-1, 1)) {
            val swing = sin(timeSec * 1.5f + side * 0.7f + dancer.phase) *
                fig.headRadius * (0.10f + swayScale)
            val topX = fig.head.x + side * (baseOff - fig.headRadius * 0.34f)
            val widenX = fig.head.x + side * (baseOff + halfW) + swing
            val endX = fig.head.x + side * (baseOff - halfW * 0.35f) + swing * 1.6f
            val innerTopX = fig.head.x + side * (baseOff - fig.headRadius * 0.34f - halfW * 1.5f)
            val innerEndX = fig.head.x + side * (baseOff - halfW * 1.35f) + swing * 1.4f

            path.reset()
            path.moveTo(topX, topY)
            path.quadTo(widenX, lerp(topY, botY, 0.45f), endX, botY)
            path.lineTo(innerEndX, botY)
            path.quadTo(
                fig.head.x + side * (baseOff + halfW * 0.25f),
                lerp(topY, botY, 0.45f),
                innerTopX,
                topY
            )
            path.close()
            canvas.drawPath(path, fillPaint)
        }
    }

    /** A cap over the head plus one heavy tail that swings behind/around her. */
    private fun drawPonytail(canvas: Canvas, fig: Figure, dancer: Dancer, timeSec: Float) {
        val r = fig.headRadius

        // Cap: a shallow dome sitting on the skull.
        path.reset()
        path.moveTo(fig.head.x - r * 1.02f, fig.head.y - r * 0.28f)
        path.quadTo(fig.head.x, fig.head.y - r * 1.62f, fig.head.x + r * 1.02f, fig.head.y - r * 0.28f)
        path.quadTo(fig.head.x, fig.head.y - r * 0.78f, fig.head.x - r * 1.02f, fig.head.y - r * 0.28f)
        path.close()
        canvas.drawPath(path, fillPaint)

        // Tail: sweeps to the figure's left-ish side and swings hard with motion.
        val swing = sin(timeSec * 2.2f + dancer.phase) * 0.45f + dancer.bounceRatio * 4f
        val dir = -1f
        val startX = fig.head.x + dir * r * 0.72f
        val startY = fig.head.y - r * 0.30f
        val reach = r * (3.5f + swing * 0.9f)
        val midX = fig.head.x + dir * reach * 0.95f
        val midY = fig.head.y - r * (0.5f + swing * 0.5f)
        val endX = fig.head.x + dir * reach * 1.05f
        val endY = fig.head.y + r * (2.8f + swing * 0.7f)

        hairPaint.strokeWidth = r * 0.62f
        path.reset()
        path.moveTo(startX, startY)
        path.quadTo(midX, midY, endX, endY)
        canvas.drawPath(path, hairPaint)

        // A second thinner strand for weight variation.
        hairPaint.strokeWidth = r * 0.26f
        path.reset()
        path.moveTo(startX - dir * r * 0.25f, startY - r * 0.2f)
        path.quadTo(midX - dir * r * 0.55f, midY + r * 0.5f, endX - dir * r * 0.7f, endY - r * 0.6f)
        canvas.drawPath(path, hairPaint)
    }

    /** Big S-wave volume down both sides; phase animates so it ripples continuously. */
    private fun drawWavyHair(
        canvas: Canvas,
        fig: Figure,
        dancer: Dancer,
        timeSec: Float,
        h: Float
    ) {
        val r = fig.headRadius
        val topY = fig.head.y - r * 0.20f
        val botY = fig.pelvis.y + r * 3.2f
        val baseOff = fig.shoulderHalf * 1.22f
        val amp = r * 0.52f
        val steps = 22

        hairPaint.strokeWidth = r * 0.44f

        for (side in intArrayOf(-1, 1)) {
            val phaseOffset = dancer.phase + side * 1.1f
            path.reset()
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val y = lerp(topY, botY, t)
                val ripple = sin(t * 7.5f + timeSec * 2.4f + phaseOffset)
                val taper = 1f - t * 0.22f
                val x = fig.head.x + side * (baseOff * taper * 0.9f + r * 0.55f) + ripple * amp * side
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, hairPaint)
        }
    }
}
