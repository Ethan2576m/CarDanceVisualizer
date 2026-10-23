package com.cardance.visualizer.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.cardance.visualizer.audio.AudioTap
import com.cardance.visualizer.audio.DemoRhythm
import com.cardance.visualizer.audio.RawFrame
import com.cardance.visualizer.audio.Rhythm
import com.cardance.visualizer.dance.DanceMode
import com.cardance.visualizer.dance.Dancer
import com.cardance.visualizer.dance.HairStyle
import com.cardance.visualizer.util.approach
import com.cardance.visualizer.util.clamp01
import kotlin.math.min
import kotlin.random.Random

/**
 * The whole visual: ambient glows, the reaction equaliser, and the three dancers.
 *
 * Layout follows the approved mockup - one large dancer dead centre, one smaller
 * dancer either side. Only the **side** dancers are allowed to overflow:
 * during a detected chorus they grow and push outward so the canvas (the screen)
 * crops them, which is the "break out of the frame" effect. The centre dancer
 * stays inside so nothing important is ever covered.
 */
class StageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    lateinit var audioTap: AudioTap

    var danceMode: DanceMode = DanceMode.RANDOM
    var sourceLabel: String = "蓝牙混音"

    /**
     * 演示模式：不依赖任何音频采集，用合成节拍驱动舞者。
     * 用于模拟器、展厅待机、以及"采不到音频"时的兜底展示。
     */
    var demoMode: Boolean = false

    private val renderer = DancerRenderer()
    private val rnd = Random(System.currentTimeMillis())
    private val demoRhythm = DemoRhythm()
    private var lastRhythm: Rhythm = Rhythm()

    private val dancers = listOf(
        Dancer(
            style = HairStyle.PONYTAIL,
            color = Color.parseColor("#A855F7"),
            centerRatioX = 0.145f,
            baseScale = 0.90f,
            allowOverflow = true,
            phase = 0.0f
        ),
        Dancer(
            style = HairStyle.STRAIGHT,
            color = Color.parseColor("#22D3EE"),
            centerRatioX = 0.500f,
            baseScale = 1.00f,
            allowOverflow = false,
            phase = 1.9f
        ),
        Dancer(
            style = HairStyle.WAVES,
            color = Color.parseColor("#EC4899"),
            centerRatioX = 0.855f,
            baseScale = 0.90f,
            allowOverflow = true,
            phase = 3.7f
        )
    )

    private var lastBeatSeen = 0L
    private var timeSec = 0f
    private var lastFrameNanos = 0L
    private val spectrum = FloatArray(RawFrame.BANDS)

    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 26f
    }
    private val strongTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E7EB")
        textSize = 30f
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val idleRhythm = Rhythm()

    init {
        setBackgroundColor(Color.parseColor(BG_HEX))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastFrameNanos = frameTimeNanos
        step(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(dt: Float) {
        timeSec += dt

        val tapReady = ::audioTap.isInitialized
        val rhythm: Rhythm
        val beatSource: Long

        if (demoMode) {
            // 演示模式：合成节拍，不看真实采集
            val demo = demoRhythm.update(dt)
            rhythm = demo
            beatSource = demoRhythm.beatCount
        } else if (tapReady) {
            rhythm = audioTap.rhythm
            beatSource = audioTap.beatCount
        } else {
            rhythm = idleRhythm
            beatSource = 0L
        }
        lastRhythm = rhythm

        if (beatSource != lastBeatSeen) {
            val fresh = min((beatSource - lastBeatSeen).toInt(), 4)
            lastBeatSeen = beatSource
            repeat(fresh) {
                dancers.forEach { it.onBeat(danceMode, rnd) }
            }
        }

        for (d in dancers) d.update(dt, rhythm, width.toFloat())

        val src = rhythm.spectrum
        for (i in spectrum.indices) {
            spectrum[i] = approach(spectrum[i], src.getOrNull(i) ?: 0f, 0.28f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val rhythm = lastRhythm

        canvas.drawColor(Color.parseColor(BG_HEX))
        drawAmbientGlow(canvas, w, h, rhythm)
        drawEqualiser(canvas, w, h, rhythm)
        drawDancers(canvas, w, h, rhythm)
        drawHud(canvas, w, h, rhythm)
    }

    private fun drawAmbientGlow(canvas: Canvas, w: Float, h: Float, rhythm: Rhythm) {
        val pulse = 0.55f + rhythm.intensity * 0.6f
        listOf(
            Triple(0.30f * w, 0.46f * h, Color.parseColor("#22D3EE")),
            Triple(0.70f * w, 0.52f * h, Color.parseColor("#A855F7")),
            Triple(0.86f * w, 0.42f * h, Color.parseColor("#EC4899"))
        ).forEach { (cx, cy, tint) ->
            val radius = w * 0.34f * (0.9f + pulse * 0.25f)
            val alpha = (46 * pulse).toInt().coerceIn(0, 90)
            val shader = RadialGradient(
                cx, cy, radius,
                Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            glowPaint.shader = shader
            canvas.drawCircle(cx, cy, radius, glowPaint)
            glowPaint.shader = null
        }
    }

    private fun drawEqualiser(canvas: Canvas, w: Float, h: Float, rhythm: Rhythm) {
        val bands = spectrum.size
        val usable = min(w * 0.82f, h * 1.4f)
        val slot = usable / bands
        val barW = slot * 0.56f
        val startX = (w - usable) / 2f
        val baseY = h * 0.945f
        val maxH = h * 0.15f

        barPaint.strokeCap = Paint.Cap.ROUND
        barPaint.strokeWidth = barW
        barPaint.shader = LinearGradient(
            startX, baseY, startX + usable, baseY - maxH,
            Color.parseColor("#22D3EE"),
            Color.parseColor("#A855F7"),
            Shader.TileMode.CLAMP
        )
        barPaint.alpha = (170 + rhythm.intensity * 85).toInt().coerceAtMost(255)

        val half = bands / 2
        for (i in 0 until bands) {
            // Mirror around the centre so it reads as a symmetric reaction floor.
            val mirroredIdx = if (i < half) half - 1 - i else i - half
            val v = clamp01(spectrum[mirroredIdx] * 1.25f + rhythm.beatPulse * 0.20f)
            val bh = (v * maxH).coerceAtLeast(4f)
            val x = startX + slot * i + slot / 2f
            canvas.drawLine(x, baseY, x, baseY - bh, barPaint)
        }
        barPaint.shader = null
        barPaint.alpha = 255
    }

    private fun drawDancers(canvas: Canvas, w: Float, h: Float, rhythm: Rhythm) {
        val floorY = h * 0.80f
        val availTop = h * 0.10f
        val availH = floorY - availTop

        for (d in dancers) {
            val figH = (availH / FIGURE_RATIO) * d.scaleNow
            val bounceLift = d.bounceRatio * figH
            val cx = w * d.centerRatioX +
                (if (d.centerRatioX < 0.5f) -1f else 1f) * d.outwardShift
            // Pelvis sits FIGURE_BELOW_ABOVE above the feet line.
            val pelvisY = floorY - FIGURE_BELOW * figH - bounceLift
            val fig = d.buildFigure(figH)
            renderer.draw(canvas, d, fig, cx, pelvisY, figH, timeSec)
        }
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float, rhythm: Rhythm) {
        val pad = 36f
        val srcText = if (demoMode) "演示模式 · 合成节拍" else "音源 · $sourceLabel"
        canvas.drawText(srcText, pad, pad + 34f, strongTextPaint)

        val bpm = rhythm.bpm
        val bpmText = if (bpm > 0f) "BPM ${bpm.toInt()}" else "BPM --"
        val modeText = if (demoMode) "演示" else danceMode.label
        canvas.drawText("$bpmText   ·   $modeText", pad, pad + 74f, textPaint)

        if (rhythm.chorus) {
            val label = "副歌 · 律动爆发"
            val tw = strongTextPaint.measureText(label)
            val left = pad
            val top = pad + 96f
            chipPaint.color = Color.parseColor("#1F0A16")
            canvas.drawRoundRect(left, top, left + tw + 40f, top + 46f, 23f, 23f, chipPaint)
            chipPaint.style = Paint.Style.STROKE
            chipPaint.strokeWidth = 2f
            chipPaint.color = Color.parseColor("#EC4899")
            canvas.drawRoundRect(left, top, left + tw + 40f, top + 46f, 23f, 23f, chipPaint)
            chipPaint.style = Paint.Style.FILL
            canvas.drawText(label, left + 20f, top + 31f, strongTextPaint)
        }

        // 右下角提示当前可用的手势
        val hint = if (demoMode) {
            "双击关闭演示 · 长按选择音乐"
        } else {
            "双击演示模式 · 长按选择音乐"
        }
        val hw = textPaint.measureText(hint)
        canvas.drawText(hint, w - hw - pad, h - pad, textPaint)

        val tapReady = ::audioTap.isInitialized
        val deadTap = tapReady && audioTap.silentSeconds >= SILENCE_WARN_SECONDS
        if (!demoMode && (!tapReady || deadTap)) {
            val msg = "未检测到音频 · 点按切换模式，双击开演示模式"
            canvas.drawText(msg, pad, h - pad, textPaint)
        }
    }

    companion object {
        private const val BG_HEX = "#0B0E14"
        /** Fraction of the figure's own height that sits below the pelvis. */
        private const val FIGURE_BELOW = 0.48f
        /** Total figure height as a fraction of the skeleton's nominal `h`. */
        private const val FIGURE_RATIO = 0.937f
        private const val SILENCE_WARN_SECONDS = 4L
    }
}
