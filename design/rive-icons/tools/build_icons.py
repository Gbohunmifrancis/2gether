#!/usr/bin/env python3
"""Writes icons.rml: Heartpact's 3D icons, one 200x200 artboard each, plus a Sheet artboard showing them all.

    python3 tools/build_icons.py && ~/.rive/bin/rive . --once     # -> build/heartpact_icons.riv

Every icon floats gently at rest ("Idle") and pops when its view model's `active` turns true ("Pop", then "Active",
a livelier loop), settling back when it turns false. The app sets `active` on the selected tab.
"""
import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import rml3d as R  # noqa: E402

NAVY, INDIGO, PERI, SOFT, WHITE, PINK = "#1F2557", "#4D58BE", "#7480D4", "#D5D8F6", "#F4F5FC", "#F45C91"


def heart(cx, cy, s, half=None):
    """The app's heart, centred on (cx, cy) at scale s (1 = 76 units wide). half: "L" or "R" for one side."""
    def p(x, y):
        return f"{cx + (x - 50) * s:.2f} {cy + (y - 50) * s:.2f}"
    left = f"M{p(50, 86)} C{p(18, 64)} {p(8, 46)} {p(12, 32)} C{p(16, 18)} {p(34, 12)} {p(50, 27)}"
    right = f"C{p(66, 12)} {p(84, 18)} {p(88, 32)} C{p(92, 46)} {p(82, 64)} {p(50, 86)}"
    if half == "L":
        return left + " Z"
    if half == "R":
        return f"M{p(50, 86)} C{p(82, 64)} {p(92, 46)} {p(88, 32)} C{p(84, 18)} {p(66, 12)} {p(50, 27)} Z"
    return f"{left} {right} Z"


def rot(points, deg, c=(50, 50)):
    a = math.radians(deg)
    return [(c[0] + (x - c[0]) * math.cos(a) - (y - c[1]) * math.sin(a),
             c[1] + (x - c[0]) * math.sin(a) + (y - c[1]) * math.cos(a)) for x, y in points]


def card(x, y, w, h, deg):
    return rot([(x, y), (x + w, y), (x + w, y + h), (x, y + h)], deg)


def star(cx, cy, r1, r2, n=5):
    return [(cx + (r1 if i % 2 == 0 else r2) * math.sin(math.pi * i / n),
             cy - (r1 if i % 2 == 0 else r2) * math.cos(math.pi * i / n)) for i in range(2 * n)]


def eyes(cx, cy, gap, prefix):
    return [dict(name=f"{prefix} pupil L", geom=("ellipse", cx - gap, cy + 0.6, 1.9, 2.2), color=NAVY, depth=0, rim=0),
            dict(name=f"{prefix} pupil R", geom=("ellipse", cx + gap, cy + 0.6, 1.9, 2.2), color=NAVY, depth=0, rim=0),
            dict(name=f"{prefix} eye L", geom=("ellipse", cx - gap, cy, 4.2, 4.6), color=WHITE, depth=1),
            dict(name=f"{prefix} eye R", geom=("ellipse", cx + gap, cy, 4.2, 4.6), color=WHITE, depth=1)]


