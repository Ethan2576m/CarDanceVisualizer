package com.cardance.visualizer.audio

import com.cardance.visualizer.util.approach
import com.cardance.visualizer.util.clamp01
import kotlin.math.sqrt

/**
 * Turns a stream of audio feature frames into musical events the dancers can follow:
 *
 * - **beat**: variance-adaptive onset detection on the bass band.
 * - **intensity**: smoothed loudness, drives how hard they dance.
 * - **chorus**: sustained energy above the long-term average; triggers the "break
 *   out of the screen" phase.
 * - **bpm**: rolling estimate from beat intervals (display only).
 *
 * All state lives here and is fed from whichever thread the [Visualizer] callback
 * uses; only primitives are published, and [consumeBeat] is polled from the UI thread.
 */
class BeatDetector {

    private val history = FloatArray(HISTORY)
    private var histIdx = 0
    private var histCount = 0

    private var lastBeatMs = 0L
    private var haveLastBeat = false
    private val intervals = FloatArray(INTERVAL_SLOTS)
    private var intervalIdx = 0
    private var intervalCount = 0

    private var bassSmoothed = 0f
    private var energySmoothed = 0f
    private var longEnergy = 0f
    private var intensitySmooth = 0f
    private var pulse = 0f

    private var chorusScore = 0
    private var pendingBeat = false

    fun reset() {
        histIdx = 0
        histCount = 0
        lastBeatMs = 0L
        haveLastBeat = false
        intervalIdx = 0
        intervalCount = 0
        bassSmoothed = 0f
        energySmoothed = 0f
        longEnergy = 0f
        intensitySmooth = 0f
        pulse = 0f
        chorusScore = 0
        pendingBeat = false
    }

    fun feed(frame: RawFrame, nowMs: Long): Rhythm {
        val bass = clamp01(frame.bass)
        val energy = clamp01(frame.energy)

        bassSmoothed = approach(bassSmoothed, bass, 0.35f)
        energySmoothed = approach(energySmoothed, energy, 0.22f)
        // Very slow tracker: acts as the "what does this song normally sound like" baseline.
        longEnergy = approach(longEnergy, energy, 0.010f)

        if (!frame.silent) {
            history[histIdx] = bass
            histIdx = (histIdx + 1) % HISTORY
            if (histCount < HISTORY) histCount++
        }

        detectBeat(frame, nowMs)
        updateChorus()

        pulse = approach(pulse, 0f, PULSE_DECAY)

        val targetIntensity = clamp01(energy * 0.75f + pulse * 0.45f)
        intensitySmooth = approach(intensitySmooth, targetIntensity, 0.25f)

        return Rhythm(
            spectrum = frame.spectrum,
            energy = energySmoothed,
            bass = bassSmoothed,
            beatPulse = pulse,
            intensity = intensitySmooth,
            chorus = chorusScore > CHORUS_HOLD,
            bpm = estimatedBpm(),
            silent = frame.silent
        )
    }

    /** True exactly once per detected beat. Call from the rendering thread. */
    fun consumeBeat(): Boolean {
        if (!pendingBeat) return false
        pendingBeat = false
        return true
    }

    private fun detectBeat(frame: RawFrame, nowMs: Long) {
        if (histCount < MIN_HISTORY) return

        val mean = meanOfHistory()
        val std = stdOfHistory()

        // Quiet passages need a bigger relative jump to be read as a hit.
        val sensitivity = when {
            std > 0.10f -> 1.10f
            std > 0.05f -> 1.22f
            else -> 1.45f
        }
        val threshold = mean * sensitivity + 0.012f

        if (frame.silent || frame.bass < MIN_BASS || frame.bass <= threshold) return

        val since = if (haveLastBeat) nowMs - lastBeatMs else Long.MAX_VALUE
        if (since < MIN_INTERVAL_MS) return

        lastBeatMs = nowMs
        haveLastBeat = true
        pulse = 1f
        pendingBeat = true

        if (since in MIN_INTERVAL_MS until MAX_USEFUL_INTERVAL_MS) {
            intervals[intervalIdx] = since.toFloat()
            intervalIdx = (intervalIdx + 1) % INTERVAL_SLOTS
            if (intervalCount < INTERVAL_SLOTS) intervalCount++
        }
    }

    private fun updateChorus() {
        val ratio = if (longEnergy > 0.03f) energySmoothed / longEnergy else 1f
        val candidate = ratio > CHORUS_RATIO && energySmoothed > CHORUS_MIN_ENERGY
        chorusScore = if (candidate) {
            (chorusScore + 1).coerceAtMost(CHORUS_MAX)
        } else {
            (chorusScore - 1).coerceAtLeast(0)
        }
    }

    private fun estimatedBpm(): Float {
        if (intervalCount == 0) return 0f
        var sum = 0f
        for (i in 0 until intervalCount) sum += intervals[i]
        val meanMs = sum / intervalCount
        if (meanMs <= 0f) return 0f
        val bpm = 60_000f / meanMs
        return bpm.coerceIn(MIN_BPM, MAX_BPM)
    }

    private fun meanOfHistory(): Float {
        var sum = 0f
        for (i in 0 until histCount) sum += history[i]
        return sum / histCount
    }

    private fun stdOfHistory(): Float {
        val mean = meanOfHistory()
        var acc = 0f
        for (i in 0 until histCount) {
            val d = history[i] - mean
            acc += d * d
        }
        return sqrt(acc / histCount)
    }

    companion object {
        private const val HISTORY = 43           // ~2s at a typical 20-30Hz capture rate
        private const val MIN_HISTORY = 8
        private const val INTERVAL_SLOTS = 12
        private const val MIN_INTERVAL_MS = 190L  // caps us at ~315 BPM
        private const val MAX_USEFUL_INTERVAL_MS = 2_000L
        private const val MIN_BASS = 0.09f
        private const val PULSE_DECAY = 0.17f

        private const val CHORUS_RATIO = 1.30f
        private const val CHORUS_MIN_ENERGY = 0.16f
        private const val CHORUS_HOLD = 30        // sustained for ~1.5s before we call it a chorus
        private const val CHORUS_MAX = 60

        private const val MIN_BPM = 55f
        private const val MAX_BPM = 190f
    }
}
