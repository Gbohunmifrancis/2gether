#!/usr/bin/env python3
"""The Google Play promo video: the phone screenshots' panorama gliding across a 1920 x 1080 frame, between the
feature graphic and the logo, over music made here (so nothing needs a licence). One per language; Arabic glides
right to left, the way Play lays out its panorama.

    python3 tools/video.py            # every language -> video/<lang>/heartpact-promo-<lang>.mp4
    python3 tools/video.py en fr      # just these

Upload to YouTube (public or unlisted, ads off, not age-restricted) and paste the link into Play Console's Video field.
Needs Pillow, numpy and ffmpeg.
"""
import pathlib
import subprocess
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent.parent
REPO = HERE.parent.parent
LANGS = ["en", "fr", "pt", "es", "ar", "hi"]
W, H, FPS = 1920, 1080, 30
PAGE = (234, 236, 249)          # the screenshots' and feature graphic's page colour
INK = (31, 37, 87)
SLIDES = ["01-today", "02-new-dare", "03-alarm", "04-camera", "05-heart-complete", "06-question", "07-games-snakes", "08-album"]

# Seconds.
INTRO, FADE, HOLD, GLIDE, OUTRO = 3.2, 0.9, 2.0, 1.4, 4.2
# Three screenshots fill each pause, centred, with an even sliver of the next ones either side.
VISIBLE = 3


