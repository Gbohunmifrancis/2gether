"""
The voice-over: each scene's line from script.json, spoken by Kokoro, an open text-to-speech model whose weights are
Apache-2.0, so the audio is free to use in ads. One WAV per line in out/build/voice/, and their lengths in
out/build/voice/lines.json.

Kokoro runs through kokoro-onnx in its own environment (see README.md), with the model and voices from
huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX in $KOKORO_DIR (model.onnx and voices.npz):
    KOKORO_DIR=... <venv>/bin/python marketing/video/tools/voice.py
"""
import json
import os
import pathlib

import numpy as np
import soundfile
from kokoro_onnx import Kokoro

HERE = pathlib.Path(__file__).resolve().parent.parent
OUT = HERE / "out/build/voice"
MODEL = pathlib.Path(os.environ["KOKORO_DIR"])

script = json.loads((HERE / "script.json").read_text())
OUT.mkdir(parents=True, exist_ok=True)
kokoro = Kokoro(str(MODEL / "model.onnx"), str(MODEL / "voices.npz"))
lines = {}
for scene in script["scenes"]:
    audio, rate = kokoro.create(scene["vo"], voice=script["voice"], speed=script["speed"], lang="en-us")
    # Trim the model's silence at either end so the timeline can place each line exactly.
    loud = np.flatnonzero(np.abs(audio) > 0.01)
    audio = audio[max(0, loud[0] - int(0.02 * rate)): loud[-1] + int(0.08 * rate)]
    soundfile.write(OUT / f"{scene['id']}.wav", audio, rate)
    lines[scene["id"]] = round(len(audio) / rate, 3)
    print(f"{scene['id']:9} {lines[scene['id']]:5.2f}s  {scene['vo']}")
(OUT / "lines.json").write_text(json.dumps(lines, indent=1))
