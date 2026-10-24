package com.cardance.visualizer.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import com.cardance.visualizer.dance.Outfit
import com.cardance.visualizer.dance.SpecialPhase
import com.cardance.visualizer.util.approach
import com.cardance.visualizer.util.clamp01
import com.cardance.visualizer.util.easeInOutQuad
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole visual: ambient glows, the reaction equaliser, and the three dancers.
 *
 * Layout follows the approved mockup - one large dancer dead centre, one smaller
 * dancer either side. Only the **side** dancers are allowed to overflow:
 * during a detected chorus they grow and push outward so the canvas (the screen)
 * crops them, which is the "break out of the frame" effect. The centre dancer
 * stays inside so nothing important is ever covered.
 *
 * On a chorus the stage hands control to one dancer for a KOF-style finishing
 * move: she gathers light, the screen flashes, then she bursts outward with
 * radial light and a shockwave. **Only she grows** - the equaliser, the HUD and
 * the other two dancers stay exactly where they are, so the moment reads as
 * "the dancer powers up", not "the screen zooms in".
 *
 * **Solo stage**: the moment music is actually playing (demo rhythm, mixed
 * output, or a local track), a single randomly rolled dancer takes the stage -
 * centred, scaled up, dancing alone. Silence for a few seconds falls back to
 * the idle single, and the next play session rolls a different face, so every
 * track feels like a new cast member walks on.
 *
 * The cast is a pool of **procedural** dancers (see [Dancer.style] /
 * [Dancer.outfit]): each one is a live vector skeleton that bends, swings and
 * bursts with the music, so the random-pick, chorus hand-off and silence
 * fallback all behave exactly as before - only now the dancer is a real model
 * moving, not a still image being rotated.
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
            style = HairStyle.STRAIGHT,
            color = Color.parseColor("#22D3EE"),
            centerRatioX = 0.125f,
            baseScale = 0.92f,
            allowOverflow = true,
            phase = 0.0f,
            outfit = Outfit.JEANS
        ),
        Dancer(
            style = HairStyle.WAVES,
            color = Color.parseColor("#EC4899"),
            centerRatioX = 0.375f,
            baseScale = 0.98f,
            allowOverflow = false,
            phase = 1.9f,
            outfit = Outfit.LONG_DRESS
        ),
        Dancer(
            style = HairStyle.RIBBON,
            color = Color.parseColor("#A855F7"),
            centerRatioX = 0.625f,
            baseScale = 0.98f,
            allowOverflow = false,
            phase = 3.7f,
            outfit = Outfit.SHORT_SKIRT
        ),
        Dancer(
            style = HairStyle.HALFUP,
            color = Color.parseColor("#F59E0B"),
            centerRatioX = 0.875f,
            baseScale = 0.92f,
            allowOverflow = true,
            phase = 5.4f,
            outfit = Outfit.SHORT_SKIRT
        )
    )

    /** Screen placement of each dancer, needed by the special-effect layer. */
    private class Placement {
        var cx = 0f
        var pelvisY = 0f
        var figH = 0f
    }

    private val placements = Array(dancers.size) { Placement() }

    /** Index of the dancer currently running a finishing move. */
    private var specialIndex = 1
    private var lastChorus = false

    /**
     * Index of the dancer currently on stage. While music plays she dances
     * alone, centred; a new one is rolled every time playback starts.
     */
    private var soloIndex = 1

    /** True while audio is live - only [soloIndex] is on stage. */
    private var soloMode = false

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

    // --- special move effects -------------------------------------------------
    private val fxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fxPath = Path()

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

    /**
     * Roll the dancer for this play session. Never repeats the previous face
     * twice in a row, so consecutive tracks always bring someone new on.
     */
    private fun rollSoloDancer() {
        if (dancers.size <= 1) {
            soloIndex = 0
            return
        }
        var next = rnd.nextInt(dancers.size)
        if (next == soloIndex) {
            next = (next + 1 + rnd.nextInt(dancers.size - 1)) % dancers.size
        }
        soloIndex = next
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

        // ---- solo stage ------------------------------------------------------
        // "每次播放音乐，随机出现出来一个跳舞": the first live frame after a
        // silence rolls a new solo dancer; a few seconds of silence hands the
        // stage back to the idle single until the next play session.
        val tapLive = tapReady && audioTap.running && audioTap.silentSeconds < SOLO_SILENCE_SEC
        val audioActive = demoMode || tapLive
        if (audioActive && !soloMode) {
            soloMode = true
            rollSoloDancer()
        } else if (!audioActive && soloMode) {
            soloMode = false
        }

        if (beatSource != lastBeatSeen) {
            val fresh = min((beatSource - lastBeatSeen).toInt(), 4)
            lastBeatSeen = beatSource
            repeat(fresh) {
                dancers.forEach { it.onBeat(danceMode, rnd) }
            }
        }

        // A chorus starts a finishing move. The performer rotates so every
        // dancer gets a turn, starting with the one in the middle. In solo
        // mode the lone dancer on stage is always the performer - nobody
        // else is visible to hand the move to.
        if (rhythm.chorus && !lastChorus) {
            specialIndex = if (soloMode) soloIndex else (specialIndex + 1) % dancers.size
            dancers[specialIndex].triggerSpecial()
        }
        lastChorus = rhythm.chorus

        for (d in dancers) d.update(dt, rhythm, width.toFloat())

        // While somebody is performing, the stage dims everything else. On the
        // single-dancer stage that is only ever the performer herself.
        val anySpecial = dancers.any { it.special != SpecialPhase.NONE }
        for (d in dancers) {
            val target = if (!anySpecial || d.special != SpecialPhase.NONE) 1f else 0.40f
            d.applyDim(target)
        }

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
        drawChargeFx(canvas, w, h)
        drawEqualiser(canvas, w, h, rhythm)
        drawDancers(canvas, w, h, rhythm)
        drawBurstFx(canvas, w, h)
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
            val mirroredIdx = if (i < half) half - 1 - i else i - bands / 2
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

        // Single-dancer stage: the rolled dancer owns the centre at all times -
        // full solo scale while music plays, a calmer idle scale in silence.
        val i = soloIndex
        val d = dancers[i]
        val scaleFix = (if (soloMode) SOLO_BASE_SCALE else IDLE_BASE_SCALE) / d.baseScale
        val figH = (availH / FIGURE_RATIO) * d.scaleNow * scaleFix
        val bounceLift = d.bounceRatio * figH
        val cx = w * SOLO_CENTER_RATIO
        // Pelvis sits FIGURE_BELOW above the feet line.
        val pelvisY = floorY - FIGURE_BELOW * figH - bounceLift

        val pl = placements[i]
        pl.cx = cx
        pl.pelvisY = pelvisY
        pl.figH = figH

        renderer.draw(canvas, d, d.buildFigure(figH), cx, pelvisY, figH, timeSec)
    }

    // ------------------------------------------------------------ special fx

    /** Light drawn behind the performer while she gathers power. */
    private fun drawChargeFx(canvas: Canvas, w: Float, h: Float) {
        val d = dancers[specialIndex]
        if (d.special != SpecialPhase.CHARGE) return
        val pl = placements[specialIndex]
        val t = easeInOutQuad(d.specialT)
        val figH = pl.figH
        val cx = pl.cx
        val cy = pl.pelvisY - figH * 0.30f
        val floorY = pl.pelvisY + figH * 0.48f

        // The rest of the stage falls back so the eye lands on her.
        canvas.drawColor(Color.argb((70 * (1f - t)).toInt().coerceIn(0, 70), 0, 0, 0))

        // Light motes pulled inward.
        val motes = 30
        for (i in 0 until motes) {
            val a = (i.toFloat() / motes) * TWO_PI + timeSec * 1.6f
            val radius = figH * (0.92f - 0.74f * t)
            val px = cx + cos(a) * radius
            val py = cy + sin(a) * radius * 0.68f
            val size = figH * (0.008f + 0.010f * t)
            fxFill.color = d.color
            fxFill.alpha = (150 + 80 * t).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, size * 2.6f, fxFill)
            fxFill.alpha = 255
            fxFill.color = Color.WHITE
            canvas.drawCircle(px, py, size, fxFill)
        }

        // A ring tightening on the floor under her.
        fxStroke.color = d.color
        fxStroke.strokeWidth = figH * 0.010f
        fxStroke.alpha = (170 * t).toInt().coerceIn(0, 255)
        canvas.drawOval(
            cx - figH * (0.46f - 0.24f * t),
            floorY - figH * 0.030f,
            cx + figH * (0.46f - 0.24f * t),
            floorY + figH * 0.030f,
            fxStroke
        )
    }

    /** Light drawn on top of the performer: the flash, the rays, the shockwave. */
    private fun drawBurstFx(canvas: Canvas, w: Float, h: Float) {
        val d = dancers[specialIndex]
        val pl = placements[specialIndex]
        val figH = pl.figH
        val cx = pl.cx
        val cy = pl.pelvisY - figH * 0.28f
        val floorY = pl.pelvisY + figH * 0.48f

        when (d.special) {
            SpecialPhase.FLASH -> {
                val a = ((1f - d.specialT) * 210).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(a, 255, 255, 255))
            }

            SpecialPhase.BURST -> {
                val t = d.specialT

                // Radial light rays.
                val rays = 22
                val len = figH * (0.45f + 1.15f * easeInOutQuad(min(1f, t * 2.2f)))
                val halfW = figH * 0.016f
                fxFill.color = d.color
                for (i in 0 until rays) {
                    val ang = (i.toFloat() / rays) * TWO_PI + t * 0.7f
                    val ca = cos(ang)
                    val sa = sin(ang)
                    fxPath.reset()
                    fxPath.moveTo(cx, cy)
                    fxPath.lineTo(cx + ca * len - sa * halfW, cy + sa * len + ca * halfW)
                    fxPath.lineTo(cx + ca * len + sa * halfW, cy + sa * len - ca * halfW)
                    fxPath.close()
                    fxFill.alpha = (110 * (1f - t * 0.55f)).toInt().coerceIn(0, 255)
                    canvas.drawPath(fxPath, fxFill)
                }

                // The shockwave slamming into the floor.
                val ringR = figH * (0.18f + 1.45f * t)
                fxStroke.color = Color.WHITE
                fxStroke.strokeWidth = figH * (0.020f * (1f - t * 0.6f) + 0.004f)
                fxStroke.alpha = ((1f - t) * 200).toInt().coerceIn(0, 255)
                canvas.drawOval(
                    cx - ringR,
                    floorY - ringR * 0.20f,
                    cx + ringR,
                    floorY + ringR * 0.20f,
                    fxStroke
                )
                fxStroke.color = d.color
                fxStroke.alpha = ((1f - t) * 140).toInt().coerceIn(0, 255)
                canvas.drawOval(
                    cx - ringR * 1.25f,
                    floorY - ringR * 0.25f,
                    cx + ringR * 1.25f,
                    floorY + ringR * 0.25f,
                    fxStroke
                )

                // The core of the release.
                fxFill.color = Color.WHITE
                fxFill.alpha = ((1f - t) * 190).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, figH * 0.085f * (1f + t * 2.2f), fxFill)
                fxFill.alpha = 255
            }

            else -> Unit
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

        // 顶部右侧：台上舞者 —— 播放中为随机登场者，待机时为当前站立者
        val cur = dancers[soloIndex]
        val soloText = if (soloMode) {
            "登场 · ${cur.style.label}·${cur.outfit.label}"
        } else {
            "待机 · ${cur.style.label}·${cur.outfit.label}"
        }
        val stw = strongTextPaint.measureText(soloText)
        canvas.drawText(soloText, w - stw - pad, pad + 34f, strongTextPaint)

        val performer = dancers[specialIndex]
        if (performer.special != SpecialPhase.NONE) {
            val label = when (performer.special) {
                SpecialPhase.CHARGE -> "蓄力"
                SpecialPhase.FLASH -> "闪光"
                SpecialPhase.BURST -> "必杀"
                SpecialPhase.NONE -> ""
            }
            val tw = strongTextPaint.measureText(label)
            val left = pad
            val top = pad + 96f
            chipPaint.color = Color.parseColor("#1F0A16")
            canvas.drawRoundRect(left, top, left + tw + 40f, top + 46f, 23f, 23f, chipPaint)
            chipPaint.style = Paint.Style.STROKE
            chipPaint.strokeWidth = 2f
            chipPaint.color = performer.color
            canvas.drawRoundRect(left, top, left + tw + 40f, top + 46f, 23f, 23f, chipPaint)
            chipPaint.style = Paint.Style.FILL
            canvas.drawText(label, left + 20f, top + 31f, strongTextPaint)
        } else if (rhythm.chorus) {
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
        private const val TWO_PI = 6.2831855f

        // --- solo stage ------------------------------------------------------
        /** Centred x-ratio for the solo dancer - she owns the middle. */
        private const val SOLO_CENTER_RATIO = 0.50f
        /** Base scale for the solo dancer, normalised across hairstyles. */
        private const val SOLO_BASE_SCALE = 1.06f
        /** Calmer scale for the same dancer while the stage is idle. */
        private const val IDLE_BASE_SCALE = 0.92f
        /** Seconds of silence before the stage falls back to the idle single. */
        private const val SOLO_SILENCE_SEC = 3L
    }
}
