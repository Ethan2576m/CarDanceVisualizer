package com.cardance.visualizer.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun clamp(v: Float, lo: Float, hi: Float): Float = v.coerceIn(lo, hi)

fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

fun easeInOutQuad(t: Float): Float {
    val x = clamp01(t)
    return if (x < 0.5f) {
        2f * x * x
    } else {
        val inv = -2f * x + 2f
        1f - (inv * inv) / 2f
    }
}

fun easeOutCubic(t: Float): Float {
    val inv = 1f - clamp01(t)
    return 1f - inv * inv * inv
}

/** Exponential-ish smoothing; [rate] is the fraction of the gap closed per call. */
fun approach(current: Float, target: Float, rate: Float): Float =
    current + (target - current) * clamp01(rate)

/** Shortest-path interpolation for angles given in degrees (handles wrap-around). */
fun lerpAngle(from: Float, to: Float, t: Float): Float {
    var delta = (to - from) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return from + delta * t
}

fun FloatArray.averageValue(): Float {
    if (isEmpty()) return 0f
    var sum = 0f
    for (v in this) sum += v
    return sum / size
}

fun FloatArray.stdDev(): Float {
    if (isEmpty()) return 0f
    val mean = averageValue()
    var acc = 0f
    for (v in this) {
        val d = v - mean
        acc += d * d
    }
    return sqrt(acc / size)
}

/** Cheap deterministic pseudo noise in [-1, 1] driven by time; used for organic sway. */
fun waveNoise(seed: Float, timeSec: Float): Float =
    ((cos(timeSec * 1.7f + seed) + cos(timeSec * 2.9f + seed * 1.7f)) * 0.25f)

const val DEG_TO_RAD: Float = (PI / 180.0).toFloat()
