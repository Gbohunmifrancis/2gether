# Heartpact launch video

A 37-second launch ad, in two formats:

| File | Size | For |
| --- | --- | --- |
| `heartpact-launch-9x16.mp4` | 1080 × 1920, 30 fps | Reels, TikTok, Shorts, Stories, vertical feed ads |
| `heartpact-launch-16x9.mp4` | 1920 × 1080, 30 fps | YouTube, X, LinkedIn, the website, Play's promo video |

H.264 and AAC, loudness -14 LUFS (the social platforms' target), the moov atom up front for streaming.

**Everything in it is the real app.**
- **Screens:** the screens are the app's own Compose screens, as the store set renders them (`StoreShotsTest`).
- **Splash:** the opening is the app's splash, frame by frame (`VideoFramesTest`), with its own sound
  (`android/app/src/main/res/raw/splash_love.ogg`).
- **Logo:** the end card's logo is the live logo's drop-in and clap, frame by frame.

Nothing is invented: no reviews, ratings, install counts or prices (`PRODUCT.md`).

## The story

| Scene | Voice | On screen |
| --- | --- | --- |
| Intro | Every couple makes promises. | The splash: the halves walk in and become the mark; it shrinks into the phone |
| Today | Heartpact helps you keep them. | Today, the dare card lifting off the screen |
| Dare | Dare each other to move. Pick the exercise, the reps, and the time. | New dare; the reps and the time lift off |
| Alarm | When it's time, their phone rings. | The alarm, on a dark ground, buzzing on the beat with ripples |
| Camera | And the camera counts every rep. | The camera screen slides up; the "14 of 20" counter lifts off |
| Heart | Every promise you keep becomes a heart, on a page you share. | Heart complete; stickers burst out |
| Question, games, album | One question a day, just for you two. / Play together, even when you're apart. / And nothing is shared until you choose. | A carousel of the three |
| End | Heartpact. Keep your promises to each other. | The live logo drops in and claps; the wordmark, the line and the Google Play badge |

The voice and the titles are in `script.json`.

## Sound

- **Voice:** Kokoro (`af_heart`), an open text-to-speech model with Apache-2.0 weights, so it can be used in ads.
- **Music:** made from scratch by `tools/music.py`, so nothing is licensed. It's a C major groove at 100 BPM that starts
  on the beat after the splash's heartbeats and dips under the voice. A whoosh marks each cut and a pop each zoom-in.
- **Badge:** the Google Play badge is Google's own (`../play-store/tools/badges/en.png`), used unaltered.

## Rebuild

```bash
# 1. The app's frames: the store screens and the splash and logo animations (android/app/build/screens)
cd android
./gradlew testDebugUnitTest --tests '*StoreShotsTest.english' --tests '*VideoFramesTest*' -Proborazzi.test.record=true
cd ..
python3 marketing/video/tools/frames.py

# 2. The voice. Kokoro runs in its own environment, with the model from Hugging Face:
python3 -m venv /tmp/tts && /tmp/tts/bin/pip install kokoro-onnx soundfile
#    model.onnx and voices/af_heart.bin from huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX into $KOKORO_DIR,
#    then pack the voices: np.savez("voices.npz", af_heart=np.fromfile("voices/af_heart.bin", "float32").reshape(-1, 1, 256))
KOKORO_DIR=... /tmp/tts/bin/python marketing/video/tools/voice.py

# 3. Music, mix and video (needs Python 3 with numpy and Pillow, ffmpeg, and Node with Playwright and Chromium)
python3 marketing/video/tools/build.py           # both formats; or: build.py 9x16
python3 marketing/video/tools/build.py stills    # key frames as JPEGs, to check the design
```

| Piece | Where |
| --- | --- |
| Script: the voice, the titles, the zoom-ins | `script.json` |
| Timeline: each scene as long as its line, on the music's beat | `tools/build.py` |
| The motion design: one page, every frame a pure function of time | `tools/video.html` |
| Frame-by-frame rendering in Chromium, piped to ffmpeg | `tools/render.cjs` |
| Music and effects | `tools/music.py` |
| The app's frames, and the logo's transparency (from black and white renders) | `tools/frames.py`, `android/app/src/test/java/app/heartpact/screens/VideoFramesTest.kt` |

To change a line, edit `script.json` and run steps 2 and 3. The scenes re-time themselves to the new voice.