ICONS = {
    # The five tabs
    "today": dict(shadow=(64, 90), parts=[
        dict(name="Ring L", geom=("rect", 31, 12, 8, 18, 4), color=WHITE, depth=3),
        dict(name="Ring R", geom=("rect", 61, 12, 8, 18, 4), color=WHITE, depth=3),
        dict(name="Heart", geom=("path", heart(50, 61, 0.36)), color=PINK, depth=3, gloss=(44, 54, 7, 5)),
        dict(name="Band", geom=("path", "M16 40 L16 33 C16 25 22 20 30 20 L70 20 C78 20 84 25 84 33 L84 40 Z"), color=INDIGO, depth=0, rim=90),
        dict(name="Page", geom=("rect", 16, 20, 68, 64, 13), color=WHITE, depth=9, gloss=(34, 46, 26, 14), glossA=90),
    ]),
    "pacts": dict(shadow=(64, 92), parts=[
        dict(name="Tick", geom=("line", "M33 49 L46 62 L69 37", 9), color=WHITE, depth=3),
        dict(name="Left", geom=("path", heart(50, 50, 1, "L")), color=NAVY, depth=9, gloss=(30, 34, 16, 10), glossA=70),
        dict(name="Right", geom=("path", heart(50, 50, 1, "R")), color=PERI, depth=9, gloss=(68, 32, 12, 7), glossA=110),
    ]),
    "food": dict(shadow=(66, 92), parts=[
        dict(name="Berry", geom=("ellipse", 67, 37, 8, 8), color=PINK, depth=3, gloss=(64, 34, 4, 3)),
        dict(name="Rice", geom=("ellipse", 40, 44, 15, 11), color=WHITE, depth=3),
        dict(name="Greens", geom=("ellipse", 62, 47, 14, 8), color=PERI, depth=3),
        dict(name="Inside", geom=("ellipse", 50, 52, 36, 8), color=NAVY, depth=0, rim=40),
        dict(name="Bowl", geom=("path", "M13 52 L87 52 C87 72 71 87 50 87 C29 87 13 72 13 52 Z"), color=INDIGO, depth=8, gloss=(32, 62, 12, 8), glossA=110),
    ]),
    "us": dict(shadow=(70, 92), parts=[
        *eyes(33, 48, 5.5, "Maya"),
        *eyes(67, 48, 5.5, "Theo"),
        dict(name="Cheek L", geom=("ellipse", 26, 56, 3.4, 2.2), color=PINK, depth=0, rim=0),
        dict(name="Cheek R", geom=("ellipse", 74, 56, 3.4, 2.2), color=PINK, depth=0, rim=0),
        dict(name="Maya", geom=("path", heart(46, 50, 1, "L")), color=NAVY, depth=9, gloss=(26, 34, 14, 9), glossA=70),
        dict(name="Theo", geom=("path", heart(54, 50, 1, "R")), color=PERI, depth=9, gloss=(72, 32, 11, 7), glossA=110),
    ]),
    "album": dict(shadow=(66, 92), parts=[
        dict(name="Sun", geom=("ellipse", 60, 42, 5, 5), color=PINK, depth=1),
        dict(name="Hills", geom=("poly", rot([(30, 66), (43, 49), (52, 59), (59, 52), (70, 66)], 6), 2), color=INDIGO, depth=1),
        dict(name="Window", geom=("poly", card(27, 33, 46, 37, 6), 3), color=SOFT, depth=0, rim=60),
        dict(name="Photo", geom=("poly", card(22, 27, 56, 55, 6), 6), color=WHITE, depth=5, gloss=(36, 36, 16, 8), glossA=80),
        dict(name="Behind", geom=("poly", card(22, 20, 56, 55, -10), 6), color=PERI, depth=5),
    ]),
    # Features
    "cycle": dict(shadow=(52, 92), parts=[
        dict(name="Drop", geom=("path", "M50 12 C50 12 22 44 22 61 C22 76 35 87 50 87 C65 87 78 76 78 61 C78 44 50 12 50 12 Z"),
             color=PINK, depth=9, gloss=(39, 58, 7, 12), glossA=170),
    ]),
    "plus": dict(shadow=(60, 92), parts=[
        dict(name="Plus", geom=("line", "M50 41 L50 63 M39 52 L61 52", 7), color=WHITE, depth=3),
        dict(name="Star", geom=("poly", star(50, 52, 38, 19), 5), color=INDIGO, depth=9, gloss=(40, 38, 14, 9), glossA=110),
    ]),
    "games": dict(shadow=(72, 90), parts=[
        dict(name="Pad H", geom=("rect", 25, 44, 18, 6, 3), color=WHITE, depth=2),
        dict(name="Pad V", geom=("rect", 31, 38, 6, 18, 3), color=WHITE, depth=2),
        dict(name="A", geom=("ellipse", 66, 42, 4.5, 4.5), color=PINK, depth=2),
        dict(name="B", geom=("ellipse", 73, 50, 4.5, 4.5), color=SOFT, depth=2),
        dict(name="Pad", geom=("path", "M24 30 L76 30 C83 30 87 35 88 42 L92 66 C94 78 82 84 74 75 L66 67 L34 67 L26 75 "
                                       "C18 84 6 78 8 66 L12 42 C13 35 17 30 24 30 Z"), color=INDIGO, depth=8, gloss=(32, 38, 16, 7), glossA=110),
    ]),
    "dares": dict(shadow=(72, 88), parts=[
        dict(name="Inner L", geom=("rect", 27, 32, 9, 36, 4), color=INDIGO, depth=6),
        dict(name="Inner R", geom=("rect", 64, 32, 9, 36, 4), color=INDIGO, depth=6),
        dict(name="Outer L", geom=("rect", 14, 26, 12, 48, 5), color=PERI, depth=7, gloss=(18, 36, 3, 9)),
        dict(name="Outer R", geom=("rect", 74, 26, 12, 48, 5), color=PERI, depth=7, gloss=(78, 36, 3, 9)),
        dict(name="Bar", geom=("rect", 30, 46, 40, 8, 4), color=NAVY, depth=4),
    ]),
    "location": dict(shadow=(46, 92), parts=[
        dict(name="Heart", geom=("path", heart(50, 40, 0.24)), color=PINK, depth=2),
        dict(name="Dot", geom=("ellipse", 50, 39, 12, 12), color=WHITE, depth=2),
        dict(name="Pin", geom=("path", "M50 89 C50 89 21 59 21 40 C21 23 34 11 50 11 C66 11 79 23 79 40 C79 59 50 89 50 89 Z"),
             color=INDIGO, depth=9, gloss=(36, 28, 10, 8), glossA=120),
    ]),
    "question": dict(shadow=(66, 90), parts=[
        dict(name="Dot 1", geom=("ellipse", 36, 42, 5, 5), color=NAVY, depth=2),
        dict(name="Dot 2", geom=("ellipse", 50, 42, 5, 5), color=INDIGO, depth=2),
        dict(name="Dot 3", geom=("ellipse", 64, 42, 5, 5), color=PINK, depth=2),
        dict(name="Bubble", geom=("path", "M33 15 L67 15 C76 15 82 21 82 30 L82 54 C82 63 76 69 67 69 L47 69 L31 82 L34 69 "
                                          "L33 69 C24 69 18 63 18 54 L18 30 C18 21 24 15 33 15 Z"), color=WHITE, depth=8, gloss=(34, 26, 16, 6), glossA=80),
    ]),
    "bell": dict(shadow=(60, 92), parts=[
        dict(name="Knob", geom=("ellipse", 50, 15, 5, 5), color=INDIGO, depth=3),
        dict(name="Bell", geom=("path", "M50 17 C35 17 27 29 27 43 L27 57 C27 61 24 64 20 67 C17 69 19 72 22 72 L78 72 "
                                        "C81 72 83 69 80 67 C76 64 73 61 73 57 L73 43 C73 29 65 17 50 17 Z"), color=INDIGO, depth=8, gloss=(38, 32, 9, 12), glossA=120),
        dict(name="Clapper", geom=("ellipse", 50, 79, 7, 6.5), color=PINK, depth=3),
    ]),
}

