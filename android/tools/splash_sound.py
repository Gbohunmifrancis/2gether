"""The splash's sound, made from scratch so it can be tuned and rebuilt (no samples, nothing licensed).

Short and soft: nothing while the halves walk in, then, as they meet (SplashTimeline.MEET, 1450 ms) two warm glass
notes a sixth apart, one per half, and as the mark forms a quiet pad that breathes out by the blink. About a second
and a half you actually hear, peaking well under full volume, with no clicks, thumps or bright overtones.

    python android/tools/splash_sound.py    # writes app/src/main/res/raw/splash_love.ogg (needs numpy and ffmpeg)
"""
import pathlib
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
LENGTH_S = 3.0
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/raw/splash_love.ogg"

track = np.zeros(int(RATE * LENGTH_S))


def at(ms: float) -> int:
    return int(RATE * ms / 1000)


def add(start_ms: float, sound: np.ndarray, gain: float) -> None:
    i = at(start_ms)
    end = min(len(track), i + len(sound))
    track[i:end] += gain * sound[: end - i]


def glass(freq: float, seconds: float = 1.3) -> np.ndarray:
    """A soft glass note: a pure tone with a faint octave, a gentle 25 ms swell and a slow, smooth fade."""
    t = np.arange(int(RATE * seconds)) / RATE
    tone = np.sin(2 * np.pi * freq * t) + 0.12 * np.sin(2 * np.pi * freq * 2 * t) * np.exp(-t * 4)
    attack = 0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.025, 0, 1))
    return tone * attack * np.exp(-t * 2.6)


def breath(freqs, seconds: float = 1.2) -> np.ndarray:
    """A quiet chord that swells in and breathes out, slightly detuned so it shimmers rather than buzzes."""
    t = np.arange(int(RATE * seconds)) / RATE
    out = np.zeros_like(t)
    for f in freqs:
        for detune in (-0.002, 0.002):
            out += np.sin(2 * np.pi * f * (1 + detune) * t)
    envelope = np.sin(np.pi * np.clip(t / seconds, 0, 1)) ** 2
    return out / (2 * len(freqs)) * envelope


def soften(signal: np.ndarray, cutoff_hz: float = 2600) -> np.ndarray:
    """A one-pole low-pass, twice: takes the edge off anything bright."""
    a = np.exp(-2 * np.pi * cutoff_hz / RATE)
    for _ in range(2):
        out = np.empty_like(signal)
        acc = 0.0
        for i, x in enumerate(signal):
            acc = (1 - a) * x + a * acc
            out[i] = acc
        signal = out
    return signal


# The halves meet: E5 from the left, then C#6 from the right, a breath apart.
add(1460, glass(659.25), 0.30)
add(1600, glass(1108.73), 0.22)

# The mark forms: a soft A major add9 under them, gone by the blink.
add(1900, breath([220.0, 277.18, 329.63, 493.88]), 0.28)

track = soften(track)
# Fade the tail to true silence, then keep the peak low: it should sit under whatever else is playing.
track[-at(300):] *= np.linspace(1, 0, at(300))
track *= 0.5 / np.max(np.abs(track))

OUT.parent.mkdir(parents=True, exist_ok=True)
# Written as a WAV, then encoded to Ogg Vorbis at high quality: the same sound at a tenth of the size.
with tempfile.TemporaryDirectory() as tmp:
    wav = pathlib.Path(tmp) / "splash_love.wav"
    with wave.open(str(wav), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes((track * 32767).astype("<i2").tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "6", str(OUT)], check=True)
print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
