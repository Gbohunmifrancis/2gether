---
name: Heartpact
description: Sticker Book on an indigo fold. Every pact a couple keeps together becomes a two-tone heart sticker, one ink folded in two, on a shared periwinkle album page.
colors:
  album-page: "#E3E5F6"
  album-page-deep: "#D7DAEF"
  sticker-stock: "#FFFFFF"
  sticker-recess: "#F2F3FA"
  ink: "#161A3D"
  ink-2: "#474C72"
  ink-3: "#6A6F92"
  ink-4: "#A4A8C6"
  rule: "#E4E6F3"
  rule-2: "#C8CBE3"
  maya-art: "#1F2557"
  theo-art: "#7480D4"
  maya-action: "#1F2557"
  maya-soft: "#D5D8F6"
  maya-tint: "#ECEEFC"
  maya-ink: "#1F2557"
  theo-action: "#4D58BE"
  theo-soft: "#DFE2FA"
  theo-tint: "#EEF0FD"
  theo-ink: "#3F49A8"
  on-action: "#FFFFFF"
  scrim: "rgba(22,26,61,.42)"
  night-page: "#10132B"
  night-page-deep: "#161A38"
  night-sticker: "#1E2347"
  night-sticker-recess: "#262C55"
  night-ink: "#EEF0FB"
  night-ink-2: "#B5B9D8"
  night-ink-3: "#8C91B6"
  night-ink-4: "#5B618C"
  night-rule: "#2B3160"
  night-rule-2: "#383F72"
  night-maya-action: "#E6E8FF"
  night-maya-soft: "#2C3263"
  night-maya-tint: "#232853"
  night-maya-ink: "#E6E8FF"
  night-theo-action: "#A9B1F2"
  night-theo-soft: "#2F3670"
  night-theo-tint: "#262C5C"
  night-theo-ink: "#A9B1F2"
  night-on-action: "#10142A"
typography:
  display:
    fontFamily: "Sora, sans-serif"
    fontSize: "60px"
    fontWeight: 600
    lineHeight: 0.92
    letterSpacing: "-0.04em"
  display-s:
    fontFamily: "Sora, sans-serif"
    fontSize: "42px"
    fontWeight: 600
    lineHeight: 0.98
    letterSpacing: "-0.035em"
  headline:
    fontFamily: "Sora, sans-serif"
    fontSize: "28px"
    fontWeight: 600
    lineHeight: 1.08
    letterSpacing: "-0.03em"
  title:
    fontFamily: "Sora, sans-serif"
    fontSize: "19px"
    fontWeight: 600
    lineHeight: 1.22
    letterSpacing: "-0.02em"
  title-s:
    fontFamily: "Sora, sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "-0.015em"
  wordmark:
    fontFamily: "Sora, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "-0.045em"
  body-l:
    fontFamily: "Figtree, sans-serif"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: 1.5
  body:
    fontFamily: "Figtree, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.45
    fontFeature: "'tnum' 1"
  small:
    fontFamily: "Figtree, sans-serif"
    fontSize: "12.5px"
    fontWeight: 500
    lineHeight: 1.4
  label:
    fontFamily: "Figtree, sans-serif"
    fontSize: "14px"
    fontWeight: 700
    lineHeight: 1.25
  label-s:
    fontFamily: "Figtree, sans-serif"
    fontSize: "12.5px"
    fontWeight: 700
    lineHeight: 1.25
rounded:
  slot: "9px"
  thumb: "14px"
  field: "18px"
  well: "20px"
  card: "26px"
  dare: "30px"
  pill: "28px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
  xxl: "24px"
