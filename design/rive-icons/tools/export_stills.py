#!/usr/bin/env python3
"""Exports each icon at rest as a transparent WebP for the Android app (res/drawable-nodpi/icon3d_<name>.webp): the
icons on the Us screen's rows, the tab bar's unselected tabs, and every icon wherever the animation can't run (animations
off, no Rive renderer, the screenshot tests).

Rive's headless capture has no alpha channel, so each icon is rendered twice, on black and on white, and the alpha is
recovered from the difference. Rendered at 3x and scaled down to 256 px.

    python3 tools/export_stills.py [out-dir]     # default: ../../android/app/src/main/res/drawable-nodpi
"""
import pathlib
import re
import shutil
import subprocess
import sys

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent.parent
RIVE = pathlib.Path.home() / ".rive/bin/rive"
SIZE, RENDER = 256, 600


def boards():
    text = (HERE / "icons.rml").read_text()
    for m in re.finditer(r'<Artboard isComponent="true"[^>]*defaultStateMachineId="([0-9:]+)"[^>]*name="([a-z]+)" id="([0-9:]+)"', text):
        yield m.group(2), m.group(3), m.group(1)


def render(work, ab, sm, background, out):
    k = RENDER / 200
    (work / "export.rml").write_text(
        f'<Rive version="1" kind="fragment"><Artboard styleId="9:2" x="0" y="-2000" width="{RENDER}" height="{RENDER}" name="export">'
        f'<LayoutComponentStyle name="Style" id="9:2"/>'
        f'<NestedArtboard artboardId="{ab}" x="0" y="0" scaleX="{k}" scaleY="{k}" name="Icon"><NestedStateMachine animationId="{sm}" name="Icon"/></NestedArtboard>'
        f'<Fill name="Bg"><SolidColor colorValue="{background}" name="C"/></Fill></Artboard></Rive>\n')
    subprocess.run([str(RIVE), str(work), f"--screenshot={out}", "--advance=1"], check=True, capture_output=True)


def matte(black, white):
    b, w = Image.open(black).convert("RGB"), Image.open(white).convert("RGB")
    out = Image.new("RGBA", b.size)
    bp, wp, op = b.load(), w.load(), out.load()
    for y in range(b.height):
        for x in range(b.width):
            br, bg, bb = bp[x, y]
            wr, wg, wb = wp[x, y]
            a = 255 - ((wr - br) + (wg - bg) + (wb - bb)) / 3
            if a <= 0:
                op[x, y] = (0, 0, 0, 0)
                continue
            s = 255 / a
            op[x, y] = (min(255, round(br * s)), min(255, round(bg * s)), min(255, round(bb * s)), round(a))
    return out


def main():
    dest = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parent.parent / "android/app/src/main/res/drawable-nodpi"
    work = HERE / "build/export"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    shutil.copy(HERE / "icons.rml", work)
    (work / "rive.yaml").write_text("name: export\nmain: export\n")
    for name, ab, sm in boards():
        render(work, ab, sm, "FF000000", work / "b.png")
        render(work, ab, sm, "FFFFFFFF", work / "w.png")
        still = matte(work / "b.png", work / "w.png").resize((SIZE, SIZE), Image.LANCZOS)
        still.save(dest / f"icon3d_{name}.webp", "WEBP", quality=92, method=6)
        print(dest / f"icon3d_{name}.webp")


if __name__ == "__main__":
    main()
