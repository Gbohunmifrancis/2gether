# AI features: backend contract

Four features ask Claude for a few words, through the Anthropic API key the server already has for food photo
estimates. Each has its own admin switch, a daily cap per person, and checks on every answer before anyone sees it.

| Feature | Where in the app | Endpoint | Cap |
|---|---|---|---|
| **Dare ideas** | "Suggest a dare" on New dare | `POST api/ai/dare-ideas` | 10 requests per person per day (`Ai:DareIdeasPerDay`) |
| **After-workout coach** | Heart complete, and the partner's done card on Today | `GET api/ai/coach/{occurrenceId}` | once per finished dare; at most 20 notes per person per day (`Ai:CoachNotesPerDay`) |
| **Weekly couple recap** | "Your week together" at the top of Us, a full view, and a push | `GET api/ai/recap` | once per couple per week (two calls: one per partner) |
| **Plan something** | Us → Plan something | `POST api/ai/plan` | 5 requests per person per day (`Ai:PlansPerDay`) |

Days are UTC days, as for food scans. A request counts the moment it is recorded, before Claude is asked, however it
ends (an error, an answer that fails the checks, the phone giving up), so parallel requests can't get past a cap.

## Is it on? `GET api/ai/status`

```json
{ "dareIdeas": true, "coach": true, "weeklyRecap": true, "planner": true, "dareIdeasLeft": 10, "plansLeft": 5 }
```

A feature is on only when the server has a key **and** its admin switch is on. Without a key everything is false and
nothing is ever sent. The app hides whatever is off (a failed call counts as off). A feature that is off answers
`404 ai.off`; the recap answers `204` (nothing this week).

## The four requests

**Dare ideas.** `POST api/ai/dare-ideas { "assignedUserId": "<me or my partner>" }` →
`{ "ideas": [{ "exerciseType": "squat", "target": 20, "measure": "reps", "scheduledTime": "18:30", "reason": "…" }], "left": 9 }`.
Up to three ideas tailored to the person the dare is for: their dares of the last three weeks (exercise, target, due
time, done, missed or called off, how many the camera counted), their active dares, their streak, and the exercise
catalog with each exercise's limits. The reason is written to the asker, in the asker's language. Every idea is checked:
an exercise outside the catalog, a target outside its limits, a time that isn't `HH:mm`, an empty reason or a repeat is
dropped. An answer with nothing left is `502 ai.unreadable` (and still counts).

**Coach.** `GET api/ai/coach/{occurrenceId}` → `{ "occurrenceId", "state": "ready" | "pending" | "unavailable", "text", "userId" }`.
For a completed dare of the caller's couple; the person who did it and their partner both read the same note. The first
reader's request writes it (2 or 3 sentences: pace against the target, what the camera's checks noticed, the streak, one
tip), in the language of the person who did the dare, and it is stored in `ai_coach_notes` (one row per occurrence, a
unique index), so it is written once. Another request meanwhile gets `pending` and asks again a couple of seconds later.
A failure is tried again after 30 seconds, three attempts at most, then `unavailable`. Dares finished more than
`Ai:CoachMaxAgeDays` (7) ago never get a note. A missed or called-off dare, or another couple's, is `404 ai.coach_none`.

