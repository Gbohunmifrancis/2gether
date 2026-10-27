#!/usr/bin/env python3
"""Writes splash.rml: the app's splash in 3D, on the timeline of the Compose splash (ui/splash/Splash.kt,
SplashTimeline), so the love sound (res/raw/splash_love.ogg) still lands on its heartbeats.

The two halves hop in from the sides with their faces, meet with a lean, close their eyes, join into the mark, the
tick draws on, the eyes open, the heart beats twice and blinks. The artboard is transparent: the app draws the
indigo background and the wordmark. One-shot, 3.3 seconds.

    python3 tools/build_splash.py     # build_icons.py runs it too
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import rml3d as R  # noqa: E402

# Night colours of the mark (ui/theme/Color.kt: MarkNightLeft, MarkNightRight; the tick is SplashIndigo).
LEFT, RIGHT, TICK, PUPIL, PINK = "#F4F5FC", "#A9B1F2", "#1F2557", "#1F2557", "#F45C91"

FPS = 60


def f(ms):
    """SplashTimeline milliseconds to frames."""
    return round(ms * FPS / 1000)


# SplashTimeline, in ms
WALK_START, WALK_END, MEET_END, TUCK_END, MORPH_END, TICK_END, FACE_END = 150, 1450, 1750, 1950, 2200, 2450, 2650
BEATS, DUB, BLINK, END = (2650, 2950), 190, 3120, 3300

GAP = 7.0  # design units each half stands off the seam until they join


def kf(value, frame, ease="cubic", curve=(0.42, 0, 0.58, 1)):
    inner = f'<CubicEaseInterpolator x1="{curve[0]}" y1="{curve[1]}" x2="{curve[2]}" y2="{curve[3]}"/>' if ease == "cubic" else ""
    return f'<KeyFrameDouble value="{value:.4f}" frame="{frame}" interpolationType="{ease}">{inner}</KeyFrameDouble>'


def track(obj, prop, frames, curve=(0.42, 0, 0.58, 1)):
    return (f'<KeyedObject objectId="{obj}"><KeyedProperty property="{prop}">'
            + "".join(kf(v, fr, curve=curve) for fr, v in frames) + "</KeyedProperty></KeyedObject>")


def pivot(name, at, inner, nid):
    """A node at `at` (artboard units) holding `inner` drawn in artboard space, so it scales and turns about `at`."""
    return f'<Node x="{at[0]:.2f}" y="{at[1]:.2f}" name="{name}" id="{nid}"><Node x="{-at[0]:.2f}" y="{-at[1]:.2f}" name="{name} art">{inner}</Node></Node>'


def eyes(cx, cy, gap, prefix):
    parts = []
    for side, x in (("L", cx - gap), ("R", cx + gap)):
        parts += [dict(name=f"{prefix} glint {side}", geom=("ellipse", x - 1.1, cy - 1.5, 1.2, 1.2), color="#FFFFFF", depth=0),
                  dict(name=f"{prefix} eye {side}", geom=("ellipse", x, cy, 3.3, 4.0), color=PUPIL, depth=0, lift=0.3)]
    return "\n".join(R.part(p) for p in parts)


def build():
    R._ids[0] = 5000           # clear of the icons' ids
    R.S, R.CX, R.CY = 2.4, 200, 196
    ids = {k: R.nid() for k in ("ab", "style", "sm", "layer", "state", "anim", "mark", "maya", "theo", "eyes_m",
                                 "eyes_t", "trim_group", "trim")}
    gap_px = GAP * R.S
    bottom = R.tx((50, 86))     # the heart's point: halves hop and squash from here
    centre = R.tx((50, 52))     # the mark beats about here
    eye_y = 36
    maya_eyes = pivot("Maya eyes", R.tx((33, eye_y)), eyes(33, eye_y, 5, "Maya"), ids["eyes_m"])
    theo_eyes = pivot("Theo eyes", R.tx((67, eye_y)), eyes(67, eye_y, 5, "Theo"), ids["eyes_t"])
    from build_icons import heart  # the same heart as the icons
    maya = R.part(dict(name="Maya body", geom=("path", heart(50, 50, 1, "L")), color=LEFT, depth=9, gloss=(30, 34, 16, 10), glossA=120))
    theo = R.part(dict(name="Theo body", geom=("path", heart(50, 50, 1, "R")), color=RIGHT, depth=9, gloss=(68, 32, 12, 7), glossA=120))
    tick = R.part(dict(name="Tick", geom=("line", "M37 59 L47 69 L65 51", 8), color=TICK, depth=3, effect=ids["trim_group"]))
    cheek_m = R.part(dict(name="Cheek L", geom=("ellipse", 24, 44, 3.4, 2.2), color=PINK, depth=0))
    cheek_t = R.part(dict(name="Cheek R", geom=("ellipse", 76, 44, 3.4, 2.2), color=PINK, depth=0))
    shadow = R.contact_shadow(76, 92)
    shadow_id = shadow.split('id="')[1].split('"')[0]

    walk0, walk1, meet1, tuck1, morph1, tick1, face1, end = (f(t) for t in (WALK_START, WALK_END, MEET_END, TUCK_END, MORPH_END, TICK_END, FACE_END, END))
    # Three hops on the way in.
    hops = [walk0 + round((walk1 - walk0) * k / 3) for k in range(4)]
    hop_y, hop_sy = [], []
    for a, b in zip(hops, hops[1:]):
        m = (a + b) // 2
        hop_y += [(a, 0), (m, -30)]
        hop_sy += [(a, 0.9), (a + 4, 1.04), (b - 4, 1.0)]
    hop_y += [(walk1, 0)]
    hop_sy += [(walk1, 0.92), (walk1 + 6, 1.0)]

    def half(node, sign, far):
        x = [(0, sign * far), (walk0, sign * far), (walk1, sign * gap_px), (tuck1, sign * gap_px), (morph1, 0)]
        lean = [(walk1, 0), ((walk1 + meet1) // 2, sign * -0.14), (meet1, 0)]
        y = hop_y + [((walk1 + meet1) // 2, -12), (meet1, 0)]
        return (track(node, "x", [(fr, bottom[0] + v) for fr, v in x], curve=(0.25, 0.1, 0.25, 1)) + track(node, "y", [(fr, bottom[1] + v) for fr, v in y])
                + track(node, "rotation", lean) + track(node, "scaleY", hop_sy))

    beats = []
    for b in BEATS:
        lub, dub = f(b), f(b + DUB)
        beats += [(lub, 1.0), (lub + 4, 1.1), (lub + 9, 1.0), (dub, 1.0), (dub + 3, 1.05), (dub + 7, 1.0)]
    eyes_open = [(0, 1), (meet1, 1), (tuck1, 0), (tick1, 0), (face1, 1)]
    blink = [(f(BLINK) - 1, 1), (f(BLINK) + 3, 0.1), (f(BLINK) + 7, 1)]
    anim = (half(ids["maya"], -1, 330) + half(ids["theo"], 1, 330)
            + track(ids["mark"], "scaleX", [(0, 1), (morph1, 1)] + beats) + track(ids["mark"], "scaleY", [(0, 1), (morph1, 1)] + beats)
            + track(ids["trim"], "end", [(0, 0), (morph1, 0), (tick1, 1)], curve=(0.3, 0, 0.2, 1))
            + track(ids["eyes_m"], "opacity", eyes_open) + track(ids["eyes_t"], "opacity", eyes_open)
            + track(ids["eyes_m"], "scaleY", [(0, 1)] + blink) + track(ids["eyes_t"], "scaleY", [(0, 1)] + blink)
            + track(shadow_id, "opacity", [(0, 0), (walk0, 0), (walk1, 1)]) + track(shadow_id, "scaleX", [(0, 0.6), (walk1, 1)] + beats))

    maya_node = f'<Node x="{bottom[0]:.2f}" y="{bottom[1]:.2f}" name="Maya" id="{ids["maya"]}"><Node x="{-bottom[0]:.2f}" y="{-bottom[1]:.2f}" name="Maya art">{maya_eyes}{cheek_m}{maya}</Node></Node>'
    theo_node = f'<Node x="{bottom[0]:.2f}" y="{bottom[1]:.2f}" name="Theo" id="{ids["theo"]}"><Node x="{-bottom[0]:.2f}" y="{-bottom[1]:.2f}" name="Theo art">{theo_eyes}{cheek_t}{theo}</Node></Node>'
    mark = pivot("Mark", centre, f"{tick}\n{maya_node}\n{theo_node}", ids["mark"])
    return f"""<Rive version="1" kind="fragment">
<Artboard defaultStateMachineId="{ids["sm"]}" styleId="{ids["style"]}" x="0" y="1400" width="400" height="400" name="splash" id="{ids["ab"]}">
<LayoutComponentStyle name="Style" id="{ids["style"]}"/>
<GroupEffect name="Draw tick" id="{ids["trim_group"]}"><TrimPath start="0" end="0" name="Trim" id="{ids["trim"]}"/></GroupEffect>
{mark}
{shadow}
<StateMachine name="Splash" id="{ids["sm"]}">
<StateMachineLayer name="Main" id="{ids["layer"]}">
<AnyState x="0" y="-150"/><ExitState x="400" y="-150"/>
<EntryState x="0" y="0"><StateTransition stateToId="{ids["state"]}"/></EntryState>
<AnimationState x="200" y="0" animationId="{ids["anim"]}" id="{ids["state"]}"/>
</StateMachineLayer>
</StateMachine>
<LinearAnimation duration="{end}" name="Splash" id="{ids["anim"]}">{anim}</LinearAnimation>
</Artboard>
</Rive>
"""


def main():
    pathlib.Path(__file__).resolve().parent.parent.joinpath("splash.rml").write_text(build())


if __name__ == "__main__":
    main()
