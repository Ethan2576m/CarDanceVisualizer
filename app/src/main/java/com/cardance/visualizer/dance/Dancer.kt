package com.cardance.visualizer.dance

import com.cardance.visualizer.audio.Rhythm
import com.cardance.visualizer.util.approach
import com.cardance.visualizer.util.clamp01
import com.cardance.visualizer.util.easeInOutQuad
import com.cardance.visualizer.util.lerp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The three silhouette looks, matching the approved design mockup. */
enum class HairStyle(val label: String) {
    STRAIGHT("长直发"),
    PONYTAIL("单马尾"),
    WAVES("大波浪")
}

/**
 * The three stages of a KOF-style finishing move.
 *
 * `CHARGE` pulls the body down and gathers light inward, `FLASH` is the
 * one-frame white-out, `BURST` is the release: the dancer grows, throws
 * radial light and slams a shockwave into the floor.
 */
enum class SpecialPhase(val label: String) {
    NONE("普通"),
    CHARGE("蓄力"),
    FLASH("闪光"),
    BURST("爆发")
}

/**
 * One dancer: owns its look, its animation state, and how it reacts to the music.
 *
 * Movement comes from three independent layers so it never looks mechanical:
 * 1. **Pose interpolation** between keyframes, retargeted on every beat.
 * 2. **Idle motion** - a slow sine sway that continues even between beats.
 * 3. **Audio-reactive terms** - bounce from [Rhythm.beatPulse], size from
 *    [Rhythm.intensity].
 *
 * On top of that sits an optional **finishing move** ([SpecialPhase]). While a
 * move is running it takes the pose over completely and drives its own scale
 * curve, so only the dancer grows - the stage, the equaliser and the HUD stay
 * exactly where they are. That is what makes it read as "the dancer is
 * powering up" rather than "the whole screen is zooming in".
 */
