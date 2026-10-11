# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Open decision for the Android app. The backend is the existing .NET 10 API with PostgreSQL and SignalR in `src/`. The rep-counting pose loop has to be a native Kotlin module whatever hosts the UI (`docs/research/mobile-pivot-viability.md` §4.2). The web app in `frontend/` predates the mobile pivot.

## Users

Two partners in a relationship, using the app together on their own Android phones. The first audience is English-speaking couples anywhere, with no regional flavour in copy or examples. Every feature has two sides: the person doing something and the partner who sees it, sets it, or answers it.

## Product Purpose

Heartpact helps a couple keep promises to each other about their health and their time together. Success is two people who keep showing up for each other: workouts done, meals logged, questions answered, streaks kept.

## Positioning

One partner sets an exercise goal for the other. At the set time the phone rings, the camera counts and verifies the reps, and the alarm keeps going for up to 30 minutes until the reps are done or both partners agree to end it. No other couples app or fitness app has partner-set, camera-verified accountability.

## Operating Context

- **The goal alarm:** a full-screen alarm at the scheduled time. The person opens the camera, does the reps on screen, sees a live count, and the partner can watch progress live. A local dismiss is always available (Play policy). Ending early otherwise needs both partners to agree.
- **Shared goals:** fitness and diet goals; a food log with photo calorie estimates shown as ranges, not medical facts.
- **Health tracking:** period tracking with per-person share levels, and a pregnancy mode with appointments and kick counts.
- **Couple features:** a map both partners see only while both share (one-tap pause, visible indicator), daily questions where a partner's answer unlocks only after you answer, couple games (quiz, memory match, "I call on"), streaks, and a shared album with an on-this-day view.
- **Notifications:** push with per-category settings and quiet hours; the exercise alarm ignores both.
- **Lifecycle:** invite by code or link, breakup, account export and deletion.

## Capabilities and Constraints

- **Platform:** Android first, iOS later. Exact alarms need a user grant on Android 14+, and the alarm must stay locally dismissible.
- **Privacy:** location is mutual-only and never framed as "checking on" a partner (Play stalkerware policy). Health data follows explicit per-feature share levels. Anything the caller may not see is hidden entirely, not greyed out.
- **Data rules:** the server owns timers, turns, scores, consent and streaks; the app renders server state.
- **Plans:** Free and Plus. One partner's Plus covers both. Plus gives 20 food scans a day instead of 3, 2 GB of album instead of 500 MB, and 90 days of question history instead of 7.
- **Food estimates:** about ±30–40% error. Always shown as a range and labelled as an estimate.

## Brand Commitments

The name is **Heartpact** (fallbacks Twinpact and Vowfit; research §7). An earlier set of mockups in `design/heartpact/` was rejected by the owner. Treat it as an anti-reference, not a starting point.

## Evidence on Hand

- Market, policy and monetization research: `docs/research/mobile-pivot-viability.md`.
- Backend contracts for every feature: `docs/*.md`.
- No real users, testimonials, reviews, press, install counts or prices to show. Do not invent any. Plan prices are not set; the Play product ids are `heartpact_plus_monthly` and `heartpact_plus_yearly`.

## Product Principles

1. **Two sides of every moment.** Each screen knows whether you are the one acting or the partner watching, setting or waiting, and shows that clearly.
2. **Accountability without cruelty.** The alarm is a promise you both agreed to, not a punishment. A way out (local dismiss, ending together) is always visible.
3. **Privacy you can see.** What your partner can see is always visible, and changing it is one tap.
4. **Honest numbers.** Estimates look like estimates, verification states say what the camera actually saw, and nothing claims more certainty than it has.
5. **Small daily rituals over big dashboards.** Each day should come down to a few clear things to do together.
