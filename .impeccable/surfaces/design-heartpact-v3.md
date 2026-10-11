---
version: 1
slug: "design-heartpact-v3"
primary_target: "design/heartpact-v3"
related_targets: []
---

# Heartpact core screens v3: Sticker Book (Android app mockups)

Scope: the core phone screens as PNG mockups for approval before app code. Visitor mode: Operate. Platform: android (Material 3 structure). Build path: code-led.

Brief from the user:
- A Mobbin screen recording, leaning on Gentler Streak and Ahead.
- Answers: a new direction from these references (illustration-led, character-driven, playful), and two characters, one per partner.
- Supersedes the Duplex Pact draft in design/heartpact-v2.

Characters: two half-heart characters, authored as SVG in design/heartpact-v3/mascots.js. Maya's half is teal and Theo's half is persimmon. Each has a face, arms, legs and a stitched seam on the cut edge, and together they make one heart. They are clearly not Gentler Streak's single heart mascot, and must never be drawn as one.

Constraints:
- Material 3 navigation, controls and system Back.
- 48dp targets.
- The local alarm dismiss is always visible.
- Location is mutual-only.
- Estimates read as ranges.

## Direction contract

THESIS: Every pact you keep together becomes a heart sticker. Your partner sticks their half when they set it, the camera sticks yours when you do it, and the whole heart lands on your shared sticker page. Refuses static progress rings, same-size icon cards, and a mascot used as decoration only.

OWN-WORLD: A periwinkle album page as the ground (#DFE3FB, nav and deeper surfaces #D3D8F5), white die-cut sticker cards with large radii and soft lilac-tinted shadows, and ink #1B2230.
- Artwork colours: Maya teal #2C8C85 and Theo persimmon #E2683C.
- Action and text colours: #23746E (5.53:1 on white) and #C4532C (4.54:1). The artwork colours are too light for white text.
- Pending is a dashed sticker slot. Signed is a half-heart sticker. Sealed is a two-tone full heart. Ended together is a heart sticker peeled at one corner. Missed is an empty slot.
- Fredoka for display and numerals, Figtree for UI text, Material Symbols Rounded at weight 300.

STORY: You see today's dare, who set it, the minutes left and the half your partner already stuck. You do it on camera and your half sticks on. The full heart joins your page. Ending early still takes both of you. What your partner can see is always one tap away.

FIRST VIEWPORT: Today screen.
- A large "Today" title with the date and a sticker-page count.
- The dare card is the largest element: the title in Fredoka, a giant minutes-left numeral, a sticker slot showing Theo's half beside your dashed half, a full-width "Start on camera" button in Maya's action teal, and "Ask Theo to call it off" beneath.
- Theo's character sits as a die-cut sticker on the card's top edge.
- Below: "This week" as a row of heart stickers, then a white sheet of rows (food, question, location).
- The Material navigation bar.

FORM: Sticker Book. Position 4 of 7 on the grounded list (sky diorama, trail map, picture book, sticker book, claymation, postcards, virtual pet). Seed key 35da5a0b.
- Warm-consumer raise: the one saturated action colour appears only on things you can press.
- Character-roster raise: each character keeps its fixed ribbon colour everywhere, and a lapsed pact prints in grey.
- Ticket-wallet raise: nothing disappears; ended pacts stay on the page, peeled, not deleted.
- Acetate-manual raise: few type sizes, one ramp.
- Wayfinding raise: Today shows the next thing to do first.
- Emission-line raise: state is carried by sticker shape and outline, never hue alone.

Signature interaction: sticking your half. The half-heart sticker drops from 1.1x, lands with a small press and settles in 240ms ease-out, and both characters pop a cheer when the heart completes.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
