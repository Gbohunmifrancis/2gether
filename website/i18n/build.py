#!/usr/bin/env python3
"""The website in other languages (docs/translation.md).

The English pages are the source. Each block of text (a paragraph, heading, list item, link or button, with its
inline tags) and each alt, aria-label, title and page description is one string; its English is the key. Each
language is one file, website/i18n/<language>.json, and this script writes website/<language>/... from the English
pages, with internal links kept in that language, hreflang links, a language switcher and the scripts' strings.

    python3 website/i18n/build.py export          # website/i18n/source.json: every string, with the page it's on
    python3 website/i18n/build.py missing fr      # what French still needs, as JSON
    python3 website/i18n/build.py merge fr b.json # adds a batch of translations to fr.json
    python3 website/i18n/build.py prune           # drops translations whose English has gone
    python3 website/i18n/build.py build           # writes the language pages (and the English pages' language links)
    python3 website/i18n/build.py check           # every string translated, same tags, pages up to date; CI runs it
    python3 website/i18n/build.py fill "[COMPANY NAME]" "Heartpact Ltd"   # a legal placeholder, in every language
"""
import html
import json
import pathlib
import re
import sys

SITE = pathlib.Path(__file__).resolve().parent.parent
HERE = SITE / "i18n"
ORIGIN = "https://heartpact.me"
PAGES = ["index.html", "privacy/index.html", "terms/index.html", "delete-account/index.html", "plus/index.html",
         "plus/thanks/index.html", "404.html"]
# Pages search engines shouldn't list (each also says noindex): not in the sitemap.
UNLISTED = {"404.html", "plus/thanks/index.html"}
LEGAL = {"privacy/index.html", "terms/index.html"}
LANGUAGES = ["fr", "pt", "es", "ar", "hi"]
NAMES = {"en": "English", "fr": "Français", "pt": "Português", "es": "Español", "ar": "العربية", "hi": "हिन्दी"}
RTL = {"ar"}
# A translated legal page says the English one applies; {english} is its address.
LEGAL_NOTE = ('This translation is for your convenience. If it differs from the <a href="{english}">English version</a>, '
              'the English version applies.')
DO_NOT_TRANSLATE = {"heartpact", "Heartpact Plus", "© 2026 Heartpact"} | set(NAMES.values())

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}
INLINE = {"a", "abbr", "b", "br", "code", "em", "i", "img", "input", "kbd", "mark", "s", "small", "span", "strong", "sub",
          "sup", "time", "u"}
UNIT = {"title", "h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "a", "button", "label", "dt", "dd", "td", "th",
        "figcaption", "summary", "legend", "caption", "option", "small"}
SKIP = {"script", "style", "svg", "template"}
TRANSLATED_ATTRS = ("alt", "aria-label", "title", "placeholder")
META_CONTENT = re.compile(r'<meta\s+(?:name="description"|property="og:(?:title|description)")\s+content="([^"]*)"')
TOKEN = re.compile(r'<!--.*?-->|<![^>]*>|<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:[^>"\']|"[^"]*"|\'[^\']*\')*)>|[^<]+', re.S)
ATTR = re.compile(r'\b(alt|aria-label|title|placeholder)="([^"]*)"')
TAG = re.compile(r'<[^>]+>')
JS_STRING = re.compile(r'\bT\("((?:[^"\\\n]|\\.)*)"\)')


class Node:
    def __init__(self, tag, attrs, start, open_end, parent):
        self.tag, self.attrs, self.start, self.open_end, self.parent = tag, attrs, start, open_end, parent
        self.close_start = self.end = open_end
        self.children = []


def parse(text):
    root = Node("#root", "", 0, 0, None)
    node = root
    for m in TOKEN.finditer(text):
        closing, tag, attrs = m.group(1), m.group(2), m.group(3)
        if tag is None:
            if not m.group(0).startswith("<"):
                node.children.append(("#text", m.start(), m.end()))
            continue
        tag = tag.lower()
        if closing:
            while node is not root and node.tag != tag:
                node.close_start = node.end = m.start()
                node = node.parent
            if node is not root:
                node.close_start, node.end = m.start(), m.end()
                node = node.parent
            continue
        child = Node(tag, attrs, m.start(), m.end(), node)
        node.children.append(child)
        if tag not in VOID and not attrs.rstrip().endswith("/"):
            node = child
    return root


def elements(node):
    for child in node.children:
        if isinstance(child, Node):
            yield child
            yield from elements(child)


def text_of(text, node):
    return "".join(text[c[1]:c[2]] if not isinstance(c, Node) else text_of(text, c) for c in node.children)


def normalize(s):
    return re.sub(r"\s+", " ", s).strip()


