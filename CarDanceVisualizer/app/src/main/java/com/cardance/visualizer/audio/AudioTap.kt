package com.cardance.visualizer.audio

import android.media.audiofx.Visualizer
import android.util.Log
import com.cardance.visualizer.util.clamp01
import kotlin.math.hypot

/**
 * A single frame of audio features extracted from the Visualizer FFT.
 *
 * [spectrum] is `BANDS` wide and log-ish binned so low frequencies (kick / bass)
 * do not dominate the whole picture.
 */
data class RawFrame(
    val spectrum: FloatArray,
    val energy: Float,
    val bass: Float,
    val silent: Boolean
) {
    companion object {
        const val BANDS = 48
    }
}

/**
 * Everything the renderer needs, derived from the raw frames.
 */
data class Rhythm(
    val spectrum: FloatArray = FloatArray(RawFrame.BANDS),
    val energy: Float = 0f,
    val bass: Float = 0f,
    val beatPulse: Float = 0f,
    val intensity: Float = 0f,
    val chorus: Boolean = false,
    val bpm: Float = 0f,
    val silent: Boolean = true
)

/**
 * Wraps [Visualizer] so we can tap the audio actually reaching the speakers.
 *
 * Two operating modes:
 * - **session 0** (default): taps the *mixed* output, so anything the head unit
 *   plays is visualised — including music streamed from a phone over Bluetooth.
 *   Whether a given head unit exposes this to third-party apps varies; if we keep
 *   getting silence the caller can fall back to local playback (see [AudioMode]).
 * - **specific session**: the app plays the file itself and we attach to that
 *   MediaPlayer session. Always works, no permissions beyond Visualizer's.
 */
class AudioTap {

    enum class AudioMode { MIXED_OUTPUT, LOCAL_SESSION }

    private var visualizer: Visualizer? = null
    private val detector = BeatDetector()

    @Volatile
    var rhythm: Rhythm = Rhythm()
        private set

    /** Increments on every detected beat so the UI thread can react exactly once. */
    @Volatile
    var beatCount: Long = 0
        private set

    @Volatile
    var mode: AudioMode = AudioMode.MIXED_OUTPUT
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** Consecutive seconds of silence seen since start; used to detect a dead tap. */
    @Volatile
    var silentSeconds: Long = 0
        private set

    var onError: ((String) -> Unit)? = null

    /** Timestamp (ms) of the last frame that carried audible signal. */
    private var lastSoundMs = 0L

    /**
     * Start tapping. Pass `0` to tap the mixed output, or a MediaPlayer
     * `audioSessionId` to tap only that player.
     */
    @Synchronized
    fun start(sessionId: Int): Boolean {
        stop()
        mode = if (sessionId == 0) AudioMode.MIXED_OUTPUT else AudioMode.LOCAL_SESSION
        return try {
            val viz = Visualizer(sessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, bytes: ByteArray, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) {
                        val raw = analyse(fft)
                        val now = System.currentTimeMillis()
                        val next = detector.feed(raw, now)
                        if (detector.consumeBeat()) beatCount++
                        rhythm = next
                        if (!raw.silent) lastSoundMs = now
                        silentSeconds = if (lastSoundMs == 0L) {
                            NEVER_HEARD_AUDIO
                        } else {
                            (now - lastSoundMs) / 1000L
                        }
                    }
                },
                Visualizer.getMaxCaptureRate(),
                false,          // waveform not needed
                true            // FFT
            )
            viz.enabled = true
            visualizer = viz
            running = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizer(session=$sessionId) unavailable", t)
            onError?.invoke(t.message ?: "Visualizer unavailable")
            running = false
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        }
        visualizer = null
        detector.reset()
        rhythm = Rhythm()
        beatCount = 0
        silentSeconds = 0
        lastSoundMs = 0L
    }

    /**
     * Convert Visualizer's packed FFT bytes into normalised bands.
     *
     * Visualizer returns an interleaved complex spectrum; the exact packing of the
     * first/last byte differs slightly from a textbook FFT, but for *visualisation*
     * the magnitude-per-bin approximation is indistinguishable and far cheaper.
     */
    private fun analyse(fft: ByteArray): RawFrame {
        val bins = fft.size / 2
        if (bins <= 1) return RawFrame(FloatArray(RawFrame.BANDS), 0f, 0f, true)

        val magnitudes = FloatArray(bins)
        var peak = 0f
        for (i in 0 until bins) {
            val re = fft[i * 2].toFloat()
            val im = fft[i * 2 + 1].toFloat()
            val m = hypot(re, im)
            magnitudes[i] = m
            if (m > peak) peak = m
        }

        val norm = if (peak > 1f) peak else 1f
        val bands = FloatArray(RawFrame.BANDS)
        var total = 0f
        for (b in 0 until RawFrame.BANDS) {
            // Log-ish band edges so bass does not swamp the display.
            val lo = (bins * b.toFloat() / RawFrame.BANDS).toInt()
            val hi = (bins * (b + 1).toFloat() / RawFrame.BANDS).toInt().coerceAtMost(bins)
            var sum = 0f
            var count = 0
            for (i in lo until hi) {
                sum += magnitudes[i]
                count++
            }
            val v = if (count > 0) clamp01((sum / count) / norm) else 0f
            bands[b] = v
            total += v
        }

        val energy = clamp01(total / RawFrame.BANDS)
        // First ~6 bands cover the kick/bass region for this bin layout.
        var bassSum = 0f
        var bassCount = 0
        val bassBands = (RawFrame.BANDS * 0.16f).toInt().coerceAtLeast(1)
        for (i in 0 until bassBands) {
            bassSum += bands[i]
            bassCount++
        }
        val bass = if (bassCount > 0) bassSum / bassCount else 0f
        val silent = energy < 0.004f
        return RawFrame(bands, energy, bass, silent)
    }

    companion object {
        private const val TAG = "AudioTap"
        /** Sentinel meaning "no audio has been heard on this tap at all yet". */
        private const val NEVER_HEARD_AUDIO = 1_000_000L
    }
}
