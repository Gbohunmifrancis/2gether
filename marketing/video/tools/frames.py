"""
Prepares the app's own pictures for the video, from the screenshot tests' output (android/app/build/screens):
- screens/: the real English screens, as the store set renders them (StoreShotsTest);
- splash/: the splash, frame by frame at 30 fps (VideoFramesTest), as JPEG;
- logo-<clip>/: the live logo's frames (VideoFramesTest) with exact transparency, recovered from the same frame on
  black and on white (difference matting: alpha = 1 - (white - black)), cropped to one box for every clip;
- art/: the app's stickers (StoreArtTest).
"""
import pathlib
import shutil

import numpy as np
from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent.parent
ROOT = HERE.parent.parent
SCREENS = ROOT / "android/app/build/screens"
BUILD = HERE / "out/build"

SCREEN_NAMES = ["today", "new-dare", "alarm", "camera", "heart-complete", "question", "games-draw", "album"]
ART = ["heart-full", "heart-left", "heart-right", "heart-empty", "both-hug", "both-cheer"]


def matte(black: Image.Image, white: Image.Image) -> Image.Image:
    b = np.asarray(black.convert("RGB"), dtype=np.float32) / 255
    w = np.asarray(white.convert("RGB"), dtype=np.float32) / 255
    alpha = np.clip(1 - (w - b).mean(axis=2), 0, 1)
    colour = np.where(alpha[..., None] > 1e-3, b / np.maximum(alpha[..., None], 1e-3), 0)
    rgba = np.dstack([np.clip(colour, 0, 1), alpha])
    return Image.fromarray((rgba * 255).round().astype(np.uint8), "RGBA")


def main() -> None:
    for folder in ["screens", "splash", "art"]:
        shutil.rmtree(BUILD / folder, ignore_errors=True)
        (BUILD / folder).mkdir(parents=True)
    for name in SCREEN_NAMES:
        shutil.copy(SCREENS / f"store/en/{name}.png", BUILD / f"screens/{name}.png")
    for name in ART:
        shutil.copy(SCREENS / f"store/art/{name}.png", BUILD / f"art/{name}.png")
    for frame in sorted((SCREENS / "video/splash").glob("*.png")):
        Image.open(frame).convert("RGB").save(BUILD / f"splash/{frame.stem}.jpg", quality=92)

    clips = {}
    for clip in ["drop", "clap", "flip"]:
        source = SCREENS / f"video/logo-{clip}"
        clips[clip] = [matte(Image.open(source / "black" / f.name), Image.open(f)) for f in sorted((source / "white").glob("*.png"))]
    # One crop box for every frame of every clip, so the logo never jumps between clips.
    box = None
    for frames in clips.values():
        for frame in frames:
            b = frame.getchannel("A").point(lambda v: 255 if v > 2 else 0).getbbox()
            if b:
                box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    pad = 12
    box = (max(0, box[0] - pad), max(0, box[1] - pad), box[2] + pad, box[3] + pad)
    for clip, frames in clips.items():
        out = BUILD / f"logo-{clip}"
        shutil.rmtree(out, ignore_errors=True)
        out.mkdir(parents=True)
        for i, frame in enumerate(frames):
            frame.crop(box).save(out / f"{i:04d}.png")
    (BUILD / "logo-box.txt").write_text(f"{box}\n")
    print("frames ready; logo box", box, {k: len(v) for k, v in clips.items()})


if __name__ == "__main__":
    main()