def units(text):
    """(start, end, key) for each block of text, and problems (text mixed with blocks, which can't be one string)."""
    found, problems = [], []

    def walk(node):
        if node.tag in SKIP:
            return
        kids = [c for c in node.children if isinstance(c, Node)]
        direct = any(not isinstance(c, Node) and text[c[1]:c[2]].strip() for c in node.children)
        all_inline = all(e.tag in INLINE for e in elements(node))
        has_text = bool(normalize(html.unescape(TAG.sub("", text_of(text, node)))))
        if (node.tag in UNIT or direct) and has_text and all_inline and node.tag != "#root":
            key = normalize(text[node.open_end:node.close_start])
            if normalize(html.unescape(TAG.sub("", key))) not in DO_NOT_TRANSLATE:
                found.append((node.open_end, node.close_start, key))
            return
        if direct and node.tag not in ("#root", "html", "body", "head"):
            problems.append(f"<{node.tag}> at {text.count(chr(10), 0, node.start) + 1} mixes text and blocks; wrap the text")
        for kid in kids:
            walk(kid)

    walk(parse(text))
    return found, problems


def attributes(text):
    """(start, end, key) for translated attribute values and page descriptions."""
    found = []
    for el in elements(parse(text)):
        ancestor, hidden = el, False
        while ancestor is not None:
            hidden = hidden or ancestor.tag in SKIP
            ancestor = ancestor.parent
        if hidden:
            continue
        for m in ATTR.finditer(el.attrs):
            if m.group(2).strip():
                begin = el.start + 1 + len(el.tag) + m.start(2)
                found.append((begin, begin + len(m.group(2)), m.group(2)))
    for m in META_CONTENT.finditer(text):
        found.append((m.start(1), m.end(1), m.group(1)))
    return found


def page_strings():
    """{key: {"pages": [...], "kind": "text"|"attribute"|"script"}} for every page and script."""
    out, problems = {}, []
    for page in PAGES:
        text = (SITE / page).read_text(encoding="utf-8")
        blocks, page_problems = units(text)
        problems += [f"{page}: {p}" for p in page_problems]
        for _, _, key in blocks:
            out.setdefault(key, {"kind": "text", "pages": []})["pages"].append(page)
        for _, _, key in attributes(text):
            out.setdefault(key, {"kind": "attribute", "pages": []})["pages"].append(page)
    out.setdefault(LEGAL_NOTE, {"kind": "text", "pages": sorted(LEGAL)})
    for script in sorted((SITE / "assets").glob("*.js")):
        for m in JS_STRING.finditer(script.read_text(encoding="utf-8")):
            key = json.loads('"' + m.group(1) + '"')
            out.setdefault(key, {"kind": "script", "pages": []})["pages"].append("assets/" + script.name)
    for entry in out.values():
        entry["pages"] = sorted(set(entry["pages"]))
    return out, problems


def catalog(language):
    path = HERE / f"{language}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def same_tags(key, value):
    return sorted(TAG.findall(key)) == sorted(TAG.findall(value))


def url(language, page):
    path = "/" + page.removesuffix("index.html")
    if page == "404.html":
        path = "/404.html"
    return ORIGIN + (path if language == "en" else "/" + language + path)


def head_block(page, language, words):
    lines = ["<!-- i18n:head (website/i18n/build.py) -->"]
    if page != "404.html":
        for code in ["en"] + LANGUAGES:
            lines.append(f'<link rel="alternate" hreflang="{code}" href="{url(code, page)}">')
        lines.append(f'<link rel="alternate" hreflang="x-default" href="{url("en", page)}">')
    scripts = {k: v for k, v in words.items() if k in SCRIPT_KEYS} if language != "en" else {}
    data = json.dumps(scripts, ensure_ascii=False, sort_keys=True).replace("<", "\\u003c")
    lines.append(f'<script type="application/json" id="strings">{data}</script>')
    lines.append("<!-- /i18n:head -->")
    return "\n".join(lines)


def languages_block(page, language, words):
    links = []
    for code in ["en"] + LANGUAGES:
        current = ' aria-current="true"' if code == language else ""
        links.append(f'<a href="{url(code, page).removeprefix(ORIGIN)}" lang="{code}" hreflang="{code}" data-lang="{code}"{current}>{NAMES[code]}</a>')
    return ('<!-- i18n:languages (website/i18n/build.py) -->\n    <nav class="languages" aria-label="' + words.get("Language", "Language") + '">\n      '
            + "\n      ".join(links) + "\n    </nav>\n    <!-- /i18n:languages -->")


def fill_markers(text, page, language, words):
    text = re.sub(r"<!-- i18n:head.*?<!-- /i18n:head -->", lambda _: head_block(page, language, words), text, flags=re.S)
    return re.sub(r"<!-- i18n:languages.*?<!-- /i18n:languages -->", lambda _: languages_block(page, language, words), text, flags=re.S)


