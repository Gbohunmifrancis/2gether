#!/usr/bin/env python3
"""The app's translation catalog (docs/translation.md).

    python3 android/i18n/catalog.py export   # writes android/i18n/source.json: every English string, where it's used
    python3 android/i18n/catalog.py check    # every language file has every string, no stale ones, same {placeholders}
    python3 android/i18n/catalog.py missing fr   # just the strings French still needs, as JSON
    python3 android/i18n/catalog.py merge fr batch.json   # adds a batch of translations to fr.json, sorted

CatalogTest.kt does the same check in the unit tests, so CI fails on a string without translations.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = ROOT / "app/src/main/java"
CATALOGS = ROOT / "app/src/main/assets/i18n"
LANGUAGES = ["fr", "pt", "es", "ar", "hi"]
# CLDR categories each language needs for whole numbers (see Tr.category).
PLURAL_FORMS = {"fr": ["one", "other"], "pt": ["one", "other"], "es": ["one", "other"], "hi": ["one", "other"],
                "ar": ["zero", "one", "two", "few", "many", "other"]}

LIT = r'"((?:[^"\\\n]|\\.)*)"'
TR = re.compile(r'\btr\(\s*' + LIT)
TRN = re.compile(r'\btrn\(([^"()]*(?:\([^()]*\)[^"()]*)*),\s*' + LIT + r'\s*,\s*' + LIT)
CALL = re.compile(r'\btrn?\(')
PLACEHOLDER = re.compile(r'\{[a-zA-Z][a-zA-Z0-9]*\}')


def unescape(literal):
    out, i = [], 0
    while i < len(literal):
        c = literal[i]
        if c == "\\":
            n = literal[i + 1]
            if n == "u":
                out.append(chr(int(literal[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b"}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def extract():
    """{key: {"plural": bool, "one": str?, "other": str?, "uses": [file:line ...], "code": first line of code}}."""
    found, problems = {}, []
    for path in sorted(SOURCES.rglob("*.kt")):
        if path.name == "Tr.kt":
            continue
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(SOURCES).as_posix()
        spans = []
        for m in TR.finditer(text):
            spans.append(m.start())
            add(found, problems, unescape(m.group(1)), None, text, m.start(), rel, m.group(1))
        for m in TRN.finditer(text):
            spans.append(m.start())
            one, other = unescape(m.group(2)), unescape(m.group(3))
            add(found, problems, one + "|" + other, (one, other), text, m.start(), rel, m.group(2) + m.group(3))
        for m in CALL.finditer(text):
            if m.start() not in spans and not text[:m.start()].rstrip().endswith("fun"):
                problems.append(f"{rel}:{line(text, m.start())}: tr()/trn() needs plain string literals")
    return found, problems


def add(found, problems, key, plural, text, at, rel, raw):
    where = f"{rel}:{line(text, at)}"
    if re.search(r'(?<!\\)\$[{a-zA-Z]', raw):
        problems.append(f"{where}: use {{name}} placeholders, not $templates, inside tr()")
    entry = found.setdefault(key, {"plural": plural is not None, "uses": [],
                                   "code": text.splitlines()[line(text, at) - 1].strip()[:200]})
    if plural:
        entry["one"], entry["other"] = plural
    entry["uses"].append(where)


def line(text, at):
    return text.count("\n", 0, at) + 1


def placeholders(s):
    return sorted(set(PLACEHOLDER.findall(s)))


def check_language(language, found):
    path = CATALOGS / f"{language}.json"
    catalog = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    problems, missing = [], []
    for key, entry in found.items():
        value = catalog.get(key)
        if value in (None, "", {}):
            missing.append(key)
            continue
        expected = placeholders(entry.get("other", key)) if entry["plural"] else placeholders(key)
        if entry["plural"]:
            if not isinstance(value, dict):
                problems.append(f"{language}: {key!r} is a count, so it needs plural forms")
                continue
            for form in PLURAL_FORMS[language]:
                if not value.get(form):
                    problems.append(f"{language}: {key!r} lacks the {form!r} form")
            for form, text in value.items():
                got = placeholders(text)
                if not set(got) <= set(expected + ["{count}"]):
                    problems.append(f"{language}: {key!r} ({form}) has placeholders {got}, the English has {expected}")
        else:
            if not isinstance(value, str):
                problems.append(f"{language}: {key!r} should be a plain string")
            elif placeholders(value) != expected:
                problems.append(f"{language}: {key!r} has placeholders {placeholders(value)}, the English has {expected}")
    for key in catalog:
        if not key.startswith("@") and key not in found:
            problems.append(f"{language}: {key!r} is no longer in the app; remove it")
    return problems, missing


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    found, problems = extract()
    if command == "export":
        out = ROOT / "i18n/source.json"
        out.write_text(json.dumps(found, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{len(found)} strings ({sum(e['plural'] for e in found.values())} counts) -> {out}")
    elif command == "merge":
        language, batch = sys.argv[2], json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
        path = CATALOGS / f"{language}.json"
        catalog = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        unknown = [k for k in batch if k not in found and not k.startswith("@")]
        catalog.update({k: v for k, v in batch.items() if k not in unknown})
        ordered = {k: catalog[k] for k in sorted(catalog, key=lambda k: (not k.startswith("@"), k.lower()))}
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(ordered, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        _, missing = check_language(language, found)
        print(f"{language}: merged {len(batch) - len(unknown)}, {len(missing)} still missing")
        for k in unknown:
            print("not a key in the app (typo in the English?):", repr(k))
        return
    elif command == "missing":
        _, missing = check_language(sys.argv[2], found)
        print(json.dumps({k: found[k] for k in missing}, ensure_ascii=False, indent=1))
        return
    for p in problems:
        print("code:", p)
    failed = bool(problems)
    if command == "check":
        for language in LANGUAGES:
            lang_problems, missing = check_language(language, found)
            for p in lang_problems:
                print(p)
            print(f"{language}: {len(found) - len(missing)}/{len(found)} translated")
            failed = failed or bool(lang_problems) or bool(missing)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
