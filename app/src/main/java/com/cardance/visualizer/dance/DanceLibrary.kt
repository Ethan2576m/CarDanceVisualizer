package com.cardance.visualizer.dance

import kotlin.random.Random

/**
 * How the next posture is chosen each beat:
 *
 * - [DanceMode.RANDOM]    - pick one of the authored keyframes outright. Repetitive
 *                           but predictable and always looks "clean".
 * - [DanceMode.GENERATED] - blend two keyframes at a random weight and perturb the
 *                           result, so every beat produces a posture that has not
 *                           been seen before.
 */
enum class DanceMode(val label: String) {
    RANDOM("随机"),
    GENERATED("AI生成")
}

object DanceLibrary {

    /**
     * Authored dance keyframes. Deliberately exaggerated - big extensions, kicks and
     * jumps - because at car-screen size subtlety is invisible.
     */
    val POSES = listOf(
        // 双臂展开·腾跃开场
        Pose(
            lean = 0f, headTilt = 0f, crouch = 0f,
            shoulderL = 215f, elbowL = 230f,
            shoulderR = 145f, elbowR = 130f,
            hipL = 335f, kneeL = 350f,
            hipR = 25f, kneeR = 10f
        ),
        // 右侧高踢腿
        Pose(
            lean = 10f, headTilt = -6f, crouch = 0f,
            shoulderL = 250f, elbowL = 268f,
            shoulderR = 95f, elbowR = 78f,
            hipL = 356f, kneeL = 358f,
            hipR = 82f, kneeR = 88f
        ),
        // 左侧高踢腿（镜像）
        Pose(
            lean = -10f, headTilt = 6f, crouch = 0f,
            shoulderL = 265f, elbowL = 282f,
            shoulderR = 110f, elbowR = 92f,
            hipL = 278f, kneeL = 272f,
            hipR = 4f, kneeR = 2f
        ),
        // 扭胯甩头
        Pose(
            lean = -14f, headTilt = 14f, crouch = 0.15f,
            shoulderL = 196f, elbowL = 252f,
            shoulderR = 300f, elbowR = 332f,
            hipL = 18f, kneeL = 8f,
            hipR = 6f, kneeR = 22f
        ),
        // 波浪下腰
        Pose(
            lean = 6f, headTilt = -18f, crouch = 0.1f,
            shoulderL = 188f, elbowL = 248f,
            shoulderR = 172f, elbowR = 112f,
            hipL = 344f, kneeL = 28f,
            hipR = 14f, kneeR = 348f
        ),
        // 蹲身弹起
        Pose(
            lean = 0f, headTilt = 0f, crouch = 0.65f,
            shoulderL = 232f, elbowL = 192f,
            shoulderR = 128f, elbowR = 168f,
            hipL = 340f, kneeL = 18f,
            hipR = 20f, kneeR = 342f
        ),
        // 阿拉贝斯克后抬腿
        Pose(
            lean = -20f, headTilt = 8f, crouch = 0f,
            shoulderL = 198f, elbowL = 152f,
            shoulderR = 330f, elbowR = 352f,
            hipL = 2f, kneeL = 0f,
            hipR = 300f, kneeR = 292f
        ),
        // 大开马步双臂平展
        Pose(
            lean = 0f, headTilt = 0f, crouch = 0.2f,
            shoulderL = 266f, elbowL = 272f,
            shoulderR = 94f, elbowR = 88f,
            hipL = 338f, kneeL = 348f,
            hipR = 22f, kneeR = 12f
        ),
        //腾跃
        Pose(
            lean = 0f, headTilt = -10f, crouch = 0f,
            shoulderL = 208f, elbowL = 196f,
            shoulderR = 152f, elbowR = 164f,
            hipL = 320f, kneeL = 300f,
            hipR = 40f, kneeR = 60f
        )
    )

    fun nextPose(current: Pose, mode: DanceMode, rnd: Random): Pose = when (mode) {
        DanceMode.RANDOM -> {
            // Avoid repeating the same shape twice in a row.
            var p = POSES[rnd.nextInt(POSES.size)]
            var guard = 0
            while (p == current && guard < 4) {
                p = POSES[rnd.nextInt(POSES.size)]
                guard++
            }
            p
        }
        DanceMode.GENERATED -> {
            val a = POSES[rnd.nextInt(POSES.size)]
            val b = POSES[rnd.nextInt(POSES.size)]
            val t = 0.25f + rnd.nextFloat() * 0.5f
            Pose.jitter(Pose.blend(a, b, t), 0.55f, rnd)
        }
    }
}
