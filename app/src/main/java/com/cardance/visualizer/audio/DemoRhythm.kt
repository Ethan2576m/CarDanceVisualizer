package com.cardance.visualizer.audio

import com.cardance.visualizer.util.clamp01
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 合成节拍源 —— 「演示模式」专用。
 *
 * 为什么需要它：
 *  - 模拟器（AVD / 雷电 / MuMu）基本不开放 session=0 混音采集，真机上能不能采到
 *    也因 ROM 而异，采不到时整个画面是静止的，无法验证舞者动作。
 *  - 车机在没播音乐时同样需要一个「有生命」的待机画面。
 *
 * 合成规则刻意与 `test/test_track.wav` 对齐，方便把电脑上的音轨和屏幕表现一一对照：
 *  - 120 BPM，与测试音轨同速；
 *  - 16 秒一个循环，结构为 弱(0-4s) → 中(4-8s) → **副歌(8-12s)** → 收(12-16s)；
 *  - 副歌段额外加八分反拍，同时 `chorus = true`，用于点亮「副歌 · 律动爆发」标签。
 */
class DemoRhythm(private val bpm: Float = 120f) {

    private var timeSec = 0f
    private var nextBeatAt = 0f
    private var nextOffBeatAt = BEAT_OFF
    private var pulse = 0f
    private var energy = 0f
    private var intensity = 0f

    /** 与真实采集同义：节拍计数，供 UI 精确触发一次舞者动作。 */
    var beatCount: Long = 0L
        private set

    private val rnd = Random(20261023)
    private val bandJitter = FloatArray(RawFrame.BANDS) { rnd.nextFloat() }

    fun update(dt: Float): Rhythm {
        timeSec += dt
        val beatLen = 60f / bpm

        var fired = 0
        while (timeSec >= nextBeatAt) {
            nextBeatAt += beatLen
            beatCount++
            fired++
        }

        val chorusNow = inChorus()
        var off = 0
        if (chorusNow) {
            while (timeSec >= nextOffBeatAt) {
                nextOffBeatAt += beatLen * 0.5f
                off++
            }
        } else {
            nextOffBeatAt = timeSec + BEAT_OFF
        }

        if (fired > 0) pulse = 1f
        if (off > 0 && pulse < 0.6f) pulse = 0.6f
        pulse = (pulse - dt * 3.4f).coerceAtLeast(0f)

        // 段落包络：平滑逼近目标能量，避免跳变
        val target = when {
            cyclePos < 0.25f -> 0.34f
            cyclePos < 0.50f -> 0.58f
            cyclePos < 0.75f -> 1.00f
            else -> 0.45f
        }
        energy += (target - energy) * (1f - exp(-dt * 3.0f))
        intensity = clamp01(energy * (0.72f + pulse * 0.38f))

        val spec = FloatArray(RawFrame.BANDS)
        for (i in 0 until RawFrame.BANDS) {
            // 低频权重更高：与真实音乐的频谱观感一致
            val shape = exp(-i / 9f)
            val wobble = 0.5f + 0.5f * sin(timeSec * 3.1f + i * 0.55f)
            val v = clamp01(
                energy * (shape * 0.85f + bandJitter[i] * 0.30f) * (0.55f + 0.45f * wobble) +
                    pulse * shape * 0.35f
            )
            spec[i] = v
        }
        var bassSum = 0f
        val bassBands = (RawFrame.BANDS * 0.16f).toInt().coerceAtLeast(1)
        for (i in 0 until bassBands) bassSum += spec[i]

        return Rhythm(
            spectrum = spec,
            energy = energy,
            bass = bassSum / bassBands,
            beatPulse = pulse,
            intensity = intensity,
            chorus = chorusNow,
            bpm = bpm,
            silent = false
        )
    }

    private fun inChorus(): Boolean = cyclePos >= 0.50f && cyclePos < 0.75f

    /** 0..1 的循环位置，与 16 秒周期对应。 */
    private val cyclePos: Float get() = (timeSec % CYCLE_SECONDS) / CYCLE_SECONDS

    companion object {
        private const val CYCLE_SECONDS = 16f
        private const val BEAT_OFF = 0.25f
    }
}
