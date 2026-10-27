"""Builds glossy, extruded "3D" icons as Rive RML from flat vector parts.

Each icon is a list of parts in a 100x100 design space, front to back. A part is a geometry (an SVG path, a rounded
rectangle, an ellipse or a stroked path) and a colour; the builder gives it a thick side (copies of the outline stepped
down and right, darker), a face lit from the top left (a gradient), a rim light and a soft inner shade, and the icon a
contact shadow. Pure RML: no scripts, so the .riv plays in any runtime without signing.
"""
import math
import re

S = 1.4            # design units to artboard units
CX, CY = 100, 94   # where the design centre (50, 50) lands on a 200x200 artboard
LIGHT = (0.38, 1.0)  # direction the sides extrude in (down and a little right)

_ids = [100]


def nid():
    _ids[0] += 1
    return f"0:{_ids[0]}"


def hexc(rgb, a=255):
    return f"{a:02X}{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}"


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(c, d, t):
    return tuple(round(a + (b - a) * t) for a, b in zip(c, d))


def lighten(c, t):
    return mix(c, (255, 255, 255), t)


def darken(c, t):
    return mix(c, (12, 14, 40), t)


# ---- SVG path to Rive vertices -------------------------------------------------------------------------------

def _tokens(d):
    for m in re.finditer(r"[MmLlHhVvCcSsQqZz]|-?\d*\.?\d+(?:e-?\d+)?", d):
        yield m.group(0)


def parse_svg(d):
    """Subpaths as lists of nodes {p, cin, cout}, closed flag. Absolute and relative M L H V C S Q Z."""
    toks = list(_tokens(d))
    i = 0
    subs, nodes = [], []
    cur = (0.0, 0.0)
    start = cur
    cmd = None
    last_c2 = None

    def num():
        nonlocal i
        v = float(toks[i]); i += 1
        return v

    def add(p, cin=None):
        nodes.append({"p": p, "cin": cin, "cout": None})

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t; i += 1
            if cmd in "Zz":
                if nodes:
                    # merge a closing node that repeats the start
                    if len(nodes) > 1 and math.dist(nodes[-1]["p"], nodes[0]["p"]) < 1e-6:
                        nodes[0]["cin"] = nodes[-1]["cin"]; nodes.pop()
                    subs.append((nodes, True))
                nodes = []; cur = start; last_c2 = None
                continue
        rel = cmd.islower()
        C = cmd.upper()
        ox, oy = cur if rel else (0.0, 0.0)
        if C == "M":
            if nodes:
                subs.append((nodes, False)); nodes = []
            cur = (ox + num(), oy + num()); start = cur
            add(cur); cmd = "l" if rel else "L"; last_c2 = None
        elif C == "L":
            cur = (ox + num(), oy + num()); add(cur); last_c2 = None
        elif C == "H":
            cur = ((cur[0] if rel else 0) + num(), cur[1]); add(cur); last_c2 = None
        elif C == "V":
            cur = (cur[0], (cur[1] if rel else 0) + num()); add(cur); last_c2 = None
        elif C in "CS":
            if C == "C":
                c1 = (ox + num(), oy + num())
            else:
                c1 = (2 * cur[0] - last_c2[0], 2 * cur[1] - last_c2[1]) if last_c2 else cur
            c2 = (ox + num(), oy + num()); p = (ox + num(), oy + num())
            nodes[-1]["cout"] = c1; add(p, c2); cur = p; last_c2 = c2
        elif C == "Q":
            q = (ox + num(), oy + num()); p = (ox + num(), oy + num())
            c1 = (cur[0] + 2 / 3 * (q[0] - cur[0]), cur[1] + 2 / 3 * (q[1] - cur[1]))
            c2 = (p[0] + 2 / 3 * (q[0] - p[0]), p[1] + 2 / 3 * (q[1] - p[1]))
            nodes[-1]["cout"] = c1; add(p, c2); cur = p; last_c2 = None
    if nodes:
        subs.append((nodes, False))
    return subs


def _area(nodes):
    pts = [n["p"] for n in nodes]
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(pts, pts[1:] + pts[:1])) / 2


def tx(p, dx=0.0, dy=0.0):
    return ((p[0] - 50) * S + CX + dx, (p[1] - 50) * S + CY + dy)


