package com.cardance.visualizer.dance

import android.graphics.PointF
import com.cardance.visualizer.util.DEG_TO_RAD
import com.cardance.visualizer.util.lerp
import com.cardance.visualizer.util.lerpAngle
import kotlin.math.cos
import kotlin.math.sin

/**
 * One dance posture.
 *
 * Every angle is **degrees measured from straight-down**, increasing clockwise
 * toward the screen right:
 * `0` = down, `90` = right, `180` = up, `270` = left.
 *
 * So an arm raised up-and-out to the left is around `215`, a leg kicked out to the
 * right around `80`. This makes poses readable when authored by hand.
 */
data class Pose(
    val lean: Float = 0f,          // whole-torso tilt, +ve leans right
    val headTilt: Float = 0f,
    val crouch: Float = 0f,        // 0..1 squat / pop amount
    val shoulderL: Float = 215f,
    val elbowL: Float = 228f,
    val shoulderR: Float = 145f,
    val elbowR: Float = 132f,
    val hipL: Float = 335f,
    val kneeL: Float = 350f,
    val hipR: Float = 25f,
    val kneeR: Float = 10f
) {
    companion object {

        fun blend(a: Pose, b: Pose, t: Float): Pose = Pose(
            lean = lerp(a.lean, b.lean, t),
            headTilt = lerp(a.headTilt, b.headTilt, t),
            crouch = lerp(a.crouch, b.crouch, t),
            shoulderL = lerpAngle(a.shoulderL, b.shoulderL, t),
            elbowL = lerpAngle(a.elbowL, b.elbowL, t),
            shoulderR = lerpAngle(a.shoulderR, b.shoulderR, t),
            elbowR = lerpAngle(a.elbowR, b.elbowR, t),
            hipL = lerpAngle(a.hipL, b.hipL, t),
            kneeL = lerpAngle(a.kneeL, b.kneeL, t),
            hipR = lerpAngle(a.hipR, b.hipR, t),
            kneeR = lerpAngle(a.kneeR, b.kneeR, t)
        )

        /** Small organic perturbation so "generated" poses never look like clones. */
        fun jitter(pose: Pose, amount: Float, r: kotlin.random.Random): Pose = Pose(
            lean = pose.lean + (r.nextFloat() - 0.5f) * 16f * amount,
            headTilt = pose.headTilt + (r.nextFloat() - 0.5f) * 22f * amount,
            crouch = (pose.crouch + (r.nextFloat() - 0.5f) * 0.35f * amount).coerceIn(0f, 1f),
            shoulderL = pose.shoulderL + (r.nextFloat() - 0.5f) * 40f * amount,
            elbowL = pose.elbowL + (r.nextFloat() - 0.5f) * 45f * amount,
            shoulderR = pose.shoulderR + (r.nextFloat() - 0.5f) * 40f * amount,
            elbowR = pose.elbowR + (r.nextFloat() - 0.5f) * 45f * amount,
            hipL = pose.hipL + (r.nextFloat() - 0.5f) * 26f * amount,
            kneeL = pose.kneeL + (r.nextFloat() - 0.5f) * 26f * amount,
            hipR = pose.hipR + (r.nextFloat() - 0.5f) * 26f * amount,
            kneeR = pose.kneeR + (r.nextFloat() - 0.5f) * 26f * amount
        )
    }
}

/**
 * Resolved joint positions in **figure-local pixels**, origin at the pelvis,
 * +y downward. Heights are derived from the requested figure height [h].
 *
 * The proportions follow a young woman's build rather than a generic stick
 * figure: a small head, long legs, shoulders a touch wider than the chest, a
 * strongly defined waist and hips just wider than the shoulders - roughly
 * 7 heads tall, with shoulder : chest : waist : hip = 1.06 : 1 : 0.70 : 1.08.
 */
