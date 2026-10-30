"""The splash's sound, made from scratch so it can be tuned and rebuilt (no samples, nothing licensed).

Two plucked voices, one for each half of the heart, call and answer each other in the Lydian mode (the major scale
with a raised fourth, which is why it sounds hopeful and a little unfinished rather than like a ringtone), and meet:

- 150-1450, the halves walk in: a kalimba-like voice steps up on the left, the answer comes a third above on the right.
- 1450-1750, they meet: both voices land together on a fifth, with a soft felt thump.
- 1750-2200, the shapes tuck away: a breathy upward sweep.
- 2200-2650, the mark forms and the tick cuts in: a warm Dmaj9 swell with a bright pluck motif over it.
- 2650 and 2950, the heartbeats: a soft low "lub-dub".
- after that, a shimmer fading out with the blink.

    python android/tools/splash_sound.py    # writes app/src/main/res/raw/splash_love.wav (needs numpy only)
"""
import pathlib
import wave

import numpy as np

RATE = 44100
LENGTH_S = 3.9
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/raw/splash_love.wav"

rng = np.random.default_rng(7)
track = np.zeros(int(RATE * LENGTH_S))


def at(ms: float) -> int:
    return int(RATE * ms / 1000)


def add(start_ms: float, sound: np.ndarray, gain: float) -> None:
    i = at(start_ms)
    end = min(len(track), i + len(sound))
    track[i:end] += gain * sound[: end - i]


def pluck(freq: float, seconds: float = 1.3, bright: float = 2.4) -> np.ndarray:
    """A kalimba-like pluck: FM with a quickly fading modulator (the 'tink'), over a round fundamental."""
    t = np.arange(int(RATE * seconds)) / RATE
    index = bright * np.exp(-t * 14)
    mod = np.sin(2 * np.pi * freq * 3.51 * t)
    tone = np.sin(2 * np.pi * freq * t + index * mod) + 0.25 * np.sin(2 * np.pi * freq * 2 * t) * np.exp(-t * 5)
    return tone * np.clip(t / 0.003, 0, 1) * np.exp(-t * 3.4)


def pad(freqs, seconds: float = 1.6) -> np.ndarray:
    """A warm swell: detuned sines per note, a slow attack and a long release."""
    t = np.arange(int(RATE * seconds)) / RATE
    out = np.zeros_like(t)
    for f in freqs:
        for detune in (-0.0035, 0.0, 0.0035):
            out += np.sin(2 * np.pi * f * (1 + detune) * t)
    envelope = np.sin(np.pi * np.clip(t / seconds, 0, 1)) ** 2.2
    return out / (3 * len(freqs)) * envelope


def sweep(seconds: float = 0.45) -> np.ndarray:
    """Breath rising in pitch: noise through a moving band, so it reads as air, not a synth."""
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    noise = rng.standard_normal(n)
    centre = 900 + 3800 * (t / seconds) ** 1.6
    phase = 2 * np.pi * np.cumsum(centre) / RATE
    band = noise * np.sin(phase)
    return band * np.sin(np.pi * t / seconds) ** 2


def thump(seconds: float = 0.22, pitch: float = 88.0) -> np.ndarray:
    """A heartbeat: a low note whose pitch drops, soft-attacked. Phone speakers barely play below 200 Hz, so the
    overtones carry it there; headphones get the low end too."""
    t = np.arange(int(RATE * seconds)) / RATE
    freq = pitch * (1 + 0.6 * np.exp(-t * 30))
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    envelope = np.clip(t / 0.008, 0, 1) * np.exp(-t * 18)
    overtones = (0.55 * np.sin(2 * phase) + 0.3 * np.sin(3 * phase)) * np.exp(-t * 10)
    return (np.sin(phase) + overtones) * envelope


# D Lydian: D E F# G# A B C#.
D4, E4, Fs4, Gs4, A4, B4, Cs5, D5, E5, Fs5, Gs5, A5, B5 = (293.66, 329.63, 369.99, 415.30, 440.00, 493.88, 554.37,
                                                          587.33, 659.25, 739.99, 830.61, 880.00, 987.77)

# The halves walk in, one stride a note: the left climbs, the right answers a step behind and a third above.
for i, note in enumerate([D4, E4, Fs4, A4]):
    add(260 + i * 300, pluck(note), 0.22)
for i, note in enumerate([Fs4, Gs4, A4, Cs5]):
    add(410 + i * 300, pluck(note, bright=1.8), 0.16)

# They meet: a fifth together, with a felt thump under it.
add(1480, pluck(D5), 0.24)
add(1480, pluck(A5, bright=1.6), 0.2)
add(1480, thump(0.2, 70.0), 0.3)

# The shapes tuck away: air rising.
add(1760, sweep(), 0.11)

# The mark forms: a Dmaj9 swell, and the motif that says "together" rising over it as the tick cuts in.
add(2150, pad([146.83, 220.0, 293.66, 369.99, 554.37]), 0.55)
for i, (note, gain) in enumerate([(Fs5, 0.2), (A5, 0.19), (B5, 0.17), (Cs5 * 2, 0.15)]):
    add(2230 + i * 85, pluck(note, 1.0, 2.8), gain)

# Two heartbeats with the mark's pulses.
add(2650, thump(), 0.55)
add(2840, thump(pitch=78.0), 0.38)
add(2950, thump(), 0.42)
add(3140, thump(pitch=78.0), 0.28)

# A last high shimmer, fading out with the blink.
add(3120, pluck(D5 * 4, 0.9, 1.2), 0.05)
add(3180, pluck(A5 * 2.0 * 1.5, 0.8, 1.2), 0.035)

# A short fade at the very end, then normalise with headroom.
track[-at(250):] *= np.linspace(1, 0, at(250))
track *= 0.8 / np.max(np.abs(track))

OUT.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(OUT), "wb") as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(RATE)
    out.writeframes((track * 32767).astype("<i2").tobytes())
print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
