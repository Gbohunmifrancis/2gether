# Translating the app

The Android app speaks English, French, Portuguese (Brazil), Spanish (Latin America), Arabic and Hindi. It follows
the phone's language; on Android 13 and later people can also pick a language for Heartpact alone in the system
settings (`res/xml/locales_config.xml`).

## How it works

English is written in the code and doubles as the key. Each other language is one file,
`android/app/src/main/assets/i18n/<language>.json`, mapping the English to the translation:

```kotlin
Text(tr("Create account"))
Text(tr("{partner} answered", "partner" to partnerName))
Text(trn(days, "{count} day to go", "{count} days to go"))
```

```json
{
  "Create account": "Créer un compte",
  "{partner} answered": "{partner} a répondu",
  "{count} day to go|{count} days to go": { "one": "Encore {count} jour", "other": "Encore {count} jours" }
}
```

A count's key is its two English forms joined by `|`. Its value has one entry per plural form the language uses:
`one` and `other` for French, Portuguese, Spanish and Hindi, and `zero`, `one`, `two`, `few`, `many` and `other`
for Arabic. Anything missing falls back to English, so the app never shows a blank. The file is read when the app
starts and again when the language changes (`Tr.load`).

## Rules for code

- Wrap every word a person reads: text on screen, buttons, dialog titles, notes and errors the app writes,
  notification titles and bodies, notification channel names, and `contentDescription`s for screen readers.
- Don't wrap what people don't read: log lines, routes, keys, API codes, JSON names, test tags, URLs, regexes, or
  exception messages for programmer errors. Don't wrap text that comes from the server or from people (partner
  names, answers, questions, server error messages).
- The first argument to `tr` (and both forms in `trn`) must be one plain string literal. No `+` and no `$name`:
  use `{name}` placeholders with clear names (`{partner}`, `{minutes}`), so a translator can move them around.
- Keep whole sentences in one string. Translators can't reorder a sentence that the code glues together.
- Counts go through `trn`, never `if (n == 1) ... else ...`. Arabic has six forms; Hindi and French treat 0 as one.
- Text kept in a top-level `val`, `const val` or object property is read once, so it would stay in the old language
  after a switch. Make it a getter (`val label get() = tr("…")`) or a function.
- Dates: write the English layout through `Dates.formatter("d MMMM")`, not `DateTimeFormatter.ofPattern`; other
  languages then get their own order and words ("27 de outubro"). Numbers with decimals use `Locale.getDefault()`.
- Don't drop a translated word into another sentence (`"{count} {exercise}"`, `"Play {game}"`): gender, case and
  articles can't follow. Write one whole phrase per case instead, as `ExerciseType.label` and `GamePlay.play` do.

## Adding or changing text

1. Write the English with `tr`/`trn` as above.
2. `python3 android/i18n/catalog.py export` lists every string, with where it is used, in `android/i18n/source.json`.
3. Add the translations to each language file. `python3 android/i18n/catalog.py missing fr` shows what French
   still needs.
4. `python3 android/i18n/catalog.py prune` drops translations whose English has gone.
5. `python3 android/i18n/catalog.py check` must pass; CI runs it. It fails on a missing translation, a translation
   whose English is gone, or placeholders that don't match.

Changing the English of a string changes its key, so every language needs the new string again.

Arabic and Hindi come from the phone's system fonts (Sora and Figtree are Latin only); `HeartpactType.forLanguage`
gives them normal letter spacing and taller lines. Arabic lays out right to left on its own.
`LocalizedShotsTest` renders key screens in every language into `android/app/build/screens/<language>/`.

## Tone

Heartpact is warm, playful and plain-spoken, for couples. Use the informal "you" where the language has one (tu in
French, você in Brazilian Portuguese, tú in Spanish, तुम in Hindi), and keep it short: buttons especially. "Heartpact"
and "Heartpact Plus" are names and stay as they are.
