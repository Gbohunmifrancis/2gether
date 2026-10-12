---
name: Heartpact
description: Duplex Pact. Every promise is a card pressed from two layers of coloured stock, one per partner, closed by two seals.
colors:
  maya-teal: "#1C6764"
  maya-ink: "#0F4744"
  maya-soft: "#D3E6E2"
  maya-tint: "#EAF3F1"
  theo-persimmon: "#C2532E"
  theo-ink: "#8E3416"
  theo-soft: "#F7DED3"
  theo-tint: "#FCF0EA"
  alarm-field: "#A9441F"
  fog-ground: "#E4E9E6"
  fog-ground-2: "#DCE2DE"
  paper: "#FBFCFA"
  paper-2: "#F3F6F4"
  ink: "#18202B"
  ink-2: "#4B5361"
  ink-3: "#6E7582"
  ink-4: "#9AA0AA"
  rule: "rgba(24,32,43,.09)"
  rule-2: "rgba(24,32,43,.16)"
  scrim: "rgba(14,19,27,.42)"
  white: "#FFFFFF"
  night-ground: "#12171D"
  night-ground-2: "#1A2027"
  night-paper: "#1D242C"
  night-paper-2: "#242C35"
  night-ink: "#ECEFEC"
  night-ink-2: "#B4BCC2"
  night-ink-3: "#8C949C"
  night-ink-4: "#5E666F"
  night-on-stock: "#0E1418"
  night-maya-teal: "#58B3AC"
  night-maya-ink: "#BFE6E1"
  night-maya-soft: "#1F3C3B"
  night-maya-tint: "#18302F"
  night-theo-persimmon: "#EE8A64"
  night-theo-ink: "#FFD3C2"
  night-theo-soft: "#4A2A1F"
  night-theo-tint: "#3A231B"
typography:
  display-l:
    fontFamily: "Bricolage Grotesque, sans-serif"
    fontSize: "76px"
    fontWeight: 500
    lineHeight: 0.86
    letterSpacing: "-0.04em"
    fontVariation: "'opsz' 96"
  display-m:
    fontFamily: "Bricolage Grotesque, sans-serif"
    fontSize: "56px"
    fontWeight: 500
    lineHeight: 0.92
    letterSpacing: "-0.03em"
    fontVariation: "'opsz' 96"
  headline-l:
    fontFamily: "Bricolage Grotesque, sans-serif"
    fontSize: "32px"
    fontWeight: 600
    lineHeight: 1.05
    letterSpacing: "-0.02em"
    fontVariation: "'opsz' 72"
  headline-m:
    fontFamily: "Bricolage Grotesque, sans-serif"
    fontSize: "26px"
    fontWeight: 600
    lineHeight: 1.12
    letterSpacing: "-0.015em"
    fontVariation: "'opsz' 48"
  title-l:
    fontFamily: "Bricolage Grotesque, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.01em"
    fontVariation: "'opsz' 32"
  title-m:
    fontFamily: "Hanken Grotesk, sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.3
  body-l:
    fontFamily: "Hanken Grotesk, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.5
  body-m:
    fontFamily: "Hanken Grotesk, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.45
  body-s:
    fontFamily: "Hanken Grotesk, sans-serif"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.4
  label-l:
    fontFamily: "Hanken Grotesk, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.25
  label-m:
    fontFamily: "Hanken Grotesk, sans-serif"
    fontSize: "12.5px"
    fontWeight: 600
    lineHeight: 1.25
rounded:
  tag: "12px"
  field: "14px"
  photo: "16px"
  fab: "18px"
  card: "22px"
  pact: "28px"
  pill: "28px"
  full: "50%"
spacing:
  hair: "2px"
  xs: "6px"
  sm: "10px"
  card-inset: "12px"
  md: "14px"
  row-x: "18px"
  gutter: "20px"
  pact-x: "22px"
  pact-top: "24px"
  touch: "48px"
