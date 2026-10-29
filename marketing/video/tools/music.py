"""
The music bed and the sound effects, made from scratch on the video's timeline (out/build/timeline.json), so nothing
is licensed and every hit lands on a cut. Writes out/build/music.wav (stereo, 48 kHz): the music, ducked under the
voice, plus whooshes on scene changes and pops on zoom-ins. The app's own splash sound and the voice are mixed in by
build.py.

A warm C major groove at 100 BPM (I-V-vi-IV), starting on the beat after the splash's heartbeats: a pad, plucked
arpeggios, a round bass and soft drums, resolving to C on the end card.
"""
import functools
import json
import pathlib
import sys

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent.parent
BUILD = HERE / "out/build"
RATE = 48000
rng = np.random.default_rng(7)

timeline = json.loads((BUILD / "timeline.json").read_text())
BEAT = timeline["beat"]
LENGTH = timeline["length"]
START = timeline["musicStart"]
END_CARD = timeline["scenes"][-1]["start"]

left = np.zeros(int(RATE * (LENGTH + 1)))
right = np.zeros_like(left)


def at(seconds: float) -> int:
    return int(RATE * seconds)


def add(seconds: float, sound: np.ndarray, gain: float, pan: float = 0.0) -> None:
    """Mixes a mono [sound] in at [seconds], panned -1 (left) to 1 (right)."""
    i = at(seconds)
    if i >= len(left):
        return
    end = min(len(left), i + len(sound))
    piece = gain * sound[: end - i]
    left[i:end] += piece * np.sqrt((1 - pan) / 2)
    right[i:end] += piece * np.sqrt((1 + pan) / 2)


def times(seconds: float) -> np.ndarray:
    return np.arange(int(RATE * seconds)) / RATE


def lowpass(x: np.ndarray, cutoff: float) -> np.ndarray:
    """A two-pole low-pass (two one-pole stages)."""
    a = np.exp(-2 * np.pi * cutoff / RATE)
    for _ in range(2):
        y = np.empty_like(x)
        acc = 0.0
        for n, v in enumerate(x):
            acc = (1 - a) * v + a * acc
            y[n] = acc
        x = y
    return x


def note(name: str) -> float:
    names = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[pitch] + 12 * (octave + 1) - 69) / 12)


# I-V-vi-IV, a bar each: chord tones for the pad, the arpeggio and the bass.
CHORDS = [
    (["C3", "G3", "C4", "E4"], ["C5", "E5", "G5", "C6"], "C2"),
    (["G2", "D3", "G3", "B3"], ["B4", "D5", "G5", "B5"], "G1"),
    (["A2", "E3", "A3", "C4"], ["C5", "E5", "A5", "C6"], "A1"),
    (["F2", "C3", "F3", "A3"], ["A4", "C5", "F5", "A5"], "F1"),
]
BAR = 4 * BEAT


def pad(freqs: list[float], seconds: float) -> np.ndarray:
    """Soft detuned saws through a gentle low-pass: warm, not buzzy."""
    t = times(seconds)
    out = np.zeros_like(t)
    for f in freqs:
        for detune in (-0.12, 0.12):
            ff = f * 2 ** (detune / 12)
            out += sum(np.sin(2 * np.pi * ff * k * t + k) / k for k in range(1, 7))
    envelope = np.minimum(1, t / 0.35) * np.minimum(1, (seconds - t) / 0.4)
    return lowpass(out * envelope, 1400) / len(freqs)


@functools.cache
def pluck(freq: float, seconds: float = 0.9) -> np.ndarray:
    """Karplus-Strong: a short burst of noise in a tuned, damped delay line, like a harp or a soft guitar."""
    period = int(RATE / freq)
    buffer = rng.uniform(-1, 1, period)
    out = np.empty(int(RATE * seconds))
    for n in range(len(out)):
        value = buffer[n % period]
        out[n] = value
        buffer[n % period] = 0.497 * (value + buffer[(n + 1) % period])
    return lowpass(out, 5000) * np.exp(-times(seconds) * 2.2)


def bass(freq: float, seconds: float) -> np.ndarray:
    t = times(seconds)
    tone = np.sin(2 * np.pi * freq * t) + 0.35 * np.sin(4 * np.pi * freq * t)
    return np.tanh(1.4 * tone) * np.minimum(1, t / 0.01) * np.exp(-t * 2.5)


def kick() -> np.ndarray:
    t = times(0.32)
    freq = 45 + 85 * np.exp(-t * 35)
    return np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-t * 11)


def clap() -> np.ndarray:
    t = times(0.25)
    noise = rng.uniform(-1, 1, len(t))
    body = noise - lowpass(noise, 900)
    return lowpass(body, 6000) * np.exp(-t * 22)


def shaker() -> np.ndarray:
    t = times(0.07)
    noise = rng.uniform(-1, 1, len(t))
    return (noise - lowpass(noise, 6000)) * np.exp(-t * 60)