data class Figure(
    val pelvis: PointF,
    val chest: PointF,
    val neck: PointF,
    val head: PointF,
    val headRadius: Float,
    val shoulderHalf: Float,
    val chestHalf: Float,
    val waistHalf: Float,
    val hipHalf: Float,
    val shoulderL: PointF,
    val shoulderR: PointF,
    val hipL: PointF,
    val hipR: PointF,
    val elbowL: PointF,
    val handL: PointF,
    val elbowR: PointF,
    val handR: PointF,
    val kneeL: PointF,
    val ankleL: PointF,
    val footL: PointF,
    val kneeR: PointF,
    val ankleR: PointF,
    val footR: PointF
)

object Kinematics {

    // Segment lengths as a fraction of total figure height.  Retuned to ~7
    // heads (was ~6.4, which read as a stylised croquis rather than a person).
    private const val TORSO = 0.282f
    // Chest -> centre of the head. Long enough that a real neck shows between
    // the collarbones and the chin (about a quarter of a head height).
    private const val NECK = 0.078f
    private const val HEAD_R = 0.067f
    private const val UPPER_ARM = 0.140f
    private const val FOREARM = 0.130f
    private const val THIGH = 0.248f
    private const val SHIN = 0.240f
    private const val FOOT = 0.048f
    private const val SHOULDER_HALF = 0.080f
    private const val CHEST_HALF = 0.076f
    private const val HIP_HALF = 0.082f
    // Narrower than both chest and hips -> a defined waist.
    private const val WAIST_HALF = 0.053f

    fun point(from: PointF, length: Float, angleDeg: Float): PointF {
        val a = angleDeg * DEG_TO_RAD
        return PointF(from.x + sin(a) * length, from.y + cos(a) * length)
    }

    fun build(pose: Pose, h: Float): Figure {
        val up = 180f + pose.lean
        val leftAng = up + 90f     // toward screen-left, rotated with the body
        val rightAng = up - 90f

        val torsoLen = TORSO * (1f - pose.crouch * 0.16f) * h
        val pelvis = PointF(0f, 0f)
        val chest = point(pelvis, torsoLen, up)

        val shoulderL = point(chest, SHOULDER_HALF * h, leftAng)
        val shoulderR = point(chest, SHOULDER_HALF * h, rightAng)
        val hipL = point(pelvis, HIP_HALF * h, leftAng)
        val hipR = point(pelvis, HIP_HALF * h, rightAng)

        val neck = point(chest, NECK * h * 0.20f, up + pose.headTilt)
        val head = point(chest, NECK * h, up + pose.headTilt)
        val elbowL = point(shoulderL, UPPER_ARM * h, pose.shoulderL)
        val handL = point(elbowL, FOREARM * h, pose.elbowL)
        val elbowR = point(shoulderR, UPPER_ARM * h, pose.shoulderR)
        val handR = point(elbowR, FOREARM * h, pose.elbowR)

        // A squat reads as extra knee bend; feet lifting off the floor line is fine
        // because the stage is abstract - it just looks like a jump.
        val extraBend = pose.crouch * 26f
        val kneeL = point(hipL, THIGH * h, pose.hipL)
        val kneeR = point(hipR, THIGH * h, pose.hipR)
        val ankleL = point(kneeL, SHIN * h * 0.90f, pose.kneeL + extraBend)
        val ankleR = point(kneeR, SHIN * h * 0.90f, pose.kneeR + extraBend)
        // The foot points down from the ankle - a flat sole, not an angled hoof.
        val footL = point(ankleL, FOOT * h, 0f)
        val footR = point(ankleR, FOOT * h, 0f)

        return Figure(
            pelvis = pelvis,
            chest = chest,
            neck = neck,
            head = head,
            headRadius = HEAD_R * h,
            shoulderHalf = SHOULDER_HALF * h,
            chestHalf = CHEST_HALF * h,
            waistHalf = WAIST_HALF * h,
            hipHalf = HIP_HALF * h,
            shoulderL = shoulderL,
            shoulderR = shoulderR,
            hipL = hipL,
            hipR = hipR,
            elbowL = elbowL,
            handL = handL,
            elbowR = elbowR,
            handR = handR,
            kneeL = kneeL,
            ankleL = ankleL,
            footL = footL,
            kneeR = kneeR,
            ankleR = ankleR,
            footR = footR
        )
    }
}
