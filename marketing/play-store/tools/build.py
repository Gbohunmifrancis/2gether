"""
Builds Heartpact's Play Store and promotion assets (marketing/play-store/README.md) from real app screens.

The screens and the app's own art come from the Android screenshot tests:

    cd android && ./gradlew testDebugUnitTest --tests '*StoreShotsTest*' --tests '*StoreArtTest*' -Proborazzi.test.record=true

then:

    python3 marketing/play-store/tools/build.py            # every language
    python3 marketing/play-store/tools/build.py en fr      # just these
"""
import json
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

TOOLS = Path(__file__).resolve().parent
OUT = TOOLS.parent
REPO = OUT.parent.parent
SCREENS = REPO / "android/app/build/screens/store"
CAPTIONS = json.loads((TOOLS / "captions.json").read_text())
LANGUAGES = ["en", "fr", "pt", "es", "ar", "hi"]
WORK = Path(tempfile.mkdtemp(prefix="heartpact-store-"))

SLICE_W, SLICE_H, SLICES = 1080, 1920, 8
PANO_W = SLICE_W * SLICES

INK, INK2, PAGE, PAGE_DEEP, MAYA, THEO = "#161A3D", "#474C72", "#E3E5F6", "#D7DAEF", "#1F2557", "#7480D4"


def fonts_css() -> str:
    faces = [
        ("Sora", "sora_semibold.ttf", 600), ("Sora", "sora_bold.ttf", 700),
        ("Figtree", "figtree_medium.ttf", 500), ("Figtree", "figtree_semibold.ttf", 600), ("Figtree", "figtree_bold.ttf", 700),
        ("NotoArabic", "NotoSansArabic-Regular.ttf", 500), ("NotoArabic", "NotoSansArabic-Bold.ttf", 700),
        ("NotoDeva", "NotoSansDevanagari-Regular.ttf", 500), ("NotoDeva", "NotoSansDevanagari-Bold.ttf", 700),
    ]
    return "\n".join(f"@font-face {{ font-family: '{family}'; src: url('{(TOOLS / 'fonts' / file).as_uri()}'); font-weight: {weight}; }}"
                     for family, file, weight in faces)


def type_css(lang: str) -> str:
    """Sora and Figtree are Latin only; Arabic and Hindi take Noto, with normal letter spacing and taller lines (as the app does)."""
    script = {"ar": "'NotoArabic', ", "hi": "'NotoDeva', "}.get(lang, "")
    tight = lang not in ("ar", "hi")
    return f"""
    .head {{ font-family: {script}'Sora', sans-serif; font-weight: {700 if script else 600}; letter-spacing: {'-0.035em' if tight else '0'};
             line-height: {1.02 if tight else 1.3}; color: {INK}; }}
    .sub {{ font-family: {script}'Figtree', sans-serif; font-weight: 500; line-height: {1.38 if tight else 1.55}; color: {INK2}; }}
    """


def trimmed_art() -> Path:
    """The app's stickers and characters, cropped to what they draw."""
    art = WORK / "art"
    art.mkdir(exist_ok=True)
    for file in (SCREENS / "art").glob("*.png"):
        image = Image.open(file)
        image.crop(image.getbbox()).save(art / file.name)
    return art