def points_path(nodes, closed, dx=0.0, dy=0.0):
    cw = _area(nodes) > 0  # y grows down: positive shoelace runs clockwise on screen
    out = [f'<PointsPath isClosed="{"true" if closed else "false"}" isClockwise="{"true" if cw else "false"}" name="Path">']
    for n in nodes:
        x, y = tx(n["p"], dx, dy)
        cin, cout = n["cin"], n["cout"]
        if cin is None and cout is None:
            out.append(f'<StraightVertex x="{x:.2f}" y="{y:.2f}"/>')
            continue

        def handle(c):
            if c is None:
                return 0.0, 0.0
            hx, hy = tx(c, dx, dy)
            return math.atan2(hy - y, hx - x), math.hypot(hx - x, hy - y)
        ir, idist = handle(cin)
        orr, odist = handle(cout)
        out.append(f'<CubicDetachedVertex x="{x:.2f}" y="{y:.2f}" inRotation="{ir:.4f}" inDistance="{idist:.2f}" '
                   f'outRotation="{orr:.4f}" outDistance="{odist:.2f}"/>')
    out.append("</PointsPath>")
    return "\n".join(out)


# ---- Geometry kinds ------------------------------------------------------------------------------------------

K = 0.5523  # cubic handle length for a quarter circle


def as_path(g):
    """Rectangles and ellipses as clockwise SVG paths. Rive's own Rectangle and Ellipse run the other way, and a
    feathered fill (the rim light, the inner shade, the contact shadow) needs the clockwise fill rule, under which
    those draw nothing."""
    if g[0] == "ellipse":
        _, x, y, rx, ry = g
        kx, ky = rx * K, ry * K
        return (f"M{x} {y - ry} C{x + kx} {y - ry} {x + rx} {y - ky} {x + rx} {y} C{x + rx} {y + ky} {x + kx} {y + ry} {x} {y + ry} "
                f"C{x - kx} {y + ry} {x - rx} {y + ky} {x - rx} {y} C{x - rx} {y - ky} {x - kx} {y - ry} {x} {y - ry} Z")
    _, x, y, w, h, r = g
    r = min(r, w / 2, h / 2)
    k = r * K
    x1, y1 = x + w, y + h
    return (f"M{x + r} {y} L{x1 - r} {y} C{x1 - r + k} {y} {x1} {y + r - k} {x1} {y + r} L{x1} {y1 - r} "
            f"C{x1} {y1 - r + k} {x1 - r + k} {y1} {x1 - r} {y1} L{x + r} {y1} C{x + r - k} {y1} {x} {y1 - r + k} {x} {y1 - r} "
            f"L{x} {y + r} C{x} {y + r - k} {x + r - k} {y} {x + r} {y} Z")


def geometry(g, dx=0.0, dy=0.0):
    """RML for the geometry, positioned in artboard space, offset by (dx, dy)."""
    kind = g[0]
    if kind in ("rect", "ellipse"):
        g, kind = ("path", as_path(g)), "path"
    if kind in ("path", "line"):
        return "\n".join(points_path(n, c, dx, dy) for n, c in parse_svg(g[1]))
    if kind == "poly":
        pts, r = g[1], g[2]
        cw = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(pts, pts[1:] + pts[:1])) > 0
        verts = "".join(f'<StraightVertex x="{tx(q, dx, dy)[0]:.2f}" y="{tx(q, dx, dy)[1]:.2f}" radius="{r * S:.2f}"/>' for q in pts)
        return f'<PointsPath isClosed="true" isClockwise="{"true" if cw else "false"}" name="Path">{verts}</PointsPath>'
    if kind == "rect":
        _, x, y, w, h, r = g
        cx, cy = tx((x + w / 2, y + h / 2), dx, dy)
        return (f'<Rectangle x="{cx - CX:.2f}" y="{cy - CY:.2f}" width="{w * S:.2f}" height="{h * S:.2f}" '
                f'cornerRadiusTL="{r * S:.2f}" originX="0.5" originY="0.5" name="Path"/>')
    if kind == "ellipse":
        _, x, y, rx, ry = g
        cx, cy = tx((x, y), dx, dy)
        return (f'<Ellipse x="{cx - CX:.2f}" y="{cy - CY:.2f}" width="{2 * rx * S:.2f}" height="{2 * ry * S:.2f}" '
                f'originX="0.5" originY="0.5" name="Path"/>')
    raise ValueError(kind)


def bbox(g):
    if g[0] in ("path", "line"):
        pts = [n["p"] for nodes, _ in parse_svg(g[1]) for n in nodes]
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        pad = g[2] / 2 if g[0] == "line" else 0
        return min(xs) - pad, min(ys) - pad, max(xs) + pad, max(ys) + pad
    if g[0] == "poly":
        xs, ys = [q[0] for q in g[1]], [q[1] for q in g[1]]
        return min(xs), min(ys), max(xs), max(ys)
    if g[0] == "rect":
        _, x, y, w, h, _r = g
        return x, y, x + w, y + h
    _, x, y, rx, ry = g
    return x - rx, y - ry, x + rx, y + ry