components:
  button-filled:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.white}"
    typography: "{typography.title-m}"
    rounded: "{rounded.pill}"
    height: "56px"
    padding: "0 24px"
  button-filled-maya:
    backgroundColor: "{colors.maya-teal}"
    textColor: "{colors.white}"
    rounded: "{rounded.pill}"
    height: "56px"
    padding: "0 24px"
  button-filled-theo:
    backgroundColor: "{colors.theo-persimmon}"
    textColor: "{colors.white}"
    rounded: "{rounded.pill}"
    height: "56px"
    padding: "0 24px"
  button-tonal-maya:
    backgroundColor: "{colors.maya-soft}"
    textColor: "{colors.maya-ink}"
    rounded: "{rounded.pill}"
    height: "56px"
    padding: "0 24px"
  button-outline:
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    height: "56px"
    padding: "0 24px"
  button-text:
    textColor: "{colors.maya-teal}"
    height: "48px"
    padding: "0 14px"
  button-small:
    rounded: "20px"
    height: "40px"
    padding: "0 18px"
  pact-card:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.pact}"
    padding: "24px 22px 20px"
  pact-strip:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.card}"
    padding: "16px 18px 14px"
  card:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.card}"
  list-row:
    height: "56px"
    padding: "10px 18px"
  seal-signed-maya:
    backgroundColor: "{colors.maya-teal}"
    textColor: "{colors.white}"
    rounded: "{rounded.full}"
    size: "40px"
  seal-signed-theo:
    backgroundColor: "{colors.theo-persimmon}"
    textColor: "{colors.white}"
    rounded: "{rounded.full}"
    size: "40px"
  seal-pending:
    backgroundColor: "{colors.fog-ground}"
    textColor: "{colors.maya-teal}"
    rounded: "{rounded.full}"
    size: "40px"
  seal-struck:
    backgroundColor: "{colors.paper-2}"
    textColor: "{colors.ink-3}"
    rounded: "{rounded.full}"
    size: "40px"
  partner-disc-maya:
    backgroundColor: "{colors.maya-teal}"
    textColor: "{colors.white}"
    rounded: "{rounded.full}"
    size: "36px"
  partner-disc-theo:
    backgroundColor: "{colors.theo-persimmon}"
    textColor: "{colors.white}"
    rounded: "{rounded.full}"
    size: "36px"
  chip:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink-2}"
    rounded: "{rounded.fab}"
    height: "36px"
    padding: "0 14px"
  chip-selected:
    backgroundColor: "{colors.maya-soft}"
    textColor: "{colors.maya-ink}"
    rounded: "{rounded.fab}"
    height: "36px"
  segmented-button:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink-2}"
    rounded: "22px"
    height: "44px"
  segmented-button-selected:
    backgroundColor: "{colors.maya-soft}"
    textColor: "{colors.maya-ink}"
  text-field:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "18px 16px 14px"
  tag-maya:
    backgroundColor: "{colors.maya-tint}"
    textColor: "{colors.maya-ink}"
    rounded: "{rounded.tag}"
    height: "24px"
    padding: "0 9px"
  tag-theo:
    backgroundColor: "{colors.theo-tint}"
    textColor: "{colors.theo-ink}"
    rounded: "{rounded.tag}"
    height: "24px"
    padding: "0 9px"
  navigation-bar:
    backgroundColor: "{colors.fog-ground-2}"
    textColor: "{colors.ink-2}"
    height: "84px"
  navigation-indicator-active:
    backgroundColor: "{colors.maya-soft}"
    textColor: "{colors.maya-teal}"
    rounded: "16px"
    width: "64px"
    height: "32px"
  fab-extended:
    backgroundColor: "{colors.maya-teal}"
    textColor: "{colors.white}"
    rounded: "{rounded.fab}"
    height: "56px"
    padding: "0 20px 0 16px"
  bottom-sheet:
    backgroundColor: "{colors.paper}"
    rounded: "28px 28px 0 0"
    padding: "10px 20px 40px"
  alarm-screen:
    backgroundColor: "{colors.alarm-field}"
    textColor: "{colors.white}"