def page(body: str, width: int, height: int, lang: str, extra_css: str = "", script: str = "") -> str:
    rtl = lang == "ar"
    return f"""<!doctype html><html lang="{lang}" dir="{'rtl' if rtl else 'ltr'}"><head><meta charset="utf-8"><style>
    {fonts_css()}
    * {{ box-sizing: border-box; margin: 0; padding: 0; }}
    html, body {{ width: {width}px; height: {height}px; overflow: hidden; }}
    body {{ position: relative; background: linear-gradient(180deg, #EAECF9 0%, {PAGE} 55%, {PAGE_DEEP} 100%); }}
    /* The album page's faint dot grid. */
    body::before {{ content: ''; position: absolute; inset: 0; background-image: radial-gradient(rgba(31,37,87,.07) 2.2px, transparent 2.6px);
                    background-size: 44px 44px; }}
    .abs {{ position: absolute; }}
    .head, .sub {{ text-wrap: balance; }}
    .screen {{ position: absolute; border: 14px solid #fff; border-radius: 70px; overflow: hidden; background: #fff;
               box-shadow: 0 34px 70px rgba(31,37,87,.26), 0 6px 16px rgba(31,37,87,.12); }}
    .screen img {{ display: block; width: 100%; }}
    .art {{ position: absolute; filter: drop-shadow(0 16px 22px rgba(31,37,87,.22)); }}
    {type_css(lang)}
    {extra_css}
    </style></head><body>{body}<script>
    // Screens marked .follow sit a set gap below the caption, measured once the fonts are in (render.cjs calls layout).
    window.layout = () => {{
      const cap = document.querySelector('.cap');
      if (cap) document.querySelectorAll('.follow').forEach(el => {{
        el.style.top = Math.max(Number(el.dataset.min), cap.offsetTop + cap.offsetHeight + Number(el.dataset.gap)) + 'px';
      }});
    }};
    {script}</script></body></html>"""


def ribbon(width: int, height: int, base: float, amplitude: float, wavelength: float, band: float, phase: float = 0.0) -> str:
    """The fold: an indigo band over a periwinkle one, a dashed stitch where they meet, waving across the whole page."""
    def path(offset: float) -> str:
        points = [(x, base + offset + amplitude * math.sin(2 * math.pi * x / wavelength + phase)) for x in range(-40, width + 41, 16)]
        return "M" + " L".join(f"{x:.0f},{y:.1f}" for x, y in points)
    return f"""<svg class="abs" style="left:0;top:0" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
      <path d="{path(-band / 2)}" fill="none" stroke="{MAYA}" stroke-width="{band}"/>
      <path d="{path(band / 2)}" fill="none" stroke="{THEO}" stroke-width="{band}"/>
      <path d="{path(0)}" fill="none" stroke="#fff" stroke-width="{max(5, band / 14):.0f}" stroke-dasharray="{band / 3.6:.0f} {band / 4.6:.0f}" stroke-linecap="round"/>
    </svg>"""


def art_tag(art: Path, name: str, cx: float, cy: float, height: float, rotate: float = 0, flip: bool = False) -> str:
    image = Image.open(art / f"{name}.png")
    width = height * image.width / image.height
    transform = f"rotate({rotate}deg)" + (" scaleX(-1)" if flip else "")
    return (f'<img class="art" src="{(art / f"{name}.png").as_uri()}" '
            f'style="left:{cx - width / 2:.0f}px;top:{cy - height / 2:.0f}px;width:{width:.0f}px;height:{height:.0f}px;transform:{transform}">')


# Art on the joins between screenshots: (join 1-7, sticker, centre y, height, rotation).
SEAM_ART = [
    (1, "maya-wave", 1330, 380, -4), (1, "heart-full", 700, 120, -14),
    (2, "heart-full", 830, 250, -12), (2, "heart-right", 1640, 120, 10),
    (3, "heart-peeled", 1480, 240, 10), (3, "heart-left", 690, 110, -8),
    (4, "theo-walk", 1330, 380, 4), (4, "heart-full", 700, 120, 12),
    (5, "heart-left", 800, 250, 8), (5, "heart-full", 1660, 110, -10),
    (6, "heart-empty", 1480, 240, -8), (6, "heart-right", 700, 120, 10),
    (7, "both-cheer", 1320, 420, 0), (7, "heart-full", 680, 120, -12),
]
TILT = [-2.0, 1.8, -1.6, 2.2, -1.8, 1.6, -2.2, 1.8]