PIVOT_Y = 144  # icons squash and stretch from their base


def kf(value, frame, ease="cubic", curve=(0.42, 0, 0.58, 1)):
    inner = f'<CubicEaseInterpolator x1="{curve[0]}" y1="{curve[1]}" x2="{curve[2]}" y2="{curve[3]}"/>' if ease == "cubic" else ""
    return f'<KeyFrameDouble value="{value}" frame="{frame}" interpolationType="{ease}">{inner}</KeyFrameDouble>'


def track(obj, prop, frames):
    return (f'<KeyedObject objectId="{obj}"><KeyedProperty property="{prop}">'
            + "".join(kf(v, f) for f, v in frames) + "</KeyedProperty></KeyedObject>")


def artboard(name, spec, x, vm):
    ids = {k: R.nid() for k in ("ab", "style", "sm", "layer", "idle_s", "pop_s", "active_s", "idle", "pop", "active", "icon")}
    body = "\n".join(R.part(p) for p in spec["parts"])
    shadow = R.contact_shadow(*spec["shadow"])
    icon, sh = ids["icon"], shadow.split('id="')[1].split('"')[0]

    def cond(value):
        return (f'<TransitionViewModelCondition opValue="equal"><TransitionPropertyViewModelComparator><BindablePropertyBoolean>'
                f'<DataBindContext sourcePathIds="{vm["vm"]}-{vm["active"]}" propertyKey="634"/></BindablePropertyBoolean>'
                f'</TransitionPropertyViewModelComparator><TransitionValueBooleanComparator value="{value}"/></TransitionViewModelCondition>')

    idle = (track(icon, "y", [(0, PIVOT_Y), (90, PIVOT_Y - 5), (180, PIVOT_Y)])
            + track(icon, "rotation", [(0, -0.025), (90, 0.025), (180, -0.025)])
            + track(sh, "scaleX", [(0, 1), (90, 0.86), (180, 1)])
            + track(sh, "opacity", [(0, 1), (90, 0.7), (180, 1)]))
    pop = (track(icon, "scaleX", [(0, 1), (8, 1.16), (16, 0.94), (24, 1.1), (34, 1.08)])
           + track(icon, "scaleY", [(0, 1), (8, 0.84), (16, 1.18), (24, 1.04), (34, 1.08)])
           + track(icon, "y", [(0, PIVOT_Y), (8, PIVOT_Y), (16, PIVOT_Y - 16), (26, PIVOT_Y), (34, PIVOT_Y - 2)])
           + track(icon, "rotation", [(0, 0), (12, -0.12), (22, 0.08), (34, 0)])
           + track(sh, "scaleX", [(0, 1), (8, 1.1), (16, 0.7), (26, 1.05), (34, 1)]))
    active = (track(icon, "scaleX", [(0, 1.08), (60, 1.08)]) + track(icon, "scaleY", [(0, 1.08), (60, 1.08)])
              + track(icon, "y", [(0, PIVOT_Y - 2), (30, PIVOT_Y - 8), (60, PIVOT_Y - 2)])
              + track(icon, "rotation", [(0, -0.05), (30, 0.05), (60, -0.05)])
              + track(sh, "scaleX", [(0, 1), (30, 0.8), (60, 1)]))
    return f"""
<Artboard isComponent="true" viewModelId="{vm["vm"]}" viewModelInstanceId="{vm["inst"]}" defaultStateMachineId="{ids["sm"]}" styleId="{ids["style"]}" x="{x % 1200}" y="{(x // 1200) * 240}" width="200" height="200" name="{name}" id="{ids["ab"]}">
<LayoutComponentStyle name="Style" id="{ids["style"]}"/>
<Node x="{R.CX}" y="{PIVOT_Y}" name="Icon" id="{icon}">
<Node x="{-R.CX}" y="{-PIVOT_Y}" name="Art">
{body}
</Node>
</Node>
{shadow}
<StateMachine name="Icon" id="{ids["sm"]}">
<StateMachineLayer name="Main" id="{ids["layer"]}">
<AnyState x="0" y="-150"/><ExitState x="600" y="-150"/>
<EntryState x="0" y="0"><StateTransition stateToId="{ids["idle_s"]}"/></EntryState>
<AnimationState x="200" y="0" animationId="{ids["idle"]}" id="{ids["idle_s"]}">
<StateTransition stateToId="{ids["pop_s"]}">{cond("true")}</StateTransition>
</AnimationState>
<AnimationState x="400" y="0" animationId="{ids["pop"]}" id="{ids["pop_s"]}">
<StateTransition stateToId="{ids["active_s"]}" enableExitTime="true" exitTimeIsPercetange="true" exitTime="100"/>
</AnimationState>
<AnimationState x="400" y="150" animationId="{ids["active"]}" id="{ids["active_s"]}">
<StateTransition stateToId="{ids["idle_s"]}" duration="250">{cond("false")}</StateTransition>
</AnimationState>
</StateMachineLayer>
</StateMachine>
<LinearAnimation loopValue="loop" duration="180" name="Idle" id="{ids["idle"]}">{idle}</LinearAnimation>
<LinearAnimation duration="34" name="Pop" id="{ids["pop"]}">{pop}</LinearAnimation>
<LinearAnimation loopValue="loop" duration="60" name="Active" id="{ids["active"]}">{active}</LinearAnimation>
</Artboard>
<ComponentAsset artboardId="{ids["ab"]}" name="{name}"/>
"""