---

# Design System: Heartpact (Duplex Pact draft, superseded)

## Overview

**Creative North Star: "The Duplex Pact"**

Heartpact is built from duplexed cotton stock. A cool fog ground carries near-white paper cards set in deep ink, and each partner owns exactly one stock colour: Maya teal and Theo persimmon. Every promise between them is a pact card pressed from two layers of that stock, the setter's colour on top, and is closed by two debossed seals, one per partner. The world refuses the couples-app category default of pink hearts, progress rings and rows of same-size icon cards.

The register is soft premium translated through Material 3: Material owns structure, navigation, controls and system Back; the brand arrives through the colour roles, the type scale, pill and soft-rectangle shapes, long low shadows and one signature motion. Density is deliberately high in the Japanese sense: on a 390×844 phone the whole day (the live pact, the next pact, food, the daily question, location, and the navigation bar) fits the first viewport without scrolling. One fixed size ramp ranks importance, and the live pact is always the largest element on the screen.

State is never carried by hue alone. A seal is solid when signed, dashed when pending, and struck through when ended; the history strip uses the same grammar day by day on one baseline at one scale. The world ships light and night schemes; night prints the same two stocks on charcoal with each partner hue lifted a step.

**Key Characteristics:**
- Two partner stocks, fixed for life, used wherever that partner's data appears.
- Pact cards carry a two-layer top edge (setter on top); nothing else does.
- Seals: solid, dashed, struck. Shape and stroke carry state, colour carries ownership.
- Bricolage Grotesque for display and numerals, Hanken Grotesk for UI text, Material Symbols Rounded at weight 300.
- Long, soft, low-opacity shadows; flat paper on fog, no bevels.
- Material 3 components and navigation, themed rather than replaced.

## Colors

Cool fog and paper with one ink, and two saturated partner stocks that never trade places.

### Primary
- **Maya Teal** (`maya-teal`): Maya's stock. Her discs, her seals, her half of every pact edge, and the action colour on any screen where Maya is the one acting (filled button, FAB, switch on, focused field, selected chip). It is also the app's default accent where no partner owns the moment: the active navigation indicator, section links and selection. White text on it reaches 6.62:1.
- **Deep Teal Ink** (`maya-ink`) and **Teal Wash** (`maya-soft`, `maya-tint`): text on Maya's tonal surfaces (8.07:1 on `maya-soft`), selected chip and segment fills, the seal ring, and Maya's tags.

### Secondary
- **Theo Persimmon** (`theo-persimmon`): Theo's stock, used exactly as Maya Teal is for Maya. The value is #C2532E, not the planned #CF5A33: white text on #CF5A33 reaches only 4.07:1, on #C2532E it reaches 4.59:1.
- **Persimmon Ink** (`theo-ink`) and **Persimmon Wash** (`theo-soft`, `theo-tint`): text on Theo's tonal surfaces (6.16:1 on `theo-soft`), the seal ring, and Theo's tags.

### Tertiary
- **Alarm Field** (`alarm-field`): the full-bleed ground of the ringing alarm and the camera's stop control, a deeper persimmon (#A9441F) so the alarm's white body text reaches 5.94:1. It belongs to the alarm moment only; it is set in the alarm and camera screens, not as a custom property in `styles.css`.