def panorama(lang: str, art: Path) -> Path:
    rtl = lang == "ar"
    text = CAPTIONS[lang]["panels"]
    body = [ribbon(PANO_W, SLICE_H, base=1250, amplitude=120, wavelength=2160, band=104)]
    for i, screen in enumerate(CAPTIONS["screens"]):
        slot = SLICES - 1 - i if rtl else i  # Play lays an Arabic listing's screenshots out right to left.
        x0 = slot * SLICE_W
        head, sub = text[i]
        brand = ""
        if i == 0:
            brand = (f'<div style="display:flex;align-items:center;gap:22px;margin-bottom:34px;direction:ltr;{"justify-content:flex-end" if rtl else ""}">'
                     f'<img src="{(art / "logo.png").as_uri()}" style="height:96px">'
                     f'<span style="font-family:Sora;font-weight:600;font-size:64px;letter-spacing:-0.045em;color:{INK}">heartpact</span></div>')
        size = 84 if i == 0 else 78
        if lang in ("ar", "hi"):
            size -= 8
        body.append(f'<div class="cap abs" data-slice="{slot}" style="left:{x0 + 80}px;top:{96 if i == 0 else 120}px;width:920px;text-align:{"right" if rtl else "left"}">'
                    f'{brand}<div class="head" style="font-size:{size}px">{head}</div>'
                    f'<div class="sub" style="font-size:38px;margin-top:22px;max-width:900px;{"margin-left:auto" if rtl else ""}">{sub}</div></div>')
        body.append(f'<div class="screen" data-slice="{slot}" style="left:{x0 + 150}px;width:780px;transform:rotate({TILT[slot]}deg)">'
                    f'<img src="{(SCREENS / lang / f"{screen}.png").as_uri()}"></div>')
    for join, name, cy, height, rotate in SEAM_ART:
        body.append(art_tag(art, name, join * SLICE_W, cy, height, rotate))
    # Each screen sits just below its caption; the checks keep captions inside their slice and under Play's 20 %.
    script = f"""
    window.layout = () => document.querySelectorAll('.cap').forEach(cap => {{
      const screen = document.querySelector('.screen[data-slice="' + cap.dataset.slice + '"]');
      screen.style.top = Math.max(450, cap.offsetTop + cap.offsetHeight + 60) + 'px';
    }});
    window.checks = () => [...document.querySelectorAll('.cap')].flatMap(cap => {{
      const r = cap.getBoundingClientRect(), s = Number(cap.dataset.slice), left = s * {SLICE_W}, out = [];
      const head = cap.querySelector('.head').getBoundingClientRect(), sub = cap.querySelector('.sub').getBoundingClientRect();
      const used = head.width * head.height + sub.width * sub.height;
      if (r.left < left || r.right > left + {SLICE_W}) out.push('caption leaves slice ' + (s + 1));
      if (used > 0.2 * {SLICE_W * SLICE_H}) out.push('caption over 20% in slice ' + (s + 1) + ': ' + Math.round(100 * used / {SLICE_W * SLICE_H}) + '%');
      return out;
    }});"""
    html = WORK / f"panorama-{lang}.html"
    html.write_text(page("".join(body), PANO_W, SLICE_H, lang, script=script))
    png = WORK / f"panorama-{lang}.png"
    render(html, png, PANO_W, SLICE_H)
    return png


def render(html: Path, png: Path, width: int, height: int, transparent: bool = False) -> None:
    subprocess.run(["node", str(TOOLS / "render.cjs"), str(html), str(png), str(width), str(height)] + (["transparent"] if transparent else []),
                   check=True, cwd=TOOLS)


def slice_panorama(lang: str, png: Path) -> list[Path]:
    """Eight 1080 × 1920 screenshots, numbered in upload order (an Arabic listing reads right to left)."""
    rtl = lang == "ar"
    out = OUT / "screenshots/phone" / lang
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    image = Image.open(png).convert("RGB")
    files = []
    for i, screen in enumerate(CAPTIONS["screens"]):
        slot = SLICES - 1 - i if rtl else i
        file = out / f"{i + 1:02d}-{screen}.jpg"
        image.crop((slot * SLICE_W, 0, (slot + 1) * SLICE_W, SLICE_H)).save(file, quality=90, optimize=True, progressive=True)
        files.append(file)
    preview(files, OUT / "previews" / f"phone-panorama-{lang}.jpg", rtl)
    return files


