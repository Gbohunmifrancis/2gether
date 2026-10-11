---
name: Heartpact
description: Sticker Book. Every pact a couple keeps together becomes a two-tone heart sticker on a shared periwinkle album page.
colors:
  album-page: "#DFE3FB"
  album-page-deep: "#D3D8F5"
  sticker-stock: "#FFFFFF"
  sticker-recess: "#F3F4FC"
  ink: "#1B2230"
  ink-2: "#4B5370"
  ink-3: "#6D7492"
  ink-4: "#A3A9C6"
  rule: "#E3E6F4"
  rule-2: "#C9CEE8"
  maya-art: "#2C8C85"
  theo-art: "#E2683C"
  maya-action: "#23746E"
  maya-soft: "#D3ECE8"
  maya-tint: "#EAF6F4"
  theo-action: "#C4532C"
  theo-soft: "#FCDCCD"
  theo-tint: "#FFF0E9"
  maya-on-soft: "#1D6862"
  theo-on-soft: "#A8401C"
  on-action: "#FFFFFF"
  scrim: "rgba(27,34,48,.42)"
  night-page: "#151933"
  night-page-deep: "#1C2142"
  night-sticker: "#232951"
  night-sticker-recess: "#2B3260"
  night-ink: "#EEF0FB"
  night-ink-2: "#B7BCDA"
  night-ink-3: "#8E94B8"
  night-ink-4: "#5E6590"
  night-rule: "#2F3664"
  night-rule-2: "#3C4475"
  night-maya-action: "#6CCFC4"
  night-maya-soft: "#1E4A4B"
  night-maya-tint: "#1B3B40"
  night-theo-action: "#FF9B73"
  night-theo-soft: "#4D2C25"
  night-theo-tint: "#3B2421"
  night-maya-on-soft: "#6CCFC4"
  night-theo-on-soft: "#FF9B73"
  night-on-action: "#10142A"
typography:
  display:
    fontFamily: "Fredoka, sans-serif"
    fontSize: "66px"
    fontWeight: 600
    lineHeight: 0.9
    letterSpacing: "-0.02em"
  display-s:
    fontFamily: "Fredoka, sans-serif"
    fontSize: "48px"
    fontWeight: 600
    lineHeight: 0.95
    letterSpacing: "-0.015em"
  headline:
    fontFamily: "Fredoka, sans-serif"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: 1.05
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Fredoka, sans-serif"
    fontSize: "21px"
    fontWeight: 600
    lineHeight: 1.18
  title-s:
    fontFamily: "Fredoka, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.22
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
    textColor: "{colors.maya-on-soft}"
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
    textColor: "{colors.maya-on-soft}"
    rounded: "13px"
    padding: "0 10px"
    height: "26px"
  tag-theo:
    backgroundColor: "{colors.theo-tint}"
    textColor: "{colors.theo-on-soft}"
    rounded: "13px"
    padding: "0 10px"
    height: "26px"
---

# Design System: Heartpact

## Overview

**Creative North Star: "The Shared Sticker Book"**

Heartpact is a periwinkle album page that two people fill together. Every pact one partner sets and the other keeps lands on that page as a heart sticker built from two halves: Maya's teal half and Theo's persimmon half. Cards are white die-cut sticker stock with large radii that lift off the page on soft lilac-tinted shadows. The two half-heart characters, each with a face, arms, legs and a stitched seam on the cut edge, are the illustration voice. They sit on cards as die-cut stickers and are never decoration-only. Two halves always make one heart, and they are never drawn as a single-heart mascot.

The structure is Material 3 on Android: large top app bars, a five-destination navigation bar, pill buttons, extended FABs, bottom sheets and system Back, with 48dp minimum targets. The world lives in the materials (album page, sticker stock, sticker states and characters), not in a custom chrome. Density is calm. Each screen shows the next thing to do first, then the week as a row of stickers, then a white sheet of rows.

State is carried by sticker shape and outline, so nothing disappears: an ended pact stays on the page, peeled, and is not deleted. Saturated colour is spent only on what you can press, or on a partner's own sticker.

**Key Characteristics:**
- Periwinkle album page ground with white sticker-stock cards (26-30px radii) and lilac-tinted lift shadows.
- Two fixed partner colours. Each has an art value for illustration and a darker action value for anything carrying a label.
- Sticker state vocabulary (heart, half, slot, peeled) that reads without hue.
- Fredoka for display and numerals, Figtree for everything you read or tap, and Material Symbols Rounded icons at weight 300.
- Estimates are always shown as ranges.
- A night scheme where the page goes deep indigo and the sticker colours stay.

## Colors

A cool periwinkle paper world with two warm-and-cool partner colours, each split into an illustration tone and a legible action tone.

### Primary
- **Maya Teal, art** (maya-art): fills Maya's half-heart, her character body and her map pin. Illustration only. White text on it measures 4.04:1, so it never carries a label.
- **Maya Teal, action** (maya-action): filled buttons, FABs, switches, the active nav icon, focused fields and links when Maya is the actor. White labels measure 5.53:1. Soft (maya-soft) and tint (maya-tint) back selected chips, segments, tonal buttons and tags. Text on them uses maya-on-soft #1D6862 (5.28:1 on soft), because the action value measures only 4.46:1 there.

