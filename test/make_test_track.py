#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成用于验证「律动舞蹈」App 的测试音轨 test_track.wav

设计目标（对齐 app 的 BeatDetector 判定逻辑）：
  * 强底鼓落在低频带（60-120 Hz）→ 触发 onset / 节拍检测
  * 段落能量分层：intro(弱) → verse(中) → chorus(强) → break(弱)
    副歌段能量持续高于长期均值 > 1.30 倍 → 触发 chorus 状态与破框动作
  * 32 秒 / 120 BPM / 4-4 拍，够跑完一轮完整的"起跳-平推-爆发-收束"

用法：python make_test_track.py [输出路径]
"""
import sys
import wave
import struct
import numpy as np

SR = 44100
BPM = 120.0
BEAT = 60.0 / BPM          # 0.5s
BAR = BEAT * 4             # 2.0s
BARS = 16                 # 共 32 秒
TOTAL = BAR * BARS
N = int(SR * TOTAL)

rng = np.random.default_rng(20261023)
out = np.zeros(N, dtype=np.float64)

# 段落划分（bar 索引，闭开区间）
SECTIONS = [
    (0, 4, "intro", 0.22),    # 0-8s   弱
    (4, 8, "verse", 0.45),    # 8-16s  中
    (8, 12, "chorus", 1.00),  # 16-24s 强（副歌）
    (12, 16, "break", 0.30),  # 24-32s 收束
]
NOTE = {"C": 261.63, "E": 329.63, "G": 392.00, "A": 440.00, "D": 293.66, "F": 349.23}


def section_gain(bar_idx):
    for b0, b1, name, g in SECTIONS:
        if b0 <= bar_idx < b1:
            return g, name
    return 0.3, "verse"


def add_kick(t0, gain):
    """底鼓：120Hz 指数下滑到 45Hz，指数衰减"""
    dur = 0.26
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = 45.0 + 85.0 * np.exp(-t * 28.0)
    phase = 2 * np.pi * np.cumsum(f) / SR
    env = np.exp(-t * 13.0)
    i0 = int(t0 * SR)
    seg = np.sin(phase) * env * gain * 0.95
    out[i0:i0 + n] += seg[: max(0, len(out) - i0)]


def add_snare(t0, gain):
    """军鼓：白噪声 + 190Hz 音体"""
    dur = 0.16
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    hp = np.diff(noise, prepend=0.0)          # 简易高通
    env = np.exp(-t * 24.0)
    seg = (hp * 0.55 + np.sin(2 * np.pi * 190 * t) * 0.35) * env * gain * 0.6
    i0 = int(t0 * SR)
    out[i0:i0 + n] += seg[: max(0, len(out) - i0)]


def add_hat(t0, gain):
    """踩镲：极短高通噪声"""
    dur = 0.05
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    hp = np.diff(noise, prepend=0.0)
    env = np.exp(-t * 70.0)
    seg = hp * env * gain * 0.22
    i0 = int(t0 * SR)
    out[i0:i0 + n] += seg[: max(0, len(out) - i0)]


def add_bass(t0, dur, freq, gain):
    """低音：基波 + 二次谐波，ADSR 简化"""
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.minimum(1.0, t / 0.02) * np.exp(-t * 2.2)
    wave_f = (np.sin(2 * np.pi * freq * t)
              + 0.32 * np.sin(4 * np.pi * freq * t)
              + 0.12 * np.sin(6 * np.pi * freq * t))
    seg = wave_f * env * gain * 0.5
    i0 = int(t0 * SR)
    out[i0:i0 + n] += seg[: max(0, len(out) - i0)]


def add_lead(t0, dur, freq, gain):
    """主旋律：带方波感的谐波叠加（副歌高光）"""
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.minimum(1.0, t / 0.015) * np.exp(-t * 1.6)
    w = (np.sin(2 * np.pi * freq * t)
         + 0.4 * np.sin(4 * np.pi * freq * t)
         + 0.25 * np.sin(6 * np.pi * freq * t)
         + 0.12 * np.sin(8 * np.pi * freq * t))
    seg = w * env * gain * 0.3
    i0 = int(t0 * SR)
    out[i0:i0 + n] += seg[: max(0, len(out) - i0)]


def add_pad(t0, dur, freqs, gain):
    """铺底和弦"""
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.minimum(1.0, t / 0.25) * np.minimum(1.0, (dur - t) / 0.25)
    seg = np.zeros(n)
    for f in freqs:
        seg += np.sin(2 * np.pi * f * t) * 0.33
    seg *= env * gain * 0.18
    i0 = int(t0 * SR)
    out[i0:i0 + n] += seg[: max(0, len(out) - i0)]


BASS_LINE = [NOTE["A"], NOTE["A"], NOTE["F"], NOTE["F"], NOTE["C"], NOTE["C"], NOTE["G"], NOTE["G"]]
LEAD_LINE = [NOTE["E"], NOTE["G"], NOTE["A"], NOTE["G"], NOTE["E"], NOTE["D"], NOTE["C"], NOTE["D"]]

for bar in range(BARS):
    g, name = section_gain(bar)
    t_bar = bar * BAR
    beat_in_bar = bar % 8

    # 铺底和弦：每小节换一次
    chord_root = BASS_LINE[beat_in_bar % len(BASS_LINE)]
    add_pad(t_bar, BAR, [chord_root, chord_root * 1.26, chord_root * 1.5], g * (1.0 if name != "intro" else 0.6))

    for b in range(4):
        t = t_bar + b * BEAT
        # 底鼓：intro 弱拍为主，其余四踩
        if name == "intro":
            if b in (0, 2):
                add_kick(t, g * 0.9)
        else:
            add_kick(t, g * 1.05)
            if b % 2 == 1:
                add_hat(t, g)
        if name in ("verse", "chorus") and b in (1, 3):
            add_snare(t, g)
        if name == "chorus":
            add_hat(t + BEAT * 0.5, g * 0.8)

    # 低音：intro 稀疏，其余每小节走两个八分
    if name != "intro":
        for b in (0, 2):
            add_bass(t_bar + b * BEAT, BEAT * 1.8, chord_root / 2.0, g)

    # 主旋律：只在副歌
    if name == "chorus":
        for b in (0, 1, 2, 3):
            add_lead(t_bar + b * BEAT, BEAT * 0.9, LEAD_LINE[(beat_in_bar * 4 + b) % len(LEAD_LINE)], g)

# 结尾一记重击，收束干净
add_kick(TOTAL - 0.6, 1.0)
add_snare(TOTAL - 0.5, 0.8)

# 归一化 + 轻限幅
peak = np.max(np.abs(out))
if peak > 0:
    out = out / peak * 0.92
out = np.tanh(out * 1.08) * 0.94

# 立体声宽度：左右做极小延时差，模拟真实录音
delay = int(0.006 * SR)
left = out
right = np.concatenate([np.zeros(delay), out[:-delay]]) * 0.98
stereo = np.stack([left, right], axis=1)
stereo = np.clip(stereo, -1.0, 1.0)

pcm = (stereo * 32767).astype(np.int16)

out_path = sys.argv[1] if len(sys.argv) > 1 else "test_track.wav"
with wave.open(out_path, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())

# 简单统计，方便核对分段能量
print("已生成:", out_path)
print("时长: %.1f 秒  BPM: %.0f  采样率: %d" % (TOTAL, BPM, SR))
rms = np.sqrt(np.mean(stereo ** 2, axis=1))
for b0, b1, nm, _ in SECTIONS:
    seg = rms[int(b0 * BAR * SR):int(b1 * BAR * SR)]
    print("  段 %-6s %4.0f-%4.0fs  RMS=%.3f" % (nm, b0 * BAR, b1 * BAR, seg.mean()))