def preview(files: list[Path], out: Path, rtl: bool = False, height: int = 800) -> None:
    """How the carousel looks on Play: the screenshots side by side with small gaps."""
    out.parent.mkdir(parents=True, exist_ok=True)
    images = [Image.open(f).convert("RGB") for f in (reversed(files) if rtl else files)]
    images = [i.resize((round(i.width * height / i.height), height), Image.LANCZOS) for i in images]
    gap = 18
    sheet = Image.new("RGB", (sum(i.width for i in images) + gap * (len(images) + 1), height + 2 * gap), (32, 33, 36))
    x = gap
    for image in images:
        sheet.paste(image, (x, gap))
        x += image.width + gap
    sheet.save(out, quality=82, optimize=True)


BADGES = {"en": "en", "fr": "fr", "pt": "pt-br", "es": "es-419", "ar": "ar", "hi": "hi"}


def badge(lang: str) -> Path:
    """Google's own "Get it on Google Play" badge in the listing's language, without its transparent margin."""
    out = WORK / f"badge-{lang}.png"
    if not out.exists():
        image = Image.open(TOOLS / "badges" / f"{BADGES[lang]}.png").convert("RGBA")
        image.crop(image.getbbox()).save(out)
    return out


def sticker(src: Path, left: float, top: float, width: float, rotate: float = 0, border: float = 14, radius: float = 70, z: int = 1,
            follow: float | None = None) -> str:
    """
    A real screen as a sticker on the album page: white keyline, rounded corners, a soft shadow. No device frame.
    With [follow], it sits that far below the caption instead (never above [top]).
    """
    flow = f' follow" data-gap="{follow:.0f}" data-min="{top:.0f}' if follow is not None else ""
    return (f'<div class="screen{flow}" style="left:{left:.0f}px;top:{top:.0f}px;width:{width:.0f}px;border-width:{border:.0f}px;border-radius:{radius:.0f}px;'
            f'transform:rotate({rotate}deg);z-index:{z}"><img src="{src.as_uri()}"></div>')


def caption(lang: str, head: str, sub: str, left: float, top: float, width: float, head_size: float, sub_size: float,
            art: Path | None = None, brand_size: float = 0, badge_height: float = 0, center: bool = False) -> str:
    rtl = lang == "ar"
    if lang in ("ar", "hi"):
        head_size *= 0.9
    align = "center" if center else ("right" if rtl else "left")
    justify = "center" if center else ("flex-end" if rtl else "flex-start")
    brand = ""
    if brand_size and art:
        brand = (f'<div style="display:flex;align-items:center;gap:{brand_size * .34:.0f}px;margin-bottom:{brand_size * .5:.0f}px;direction:ltr;justify-content:{justify}">'
                 f'<img src="{(art / "logo.png").as_uri()}" style="height:{brand_size * 1.5:.0f}px">'
                 f'<span style="font-family:Sora;font-weight:600;font-size:{brand_size:.0f}px;letter-spacing:-0.045em;color:{INK}">heartpact</span></div>')
    play = ""
    if badge_height:
        # The page's own direction lines the badge up with the text: flex-start is the right edge in Arabic.
        play = (f'<div style="display:flex;justify-content:{"center" if center else "flex-start"};margin-top:{badge_height * .4:.0f}px">'
                f'<img src="{badge(lang).as_uri()}" style="height:{badge_height:.0f}px"></div>')
    return (f'<div class="cap abs" style="left:{left:.0f}px;top:{top:.0f}px;width:{width:.0f}px;text-align:{align};z-index:3">{brand}'
            f'<div class="head" style="font-size:{head_size:.0f}px">{head}</div>'
            f'<div class="sub" style="font-size:{sub_size:.0f}px;margin-top:{sub_size * .55:.0f}px">{sub}</div>{play}</div>')


def mirror(lang: str, width: int, left: float, size: float) -> float:
    """Arabic layouts mirror: what sits on the left goes to the right."""
    return width - left - size if lang == "ar" else left