# Geometry is absolute, in a Shape at 0,0; the icon's Node moves it all as one.
def shape_open(g, name, sid=None):
    at = 'x="0" y="0"'
    ident = f' id="{sid}"' if sid else ""
    return f'<Shape {at} name="{name}"{ident}>'


def paint(g, inner, effect=None):
    """A Fill, or for a line a Stroke, around the given colour element. effect: a GroupEffect id every stroke uses."""
    if g[0] == "line":
        use = f'<TargetEffect targetId="{effect}" name="Effect"/>' if effect else ""
        return f'<Stroke thickness="{g[2] * S:.2f}" cap="round" join="round" name="Paint">{inner}{use}</Stroke>'
    return f'<Fill name="Paint">{inner}</Fill>'


def part(p):
    """One part: face (lit), rim light, inner shade, then its extruded side, front to back."""
    g, base = p["geom"], rgb(p["color"])
    depth = p.get("depth", 6)
    x0, y0, x1, y1 = bbox(g)
    a = tx((x0, y0)); b = tx((x1, y1))
    hi, lo = lighten(base, p.get("lift", 0.22)), darken(base, 0.12)
    out = [f'<Node name="{p["name"]}">']
    # Face
    out.append(shape_open(g, p["name"] + " face"))
    out.append(geometry(g))
    grad = (f'<LinearGradient startX="{a[0]:.1f}" startY="{a[1]:.1f}" endX="{b[0]:.1f}" endY="{b[1]:.1f}" name="Lit">'
            f'<GradientStop colorValue="{hexc(hi)}" position="0"/><GradientStop colorValue="{hexc(base)}" position="0.55"/>'
            f'<GradientStop colorValue="{hexc(lo)}" position="1"/></LinearGradient>')
    out.append(paint(g, grad, p.get("effect")) if g[0] == "line" else f'<Fill name="Face">{grad}</Fill>')
    out.append("</Shape>")
    if p.get("gloss") and g[0] != "line":
        gx, gy, grx, gry = p["gloss"]
        clip_id = nid()
        cx, cy = tx((gx, gy))
        out.insert(1, f'<Shape x="{cx:.1f}" y="{cy:.1f}" name="{p["name"]} gloss">'
                      f'<Ellipse width="{2 * grx * S:.1f}" height="{2 * gry * S:.1f}" originX="0.5" originY="0.5" name="Path"/>'
                      f'<Fill name="F"><RadialGradient startX="0" startY="0" endX="{grx * S:.1f}" endY="0" name="G">'
                      f'<GradientStop colorValue="{hexc((255, 255, 255), p.get("glossA", 150))}" position="0"/>'
                      f'<GradientStop colorValue="{hexc((255, 255, 255), 0)}" position="1"/></RadialGradient></Fill>'
                      f'<ClippingShape sourceId="{clip_id}" name="Clip"/></Shape>')
        out.append(shape_open(g, f'{p["name"]} clip', clip_id) + geometry(g) + "</Shape>")
    # Side: stepped copies, darkest at the back
    if depth > 0:
        steps = max(3, int(depth * 1.6))
        side_top, side_bottom = darken(base, 0.28), darken(base, 0.48)
        L = math.hypot(*LIGHT)
        for i in range(1, steps + 1):
            t = i / steps
            dx, dy = LIGHT[0] / L * depth * S * t, LIGHT[1] / L * depth * S * t
            col = mix(side_top, side_bottom, t)
            out.append(shape_open(g, f'{p["name"]} side {i}'))
            out.append(geometry(g, dx, dy))
            out.append(paint(g, f'<SolidColor colorValue="{hexc(col)}" name="C"/>', p.get("effect")))
            out.append("</Shape>")
    out.append("</Node>")
    return "\n".join(out)


def contact_shadow(width, y):
    """A soft oval under the icon: a radial gradient on a circle squashed flat (no feather, so it renders
    everywhere and costs the GPU little)."""
    cx, cy = tx((50, y + 7))  # below the extruded sides, which would hide it
    r = width / 2
    circle = geometry(("ellipse", 50, 50, r, r), -CX, -CY)  # centred on the shape, so it scales in place
    return (f'<Shape x="{cx:.1f}" y="{cy:.1f}" scaleY="0.18" name="Shadow" id="{nid()}">{circle}'
            f'<Fill name="F"><RadialGradient startX="0" startY="0" endX="{r * S:.1f}" endY="0" name="G">'
            f'<GradientStop colorValue="{hexc((16, 19, 43), 120)}" position="0"/>'
            f'<GradientStop colorValue="{hexc((16, 19, 43), 60)}" position="0.55"/>'
            f'<GradientStop colorValue="{hexc((16, 19, 43), 0)}" position="1"/></RadialGradient></Fill></Shape>')