def whoosh(seconds: float = 0.55, rising: bool = True) -> np.ndarray:
    """Band-limited noise sweeping up (or down), swelling to the cut."""
    t = times(seconds)
    noise = rng.uniform(-1, 1, len(t))
    p = t / seconds
    sweep = 400 + 4000 * (p if rising else 1 - p) ** 2
    out = np.empty_like(noise)
    acc_low = acc_band = 0.0
    for n, v in enumerate(noise):
        a = np.exp(-2 * np.pi * sweep[n] / RATE)
        acc_low = (1 - a) * v + a * acc_low
        acc_band = 0.8 * acc_band + 0.2 * (v - acc_low)
        out[n] = acc_low - acc_band
    envelope = np.sin(np.pi * p) ** 2 * (p ** 0.5 if rising else 1)
    return out * envelope


def pop() -> np.ndarray:
    t = times(0.12)
    freq = 700 + 900 * np.exp(-t * 40)
    return np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-t * 35)


def chime(freq: float, seconds: float = 2.0) -> np.ndarray:
    t = times(seconds)
    tone = np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(2 * np.pi * freq * 2.01 * t) * np.exp(-t * 5)
    return tone * np.minimum(1, t / 0.004) * np.exp(-t * 2.6)


# ---- The groove, bar by bar, from the first downbeat to the end card --------------------------------------------
bars = int(np.ceil((END_CARD - START) / BAR))
for b in range(bars):
    t0 = START + b * BAR
    padv, arp, root = CHORDS[b % 4]
    # Bar 1 is the pad and the arpeggio alone; the drums come in from bar 2.
    add(t0, pad([note(n) for n in padv], BAR + 0.3), 0.075, pan=0)
    for step in range(8):
        f = note(arp[[0, 2, 1, 3, 2, 1, 3, 2][step]])
        add(t0 + step * BEAT / 2, pluck(f), 0.11, pan=-0.35 if step % 2 else 0.35)
    for beat in range(4):
        tb = t0 + beat * BEAT
        add(tb, bass(note(root), BEAT * 0.9), 0.20 if beat % 2 == 0 else 0.14)
        if b >= 1:
            add(tb, kick(), 0.42)
            if beat % 2 == 1:
                add(tb, clap(), 0.12, pan=0.1)
            for s in range(4):
                add(tb + s * BEAT / 4, shaker(), 0.035 if s % 2 else 0.02, pan=0.5)

# A short lift into the end card, then the home chord ringing out with a little chime run (the splash's C major).
add(END_CARD - 0.6, whoosh(0.6), 0.10)
add(END_CARD, pad([note(n) for n in ["C3", "G3", "C4", "E4", "G4"]], 4.5), 0.10)
add(END_CARD, bass(note("C2"), 2.5), 0.22)
add(END_CARD, kick(), 0.4)
for i, name in enumerate(["C5", "E5", "G5", "C6", "E6"]):
    add(END_CARD + 0.75 + i * 0.09, chime(note(name)), 0.06, pan=-0.3 + 0.15 * i)

# Before the groove: a pad swelling under the splash's last heartbeats.
add(START - 1.2, pad([note(n) for n in ["C3", "G3", "C4", "E4"]], 1.5), 0.045)

# ---- Ducking: the music dips under the voice, smoothly ---------------------------------------------------------
duck = np.ones_like(left)
for line in timeline["voice"]:
    a, b = at(line["start"] - 0.12), at(line["start"] + line["length"] + 0.15)
    duck[max(0, a):b] = 0.42
kernel = np.hanning(at(0.25))
duck = np.convolve(duck, kernel / kernel.sum(), mode="same")
left *= duck
right *= duck

# ---- Effects, not ducked: on the cuts and the zoom-ins ----------------------------------------------------------
for scene in timeline["scenes"][1:-1]:
    add(scene["start"] - 0.35, whoosh(0.5), 0.07, pan=0.2)
    for z in scene.get("zoomAt", []):
        add(z, pop(), 0.09)

# A light room reverb on everything: an exponentially decaying noise tail, mixed in low.
ir = rng.normal(0, 1, at(1.4)) * np.exp(-times(1.4) * 4.5)
ir /= np.sqrt(np.sum(ir ** 2))
n = 1 << int(np.ceil(np.log2(len(left) + len(ir))))
for channel in (left, right):
    wet = np.fft.irfft(np.fft.rfft(channel, n) * np.fft.rfft(ir, n), n)[: len(channel)]
    channel += 0.18 * wet

stereo = np.stack([left, right], axis=1)[: at(LENGTH)]
stereo[-at(0.8):] *= np.linspace(1, 0, at(0.8))[:, None]
stereo *= 0.6 / max(1e-9, np.max(np.abs(stereo)))

import wave
with wave.open(str(BUILD / "music.wav"), "wb") as out:
    out.setnchannels(2)
    out.setsampwidth(2)
    out.setframerate(RATE)
    out.writeframes((stereo * 32767).astype("<i2").tobytes())
print("wrote", BUILD / "music.wav", f"{LENGTH:.1f}s", file=sys.stderr)