def icon(art: Path) -> None:
    out = OUT / "icon"
    out.mkdir(parents=True, exist_ok=True)
    Image.open(SCREENS / "art/play-icon-512.png").convert("RGBA").save(out / "heartpact-play-icon-512.png", optimize=True)


LOGO_SVG = REPO / "design/heartpact-v3/logo"
NIGHT_INK = "#EEF0FB"


def logo() -> None:
    """
    The logo kit: the mark as SVG and PNG, the mark with the wordmark (light and dark grounds), and the app icon at 512
    and 1024. PNGs other than the icon are on transparent ground. The icon is the launcher's own layers.
    """
    out = OUT / "logo"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    for source, name in (("heartpact-mark.svg", "heartpact-mark.svg"), ("heartpact-mark-dark.svg", "heartpact-mark-on-dark.svg"),
                         ("ic_launcher_background.svg", "app-icon-background.svg"), ("ic_launcher_foreground.svg", "app-icon-foreground.svg"),
                         ("ic_launcher_monochrome.svg", "app-icon-monochrome.svg")):
        shutil.copy(LOGO_SVG / source, out / name)

    def draw(name: str, inner: str, width: int, height: int, transparent: bool = True, pad: int = 0) -> None:
        html = WORK / f"logo-{name}.html"
        html.write_text(f"""<!doctype html><html><head><meta charset="utf-8"><style>{fonts_css()}
            * {{ margin: 0; padding: 0; }} html, body {{ width: {width}px; height: {height}px; background: transparent; overflow: hidden; }}
            body {{ display: flex; align-items: center; justify-content: center; }}</style></head><body>{inner}</body></html>""")
        png = WORK / f"logo-{name}.png"
        render(html, png, width, height, transparent)
        image = Image.open(png).convert("RGBA")
        if transparent:
            image = image.crop(image.getbbox())
            framed = Image.new("RGBA", (image.width + 2 * pad, image.height + 2 * pad), (0, 0, 0, 0))
            framed.alpha_composite(image, (pad, pad))
            image = framed
        image.save(out / f"{name}.png", optimize=True)

    for svg, name in (("heartpact-mark.svg", "heartpact-mark"), ("heartpact-mark-dark.svg", "heartpact-mark-on-dark")):
        draw(name, f'<img src="{(LOGO_SVG / svg).as_uri()}" style="height:1024px">', 1400, 1100)
    for svg, ink, name in (("heartpact-mark.svg", INK, "heartpact-lockup"), ("heartpact-mark-dark.svg", NIGHT_INK, "heartpact-lockup-on-dark")):
        draw(name, f'<div style="display:flex;align-items:center;gap:90px"><img src="{(LOGO_SVG / svg).as_uri()}" style="height:330px">'
                   f'<span style="font-family:Sora;font-weight:600;font-size:250px;letter-spacing:-0.045em;color:{ink};line-height:1">heartpact</span></div>',
             2600, 700, pad=40)
    layers = "".join(f'<img src="{(LOGO_SVG / svg).as_uri()}" style="position:absolute;inset:0;width:1024px;height:1024px">'
                     for svg in ("ic_launcher_background.svg", "ic_launcher_foreground.svg"))
    draw("heartpact-app-icon-1024", f'<div style="position:relative;width:1024px;height:1024px">{layers}</div>', 1024, 1024, transparent=False)
    shutil.copy(OUT / "icon/heartpact-play-icon-512.png", out / "heartpact-app-icon-512.png")