def sheet(boards, cols=6, k=1.5):
    """Every icon, k times its size, on the app's page colour: for looking at, not for the app."""
    rows = math.ceil(len(boards) / cols)
    c = 200 * k
    nested = "".join(f'<NestedArtboard artboardId="{ab}" x="{(i % cols) * c}" y="{(i // cols) * c}" scaleX="{k}" scaleY="{k}" name="{n}">'
                     f'<NestedStateMachine animationId="{sm}" name="Icon"/></NestedArtboard>'
                     for i, (n, ab, sm) in enumerate(boards))
    style = R.nid()
    return (f'<Artboard styleId="{style}" x="0" y="{-60 - rows * c:.0f}" width="{cols * c:.0f}" height="{rows * c:.0f}" name="Sheet">'
            f'<LayoutComponentStyle name="Style" id="{style}"/>{nested}'
            f'<Fill name="Page"><SolidColor colorValue="FFE3E5F6" name="C"/></Fill></Artboard>')


def main():
    vm = {k: R.nid() for k in ("vm", "active", "inst")}
    out = ['<Rive version="1" kind="fragment">',
           f'<ViewModel defaultInstanceId="{vm["inst"]}" name="Icon" id="{vm["vm"]}">'
           f'<ViewModelPropertyBoolean name="active" id="{vm["active"]}"/>'
           f'<ViewModelInstance exports="true" name="Default" id="{vm["inst"]}">'
           f'<ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{vm["active"]}"/></ViewModelInstance></ViewModel>']
    boards = []
    for i, (name, spec) in enumerate(ICONS.items()):
        text = artboard(name, spec, i * 240, vm)
        boards.append((name, text.split('name="' + name + '" id="')[1].split('"')[0], text.split('defaultStateMachineId="')[1].split('"')[0]))
        out.append(text)
    out.append(sheet(boards))
    out.append("</Rive>")
    pathlib.Path(__file__).resolve().parent.parent.joinpath("icons.rml").write_text("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
