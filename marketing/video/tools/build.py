"""
Builds the launch video from the app's own screens and animations:

    python3 marketing/video/tools/build.py            # both formats: out/heartpact-launch-9x16.mp4 and -16x9.mp4
    python3 marketing/video/tools/build.py 9x16       # one format
    python3 marketing/video/tools/build.py stills     # a few key frames per format, as JPEGs, for checking the design

Before it: the app's frames (frames.py, from the screenshot tests) and the voice (voice.py, see README.md).
Steps: the timeline (each scene as long as its line needs, on the music's beat), the music (music.py), the audio mix
(ffmpeg: music, the app's splash sound, the voice), then every frame of video.html rendered in Chromium (render.cjs)
and encoded with the audio.
"""
import json
import math
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent.parent
TOOLS = HERE / "tools"
BUILD = HERE / "out/build"
ROOT = HERE.parent.parent
SPLASH_SOUND = ROOT / "android/app/src/main/res/raw/splash_love.ogg"

FPS = 30
BEAT = 0.6                      # 100 BPM
SPLASH_END = 3.3                # SplashTimeline.END
FORMATS = {"9x16": (1080, 1920), "16x9": (1920, 1080)}
# Shortest scene, in beats, so each screen is on long enough to read; zoom-ins need a little more.
MIN_BEATS = {"today": 5, "dare": 6, "alarm": 5, "camera": 5, "heart": 6, "question": 5, "games": 5, "album": 5}
END_CARD = 5.4


def beats(seconds: float, least: int) -> float:
    return max(least, math.ceil(seconds / BEAT - 1e-6)) * BEAT


def timeline() -> dict:
    script = json.loads((HERE / "script.json").read_text())
    lengths = json.loads((BUILD / "voice/lines.json").read_text())
    scenes, voice = [], []
    t = 0.0
    for scene in script["scenes"]:
        sid = scene["id"]
        if sid == "intro":
            start, end, vo = 0.0, SPLASH_END, 0.55
        elif sid == "end":
            start, end, vo = t, t + END_CARD, t + 0.75
        else:
            lead = 0.3 if scene.get("carousel") else 0.4
            start, vo = t, t + lead
            end = start + beats(lead + lengths[sid] + 0.5, MIN_BEATS[sid])
        entry = {k: v for k, v in scene.items() if k != "vo"} | {"start": round(start, 3), "end": round(end, 3)}
        if scene.get("zoom"):
            entry["zoomAt"] = [round(start + 0.65, 3)]
        scenes.append(entry)
        voice.append({"id": sid, "start": round(vo, 3), "length": lengths[sid]})
        t = end
    result = {"fps": FPS, "beat": BEAT, "musicStart": SPLASH_END, "length": round(t, 3), "scenes": scenes, "voice": voice}
    (BUILD / "timeline.json").write_text(json.dumps(result, indent=1))
    return result


def mix(plan: dict) -> pathlib.Path:
    """Music + the app's splash sound + the voice, levelled for social video (-14 LUFS, peaks under -1.5 dB)."""
    inputs = ["-i", str(BUILD / "music.wav"), "-i", str(SPLASH_SOUND)]
    chains = ["[1:a]aresample=48000,aformat=channel_layouts=stereo,volume=0.95[s]"]
    labels = ["[0:a]", "[s]"]
    for i, line in enumerate(plan["voice"]):
        inputs += ["-i", str(BUILD / f"voice/{line['id']}.wav")]
        ms = int(line["start"] * 1000)
        chains.append(
            f"[{i + 2}:a]aresample=48000,highpass=f=90,"
            f"acompressor=threshold=0.1:ratio=3:attack=5:release=120,volume=2.2,"
            f"aformat=channel_layouts=stereo,adelay={ms}|{ms}[v{i}]")
        labels.append(f"[v{i}]")
    graph = ";".join(chains) + ";" + "".join(labels) + f"amix=inputs={len(labels)}:normalize=0,atrim=0:{plan['length']}," \
        "loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000[out]"
    out = BUILD / "audio.wav"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *inputs, "-filter_complex", graph, "-map", "[out]", "-ar", "48000", str(out)], check=True)
    return out


def render(name: str, audio: pathlib.Path, plan: dict) -> pathlib.Path:
    width, height = FORMATS[name]
    silent = BUILD / f"video-{name}.mp4"
    subprocess.run(["node", str(TOOLS / "render.cjs"), str(TOOLS / "video.html"), str(width), str(height), str(FPS), str(plan["length"]), str(silent)], check=True)
    out = HERE / f"out/heartpact-launch-{name}.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(silent), "-i", str(audio), "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-shortest", "-movflags", "+faststart", str(out)], check=True)
    return out


def stills(plan: dict) -> None:
    for name, (width, height) in FORMATS.items():
        times = []
        for scene in plan["scenes"]:
            mid = (scene["start"] + scene["end"]) / 2
            times += [scene["start"] + 0.25, mid, scene["end"] - 0.15]
        subprocess.run(["node", str(TOOLS / "render.cjs"), str(TOOLS / "video.html"), str(width), str(height), "stills",
                        ",".join(f"{t:.3f}" for t in times), str(BUILD / f"stills-{name}")], check=True)


def main() -> None:
    args = sys.argv[1:]
    plan = timeline()
    if args == ["stills"]:
        stills(plan)
        return
    subprocess.run([sys.executable, str(TOOLS / "music.py")], check=True)
    audio = mix(plan)
    for name in args or list(FORMATS):
        print("wrote", render(name, audio, plan))


if __name__ == "__main__":
    main()