### Neutral
- **Fog Ground** (`fog-ground`): the app background behind every screen, and the well inside a pending seal.
- **Fog Ground Step** (`fog-ground-2`): one tonal step down, used for the navigation bar and its gesture gap.
- **Paper** (`paper`): cards, pact cards, sheets, chips, segmented buttons and fields.
- **Recessed Paper** (`paper-2`): the fill of a struck seal.
- **Ink** (`ink`): all primary text, the default filled button, the gesture handle. 15.94:1 on paper, 13.35:1 on fog.
- **Ink 2 / 3 / 4** (`ink-2`, `ink-3`, `ink-4`): secondary text and icons (7.53:1 on paper), tertiary captions under seal labels (4.5:1 on paper), and non-text marks only (rest-day dots, the switch track outline, the sheet grip).
- **Rules** (`rule`, `rule-2`): hairline dividers between rows and above seal rows; stronger rule for outlines on chips, segments, fields and outlined buttons.
- **Scrim** (`scrim`): behind bottom sheets.
- **Night scheme** (`night-*`): the same roles printed on charcoal. `night-maya-teal` and `night-theo-persimmon` lift each stock a step for contrast; text on filled stock switches to `night-on-stock`, which reaches 7.47:1 on both.

### Named Rules
**The Two Stocks Rule.** Maya is always teal and Theo is always persimmon, on every screen and in both schemes. A partner's colour marks that partner's data, seal or action; it is never decoration and never swapped for variety.

**The Actor's Colour Rule.** The filled action on a screen takes the stock of the person who acts there. The partner who sets, watches or waits sees their own colour only on their own marks.

**The Alarm Field Rule.** The deep alarm field is reserved for the ringing alarm and its camera control. Nothing else fills a screen with stock colour.

## Typography

**Display Font:** Bricolage Grotesque (with sans-serif), self-hosted variable woff2, weights 200 to 800, optical size set per role.
**Body Font:** Hanken Grotesk (with sans-serif), self-hosted variable woff2, weights 100 to 900.
**Icons:** Material Symbols Rounded, FILL 0, weight 300, optical size 24; selected navigation items fill at weight 400.

**Character:** A characterful grotesque with optical sizing for titles and big numerals, paired with a calm, even grotesque for everything the hand touches. Numerals are tabular everywhere so counts and timers never jitter.

### Hierarchy
The ramp follows Material's role names. Values are authored as CSS px in 390-wide mockups and map 1:1 to sp on a phone.
- **Display L / M**: the giant numerals that rank the moment: minutes left on the live pact, the alarm clock, the rep count, the week of pregnancy, food totals. Never body copy.
- **Headline L**: large top-app-bar screen titles ("Today", "Pacts", "Food").
- **Headline M**: the title of the live pact card and of focused single-purpose screens.
- **Title L**: compact top-app-bar titles and secondary pact strips.
- **Title M**: emphasised UI lines.
- **Body L / M / S**: supporting copy, meta lines ("Set by Theo · rings at 8:00"), captions under seals and rows.
- **Label L / M**: buttons, chips, segment labels, field labels and the navigation labels.

### Named Rules
**The Star-Atlas Rule.** One fixed ramp ranks importance. The live pact's numeral is the largest element on its screen; nothing else may compete with it in size.

**The Tabular Rule.** Every count, time and estimate uses tabular figures, and estimates read as ranges ("~1,240", "likely 520–700").

## Layout

Edge-to-edge Android phone frames at 390×844: a 36px status bar on top, a gesture handle at the bottom, and the Material navigation bar (84px plus a 24px gesture gap) with five destinations: Today, Pacts, Food, Us, Album. Screens with a single primary action pair it with an extended FAB at the bottom right.

The screen gutter is `gutter` (20px) for text and headers; cards and pact cards sit closer to the edge at `card-inset` (12px) so paper reads as sheets laid on fog. Stacked cards are separated by `sm` (10px). Inside cards, rows run `row-x` (18px) side padding at a 56px minimum height with a hairline between them; pact cards pad `pact-top` and `pact-x`. Small vertical steps use `xs` (6px), `sm` (10px) and `md` (14px). Every touch target is at least `touch` (48px).

History views put one mark per day on one baseline at one scale, so any two weeks compare at a glance.

### Named Rules
**The Whole-Day Rule.** On a 390×844 phone the Today screen fits the whole day in the first viewport. Add density by tightening rows, not by pushing the day below the fold.