class Dancer(
    val style: HairStyle,
    val color: Int,
    val centerRatioX: Float,
    val baseScale: Float,
    val allowOverflow: Boolean,
    val phase: Float
) {

    private var fromPose: Pose = DanceLibrary.POSES[0]
    private var toPose: Pose = fromPose
    private var poseT = 1f
    private var timeSec = 0f

    /** Current whole-body sway in degrees. The renderer reuses it for hair motion. */
    var sway: Float = 0f
        private set

    /** Vertical hop as a fraction of figure height (+ = upward on screen handled by caller). */
    var bounceRatio: Float = 0f
        private set

    /**
     * Live music energy, 0..1.
     *
     * The renderer uses it to open the gap between the double contour lines and
     * to widen the hair ripple, so the drawing itself gets more intense when
     * the track does - not just the colour.
     */
    var energy: Float = 0f
        private set

    /** Live scale multiplier, including chorus growth. */
    var scaleNow: Float = baseScale
        private set

    /** Horizontal push outward, in px. Only nonzero for [allowOverflow] dancers. */
    var outwardShift: Float = 0f
        private set

    /** Which stage of the finishing move is running. */
    var special: SpecialPhase = SpecialPhase.NONE
        private set

    /** Progress inside the current [special] stage, 0..1. */
    var specialT: Float = 0f
        private set

    /**
     * 1 = full brightness. Other dancers dim down while somebody else is
     * performing, which focuses the eye on the one who is powering up.
     */
    var dim: Float = 1f
        private set

    private var specialClock = 0f

    private val poseDuration = 0.42f

    init {
        scaleNow = baseScale
    }

    /** Called once per detected beat. */
    fun onBeat(mode: DanceMode, rnd: Random) {
        fromPose = postureCore()
        toPose = DanceLibrary.nextPose(toPose, mode, rnd)
        poseT = 0f
    }

    /** Start a finishing move. Ignored if one is already running. */
    fun triggerSpecial() {
        if (special != SpecialPhase.NONE) return
        special = SpecialPhase.CHARGE
        specialClock = 0f
        specialT = 0f
    }

    /** Nudge the brightness toward [target] - used while another dancer performs. */
    fun applyDim(target: Float) {
        dim = approach(dim, target, 0.12f)
    }

    fun update(dtSec: Float, rhythm: Rhythm, screenWidth: Float) {
        timeSec += dtSec
        poseT = (poseT + dtSec / poseDuration).coerceAtMost(1f)

        val intensity = clamp01(rhythm.intensity)

        // ---- finishing move clock -------------------------------------------
        if (special != SpecialPhase.NONE) {
            specialClock += dtSec
            val chargeEnd = CHARGE_SEC
            val flashEnd = chargeEnd + FLASH_SEC
            val burstEnd = flashEnd + BURST_SEC
            special = when {
                specialClock >= burstEnd -> SpecialPhase.NONE
                specialClock >= flashEnd -> SpecialPhase.BURST
                specialClock >= chargeEnd -> SpecialPhase.FLASH
                else -> SpecialPhase.CHARGE
            }
            if (special == SpecialPhase.NONE) {
                specialClock = 0f
                specialT = 0f
            } else {
                val dur = when (special) {
                    SpecialPhase.CHARGE -> CHARGE_SEC
                    SpecialPhase.FLASH -> FLASH_SEC
                    SpecialPhase.BURST -> BURST_SEC
                    SpecialPhase.NONE -> 1f
                }
                specialT = (specialClock / dur).coerceIn(0f, 1f)
            }
        }

        // ---- scale ----------------------------------------------------------
        // Outside a finishing move the dancer only breathes with the music.
        // The big growth happens exclusively inside BURST, and only for the
        // dancer that is performing.
        val moveScale = when (special) {
            SpecialPhase.CHARGE -> lerp(1f, 0.90f, easeInOutQuad(specialT))
            SpecialPhase.FLASH -> lerp(0.90f, 1.05f, specialT)
            SpecialPhase.BURST -> {
                val rise = easeInOutQuad(min(1f, specialT * 3.4f))
                val settle = 1f - max(0f, specialT - 0.58f) / 0.42f * 0.20f
                lerp(1.05f, 1.52f, rise) * settle
            }
            SpecialPhase.NONE -> 1f
        }
        val targetScale = baseScale * (1f + intensity * 0.12f) * moveScale
        scaleNow = approach(scaleNow, targetScale, if (special == SpecialPhase.NONE) 0.10f else 0.34f)

        val targetShift =
            if (allowOverflow && rhythm.chorus && special == SpecialPhase.NONE) screenWidth * OVERFLOW_RATIO else 0f
        outwardShift = approach(outwardShift, targetShift, 0.06f)

        val beatBob = rhythm.beatPulse * BEAT_BOB
        val idleBob = (sin(timeSec * 2.1f + phase) * 0.5f + 0.5f) * IDLE_BOB
        bounceRatio = approach(bounceRatio, beatBob + idleBob, 0.22f)

        energy = approach(
            energy,
            (intensity * 0.72f + rhythm.beatPulse * 0.38f).coerceIn(0f, 1f),
            0.16f
        )
    }

    /** Pose without idle motion - used as the interpolation origin when retargeting. */
    fun postureCore(): Pose = Pose.blend(fromPose, toPose, easeInOutQuad(poseT))

    /** Pose with idle sway applied, plus the finishing-move override. */
    fun posture(): Pose {
        val core = postureCore()
        val base = core.copy(
            lean = core.lean + sway,
            headTilt = core.headTilt - sway * 0.6f
        )
        return when (special) {
            SpecialPhase.CHARGE ->
                Pose.blend(base, DanceLibrary.CHARGE_POSE, easeInOutQuad(specialT) * 0.88f)
            SpecialPhase.FLASH ->
                Pose.blend(base, DanceLibrary.BURST_POSE, 0.45f)
            SpecialPhase.BURST ->
                Pose.blend(base, DanceLibrary.BURST_POSE, min(1f, specialT * 3.2f) * 0.92f)
            SpecialPhase.NONE -> base
        }
    }

    fun buildFigure(heightPx: Float): Figure = Kinematics.build(posture(), heightPx)

    companion object {
        private const val OVERFLOW_RATIO = 0.065f   // pushes the edge dancers off-frame
        private const val BEAT_BOB = 0.055f
        private const val IDLE_BOB = 0.018f
        private const val IDLE_SWAY = 7f

        const val CHARGE_SEC = 0.52f
        const val FLASH_SEC = 0.13f
        const val BURST_SEC = 0.95f
    }
}
