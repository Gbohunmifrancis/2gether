"""The skill games' level sounds, made from scratch like the splash's (no samples, nothing licensed). All soft,
short and warm, in the splash's glass voice, peaking well under full volume:

- level_up: a level cleared. A rising three-note glass arpeggio over a breath of a chord (about 1.2 s).
- level_star: one star landing. A single small bell; the app plays it three times, a step higher each star.
- level_try: a level not cleared. Two gentle notes falling a third, never a buzzer (about 0.6 s).
- level_whoosh: the heart closing and opening between levels. A soft airy swell (about 0.6 s).

    python3 android/tools/game_sounds.py    # writes app/src/main/res/raw/level_*.ogg (needs numpy and ffmpeg)
"""
import pathlib
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
RAW = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/raw"


def tone(freq: float, seconds: float, decay: float, octave: float = 0.12) -> np.ndarray:
    """A soft glass note: a pure tone with a faint, quickly fading octave, a 20 ms swell and a smooth fade."""
    t = np.arange(int(RATE * seconds)) / RATE
    body = np.sin(2 * np.pi * freq * t) + octave * np.sin(2 * np.pi * freq * 2 * t) * np.exp(-t * 6)
    attack = 0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.02, 0, 1))
    return body * attack * np.exp(-t * decay)


def chord(freqs, seconds: float) -> np.ndarray:
    """A quiet chord that swells in and breathes out, slightly detuned so it shimmers."""
    t = np.arange(int(RATE * seconds)) / RATE
    out = sum(np.sin(2 * np.pi * f * (1 + d) * t) for f in freqs for d in (-0.002, 0.002))
    return out / (2 * len(freqs)) * np.sin(np.pi * np.clip(t / seconds, 0, 1)) ** 2


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
    fade = int(RATE * 0.03)
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
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "4", str(out)], check=True)
        print(out, out.stat().st_size, "bytes")


C5, E5, G5, C6, A4, F4 = 523.25, 659.25, 783.99, 1046.5, 440.0, 349.23

# Cleared: C, E, G rising, an octave C to land on, over a soft C-major breath.
write("level_up", mix(1.3, [
    (0, chord([C5 / 2, E5 / 2, G5 / 2], 1.2), 0.25),
    (0, tone(C5, 1.0, 3.2), 0.5),
    (110, tone(E5, 1.0, 3.2), 0.48),
    (220, tone(G5, 1.0, 3.0), 0.46),
    (360, tone(C6, 0.9, 3.4), 0.36),
]), peak=0.55)

# A star: one small bell, played at three pitches by the app.
write("level_star", mix(0.45, [(0, tone(G5 * 1.5, 0.45, 8.0, octave=0.25), 1.0)]), peak=0.45, cutoff=4200)

# Not yet: A down to F, soft and short.
write("level_try", mix(0.65, [(0, tone(A4, 0.5, 6.0), 0.5), (150, tone(F4, 0.5, 6.0), 0.5)]), peak=0.4, cutoff=2400)

# The heart iris: filtered noise that swells and fades, with a faint rising tone under it.
rng = np.random.default_rng(7)
n = int(RATE * 0.6)
t = np.arange(n) / RATE
noise = rng.standard_normal(n) * np.sin(np.pi * t / 0.6) ** 2
rise = np.sin(2 * np.pi * (300 * t + 400 * t * t)) * np.sin(np.pi * t / 0.6) ** 2 * 0.25
write("level_whoosh", soften(noise, 900) * 6 + rise, peak=0.3, cutoff=1800)