components:
  button-filled-maya:
    backgroundColor: "{colors.maya-action}"
    textColor: "{colors.on-action}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 24px"
    height: "56px"
  button-filled-theo:
    backgroundColor: "{colors.theo-action}"
    textColor: "{colors.on-action}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 24px"
    height: "56px"
  button-filled-neutral:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.on-action}"
    rounded: "{rounded.pill}"
    padding: "0 24px"
    height: "56px"
  button-outline:
    backgroundColor: "{colors.sticker-stock}"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    padding: "0 24px"
    height: "56px"
  button-text-maya:
    textColor: "{colors.maya-action}"
    padding: "0 14px"
    height: "46px"
  chip:
    backgroundColor: "{colors.sticker-stock}"
    textColor: "{colors.ink-2}"
    rounded: "{rounded.pill}"
    padding: "0 15px"
    height: "38px"
  chip-selected-maya:
    backgroundColor: "{colors.maya-soft}"
    textColor: "{colors.maya-ink}"
    rounded: "{rounded.pill}"
    padding: "0 15px"
    height: "38px"
  card:
    backgroundColor: "{colors.sticker-stock}"
    rounded: "{rounded.card}"
    padding: "16px 18px"
  dare-card:
    backgroundColor: "{colors.sticker-stock}"
    rounded: "{rounded.dare}"
    padding: "22px 20px 14px"
  slot-well:
    backgroundColor: "{colors.sticker-recess}"
    rounded: "{rounded.well}"
    padding: "10px 14px 10px 10px"
  text-field:
    backgroundColor: "{colors.sticker-stock}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "18px 16px 14px"
  nav-bar:
    backgroundColor: "{colors.album-page-deep}"
    textColor: "{colors.ink-2}"
    height: "84px"
  nav-indicator-active:
    backgroundColor: "{colors.sticker-stock}"
    textColor: "{colors.maya-action}"
    rounded: "16px"
    width: "64px"
    height: "32px"
  fab-maya:
    backgroundColor: "{colors.maya-action}"
    textColor: "{colors.on-action}"
    rounded: "{rounded.well}"
    padding: "0 20px 0 16px"
    height: "56px"
  tag-maya:
    backgroundColor: "{colors.maya-tint}"
    textColor: "{colors.maya-ink}"
    rounded: "13px"
    padding: "0 10px"
    height: "26px"
  tag-theo:
    backgroundColor: "{colors.theo-tint}"
    textColor: "{colors.theo-ink}"
    rounded: "13px"
    padding: "0 10px"
    height: "26px"
---

# Design System: Heartpact

## Overview

**Creative North Star: "The Shared Sticker Book"**

Heartpact is a periwinkle album page that two people fill together, now on an indigo fold: one ink folded in two. Every pact one partner sets and the other keeps lands on that page as a heart sticker built from two halves, Maya's solid indigo half and Theo's periwinkle half, meeting on the fold line. Cards are white die-cut sticker stock with large radii that lift off the page on soft indigo-tinted shadows. The two half-heart characters, each with a face, arms, legs and a stitched seam on the cut edge, are the illustration voice. They sit on cards as die-cut stickers and are never decoration-only. Two halves always make one heart, and they are never drawn as a single-heart mascot.

The structure is Material 3 on Android: large top app bars, a five-destination navigation bar, pill buttons, extended FABs, bottom sheets and system Back, with 48dp minimum targets. The world lives in the materials (album page, sticker stock, sticker states and characters), not in a custom chrome. Density is calm. Each screen shows the next thing to do first, then the week as a row of stickers, then a white sheet of rows.

State is carried by sticker shape and outline, so nothing disappears: an ended pact stays on the page, peeled, and is not deleted. Saturated colour is spent only on what you can press, or on a partner's own sticker.