## Elevation & Depth

Depth is tonal first and shadowed second. Fog, its darker step and paper make three planes; long, soft, low-opacity shadows sit cards on the fog without hard edges. Shadows are built from a tight 1px contact shadow plus a long negative-spread blur, so the lift reads as a pressed sheet, not a floating tile. Interactive depth is a single inset well, used for pending seals. In the night scheme each lift gains a faint inner top highlight and a darker drop.

### Shadow Vocabulary
- **Lift 1** (`0 1px 1px rgba(24,32,43,.04), 0 10px 24px -16px rgba(24,32,43,.24)`): list cards and slim pact strips.
- **Lift 2** (`0 1px 1px rgba(24,32,43,.04), 0 18px 40px -18px rgba(24,32,43,.30)`): the live pact card.
- **Lift 3** (`0 2px 4px rgba(24,32,43,.06), 0 28px 60px -20px rgba(24,32,43,.38)`): the FAB and bottom sheets.
- **Well** (`inset 0 2px 4px rgba(24,32,43,.09)`): the debossed hollow of a pending seal.
- **Stock glow** (`0 1px 1px rgba(24,32,43,.08), 0 10px 20px -12px <actor stock>`): under filled buttons, tinted by the actor's colour.

### Named Rules
**The Pressed-Not-Floating Rule.** Shadows are long, soft and low. No hard offset shadows and no painted bevels; a seal's deboss lives in its press motion, not in a gradient.

## Shapes

Soft rectangles and full circles. Pact cards and pill buttons share the largest radius (`pact`, 28px); list cards and slim strips use `card` (22px); chips and the FAB use 18px; photos and the navigation indicator 16px; fields and snackbars 14px; tags 12px. Discs and seals are full circles.

The signature shape is the duplex edge: a 6px band across the top of a pact card split into two 3px layers, the setter's stock over the partner's (4px on slim strips). When both seals are pressed, the same band appears along the bottom in reverse order and the pact reads as closed. Overlapping partner discs sit in a ring of the ground colour so the pair reads as two separate stocks.

### Named Rules
**The Duplex Edge Rule.** Only pact cards carry the two-layer edge. No other card, sheet or banner borrows it.

## Components

### Buttons
Material 3 roles in pill form, coloured by whoever acts.
- **Shape:** full pill (`pill`, 56px tall; small variant 40px tall).
- **Filled:** the actor's stock with white text and a stock-tinted glow; ink when no partner owns the action. A trailing-knob variant puts the forward arrow in a 40px translucent circle at the right edge ("Start on camera").
- **Tonal:** the actor's soft wash with the actor's ink text.
- **Outlined:** stronger rule stroke (1.25px) with ink text.
- **Text:** 48px tall, actor's colour, used for the escape action under a primary ("Ask Theo to end it").
- **Night:** filled buttons switch their label to `night-on-stock`.

### Partner Discs and Seals
Character line: ownership in colour, state in stroke.
- **Disc:** 36px circle in the partner's stock with an initial (24px small, 56px large). Paired discs overlap by 10px inside a ground-coloured ring.
- **Seal, signed:** 40px circle filled with the signer's stock, white icon, inside a 3px ring of the signer's soft wash.
- **Seal, pending:** the fog ground with the inset well and a 1.5px dashed outline in the owner's stock.
- **Seal, struck:** recessed paper, 1.5px solid rule outline, and a diagonal stroke through it. Defined in the stylesheet; ended days appear in the rendered history strip with the same grammar.
- **Sizes:** 28px in strips, 40px default, 64px on the sealed screen.
- **Press motion:** over 220ms with ease-out (`cubic-bezier(.2,.8,.2,1)`), the disc sinks to 0.88 scale with an inset shadow, fills in the signer's stock, and the pact's lower duplex edge draws across the card. With Remove animations on, it cuts to the final frame.

