# Translating Heartpact

Heartpact speaks English, French, Portuguese (Brazil), Spanish (Latin America), Arabic and Hindi: the Android app,
the API's messages and the website. Each works the same way: English is written in the code or page and is also the
key, and each other language is one JSON file mapping the English to the translation. The sections below cover the
app, then the API, then the website.

## The app

The Android app follows the phone's language; on Android 13 and later people can also pick a language for Heartpact alone in the system
settings (`res/xml/locales_config.xml`).

### How it works

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

### Rules for code

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
- A link inside a sentence wraps its words in a tag, and `linkedText` turns the tag into the link:
  `tr("By continuing you agree to the <terms>Terms</terms> and the <privacy>Privacy Policy</privacy>.")`. A
  translation keeps the same tags around its own words; `check` compares them.

### Adding or changing text

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

## The API

The server answers in the language the request asks for (`Accept-Language`; the app sends the phone's language, the
website's deletion page sends the page's). Only the UI language changes: numbers and dates the server formats or
parses stay in the invariant culture.

```csharp
return UnprocessableEntity(new ApiError("auth.invalid_country", Loc.T("Choose your country from the list.")));
Loc.T("Use at least {min} characters.", ("min", PasswordResetRules.MinPasswordLength));
Loc.Plural(count, "{name} added {count} photo to your album.", "{name} added {count} photos to your album.", ("name", name));
```

- The catalogs are `src/Twogether.Shared/Localization/i18n/<language>.json`, embedded in the assembly.
- Notifications are read by someone else, so they are written in the **recipient's** language:
  `using (Loc.Use(await db.LanguageOfAsync(partnerId, ct))) { ... }`. Each user's language is stored (`User.Language`)
  from their app's requests. The password-reset email uses the language of the request that asked for it.
- Text that is translated where it is shown, not where it is defined (the daily question bank, exercise names), is
  marked `Loc.N("...")` so the catalog finds it, and shown with `Loc.T(definition.Text)`.
- No `$"..."` inside `Loc.T`: use `{name}` placeholders. Error fields built once (`static readonly`) would be stuck in
  English: make them properties (`=>`).
- Admin-only messages and the old web dashboard stay English.
- `python3 android/i18n/catalog.py <export|check|missing fr|merge fr file|prune> --server` works as for the app; CI
  runs `check --server`.

## The website

The English pages in `website/` are the source. `website/i18n/build.py` treats each block of text (a paragraph,
heading, list item, link or button, with its inline tags) and each `alt`, `aria-label` and page description as one
string, and writes `website/<language>/...` with links kept in that language, `hreflang` links, a language switcher in
the footer, and the scripts' words (`T("...")` in `assets/*.js`). Arabic pages are right to left; the hero's two
halves keep their places because they are a picture of the couple.

1. Edit the English page as usual.
2. `python3 website/i18n/build.py missing fr` lists what French needs; `merge fr batch.json` adds translations (a
   translation must keep the English's tags exactly); `prune` drops strings that are gone.
3. `python3 website/i18n/build.py build` writes the language pages and the sitemap. Commit them.
4. `python3 website/i18n/build.py check` must pass; the deploy runs it and won't publish a stale or half-translated
   site.

A first-time visitor on an English page goes once to their browser's language if we have it; picking a language in
the footer is remembered. The privacy policy and terms in other languages say the English version applies.

## Tone

Heartpact is warm, playful and plain-spoken, for couples. Use the informal "you" where the language has one (tu in
French, você in Brazilian Portuguese, tú in Spanish, तुम in Hindi), and keep it short: buttons especially. "Heartpact"
and "Heartpact Plus" are names and stay as they are.