def feature(lang: str, art: Path) -> None:
    """The 1024 × 500 feature graphic: the promise on one side, three real screens on the other. No app icon (Play asks not to repeat it)."""
    W, H = 1024, 500
    head, sub = CAPTIONS[lang]["feature"]
    screens = [("today", 590, 92, 158, -6), ("camera", 712, 56, 176, 2), ("heart-complete", 852, 100, 152, 7)]
    body = [ribbon(W, H, base=405, amplitude=26, wavelength=1100, band=30, phase=1.2)]
    body.append(caption(lang, head, sub, mirror(lang, W, 56, 470), 92, 470, 46, 21))
    for name, left, top, width, rotate in screens:
        body.append(sticker(SCREENS / lang / f"{name}.png", mirror(lang, W, left, width), top, width, -rotate if lang == "ar" else rotate, border=6, radius=24, z=2 if name == "camera" else 1))
    body.append(art_tag(art, "both-hug", mirror(lang, W, 516, 0), 420, 120))
    body.append(art_tag(art, "heart-full", mirror(lang, W, 980, 0), 470, 70, 12))
    html = WORK / f"feature-{lang}.html"
    html.write_text(page("".join(body), W, H, lang))
    out = OUT / "feature-graphic" / lang
    out.mkdir(parents=True, exist_ok=True)
    png = WORK / f"feature-{lang}.png"
    render(html, png, W, H)
    Image.open(png).convert("RGB").save(out / "feature-graphic-1024x500.png", optimize=True)  # 24-bit, no alpha


# Tablets: the screens that still read well on the phone layout the app uses on tablets.
TABLET_SCREENS = [("alarm", 2), ("camera", 3), ("games-draw", 6), ("album", 7), ("food", "food")]


def tablets(lang: str, art: Path) -> None:
    """Landscape 16:9 screenshots for 7-inch (1920 × 1080) and 10-inch (2560 × 1440) tablets, from screens rendered at those sizes."""
    for kind, W, H in (("tablet7", 1920, 1080), ("tablet10", 2560, 1440)):
        s = H / 1080
        out = OUT / "screenshots" / kind / lang
        shutil.rmtree(out, ignore_errors=True)
        out.mkdir(parents=True)
        files = []
        for n, (name, key) in enumerate(TABLET_SCREENS):
            head, sub = CAPTIONS[lang]["panels"][key] if isinstance(key, int) else CAPTIONS[lang][key]
            body = [ribbon(W, H, base=820 * s, amplitude=60 * s, wavelength=1900 * s, band=70 * s, phase=n * 1.3)]
            body.append(caption(lang, head, sub, mirror(lang, W, 110 * s, 760 * s), 180 * s, 760 * s, 84 * s, 38 * s))
            body.append(sticker(SCREENS.parent / f"store-{kind}" / lang / f"{name}.png", mirror(lang, W, 1080 * s, 640 * s), 110 * s, 640 * s,
                                1.6 if n % 2 else -1.6, border=14 * s, radius=56 * s))
            character = ["maya-wave", "theo-walk", "both-cheer", "maya-walk", "theo-stand"][n]
            body.append(art_tag(art, character, mirror(lang, W, 330 * s, 0), 860 * s, 300 * s))
            body.append(art_tag(art, "heart-full", mirror(lang, W, 900 * s, 0), 700 * s, 120 * s, 12))
            html = WORK / f"{kind}-{lang}-{n}.html"
            html.write_text(page("".join(body), W, H, lang))
            png = WORK / f"{kind}-{lang}-{n}.png"
            render(html, png, W, H)
            file = out / f"{n + 1:02d}-{name}.jpg"
            Image.open(png).convert("RGB").save(file, quality=90, optimize=True, progressive=True)
            files.append(file)
        preview(files, OUT / "previews" / f"{kind}-{lang}.jpg", height=540)