### Mark and Logo
The mark is a geometric heart: a square turned 45° with two round lobes (lobe radius half the square's side), split on its vertical centre line. The left half is solid indigo (maya-art) and the right half periwinkle (theo-art). A tick is cut clean through both halves (a mask, so whatever is behind shows through): a short stroke in Maya's half and a long one in Theo's, a promise both kept. The cut is what separates the mark from ordinary hearts, and it is reserved for the mark: in-app stickers never carry it. On night grounds the left half turns white (#FFFFFF) and the right half takes night-theo-action. The wordmark is lowercase "heartpact" in Sora 600 at -0.045em (the wordmark role), set in ink beside the mark. Clear space is one lobe radius on every side.

Assets live in `design/heartpact-v3/logo/`: `heartpact-mark.svg` (light grounds), `heartpact-mark-dark.svg` (night grounds), the Android adaptive icon layers `ic_launcher_foreground.svg` (the night mark at 54% scale), `ic_launcher_background.svg` (an indigo gradient, #2C3474 to #1A1F4D) and `ic_launcher_monochrome.svg` (left half solid, right half at 55% opacity), plus `play-store-icon-512.png` and the `brand.png` board. In-app, the mark leads the splash (night mark on indigo, wordmark at the foot), the welcome screen with the wordmark, and sign-in. `logo/explore.png` and `logo/distinct.png` record the directions considered.

**Key Characteristics:**
- Periwinkle album page ground with white sticker-stock cards (26-30px radii) and indigo-tinted lift shadows.
- One ink folded in two: Maya is solid indigo, Theo is periwinkle. Maya's art and action are the same indigo; Theo has a lighter art value and a deeper action value for anything carrying a label.
- A geometric split heart shared by the mark and every in-app sticker; only the mark carries the Kept Promise tick, and the characters keep a softer hand-drawn half-heart.
- Sticker state vocabulary (heart, half, slot, peeled) that reads without hue.
- Sora for display, numerals and titles with tight negative tracking; Figtree for everything you read or tap; Material Symbols Rounded icons at weight 300.
- Estimates are always shown as ranges.
- A night scheme where the page goes deep indigo, Maya's action turns near-white, and the sticker colours stay.

## Colors

A cool periwinkle paper world in a single indigo family: one partner is the solid ink, the other its lighter fold.

### Primary
- **Fold Indigo** (maya-art, maya-action, maya-ink): Maya's colour, one value for everything. It fills her half of every heart sticker and the left half of the mark, and it carries filled buttons, FABs, switches, the active nav icon, focused fields and links when Maya is the actor. White labels measure 14.38:1. It also serves as the text ink on her soft (maya-soft, 10.26:1) and tint (maya-tint) fills, which back selected chips, segments, tonal buttons and tags.

### Secondary
- **Theo Periwinkle, art** (theo-art): fills Theo's half of every heart sticker, his character and the right half of the mark. It measures 3.63:1 on white stock and 3.96:1 against Fold Indigo, enough to hold the fold as a graphic edge, so it is illustration only and never carries text.
- **Theo Periwinkle, action** (theo-action): filled buttons when Theo is the actor, or when the action is Theo's to agree to (for example "Call it off together"). White labels measure 6.08:1. Soft and tint versions back his chips and tags, with text in theo-ink (6.01:1 on soft).

### Neutral
- **Album Page** (album-page): the app background on every daytime screen.
- **Album Page, deep** (album-page-deep): the navigation bar, its gesture gap, and wells set into the page.
- **Sticker Stock** (sticker-stock): cards, the dare card, sheets, fields, chips and the sticker keyline.
- **Sticker Recess** (sticker-recess): recessed areas inside a card, such as the slot well, tags, the missed-slot fill and the peeled corner.
- **Ink** (ink, then ink-2, ink-3 and ink-4): text from primary through muted (ink-2) and faint (ink-3). ink-4 is non-text only: the dashed coming-up slot, the switch track outline and the sheet grip. Ink is also the ground of the full-screen alarm and the camera counter panel.
- **Rules** (rule, rule-2): the dashed row perforations and chip outlines (rule), and field and outline-button strokes, the missed-slot border and the peel crease (rule-2).
- **Night** (night-*): the album page after lights out. Surfaces go deep indigo. Maya's action turns near-white (night-maya-action) and Theo's lightens (night-theo-action); both take dark labels (night-on-action, 15.02:1 and 8.88:1), and both on-soft inks equal the night action values. The sticker art keeps its daytime hex.

### Named Rules
**The Fixed Ribbon Rule.** Each partner's colour belongs to that partner on every screen. Fold Indigo is always Maya and periwinkle is always Theo, whoever is viewing. A colour is never reassigned to mean "you" or "them".

**The Art-Is-Not-Action Rule.** This applies to Theo. theo-art fills stickers, characters, the mark and pins only. Anything of Theo's with a text label, including a filled button, FAB, tag or initial disc, uses theo-action. Maya's art and action are the same indigo, so her half needs no split.

**The Press-or-Own Rule.** Saturated colour appears only on what you can press, or on that partner's own sticker. Grounds, headers and full-screen moments stay neutral. The alarm grounds on ink, not on a partner colour.

**The Faint-on-Stock Rule.** ink-3 is used only on white sticker stock (4.88:1). On the album page it drops to 3.91:1, so secondary text on the page uses ink-2.

## Typography

**Display Font:** Sora (self-hosted variable woff2 at `fonts/sora-latin.woff2`, 400-700), with a sans-serif fallback
**Body Font:** Figtree (self-hosted variable woff2, 300-900), with a sans-serif fallback
**Icons:** Material Symbols Rounded, FILL 0 at weight 300 and optical size 24. Active nav icons switch to FILL 1 at weight 400.

**Character:** Sora's geometric, tightly tracked letterforms echo the geometric heart and carry the wordmark, app bar titles, section heads and every big number. Figtree is the quiet, legible voice for anything you read or tap. All text sets tabular numerals, so timers and counts don't jitter.

### Hierarchy
- **Display** (600, 60px, 0.92, -0.04em): the giant minutes-left numeral on the dare card, rep counts and day numbers. The alarm clock enlarges it to 108px as a single full-screen exception.
- **Display S** (600, 42px, 0.98, -0.035em): secondary hero numerals, such as calorie ranges, the remaining ring time and the invite code.
- **Headline** (600, 28px, 1.08, -0.03em): large top-app-bar titles (Today, Pacts, Food, Us) and onboarding headlines.
- **Title** (600, 19px, 1.22, -0.02em): dare titles and the small app-bar title.
- **Title S** (600, 16px, 1.25, -0.015em): section headings and secondary card titles.
- **Body L** (500 Figtree, 16px, 1.5): alarm and onboarding sentences.
- **Body** (500, 14px, 1.45): supporting card copy.
- **Small** (500, 12.5px, 1.4): metadata, row subtitles and date captions.
- **Label** (700, 14px) and **Label S** (700, 12.5px): row keys, card labels, tags and chips. Buttons use Figtree 700 at 16px.
- **Wordmark** (Sora 600, 22px in-app, -0.045em): the lowercase "heartpact" lockup only. It is part of the logo, not a reading role.

### Named Rules
**The One Ramp Rule.** The ten reading roles above are the whole ramp. A new screen picks from them rather than adding sizes.

**The Geometric Numeral Rule.** Every number that matters (minutes, reps, counts, estimates) is set in Sora with its negative tracking. Figtree numerals are for metadata only.

## Layout

The design targets a 390 x 844dp compact Android phone in portrait. Screens stack top to bottom: a status bar, a large top app bar (title plus a single sub line), then content. Tab screens end with the 84px navigation bar and a 24px gesture gap. Cards sit 12px from the screen edges with 12px between them. Text that sits directly on the page (headers, sections and legends) uses a 20px gutter. Card interiors pad 16-22px. List rows have a minimum height of 54px and are separated by 2px dashed perforations. Interactive targets are at least 48dp, and icon buttons are 48 x 48. Extended FABs float 16px from the right edge, above the navigation bar.

**The Next-Thing-First Rule.** The first card on a screen is the next thing to do: the live dare on Today, the current pact on Pacts, and the estimate so far on Food. Summaries come after it.

## Elevation & Depth

Depth is a sticker lifting off a page. Shadows are soft, offset downward, and tinted indigo (rgba(31,37,87,...)) rather than grey, with a 1-2px contact line in ink. There are three steps: lift-1 for cards, lift-2 for the dare card, and lift-3 for the FAB and bottom sheet. Filled buttons cast a soft glow in their own action colour beneath them. Sticker artwork carries a thick white keyline and a small indigo drop shadow, like a die-cut sticker. At night, the lifts become black shadows with a 1px inset top highlight, and the white keyline stays white. Exact values are in the sidecar.

**The Lift-Not-Line Rule.** Cards separate from the page by lift and colour, not by borders. Strokes are reserved for fields, outline buttons and unselected chips.

## Shapes

Shapes are generous and round: dare cards at 30px, cards at 26px, wells and FABs at 20px, fields at 18px, and fully pill-shaped buttons, chips, segments and tags. The heart, split down its centre into two halves, is the system's one silhouette. In-app stickers and the mark draw it geometrically: a square turned 45° with two round lobes, so the bottom point and the side shoulders are straight and the top is two arcs. The characters keep a softer, hand-drawn half-heart with curved sides. Sticker slots are drawn as the geometric half-outline when pending, and as a small rounded tile (9px) in compact week rows. Row dividers are 2px dashed perforations, like a page meant to be torn.

## Components

### Sticker States (signature)
The vocabulary every screen shares, drawn with the geometric heart. Each state differs in shape or outline, so it reads without colour:
- **Done together:** a two-tone whole heart (indigo left, periwinkle right) with a 13px white keyline and a soft indigo drop shadow.
- **One side pending:** the partner's filled half beside a dashed half-outline slot (5px stroke, 9/8 dash), stroked in the pending partner's action colour.
- **Called off together:** the whole heart at half opacity, with its top-right lobe quarter peeled back to show sticker-recess and a rule-2 crease.
- **Missed:** an empty slot with a solid rule-2 border and a recess fill.
- **Coming up:** an empty slot with a dashed ink-4 border.

Sticker fills stay fixed at night (indigo and periwinkle on a white keyline); only the dashed pending slot follows the night action colours. The signature motion is sticking your half: it drops in from 1.1x, lands with a small press and settles in 240ms ease-out, and both characters cheer when the heart completes. Ended pacts stay on the page.

### Characters
Maya and Theo are half-heart characters drawn as authored SVG with smile, cheer, worried and sleep moods. Maya's body is a slightly lifted indigo (#262D66, shaded #1A1F4D) with a white face; Theo's body is periwinkle (theo-art, shaded #5F6BC6) with an ink face. Both have rose cheeks (#E58AA8 on Maya, #F2A3BA on Theo), a soft white highlight and a dashed white stitch on the cut edge. On cards they appear as die-cut stickers with a white keyline, pegged over the top edge, and they keep their colours at night. Each character always appears in its own colour, and together they make one heart.

### Buttons
- **Shape:** full pill (56px tall; 40px for the small size).
- **Filled:** the actor's action colour with a white Figtree 700 label at 16px, a leading icon where useful, and a matching-colour glow beneath. It falls back to ink when no partner owns the action. At night it takes dark labels.
- **Tonal:** soft partner fill with the partner's on-soft ink, for secondary choices.
- **Outline:** a 2px rule-2 stroke with ink text, for "Keep it" style refusals.
- **Text:** action-colour label, 46px tall. Used for the partner-consent exit ("Ask Theo to call it off").

### Chips and Segments
- **Style:** pill, sticker-stock fill, a 2px rule inset stroke, and an ink-2 label in Figtree 700 at 13.5px.
- **Selected:** the soft fill of the acting partner with on-soft ink text and a leading check. Segmented buttons use the same on-state inside a 46px pill track.

### Cards / Containers
- **Corner Style:** 26px for cards, 30px for the dare card.
- **Background:** sticker-stock, with sticker-recess for the wells inside.
- **Shadow Strategy:** lift-1, or lift-2 for the dare card (see Elevation & Depth).
- **Border:** none. Rows inside use dashed rule perforations.
- **Internal Padding:** 16-22px.

### Inputs / Fields
- **Style:** an outlined Material field on sticker stock, with an 18px radius, a 2px rule-2 inset stroke, and a label notched into the top edge.
- **Focus:** a 2.5px stroke in the actor's action colour, with the label and caret in the same colour.

### Navigation
The Material 3 navigation bar on album-page-deep has five destinations: Today, Pacts, Food, Us and Album. The active item gets a 64 x 32 sticker-stock indicator pill, a filled icon in maya-action, and an ink 800-weight label. Inactive items show an ink-2 600-weight label at 12px.

### Full-Screen Alarm
The alarm grounds on ink, not on a partner colour. It shows the 108px Sora clock, the worried character sticker and the dare card in sticker stock with the filled camera action. Below come the partner's live-visibility row and the remaining ring time. "Ask Theo to call it off" and the local "Silence on this phone" are always visible at the bottom, the latter with a line saying what silencing does.

### Estimates
Calorie and macro figures are presented as ranges (1,050-1,430 kcal, Protein 30-38 g). A "~" point value is only ever secondary to a visible range, and the screen labels the figure as an estimate.

## Do's and Don'ts

### Do:
- **Do** keep indigo on Maya and periwinkle on Theo on every screen, in the mark, illustration, slots and actions alike.
- **Do** use maya-action (#1F2557) or theo-action (#4D58BE) under any white label. Keep theo-art for stickers, characters, the mark and pins.
- **Do** carry every pact state by sticker shape and outline (whole, half plus dashed slot, peeled, solid empty slot, dashed empty slot) so it reads without hue.
- **Do** draw in-app stickers and the mark with the geometric heart, and keep the softer hand-drawn half-heart for the characters.
- **Do** use heartpact-mark-dark.svg (white and #A9B1F2 halves) on night grounds, and keep one lobe radius of clear space around the mark.
- **Do** show food and nutrition estimates as ranges and label them as estimates.
- **Do** ground the full-screen alarm on ink (#161A3D), and keep the local silence control visible.
- **Do** keep ended pacts on the page as peeled hearts.
- **Do** keep text at 4.5:1 or above (ink-3 only on sticker stock), and set any label on a soft or tint fill in the partner's on-soft ink.

### Don't:
- **Don't** put any text on theo-art (#7480D4, 3.63:1 against white).
- **Don't** fill a ground, header or full-screen moment with a partner colour. Saturated colour goes only on what you can press, or on that partner's own sticker.
- **Don't** signal a state by hue alone, for example a grey heart for missed or a red heart for ended.
- **Don't** recolour sticker art for the night scheme; the white keyline frames the fixed indigo and periwinkle.
- **Don't** draw the two characters as a single-heart mascot, or use them as decoration with no role on the screen.
- **Don't** show a bare point estimate as the primary figure for calories or macros.
- **Don't** replace sticker progress with progress rings or same-size icon cards.