def translate(page, language, words):
    """The page in [language]; raises on a string without a translation or with different tags."""
    source = (SITE / page).read_text(encoding="utf-8")
    edits, missing = [], []
    blocks, _ = units(source)
    for start, end, key in blocks + attributes(source):
        value = words.get(key)
        if not value:
            missing.append(key)
            continue
        if not same_tags(key, value):
            raise ValueError(f"{language}: tags differ in {value!r}")
        edits.append((start, end, value.replace('"', "&quot;") if source[start - 1:start] == '"' else value))
    if missing:
        raise ValueError(f"{language}/{page}: {len(missing)} strings missing, e.g. {missing[0]!r}")
    text = source
    for start, end, value in sorted(edits, reverse=True):
        text = text[:start] + value + text[end:]
    text = text.replace('<html lang="en">', f'<html lang="{language}"' + (' dir="rtl"' if language in RTL else "") + ">", 1)
    # Internal pages stay in this language; assets, anchors and other sites don't change.
    text = re.sub(r'href="/(?!assets/|' + language + r'/)([^"#]*)"', lambda m: f'href="/{language}/{m.group(1)}"', text)
    text = re.sub(r'(<link rel="canonical" href="|<meta property="og:url" content=")' + re.escape(ORIGIN) + r'/(?!' + language + r'/)',
                  lambda m: m.group(1) + ORIGIN + f"/{language}/", text)
    if page in LEGAL:
        note = words[LEGAL_NOTE].replace("{english}", url("en", page).removeprefix(ORIGIN))
        text = re.sub(r"(</h1>)", lambda m: m.group(1) + f'\n  <p class="note translation-note">{note}</p>', text, count=1)
    return fill_markers(text, page, language, words)


def outputs():
    """{path: text} for every page this script writes: the English pages' language links and each language's pages."""
    out = {}
    for page in PAGES:
        out[page] = fill_markers((SITE / page).read_text(encoding="utf-8"), page, "en", {})
    for language in LANGUAGES:
        words = catalog(language)
        for page in PAGES:
            out[f"{language}/{page}"] = translate(page, language, words)
    urls = [url(code, page) for page in PAGES if page not in UNLISTED for code in ["en"] + LANGUAGES]
    out["sitemap.xml"] = ('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
                          + "".join(f"  <url><loc>{u}</loc></url>\n" for u in urls) + "</urlset>\n")
    return out


def main():
    global SCRIPT_KEYS
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    found, problems = page_strings()
    SCRIPT_KEYS = {k for k, v in found.items() if v["kind"] == "script"}
    if command == "export":
        (HERE / "source.json").write_text(json.dumps(found, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{len(found)} strings -> {HERE / 'source.json'}")
    elif command == "missing":
        words = catalog(sys.argv[2])
        print(json.dumps({k: v for k, v in found.items() if not words.get(k)}, ensure_ascii=False, indent=1))
        return
    elif command == "merge":
        language, batch = sys.argv[2], json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
        words = catalog(language)
        unknown = [k for k in batch if k not in found]
        bad = [k for k in batch if k in found and not same_tags(k, batch[k])]
        words.update({k: v for k, v in batch.items() if k in found and k not in bad})
        (HERE / f"{language}.json").write_text(json.dumps(dict(sorted(words.items(), key=lambda kv: kv[0].lower())), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{language}: merged {len(batch) - len(unknown) - len(bad)}, {sum(1 for k in found if not words.get(k))} still missing")
        for k in unknown:
            print("not a key on the site (copy it exactly from the todo file):", repr(k))
        for k in bad:
            print("tags differ from the English (keep every tag exactly):", repr(k))
        return
    elif command == "fill":
        # Names and addresses read the same in every language, so a placeholder is replaced everywhere at once,
        # keys included, and the translations stay matched to the English.
        placeholder, value = sys.argv[2], sys.argv[3]
        pages = [page for page in PAGES if placeholder in (SITE / page).read_text(encoding="utf-8")]
        for page in pages:
            path = SITE / page
            path.write_text(path.read_text(encoding="utf-8").replace(placeholder, html.escape(value, quote=False)), encoding="utf-8")
        for language in LANGUAGES:
            words = catalog(language)
            filled = {k.replace(placeholder, html.escape(value, quote=False)): v.replace(placeholder, html.escape(value, quote=False)) for k, v in words.items()}
            (HERE / f"{language}.json").write_text(json.dumps(filled, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{placeholder} -> {value} in {', '.join(pages) or 'no page'} and every language; run build next")
        return
    elif command == "prune":
        for language in LANGUAGES:
            words = catalog(language)
            kept = {k: v for k, v in words.items() if k in found}
            (HERE / f"{language}.json").write_text(json.dumps(kept, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
            print(f"{language}: dropped {len(words) - len(kept)}")
        return
    for p in problems:
        print("page:", p)
    failed = bool(problems)
    for language in LANGUAGES:
        words = catalog(language)
        missing = [k for k in found if not words.get(k)]
        stale = [k for k in words if k not in found]
        bad = [k for k in found if words.get(k) and not same_tags(k, words[k])]
        print(f"{language}: {len(found) - len(missing)}/{len(found)} translated" + (f", {len(stale)} stale" if stale else "") + (f", {len(bad)} with changed tags" if bad else ""))
        failed = failed or bool(missing or stale or bad)
    if command == "build":
        if failed:
            sys.exit("Not every language is complete; nothing written.")
        for path, text in outputs().items():
            target = SITE / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        print("written")
    elif command == "check" and not failed:
        stale = [p for p, text in outputs().items() if not (SITE / p).exists() or (SITE / p).read_text(encoding="utf-8") != text]
        for p in stale:
            print("out of date (run build):", p)
        failed = bool(stale)
    sys.exit(1 if failed else 0)


SCRIPT_KEYS = set()

if __name__ == "__main__":
    main()
