"""Snakes and ladders' sounds, made from scratch like the level sounds (tools/game_sounds.py: no samples, nothing
licensed). Short, soft and warm, peaking well under full volume:

- snakes_dice: the die rattling and tumbling, a handful of small wooden clicks that slow down (about 0.6 s).
- snakes_step: one hop, a soft wooden tick; the app plays it a little higher each square.
- snakes_ladder: climbing a ladder, a quick glass arpeggio up (about 0.8 s).
- snakes_slide: sliding down a snake, a soft whistle that glides down an octave and a half (about 0.9 s).
- snakes_boost: a love boost or a swap, an airy whoosh with a sparkle on top (about 0.5 s).
- snakes_win: reaching 100, a little chime: a rising chord and a bell (about 1.4 s).

    python3 android/tools/snakes_sounds.py    # writes app/src/main/res/raw/snakes_*.ogg (needs numpy and ffmpeg)
"""
import pathlib
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
RAW = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/raw"
rng = np.random.default_rng(100)


def tone(freq: float, seconds: float, decay: float, octave: float = 0.12) -> np.ndarray:
    """A soft glass note: a pure tone with a faint, quickly fading octave, a 20 ms swell and a smooth fade."""
    t = np.arange(int(RATE * seconds)) / RATE
    body = np.sin(2 * np.pi * freq * t) + octave * np.sin(2 * np.pi * freq * 2 * t) * np.exp(-t * 6)
    attack = 0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.02, 0, 1))
    return body * attack * np.exp(-t * decay)


def click(seconds: float = 0.05, pitch: float = 900.0) -> np.ndarray:
    """A small wooden knock: a damped tone with a breath of noise at the very start."""
    t = np.arange(int(RATE * seconds)) / RATE
    knock = np.sin(2 * np.pi * pitch * t) * np.exp(-t * 90) + 0.5 * np.sin(2 * np.pi * pitch * 1.7 * t) * np.exp(-t * 140)
    knock += rng.standard_normal(len(t)) * np.exp(-t * 400) * 0.35
    return knock


def soften(signal: np.ndarray, cutoff_hz: float) -> np.ndarray:
    """A one-pole low-pass, twice: takes the edge off anything bright."""
    a = np.exp(-2 * np.pi * cutoff_hz / RATE)
    for _ in range(2):
        out = np.empty_like(signal)
        acc = 0.0
        for i in range(len(signal)):
            acc = (1 - a) * signal[i] + a * acc
            out[i] = acc
        signal = out
    return signal


def mix(length_s: float, parts) -> np.ndarray:
    track = np.zeros(int(RATE * length_s))
    for start_ms, sound, gain in parts:
        i = int(RATE * start_ms / 1000)
        end = min(len(track), i + len(sound))
        track[i:end] += gain * sound[: end - i]
    return track


def write(name: str, signal: np.ndarray, peak: float, cutoff: float = 3200) -> None:
    signal = soften(signal, cutoff)
    fade = int(RATE * 0.02)
    signal[-fade:] *= np.linspace(1, 0, fade)
    signal = signal / np.max(np.abs(signal)) * peak
    with tempfile.TemporaryDirectory() as tmp:
        wav = pathlib.Path(tmp) / f"{name}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes((signal * 32767).astype(np.int16).tobytes())
        out = RAW / f"{name}.ogg"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "3", str(out)], check=True)
        print(out, out.stat().st_size, "bytes")


C5, D5, E5, G5, A5, C6, E6, G6 = 523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1318.5, 1568.0

# The die: clicks closer together at first, then spreading out and softening as it settles.
times, t, gap = [], 0.0, 28.0
while t < 560:
    times.append(t)
    t += gap
    gap *= 1.16
write("snakes_dice", mix(0.65, [(ms, click(0.05, 700 + 260 * rng.random()), 1.0 - 0.6 * i / len(times)) for i, ms in enumerate(times)]), peak=0.42, cutoff=3600)

# A hop: one soft tick.
write("snakes_step", mix(0.09, [(0, click(0.08, 1150), 1.0)]), peak=0.32, cutoff=3000)

# A ladder: C E G C E, quick and rising.
write("snakes_ladder", mix(0.85, [(i * 75, tone(f, 0.6, 7.0), 0.5) for i, f in enumerate([C5, E5, G5, C6, E6])]), peak=0.45)

# A snake: a slide whistle from G6 down to C5, wobbling a touch, with a soft landing note.
n = int(RATE * 0.8)
tt = np.arange(n) / RATE
freq = G6 * (C5 / G6) ** (tt / 0.8) * (1 + 0.012 * np.sin(2 * np.pi * 7 * tt))
slide = np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.sin(np.pi * np.clip(tt / 0.8, 0, 1)) ** 0.6
write("snakes_slide", mix(0.95, [(0, slide, 0.45), (760, tone(C5 / 2, 0.18, 14.0), 0.35)]), peak=0.38, cutoff=2600)

# A boost: filtered noise that swells and fades, a rising tone under it and a sparkle at the end.
n = int(RATE * 0.45)
tt = np.arange(n) / RATE
air = soften(rng.standard_normal(n), 1200) * 6 * np.sin(np.pi * tt / 0.45) ** 2
rise = np.sin(2 * np.pi * (500 * tt + 900 * tt * tt)) * np.sin(np.pi * tt / 0.45) ** 2 * 0.3
write("snakes_boost", mix(0.55, [(0, air + rise, 1.0), (300, tone(E6, 0.25, 12.0, octave=0.3), 0.25)]), peak=0.32, cutoff=2400)

# A win: C E G rising together into a C-major bell and a high sparkle.
write("snakes_win", mix(1.45, [
    (0, tone(C5, 1.2, 2.6), 0.4),
    (90, tone(E5, 1.2, 2.6), 0.38),
    (180, tone(G5, 1.2, 2.6), 0.36),
    (300, tone(C6, 1.1, 2.4, octave=0.25), 0.42),
    (420, tone(G6, 0.6, 5.0, octave=0.3), 0.18),
]), peak=0.5)