### Secondary
- **Theo Persimmon, art** (theo-art): fills Theo's half-heart and his character. Illustration only. White text on it measures 3.34:1.
- **Theo Persimmon, action** (theo-action): filled buttons when Theo is the actor, or when the action is Theo's to agree to (for example "Call it off together"). White labels measure 4.54:1. Soft and tint versions follow Maya's pattern, with text in theo-on-soft #A8401C (4.76:1 on soft; the action value measures only 3.52:1 there). At night both on-soft inks equal the night action values.

### Neutral
- **Album Page** (album-page): the app background on every daytime screen.
- **Album Page, deep** (album-page-deep): the navigation bar, its gesture gap, and wells set into the page.
- **Sticker Stock** (sticker-stock): cards, the dare card, sheets, fields, chips and the sticker keyline.
- **Sticker Recess** (sticker-recess): recessed areas inside a card, such as the slot well, tags and the missed-slot fill.
- **Ink** (ink, then ink-2, ink-3 and ink-4): text from primary through muted (ink-2) and faint (ink-3). ink-4 is non-text only: the dashed coming-up slot, the switch track outline and the sheet grip. Ink is also the ground of the full-screen alarm.
- **Rules** (rule, rule-2): the dashed row perforations and chip outlines (rule), and field and outline-button strokes plus the missed-slot border (rule-2).
- **Night** (night-*): the album page after lights out. Surfaces go deep indigo, and action colours lighten (night-maya-action, night-theo-action) and take dark labels (night-on-action, 9.8:1 and 8.8:1). The sticker art keeps its daytime hex.

### Named Rules
**The Fixed Ribbon Rule.** Each partner's colour belongs to that partner on every screen. Teal is always Maya and persimmon is always Theo, whoever is viewing. A colour is never reassigned to mean "you" or "them".

**The Art-Is-Not-Action Rule.** maya-art and theo-art fill stickers, characters and pins only. Anything with a text label, including a filled button, FAB, tag or initial disc, uses the action value.

**The Press-or-Own Rule.** Saturated colour appears only on what you can press, or on that partner's own sticker. Grounds, headers and full-screen moments stay neutral. The alarm grounds on ink (#1B2230), not on a partner colour.

**The Faint-on-Stock Rule.** ink-3 is used only on sticker stock (4.6:1 on white). On the album page it drops to 3.62:1, so secondary text on the page uses ink-2.

## Typography

**Display Font:** Fredoka (self-hosted variable woff2, 300-700), with a sans-serif fallback
**Body Font:** Figtree (self-hosted variable woff2, 300-900), with a sans-serif fallback
**Icons:** Material Symbols Rounded, FILL 0 at weight 300 and optical size 24. Active nav icons switch to FILL 1 at weight 400.

**Character:** Fredoka's rounded, sticker-cut letterforms carry titles and every big number. Figtree is the quiet, legible voice for anything you read or tap. All text sets tabular numerals, so timers and counts don't jitter.

### Hierarchy
- **Display** (600, 66px, 0.9, -0.02em): the giant minutes-left numeral on the dare card, rep counts and day numbers. The alarm clock enlarges it to 108px as a single full-screen exception.
- **Display S** (600, 48px, 0.95): secondary hero numerals, such as calorie ranges, the remaining ring time and the invite code.
- **Headline** (600, 30px, 1.05): large top-app-bar titles (Today, Pacts, Food, Us).
- **Title** (600, 21px, 1.18): dare titles and the small app-bar title.
- **Title S** (600, 17px, 1.22): section headings and secondary card titles.
- **Body L** (500 Figtree, 16px, 1.5): alarm and onboarding sentences.
- **Body** (500, 14px, 1.45): supporting card copy.
- **Small** (500, 12.5px, 1.4): metadata, row subtitles and date captions.
- **Label** (700, 14px) and **Label S** (700, 12.5px): row keys, card labels, tags and chips. Buttons use Figtree 700 at 16px.

### Named Rules
**The One Ramp Rule.** The ten roles above are the whole ramp. A new screen picks from them rather than adding sizes.

**The Rounded Numeral Rule.** Every number that matters (minutes, reps, counts, estimates) is set in Fredoka. Figtree numerals are for metadata only.

## Layout

The design targets a 390 x 844dp compact Android phone in portrait. Screens stack top to bottom: a status bar, a large top app bar (title plus a single sub line), then content. Tab screens end with the 84px navigation bar and a 24px gesture gap. Cards sit 12px from the screen edges with 12px between them. Text that sits directly on the page (headers, sections and legends) uses a 20px gutter. Card interiors pad 16-22px. List rows have a minimum height of 54px and are separated by 2px dashed perforations. Interactive targets are at least 48dp, and icon buttons are 48 x 48. Extended FABs float 16px from the right edge, above the navigation bar.

**The Next-Thing-First Rule.** The first card on a screen is the next thing to do: the live dare on Today, the current pact on Pacts, and the estimate so far on Food. Summaries come after it.