def ease(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


def panorama(lang):
    """The eight screenshots side by side at the frame's height (Arabic in reverse, as Play shows it), with a page-coloured
    margin at both ends so the first and last three sit centred like the rest."""
    names = SLIDES[::-1] if lang == "ar" else SLIDES
    shots = [Image.open(HERE / f"screenshots/phone/{lang}/{name}.jpg").convert("RGB") for name in names]
    width = round(shots[0].width * H / shots[0].height)
    margin = (W - VISIBLE * width) // 2
    strip = Image.new("RGB", (width * len(shots) + 2 * margin, H), PAGE)
    for i, shot in enumerate(shots):
        strip.paste(shot.resize((width, H), Image.LANCZOS), (margin + i * width, 0))
    return strip, width


def card_intro(lang):
    graphic = Image.open(HERE / f"feature-graphic/{lang}/feature-graphic-1024x500.png").convert("RGB")
    scale = W / graphic.width
    graphic = graphic.resize((W, round(graphic.height * scale)), Image.LANCZOS)
    card = Image.new("RGB", (W, H), PAGE)
    card.paste(graphic, (0, (H - graphic.height) // 2))
    return card


def card_outro():
    card = Image.new("RGB", (W, H), PAGE)
    lockup = Image.open(HERE / "logo/heartpact-lockup.png").convert("RGBA")
    lockup = lockup.resize((900, round(lockup.height * 900 / lockup.width)), Image.LANCZOS)
    top = (H - lockup.height) // 2 - 60
    card.paste(lockup, ((W - lockup.width) // 2, top), lockup)
    font = ImageFont.truetype(str(HERE / "tools/fonts/sora_semibold.ttf"), 54)
    draw = ImageDraw.Draw(card)
    text = "heartpact.me"
    box = draw.textbbox((0, 0), text, font=font)
    draw.text(((W - (box[2] - box[0])) // 2, top + lockup.height + 70), text, font=font, fill=INK)
    return card


def zoom(image, factor):
    """A gentle push-in about the centre."""
    if factor <= 1.0001:
        return image
    w, h = round(W / factor), round(H / factor)
    left, top = (W - w) // 2, (H - h) // 2
    return image.crop((left, top, left + w, top + h)).resize((W, H), Image.BILINEAR)


def stops(strip_width, slide_width, rtl):
    """Where the glide rests: slides 1-3 centred, then 2-4, and so on to 6-8 (from the right in Arabic)."""
    end = strip_width - W
    xs = [k * slide_width for k in range(len(SLIDES) - VISIBLE + 1)]
    return [end - x for x in xs] if rtl else xs


def frames(lang):
    strip, slide = panorama(lang)
    xs = stops(strip.width, slide, lang == "ar")
    intro, outro = card_intro(lang), card_outro()
    pano_len = len(xs) * HOLD + (len(xs) - 1) * GLIDE
    total = INTRO + pano_len + OUTRO

    def pano_at(t):
        # t from the start of the panorama: hold, glide, hold, ...
        step = HOLD + GLIDE
        k = min(int(t // step), len(xs) - 1)
        within = t - k * step
        x = xs[k] if within <= HOLD or k == len(xs) - 1 else xs[k] + (xs[k + 1] - xs[k]) * ease((within - HOLD) / GLIDE)
        return strip.crop((round(x), 0, round(x) + W, H))

    count = round(total * FPS)
    for i in range(count):
        t = i / FPS
        if t < INTRO:
            frame = zoom(intro, 1 + 0.035 * t / INTRO)
            if t < 0.5:
                frame = Image.blend(Image.new("RGB", (W, H), PAGE), frame, t / 0.5)
            if t > INTRO - FADE:
                frame = Image.blend(frame, pano_at(0), ease((t - (INTRO - FADE)) / FADE))
        elif t < INTRO + pano_len:
            frame = pano_at(t - INTRO)
            if t > INTRO + pano_len - FADE:
                frame = Image.blend(frame, outro, ease((t - (INTRO + pano_len - FADE)) / FADE))
        else:
            u = t - INTRO - pano_len
            frame = zoom(outro, 1 + 0.03 * u / OUTRO)
            if u > OUTRO - 0.8:
                frame = Image.blend(frame, Image.new("RGB", (W, H), PAGE), (u - (OUTRO - 0.8)) / 0.8)
        yield frame.tobytes()
    frames.total = total


RATE = 44100


def music(seconds, out):
    """A soft, original bed: warm pad chords, a gentle plucked arpeggio, the splash's heartbeat on the logo."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    left = np.zeros(n)
    right = np.zeros(n)
    bpm = 92
    beat = 60 / bpm
    bar = 4 * beat
    # C, Am, F, G (in Hz, root position triads an octave below middle C and up).
    chords = [[130.81, 164.81, 196.00], [110.00, 130.81, 164.81], [87.31, 110.00, 130.81], [98.00, 123.47, 146.83]]
    for b in range(int(seconds / bar) + 1):
        chord = chords[b % 4]
        start = b * bar
        i0, i1 = int(start * RATE), min(n, int((start + bar + 0.6) * RATE))
        if i0 >= n:
            break
        tt = t[i0:i1] - start
        env = np.minimum(1, tt / 0.8) * np.exp(-np.maximum(0, tt - bar) * 4)
        pad = sum(np.sin(2 * np.pi * f * tt) * 0.5 + np.sin(2 * np.pi * f * 2 * tt + 0.3) * 0.12 for f in chord)
        left[i0:i1] += 0.06 * env * pad
        right[i0:i1] += 0.06 * env * np.sin(2 * np.pi * chord[0] * 1.002 * tt) * 0.5 + 0.06 * env * pad * 0.9
        # Arpeggio: chord tones two octaves up, eighth notes.
        notes = [chord[0] * 4, chord[1] * 4, chord[2] * 4, chord[1] * 4] * 2
        for k, f in enumerate(notes):
            s = start + k * beat / 2
            j0, j1 = int(s * RATE), min(n, int((s + 0.9) * RATE))
            if j0 >= n:
                break
            u = t[j0:j1] - s
            pluck = (np.sin(2 * np.pi * f * u) + 0.25 * np.sin(2 * np.pi * 2 * f * u)) * np.exp(-u * 5.5) * np.minimum(1, u / 0.004)
            pan = 0.35 + 0.3 * (k % 2)
            left[j0:j1] += 0.05 * pluck * (1 - pan)
            right[j0:j1] += 0.05 * pluck * pan
    stereo = np.stack([left, right], axis=1)
    # Fade in and out.
    fade = np.ones(n)
    fi, fo = int(1.0 * RATE), int(2.0 * RATE)
    fade[:fi] = np.linspace(0, 1, fi)
    fade[-fo:] = np.linspace(1, 0, fo)
    stereo *= fade[:, None]
    stereo /= max(1e-9, np.abs(stereo).max()) / 0.6
    with wave.open(str(out), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((stereo * 32767).astype("<i2").tobytes())


def build(lang):
    out_dir = HERE / "video" / lang
    out_dir.mkdir(parents=True, exist_ok=True)
    tmp = out_dir / "video.tmp.mp4"
    proc = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                             "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(tmp)],
                            stdin=subprocess.PIPE)
    for frame in frames(lang):
        proc.stdin.write(frame)
    proc.stdin.close()
    if proc.wait() != 0:
        raise SystemExit("ffmpeg failed")
    total = frames.total
    bed = out_dir / "music.tmp.wav"
    music(total, bed)
    # The splash's heartbeat lands with the logo.
    beat_at = total - 4.0
    final = out_dir / f"heartpact-promo-{lang}.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(tmp), "-i", str(bed), "-i", str(REPO / "android/app/src/main/res/raw/splash_love.ogg"),
                    "-filter_complex", f"[2:a]adelay={int(beat_at * 1000)}|{int(beat_at * 1000)},volume=0.8[b];[1:a][b]amix=inputs=2:duration=first:normalize=0[a]",
                    "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", str(final)], check=True)
    tmp.unlink()
    bed.unlink()
    print(final, f"{total:.1f}s")


if __name__ == "__main__":
    for lang in sys.argv[1:] or LANGS:
        build(lang)