**Weekly recap.** `WeeklyRecapWorker` runs every 15 minutes. For each active couple whose week has ended (Sunday 18:00
in the couple's time zone: the first partner's saved zone, else the second's, else UTC) it claims the week's row in
`weekly_recaps` (unique on couple and week, so whichever API instance gets there first writes it, once), counts the
seven days before (hearts made, each partner's dares done, missed and called off, streaks, games played and won,
photos added, questions answered) and, unless nothing at all happened (`Skipped`, nothing sent), asks Claude for a
recap for each partner in their own language: a title, 3 or 4 short lines and a light suggestion for next week. Both
recaps and both `weeklyRecap` notifications are saved together, so the push goes out only when both are written. A
failure is retried 10 minutes later, three attempts at most, and only within a day of the week's end: a recap never
arrives on a Wednesday. Outside Sunday 04:00 to Tuesday 06:00 UTC (when Sunday 18:00 can be happening somewhere, plus a
day) a run doesn't touch the database. `GET api/ai/recap` returns the couple's latest recap from the last 8 days, the
reader's own words and the counts (`myDaresDone`, `partnerDaresDone`…), or `204`.

**Plan something.** `POST api/ai/plan { "mood": "cosy|active|adventurous|romantic", "budget": "free|low|treat", "setting": "in|out", "city": "Lagos" }`
→ `{ "dates": [{ "title", "why" }], "games": [{ "gameType": "skillDuel", "why" }], "left": 4 }`. Three date ideas and
two of the app's games to play tonight (preferring games the couple has played less in the last 30 days). The city is
optional, typed by the person, at most 60 characters of letters, spaces and the punctuation place names use
(`422 ai.city_invalid` otherwise), and sent only when the setting is `out`. Games outside the catalog are dropped.

Errors: `400 ai.no_couple`, `422` for a bad request, `429 ai.limit` at the cap (the message says when there are more),
`502` when Claude couldn't answer or the answer failed the checks (`ai.busy`, `ai.failed`, `ai.timeout`, `ai.unreadable`).

## What is sent to Claude, and what never is

Each prompt is fixed instructions plus one record of facts as JSON (`Twogether.Application/Features/Ai/AiFacts.cs`), so
adding a field there is the only way to send more:

- counts (dares done, missed and called off, hearts, games played and won, photos added, questions answered),
- exercise names, targets and the camera's check counts (form faults, too-fast reps, check-ins, reps while the phone
  moved, seconds out of frame), times (when dares are due, how long the camera session took),
- streaks, first names (the first word of each display name), the reply language, the account's country,
- for Plan something: the mood, budget, in or out, and the city the person typed.

**Never**: photos, captions, messages, daily answers, game answers or drawings, meals, cycle or pregnancy data,
location, email addresses, full names, a dare's title (people can type anything there) or a note asking to be let off.
`AiFeatureTests` plants each of these in the database and checks no prompt contains them. Claude's replies are
written in the reader's language (the asker's for ideas and plans, the person who did the dare for the coach, each
partner's for the recap).

## The client

`AnthropicClaudeClient` (`IClaudeClient`) is the one way these features reach Claude. It is built like the food
estimator's (`AnthropicClients`: the same key, `Food:AnthropicApiKey` or `ANTHROPIC_API_KEY`, and the same model,
`Food:Model`), with the SDK's retries with backoff on 429, 5xx and dropped connections (`Ai:MaxRetries`, 2), one time
limit for the whole call, retries included (`Ai:TimeoutSeconds`, 25, under the app's 30-second read timeout), and a
JSON answer held to a schema at low effort. A refusal or an answer cut off at the token limit is a failure, never
half-parsed. Only token counts are logged.

Every call is a row in `ai_requests` (person, couple, feature, model, success or error code, input and output tokens,
latency; nothing that was sent or written). The caps count these rows; the admin Health page prices them with the food
scan prices (`Admin__FoodScanPricePerMillionInput`/`Output`, same model) in the "Claude" tile.

## Switches

Admin → Features: **Dare ideas** (`aiDareIdeas`), **After-workout coach** (`aiCoach`), **Weekly recap**
(`aiWeeklyRecap`), **Plan something** (`aiPlanner`). Rows `feature.aiDareIdeas` and so on in `app_settings`; unlike the
other flags these are **on** until an admin turns them off (no row, or `"true"`, is on). See `docs/feature-flags.md`.
Turning one off hides it in every app on its next status check, refuses its requests, and for the recap stops new ones
being written and hides this week's card; the coach's notes already written stop showing.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `Ai__DareIdeasPerDay` | 10 | dare idea requests per person per UTC day (0 turns them off for everyone) |
| `Ai__PlansPerDay` | 5 | Plan something requests per person per UTC day |
| `Ai__CoachNotesPerDay` | 20 | coach notes written per person per UTC day |
| `Ai__TimeoutSeconds` | 25 | one call, retries included |
| `Ai__MaxRetries` | 2 | retries on 429, 5xx and network errors |
| `Ai__CoachMaxAgeDays` | 7 | a dare finished longer ago gets no note |

## Data and deletion

`ai_coach_notes` (cascades with its occurrence) and `weekly_recaps` (cascades with the couple) hold what Claude wrote;
`ai_requests` holds only what each call cost. Deleting an account removes the person's coach notes, every recap of a
couple they were in, and their request rows. The account export includes their coach notes and the recaps written for
them. Admins see none of it: no admin endpoint returns a note or a recap.
