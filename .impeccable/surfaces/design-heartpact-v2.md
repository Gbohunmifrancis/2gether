---
version: 1
slug: "design-heartpact-v2"
primary_target: "design/heartpact-v2"
related_targets: []
---

# Heartpact core screens (Android app mockups)

Scope: the core phone screens of the Android app as PNG mockups, for approval before app code. Visitor mode: Operate. Platform: android (Material 3 structure). Build path: code-led (app screens, no image generation).

Audience and job: two partners on their own phones. Each screen knows whether you act or your partner watches, sets, or waits.

Constraints: Material 3 navigation, controls and system Back. Edge-to-edge with the Android status bar and gesture handle. 48dp targets. The local alarm dismiss is always visible. Location is mutual-only, never "checking on". Estimates read as ranges.

Pinned by the user: soft premium (warm, high-end, generous spacing, refined type, soft depth, gentle motion), translated through Material theming.

Unresolved: the app stack; real photography for the album (stock photos are labelled as stand-ins).

## Direction contract

THESIS: Every promise between the two of you is a pact card pressed from two layers of coloured stock, one per partner, closed by two seals. Refuses the category default of pink hearts, progress rings and same-size icon cards.

OWN-WORLD: Duplexed cotton stock. Cool fog ground #E4E9E6, near-white paper cards, ink #18202B. Each partner owns one stock colour everywhere: Maya teal #1C6764, Theo persimmon #C2532E. The planned #CF5A33 gives white text only 4.07:1; #C2532E reaches 4.59:1. The alarm field uses the deeper #A9441F so its white body text reaches 5.94:1. A two-layer top edge marks pact cards only. Debossed seal discs: pressed solid when signed, dashed outline when pending, struck when ended. Soft, long, low-opacity shadows. Bricolage Grotesque for display and numerals, Hanken Grotesk for UI text, Material Symbols Rounded at weight 300.

STORY: You see what is due today, who set it, and how long until it rings. You do it on camera, your seal presses, and the pact closes. Ending early needs both seals. Privacy and sharing are always one tap from where the data shows.

FIRST VIEWPORT: Today screen. Large "Today" title at top left with the date and the together-streak, the couple's two stock discs at top right. The live pact is the largest element: a duplex card holding the title, a giant minutes-left numeral, two seals in a row and a full-width "Start on camera" button in the actor's colour, with "Ask Theo to end it" beneath. Below it, the other pact as a compact duplex strip, then one paper sheet of rows (food, question, location), then the Material navigation bar: Today, Pacts, Food, Us, Album.

FORM: Duplex Pact. Position 6 of 7 on the grounded list (the literal "pact" reading, rendered as duplex stock rather than cream and serif). Seed key f8fdbbc9.
- Japanese-density raise: the whole day fits the first viewport on a 390×844 phone.
- Star-atlas raise: one fixed size ramp ranks importance, and the live pact is always the largest element.
- Character-roster raise: each partner's colour is fixed and used everywhere their data appears.
- Botanical-folio raise: history views keep every day on one baseline at one scale.
- Emission-line raise: state is never hue alone. Dashed means pending, solid means signed, struck means ended.

Signature interaction: pressing a seal. The disc sinks with a debossed inset and fills in the signer's stock colour over 220ms, ease-out. When both seals are pressed, the pact's two-layer edge closes along the card.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