### Pact Cards
- **Corner Style:** 28px (live), 22px (slim strip).
- **Background:** paper, with the duplex top edge.
- **Shadow Strategy:** Lift 2 for the live pact, Lift 1 for strips.
- **Content:** title (Headline M), a meta line naming who set it and when it rings, a Display numeral, a seal row above a hairline, then the actor's filled button and the text escape.

### Cards and Lists
- **Corner Style:** 22px. **Background:** paper. **Shadow:** Lift 1. **Border:** none.
- **Rows:** 56px minimum, 18px sides, an outlined icon in `ink-2`, a label, and a right-aligned value whose key figure is set in ink at weight 600. Rows are divided by a hairline rule.

### Chips and Segmented Buttons
- **Chip:** 36px, 18px radius, paper with a 1.25px rule outline and `ink-2` label. Selected: the actor's soft wash, no outline, actor's ink text, leading check.
- **Segmented:** 44px, 22px radius, paper with 1.25px rule outline and dividers; the selected segment takes the actor's soft wash.

### Inputs / Fields
- **Style:** Material outlined field: 1.25px rule stroke, 14px radius, paper fill, floating 12px label notched into the stroke.
- **Focus:** 2px stroke and label in the actor's stock, with a 2px caret in the same colour.

### Switch
- Material 3 switch, 52×32px. Off: fog step track, `ink-4` outline, 16px `ink-3` thumb. On: actor's stock track, 24px white thumb.

### Navigation
- **Bar:** Material navigation bar on the fog step, five destinations, 12px labels at weight 500 in `ink-2`.
- **Active:** a 64×32px pill indicator in teal wash, filled icon in teal, label in ink at weight 700.
- **Top app bars:** large title bar (Headline L with a meta line and the couple's discs at the right) on home destinations; 64px compact bar (Title L with 48px icon buttons) on detail screens.

### FAB and Bottom Sheet
- **Extended FAB:** 56px tall, 18px radius, actor's stock, white label, Lift 3. One per screen.
- **Bottom sheet:** paper, 28px top corners, a 32×4px grip, Lift 3, over the scrim. Used for decisions that need both partners, such as ending a pact together.

### Tags
- 24px pills (12px radius) at Label size. Neutral tags sit on fog; partner tags use the partner's tint with the partner's ink.

### Alarm Screen
- The only full-bleed stock field: `alarm-field` ground, white Display clock, the pact card on paper with a deeper warm shadow, a countdown to the 30-minute limit, a visible escape ("Ask Theo to end it" on a translucent dark pill) and a local "Silence on this phone" text button that is always present.

## Do's and Don'ts

### Do:
- **Do** colour every partner-owned mark in that partner's stock: Maya `maya-teal`, Theo `theo-persimmon`, in both schemes.
- **Do** give the filled action the actor's stock, and keep the partner's colour on the partner's own marks.
- **Do** show state with shape and stroke: solid seal for signed, dashed for pending, struck for ended.
- **Do** keep the live pact the largest element on its screen, with its numeral set in a Display role.
- **Do** keep white text on stock at or above 4.5:1: use `theo-persimmon` (4.59:1), never #CF5A33 (4.07:1), and `alarm-field` (5.94:1) under the alarm's body text.
- **Do** use Material 3 components, the five-destination navigation bar, 48px minimum targets and system Back.
- **Do** design and check the night scheme as its own palette, not an inversion.
- **Do** show estimates as ranges with a tilde or "likely", in tabular figures.

### Don't:
- **Don't** use pink hearts, progress rings or rows of same-size icon cards.
- **Don't** put the duplex edge on anything but a pact card.
- **Don't** encode state by hue alone.
- **Don't** fill a screen with stock colour outside the ringing alarm.
- **Don't** use hard offset shadows or painted bevels.
- **Don't** hand-pick type sizes per screen; map text to the role ramp.
- **Don't** set small text in `theo-persimmon` on paper; it sits at 4.46:1. Use `theo-ink` for small persimmon text.
