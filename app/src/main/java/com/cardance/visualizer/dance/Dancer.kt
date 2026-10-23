package com.cardance.visualizer.dance

import com.cardance.visualizer.audio.Rhythm
import com.cardance.visualizer.util.approach
import com.cardance.visualizer.util.clamp01
import com.cardance.visualizer.util.easeInOutQuad
import kotlin.math.sin
import kotlin.random.Random

/** The three silhouette looks, matching the approved design mockup. */
enum class HairStyle(val label: String) {
    STRAIGHT("长直发"),
    PONYTAIL("单马尾"),
    WAVES("大波浪")
}

/**
 * One dancer: owns its look, its animation state, and how it reacts to the music.
 *
 * Movement comes from three independent layers so it never looks mechanical:
 * 1. **Pose interpolation** between keyframes, retargeted on every beat.
 * 2. **Idle motion** - a slow sine sway that continues even between beats.
 * 3. **Audio-reactive terms** - bounce from [Rhythm.beatPulse], size from
 *    [Rhythm.intensity], and in a chorus the edge dancers push outward past the
 *    screen edge (the "break the frame" effect).
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

    fun update(dtSec: Float, rhythm: Rhythm, screenWidth: Float) {
        timeSec += dtSec
        poseT = (poseT + dtSec / poseDuration).coerceAtMost(1f)

        val intensity = clamp01(rhythm.intensity)
        val chorusKick = if (allowOverflow && rhythm.chorus) CHORUS_GROWTH else 0f
        val targetScale = baseScale * (1f + intensity * INTENSITY_GROWTH + chorusKick)
        scaleNow = approach(scaleNow, targetScale, 0.10f)

        val targetShift =
            if (allowOverflow && rhythm.chorus) screenWidth * OVERFLOW_RATIO else 0f
        outwardShift = approach(outwardShift, targetShift, 0.06f)

        val beatBob = rhythm.beatPulse * BEAT_BOB
        val idleBob = (sin(timeSec * 2.1f + phase) * 0.5f + 0.5f) * IDLE_BOB
        bounceRatio = approach(bounceRatio, beatBob + idleBob, 0.22f)

        energy = approach(
            energy,
            (intensity * 0.72f + rhythm.beatPulse * 0.38f).coerceIn(0f, 1f),
            0.16f
        )

        val targetSway = sin(timeSec * 1.35f + phase) * IDLE_SWAY +
            rhythm.beatPulse * IDLE_SWAY * 0.8f
        sway = approach(sway, targetSway, 0.18f)
    }

    /** Pose without idle motion - used as the interpolation origin when retargeting. */
    fun postureCore(): Pose = Pose.blend(fromPose, toPose, easeInOutQuad(poseT))

    /** Pose with idle sway applied. This is what actually gets drawn. */
    fun posture(): Pose {
        val core = postureCore()
        return core.copy(
            lean = core.lean + sway,
            headTilt = core.headTilt - sway * 0.6f
        )
    }

    fun buildFigure(heightPx: Float): Figure = Kinematics.build(posture(), heightPx)

    companion object {
        private const val INTENSITY_GROWTH = 0.34f
        private const val CHORUS_GROWTH = 0.42f
        private const val OVERFLOW_RATIO = 0.065f   // pushes the edge dancers off-frame
        private const val BEAT_BOB = 0.055f
        private const val IDLE_BOB = 0.018f
        private const val IDLE_SWAY = 7f
    }
}
