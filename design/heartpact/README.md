# Heartpact design mockups

Core screens for the Heartpact mobile app (Android-first), drawn before implementation.
Rendered PNGs are in `png/` at 390×844 logical pixels, 2× scale (iPhone 14 / Pixel-class viewport).
`png/00-contact-sheet.png` shows all twenty screens on one page.

## Rebuild

```bash
node render.js            # renders screens/*.html -> png/*.png
node render.js 06         # only screens whose filename contains "06"
W=390 COLS=5 node sheet.js  # contact sheet
```

`render.js` wraps each fragment in the phone frame, the status bar and, when the fragment starts with
`<!-- tab:home -->` (or goals / cycle / us), the bottom tab bar. `<!-- dark -->` switches to the plum theme.
Playwright is loaded from `/opt/node-tools/node_modules/playwright` in the cloud session; point it at a
local install elsewhere.

## Inspiration (Mobbin, Health & Fitness, sorted by popularity)

The listing itself is behind a login, so the references are the apps and pattern tags Mobbin indexes
for that category, applied from memory of those apps:

| Pattern | Borrowed from | Where it shows up |
|---|---|---|
| Two concentric activity rings, one per partner | Apple Fitness | Home, Goals, Welcome |
| Big tabular numbers with small unit labels | Apple Health, WHOOP | Camera counter, Food, Snap result |
| Calorie ring plus macro pills and photo-first meal cards | Cal AI, MacroFactor, MyFitnessPal | Food log, Snap result |
| Phase-coloured calendar and a hero "Day N" card | Flo, Clue | Cycle |
| Dark full-bleed hero cards with one accent colour | Nike Run Club, Peloton, Centr | Challenge, Alarm, Flashcards |
| Map hero with a bottom sheet of people | Strava, Life360 | Map |
| Chips, segmented controls, bold rounded type | Runna, Fitbod | Assign challenge, Goals |
| Single-screen paywall with a highlighted annual plan | Most of the category's "Subscribing" flows | Paywall |

## Design tokens (see `styles.css`)

- Background `#FAF6F1`, surface `#FFFFFF`, ink `#1C1A2B`, plum (dark surfaces) `#2A1E3B`.
- Partner A rose `#F2546B`, partner B teal `#2BB7A3`. Every two-person element uses this pairing.
- Gold `#F5B23D` for streaks and food, sky `#7C8CF8` for fertile days and questions.
- Radius 24 px on cards, 18 px on buttons, 16 px gutters. Inter / Inter Display.

## Screens

| # | Screen | Flow |
|---|---|---|
| 01 | Welcome | Onboarding |
| 02 | Pair up (invite code, QR) | Onboarding |
| 03 | Home dashboard | Core |
| 04 | Assign a challenge (exercise, reps, deadline, alarm window, stake) | Challenge |
| 05 | Challenge received (countdown) | Challenge |
| 06 | Camera rep counter (on-device pose) | Challenge |
| 07 | Verified, proof clip, scoreboard | Challenge |
| 08 | Alarm ringing (locally dismissable, capped) | Challenge |
| 09 | Partner asks to be released (bottom sheet) | Challenge |
| 10 | Goals (shared weekly target, per-partner goals) | Fitness |
| 11 | Food log (calorie ring, macros, meals) | Diet |
| 12 | Snap result sheet (estimate with range) | Diet |
| 13 | Cycle (calendar, partner visibility) | Health |
| 14 | Pregnancy mode | Health |
| 15 | Map (mutual location, pause) | Location |
| 16 | Daily question | Connection |
| 17 | Flashcards ("Know me?") | Connection |
| 18 | Memories (gallery, import) | Connection |
| 19 | Paywall (one plan per couple) | Monetization |
| 20 | Us (profile, alarm limits, privacy, pause) | Settings |

The alarm design follows the policy findings in `docs/research/mobile-pivot-viability.md`: the device
owner can always silence it, the duration is capped by the receiving partner's own limit, and the
two-partner agreement governs completion and streak credit rather than the audio.
