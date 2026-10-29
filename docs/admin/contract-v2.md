# Heartpact admin console v2: API contract

All under `/api/admin/` (existing auth: admin bearer token, admin audience). Wire JSON is camelCase, enums camelCase
strings, times ISO-8601 UTC strings ending in Z, days `yyyy-MM-dd` (UTC). Every analytics query counts only
`AdminScope.Measured(...)` users (src/Twogether.Application/Features/Admin/AdminScope.cs: not internal, not admins),
and their couples/activity. Rates that can't be computed are `null` (the UI shows "—"), never 0.

PRIVACY (non-negotiable, docs/admin.md): counts and account metadata only. Never return what couples wrote, photographed,
logged, answered, their cycle/pregnancy details, or locations. Exercise names, game types, product ids, countries and
skill scores (numbers) are fine. Display names and emails are fine (account metadata).

"Active" (for retention, rhythm, journey): a person did something that day: completed or started a dare, logged a meal,
synced a photo, answered a question, finished a game or made a game move, or signed in (LastActiveAtUtc). Build it from
the timestamps the tables already have; no content.

## Live

### GET `analytics/pulse` → `Pulse`
```ts
interface Pulse {
  nowUtc: string;
  onlineNow: number;          // people with LastActiveAtUtc in the last 5 minutes
  activeToday: number;
  ringingNow: number;         // dares alarming right now
  onCameraNow: number;        // dares in progress right now
  openGames: number;
  heartsToday: number;        // dares completed today (UTC)
  signupsToday: number;
  couplesLinkedToday: number;
  plusStartedToday: number;
  recent: LiveEvent[];        // newest first, up to 30, last 24 h
}
type LiveKind = "signedUp" | "coupleLinked" | "coupleEnded" | "dareCompleted" | "dareMissed" | "gameFinished"
  | "plusStarted" | "plusEnded" | "accountDeleted" | "alert";
interface LiveEvent {
  id: string;                 // stable, for de-duplication (e.g. "dareCompleted:<occurrenceId>")
  kind: LiveKind;
  atUtc: string;
  userId: string | null;
  displayName: string | null; // the actor, when there is one
  partnerName: string | null; // for couple events
  detail: string | null;      // exercise ("squats"), game type ("skillDuel"), product id, country code, or alert text
  severity: "info" | "good" | "warn" | "bad";
}
```
### GET `analytics/live/events` → `text/event-stream`
Server-Sent Events, authenticated with the normal bearer header (the client reads it with `fetch` streaming, not
EventSource). `event: live` with `data: <LiveEvent JSON>` as things happen (polling the DB every ~3 s server-side is fine:
emit events newer than the last seen, de-duplicated by id); `event: ping` every 20 s. Ends when the token expires.

## Insights

### GET `analytics/funnel?days=30` → `Funnel`
People who signed up in the window, and how far they got.
```ts
interface Funnel { days: number; steps: FunnelStep[] }
interface FunnelStep {
  key: "signedUp" | "invitedOrJoined" | "linked" | "firstDareSet" | "firstDareDone" | "firstHeart" | "activeWeek2" | "plus";
  count: number;
  medianHoursFromPrevious: number | null;
}
```
`firstHeart` = both partners have completed a dare set by the other. `activeWeek2` = active in days 8-14 after sign-up.

### GET `analytics/retention?weeks=12` → `Retention`
Couples by the week they linked (Monday UTC), and the share active in each following week (either partner active).
```ts
interface Retention { weeks: number; cohorts: { weekStart: string; size: number; retained: (number | null)[] }[] }
// retained[0] is week 0 (always counted), then weeks 1..n; null for weeks that haven't happened yet. Values 0..1.
```

### GET `analytics/rhythm?days=30` → `Rhythm`
```ts
interface Rhythm { days: number; actions: number[][]; daresDone: number[][]; rings: number[][] }
// Each is 7x24: [weekday 0=Monday..6][hour 0..23] in UTC. actions = all "active" actions; rings = alarms started.
```

### GET `analytics/dares?days=30` → `DareInsights`
```ts
interface DareInsights {
  days: number;
  exercises: {
    exercise: string; set: number; completed: number; missed: number; calledOff: number;
    completionRate: number | null; medianSecondsToStart: number | null; medianSecondsOnCamera: number | null;
    averageConfidence: number | null; checksFailed: number;
  }[];
  earlyStarts: number;            // started before the alarm
  medianRepsTarget: number | null;
  streaks: { length: string; couples: number }[]; // buckets "0", "1-2", "3-6", "7-13", "14-29", "30+" (current streak)
}
```