## Elevation & Depth

Depth is a sticker lifting off a page. Shadows are soft, offset downward, and tinted lilac (rgba(52,58,120,...)) rather than grey, with a 1-2px contact line. There are three steps: lift-1 for cards, lift-2 for the dare card, and lift-3 for the FAB and bottom sheet. Filled buttons cast a soft glow in their own action colour beneath them. Sticker artwork carries a thick white keyline and a small drop shadow, like a die-cut sticker. At night, the lifts become black shadows with a 1px inset top highlight. Exact values are in the sidecar.

**The Lift-Not-Line Rule.** Cards separate from the page by lift and colour, not by borders. Strokes are reserved for fields, outline buttons and unselected chips.

## Shapes

Shapes are generous and round: dare cards at 30px, cards at 26px, wells and FABs at 20px, fields at 18px, and fully pill-shaped buttons, chips, segments and tags. The heart, split down its centre into two halves, is the system's one silhouette. It appears as a sticker in four forms and as both characters. Sticker slots are drawn as the heart-half outline when pending, and as a small rounded tile (9px) in compact week rows. Row dividers are 2px dashed perforations, like a page meant to be torn.

## Components

### Sticker States (signature)
The vocabulary every screen shares. Each state differs in shape or outline, so it reads without colour:
- **Done together:** a two-tone whole heart (teal left, persimmon right) with a white keyline.
- **One side pending:** the partner's filled half beside a dashed half-outline slot, which is stroked in the pending partner's action colour.
- **Called off together:** the whole heart at reduced opacity, with its top-right corner peeled back to show recess and a rule-2 crease.
- **Missed:** an empty slot with a solid rule-2 border and a recess fill.
- **Coming up:** an empty slot with a dashed ink-4 border.

The signature motion is sticking your half: it drops in from 1.1x, lands with a small press and settles in 240ms ease-out, and both characters cheer when the heart completes. Ended pacts stay on the page.

### Characters
Maya and Theo are half-heart characters drawn as authored SVG, with art-colour bodies, action-colour shading, ink faces, and smile, cheer, worried and sleep moods. On cards they appear as die-cut stickers pegged over the top edge. Each character always appears in its own colour, and together they make one heart.

### Buttons
- **Shape:** full pill (56px tall; 40px for the small size).
- **Filled:** the actor's action colour with a white Figtree 700 label at 16px, a leading icon where useful, and a matching-colour glow beneath. It falls back to ink when no partner owns the action. At night it takes dark labels.
- **Tonal:** soft partner fill with on-soft text (never the action value), for secondary choices.
- **Outline:** a 2px rule-2 stroke with ink text, for "Keep it" style refusals.
- **Text:** action-colour label, 46px tall. Used for the partner-consent exit ("Ask Theo to call it off").

### Chips and Segments
- **Style:** pill, sticker-stock fill, a 2px rule inset stroke, and an ink-2 label in Figtree 700 at 13.5px.
- **Selected:** the soft fill of the acting partner with action-colour text and a leading check. Segmented buttons use the same on-state inside a 46px pill track.

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
The alarm grounds on ink, not on a partner colour. It shows the 108px Fredoka clock, the worried character sticker and the dare card in sticker stock with the filled camera action. Below come the partner's live-visibility row and the remaining ring time. "Ask Theo to call it off" and the local "Silence on this phone" are always visible at the bottom, the latter with a line saying what silencing does.

### Estimates
Calorie and macro figures are presented as ranges (1,050-1,430 kcal, Protein 30-38 g). A "~" point value is only ever secondary to a visible range, and the screen labels the figure as an estimate.

## Do's and Don'ts

### Do:
- **Do** keep teal on Maya and persimmon on Theo on every screen, in illustration, slots and actions alike.
- **Do** use maya-action (#23746E) or theo-action (#C4532C) under any white label. Keep maya-art and theo-art for stickers, characters and pins.
- **Do** carry every pact state by sticker shape and outline (whole, half plus dashed slot, peeled, solid empty slot, dashed empty slot) so it reads without hue.
- **Do** show food and nutrition estimates as ranges and label them as estimates.
- **Do** ground the full-screen alarm on ink (#1B2230), and keep the local silence control visible.
- **Do** keep ended pacts on the page as peeled hearts.
- **Do** keep text at 4.5:1 or above (ink-3 only on sticker stock), and set any label on a soft or tint fill in the on-soft ink, not the action value.

### Don't:
- **Don't** put white text on maya-art (#2C8C85, 4.04:1) or theo-art (#E2683C, 3.34:1).
- **Don't** fill a ground, header or full-screen moment with a partner colour. Saturated colour goes only on what you can press, or on that partner's own sticker.
- **Don't** signal a state by hue alone, for example a grey heart for missed or a red heart for ended.
- **Don't** draw the two characters as a single-heart mascot, or use them as decoration with no role on the screen.
- **Don't** show a bare point estimate as the primary figure for calories or macros.
- **Don't** replace sticker progress with progress rings or same-size icon cards.
