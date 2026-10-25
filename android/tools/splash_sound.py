"""The splash's sound, made from scratch so it can be tuned and rebuilt (no samples, nothing licensed).

It follows the splash's timeline (ui/splash/Splash.kt, SplashTimeline), in milliseconds:

- 1450-1750, the halves meet and hop: two soft rising "boops", one per half.
- 2200-2450, the halves become the mark and the tick cuts in: a gentle music-box arpeggio (C major, up).
- 2650 and 2950, the mark's two heartbeats: soft low "lub-dub" thumps.
- after that, a quiet shimmer fading out with the blink.

    python3 android/tools/splash_sound.py    # writes app/src/main/res/raw/splash_love.ogg (needs numpy and ffmpeg)
"""
import pathlib
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
LENGTH_S = 3.9
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/raw/splash_love.ogg"

track = np.zeros(int(RATE * LENGTH_S))


def at(ms: float) -> int:
    return int(RATE * ms / 1000)


def add(start_ms: float, sound: np.ndarray, gain: float) -> None:
    i = at(start_ms)
    end = min(len(track), i + len(sound))
    track[i:end] += gain * sound[: end - i]


def bell(freq: float, seconds: float = 1.6) -> np.ndarray:
    """A music-box note: a sine with a few soft, slightly inharmonic overtones, a quick attack and a long decay."""
    t = np.arange(int(RATE * seconds)) / RATE
    tone = (np.sin(2 * np.pi * freq * t)
            + 0.35 * np.sin(2 * np.pi * freq * 2.0 * t) * np.exp(-t * 6)
            + 0.12 * np.sin(2 * np.pi * freq * 3.01 * t) * np.exp(-t * 9)
            + 0.05 * np.sin(2 * np.pi * freq * 4.2 * t) * np.exp(-t * 14))
    attack = np.clip(t / 0.004, 0, 1)
    return tone * attack * np.exp(-t * 3.2)


def boop(start_hz: float, end_hz: float, seconds: float = 0.16) -> np.ndarray:
    """A round, rising little note, like a bubble."""
    t = np.arange(int(RATE * seconds)) / RATE
    freq = start_hz + (end_hz - start_hz) * (t / seconds) ** 0.6
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    envelope = np.sin(np.pi * t / seconds) ** 1.5
    return (np.sin(phase) + 0.15 * np.sin(2 * phase)) * envelope


def thump(seconds: float = 0.22, pitch: float = 92.0) -> np.ndarray:
    """
    A heartbeat: a low note whose pitch drops, soft-attacked. Phone speakers barely play below 200 Hz, so the
    overtones carry it there; headphones get the low end too.
    """
    t = np.arange(int(RATE * seconds)) / RATE
    freq = pitch * (1 + 0.6 * np.exp(-t * 30))
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    envelope = np.clip(t / 0.008, 0, 1) * np.exp(-t * 18)
    overtones = (0.55 * np.sin(2 * phase) + 0.3 * np.sin(3 * phase)) * np.exp(-t * 10)
    return (np.sin(phase) + overtones) * envelope


def heartbeat(start_ms: float, gain: float) -> None:
    add(start_ms, thump(), gain)
    add(start_ms + 190, thump(pitch=82.0), gain * 0.7)


# The halves meet: one boop each, the second a little higher.
add(1480, boop(520, 780), 0.16)
add(1600, boop(660, 990), 0.14)

# The mark forms and the tick cuts in: C5 E5 G5 C6, and a soft E6 on top as the tick lands.
for i, (note, gain) in enumerate([(523.25, 0.20), (659.25, 0.19), (783.99, 0.18), (1046.5, 0.17), (1318.5, 0.08)]):
    add(2200 + i * 70, bell(note), gain)

# Two heartbeats with the mark's pulses.
heartbeat(2650, 0.55)
heartbeat(2950, 0.42)

# A last high shimmer, fading out with the blink.
add(3120, bell(2093.0, 0.8), 0.035)
add(3160, bell(2637.0, 0.7), 0.025)

# A short fade at the very end, then normalise with headroom.
track[-at(250):] *= np.linspace(1, 0, at(250))
track *= 0.8 / np.max(np.abs(track))

with tempfile.TemporaryDirectory() as tmp:
    wav = pathlib.Path(tmp) / "splash.wav"
    with wave.open(str(wav), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes((track * 32767).astype("<i2").tobytes())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "5", str(OUT)], check=True)
print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