### GET `analytics/games?days=30` → `GameInsights`
```ts
interface GameInsights {
  days: number;
  types: { type: string; started: number; finished: number; abandoned: number; medianMinutes: number | null }[];
  skills: { kind: string; rounds: number; averageScore: number | null; buckets: number[] }[]; // 10 buckets: 0-9 … 90-100
  duels: { finished: number; ties: number; medianMargin: number | null }; // ties: level on hearts and points; margin in hearts
}
```
Skill rounds come from finished/ongoing Heart to heart duels' state (scores only).

### GET `analytics/growth?days=90` → `Growth`
```ts
interface Growth {
  days: number;
  endingIn7Days: number; endingIn30Days: number;
  renewed: number; lapsed: number; renewalRate: number | null;
  path: { key: "signedUp" | "linked" | "hitScanLimit" | "nearAlbumLimit" | "plus"; count: number }[];
  medianDaysToPlus: number | null;
  plusByCountry: { name: string; count: number }[];
}
```

### GET `analytics/world` → `World`
```ts
interface World { countries: { code: string; people: number; couples: number; plus: number; activeWeek: number }[]; unknown: number }
// code: ISO 3166-1 alpha-2 uppercase (users.CountryCode); a couple counts in a country when either partner is there.
```

### GET `analytics/couples/top?days=30&limit=12` → `TopCouple[]`
```ts
interface TopCouple { coupleId: string; names: [string, string]; hearts: number; daresSet: number; games: number; streak: number; plus: boolean; linkedAtUtc: string }
```

## Ops

### GET `system/health` → `Health`
```ts
interface Health {
  nowUtc: string;
  probes: { name: "database" | "storage" | "push" | "email" | "foodModel"; status: "up" | "degraded" | "down" | "off"; latencyMs: number | null; detail: string | null }[];
  workers: { name: string; lastRunUtc: string | null; lastErrorUtc: string | null; lastError: string | null; everySeconds: number; status: "ok" | "late" | "failing" | "unknown" }[];
  queues: { name: string; waiting: number; oldestUtc: string | null }[];
  storageBytes: number;              // album objects counted in the DB
  foodScanCost: { days: number; inputTokens: number; outputTokens: number; estimatedCost: number | null; currency: string };
  notMonitored: string[];            // honest list of what nothing watches yet
}
```
Workers report into a singleton `WorkerHeartbeats` (name, last run, last error) that each background worker updates
every loop; "late" = no run for 3x its interval; "failing" = last loop errored.
Food scan price: `Admin__FoodScanPricePerMillionInput` / `...Output` (decimal, in `Admin__Currency`); null cost when unset.

### GET `alerts` → `Alert[]` (current, computed)
```ts
interface Alert { key: string; severity: "warn" | "bad"; title: string; detail: string; sinceUtc: string; link: string | null }
```
Rules (each with a 60-minute cooldown once raised; also emitted on the live stream as kind "alert"):
sign-up wave (>=10 sign-ups in 15 min); food-scan flood (one person >=20 scans in 10 min); payment trouble (subscriptions
newly onHold/inGracePeriod, or verify failures); push failing (>=20% failed in the last hour, at least 10 sent);
worker late/failing; album storage over 80% of a configured `Admin__StorageBudgetGb` (when set).

## People

### POST `users/{id}/internal` `{ internal: boolean, reason: string }` → 204
Audited. Internal accounts show a badge everywhere and are left out of analytics.

### GET `users/{id}/journey?days=90` → `Journey`
```ts
interface Journey { days: number; items: { day: string; events: { kind: JourneyKind; count: number; detail: string | null }[] }[] }
type JourneyKind = "signedUp" | "linked" | "unlinked" | "active" | "dareSet" | "dareCompleted" | "dareMissed" | "mealLogged"
  | "photoSynced" | "questionAnswered" | "gameFinished" | "plusStarted" | "plusEnded" | "adminAction";
// detail: exercise for dares, game type for games, product for plus, the action name for admin actions. Days with nothing omitted.
```

## Step-up for destructive actions
Disable, delete account, refund, revoke, stop renewal, give Plus, reset another admin's authenticator, and marking
internal now need a fresh authenticator code: header `X-Admin-Code: 123456`, verified like sign-in (a code works once,
the existing `LastUsedStep`). Missing or wrong: `403 { code: "admin.step_up_required" | "admin.step_up_invalid", message }`.
The dashboard asks for the code in a dialog and retries with it.