def promo(lang: str, art: Path) -> None:
    """Social and web: feed posts, a story, an X / Twitter card and a link preview, each with Google's Play badge."""
    out = OUT / "promo" / lang
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    panels = CAPTIONS[lang]["panels"]

    def save(name: str, body: list[str], W: int, H: int) -> None:
        html = WORK / f"promo-{lang}-{name}.html"
        html.write_text(page("".join(body), W, H, lang))
        png = WORK / f"promo-{lang}-{name}.png"
        render(html, png, W, H)
        Image.open(png).convert("RGB").save(out / f"{name}.jpg", quality=92, optimize=True, progressive=True)

    # Feed posts, 4:5 (Instagram, Facebook): caption and badge on top, the real screen below.
    for n, (index, screen) in enumerate([(0, "today"), (3, "camera"), (5, "question"), (6, "games-draw")]):
        head, sub = panels[index]
        body = [ribbon(1080, 1350, base=1000, amplitude=70, wavelength=1500, band=80, phase=n)]
        body.append(caption(lang, head, sub, 80, 90, 920, 70, 34, art, brand_size=46 if index == 0 else 0, badge_height=80))
        body.append(sticker(SCREENS / lang / f"{screen}.png", 200, 380, 680, -1.6 if n % 2 else 1.6, follow=80))
        body.append(art_tag(art, ["maya-wave", "theo-walk", "both-cheer", "heart-full"][n], mirror(lang, 1080, 110, 0), 1180, 260))
        body.append(art_tag(art, "heart-left", mirror(lang, 1080, 980, 0), 900, 130, 10))
        save(f"post-{n + 1}-{screen}-1080x1350", body, 1080, 1350)

    # Story, 9:16 (Instagram, WhatsApp, TikTok): everything inside the safe area (250 px top, 340 px bottom).
    head, sub = panels[0]
    body = [ribbon(1080, 1920, base=1420, amplitude=80, wavelength=1500, band=90)]
    body.append(caption(lang, head, sub, 90, 260, 900, 78, 36, art, brand_size=50, badge_height=88, center=True))
    body.append(sticker(SCREENS / lang / "today.png", 120, 760, 440, -5, border=10, radius=48, follow=130))
    body.append(sticker(SCREENS / lang / "camera.png", 520, 720, 460, 4, border=10, radius=48, z=2, follow=90))
    body.append(art_tag(art, "both-cheer", 540, 1600, 300))
    save("story-1080x1920", body, 1080, 1920)

    # Wide: X / Twitter (1600 × 900) and link previews (1200 × 630: website, WhatsApp, Facebook, LinkedIn).
    for name, W, H in (("x-1600x900", 1600, 900), ("link-preview-1200x630", 1200, 630)):
        s = H / 900
        body = [ribbon(W, H, base=740 * s, amplitude=50 * s, wavelength=1500 * s, band=60 * s)]
        body.append(caption(lang, head, sub, mirror(lang, W, 90 * s, 640 * s), 130 * s, 640 * s, 72 * s, 30 * s, art, brand_size=44 * s, badge_height=84 * s))
        for screen, right, top, width, rotate, z in (("today", 840, 140, 300, -6, 1), ("camera", 590, 90, 330, 2, 2), ("heart-complete", 320, 150, 300, 7, 1)):
            body.append(sticker(SCREENS / lang / f"{screen}.png", mirror(lang, W, W - right * s, width * s), top * s, width * s,
                                -rotate if lang == "ar" else rotate, border=10 * s, radius=44 * s, z=z))
        save(name, body, W, H)


LIMITS = {"title.txt": 30, "short-description.txt": 80, "full-description.txt": 4000, "release-notes.txt": 500}
BANNED = ["free", "best", "#1", "number one", "download now", "install now"]


def check_listing() -> bool:
    """Every language has the four Play Console texts, each within its limit, with no words Play turns down."""
    ok = True
    for lang in LANGUAGES:
        for name, limit in LIMITS.items():
            file = OUT / "listing" / lang / name
            if not file.exists():
                print(f"{lang}: {name} missing")
                ok = False
                continue
            text = file.read_text()
            if len(text) > limit:
                print(f"{lang}: {name} is {len(text)} characters (limit {limit})")
                ok = False
            if lang == "en" and (found := [w for w in BANNED if w in text.lower()]):
                print(f"{lang}: {name} says {', '.join(found)}")
                ok = False
    return ok


def main() -> None:
    if sys.argv[1:] == ["check"]:
        sys.exit(0 if check_listing() else 1)
    languages = sys.argv[1:] or LANGUAGES
    art = trimmed_art()
    icon(art)
    logo()
    if sys.argv[1:] == ["logo"]:
        return
    for lang in languages:
        slice_panorama(lang, panorama(lang, art))
        feature(lang, art)
        tablets(lang, art)
        promo(lang, art)
        print(f"{lang}: phone, feature graphic, tablets, promo")


if __name__ == "__main__":
    main()
