# Couple Goals: daily exercise alarms verified by computer vision

This document is the design and wire contract for the Couple Goals feature.
It is the source of truth for names used across Core, Application,
Infrastructure, Api and the Next.js frontend. Change it before changing the code.

## Product

Twogether becomes a couple goals app. The couple keeps a list of exercise goals.
Each goal belongs to exactly one partner and has a set time every day. When that
time arrives the server opens an **occurrence** for the day and the assigned
partner's device starts **ringing an alarm**. The alarm cannot be dismissed. It
stops only when the assigned partner opens the camera, performs the exercise
live in front of it, and the on-device pose detector counts the target number
of reps (or seconds held). The server validates the completion, records it, and
tells both partners the goal is done. If the grace window passes without a
completion the occurrence is marked **missed** and both partners are told.

The other partner can watch progress live (rep count, confidence), sees
completions and misses as notifications, and both partners see streaks and a
history. Either partner may create goals for either partner.

Existing features (love notes, games, cycle, map) stay but are secondary. Home
becomes goals-first.

## Server owns the alarm

Per repo rules, the server owns timers and state. The alarm is "on" on a device
exactly when that user has an occurrence whose status is `alarming` or
`inProgress`. The client never decides that an alarm should start or stop on its
own; it derives it from server state (REST snapshot plus SignalR events), and
re-fetches the snapshot on reconnect.

## Core (`src/Twogether.Core`)

### Enums (`Enums/`)

```csharp
public enum ExerciseType { Squat, PushUp, JumpingJack, Plank, ArmRaise, HighKnees, SitUp }
public enum ExerciseMeasure { Reps, Seconds }
public enum ExerciseOccurrenceStatus { Alarming, InProgress, Completed, Missed, Cancelled }
```

`NotificationType` gains: `ExerciseDue, ExerciseCompleted, ExerciseMissed, ExerciseGoalChanged`.

Wire form (camelCase strings): `squat, pushUp, jumpingJack, plank, armRaise, highKnees, sitUp`;
`reps, seconds`; `alarming, inProgress, completed, missed, cancelled`.

### `Exercise/ExerciseCatalog.cs` (pure, static)

```csharp
public sealed record ExerciseDefinition(ExerciseType Type, string DisplayName, ExerciseMeasure Measure, int DefaultTarget, int MinTarget, int MaxTarget, double MinSecondsPerUnit, string Cue);
public static class ExerciseCatalog
{
    public static IReadOnlyList<ExerciseDefinition> All { get; }
    public static ExerciseDefinition Get(ExerciseType type);
    public static ExerciseMeasure MeasureOf(ExerciseType type);
}
```

Values:

| Type | DisplayName | Measure | Default | Min | Max | MinSecondsPerUnit |
|---|---|---|---|---|---|---|
| Squat | Squats | Reps | 15 | 1 | 200 | 1.0 |
| PushUp | Push-ups | Reps | 10 | 1 | 200 | 0.8 |
| JumpingJack | Jumping jacks | Reps | 20 | 1 | 300 | 0.45 |
| Plank | Plank hold | Seconds | 30 | 5 | 600 | 0.9 (fraction of target seconds that must elapse) |
| ArmRaise | Overhead arm raises | Reps | 15 | 1 | 300 | 0.6 |
| HighKnees | High knees | Reps | 30 | 1 | 400 | 0.3 |
| SitUp | Sit-ups | Reps | 10 | 1 | 200 | 1.0 |

### `Exercise/ExerciseSchedule.cs` (pure, static)

```csharp
public static class ExerciseSchedule
{
    // Resolves an IANA or Windows id; returns TimeZoneInfo.Utc when unknown.
    public static TimeZoneInfo ResolveTimeZone(string? timeZoneId);
    public static DateOnly LocalDate(DateTime utc, TimeZoneInfo zone);
    // Earliest UTC instant >= fromUtc whose local wall clock in `zone` is `scheduledTime`.
    // DST gap: the first valid instant after the gap. Ambiguous: the earlier instant.
    public static DateTime NextDueUtc(TimeOnly scheduledTime, TimeZoneInfo zone, DateTime fromUtc);
    // The UTC instant for `scheduledTime` on a given local date (same DST rules).
    public static DateTime ToUtc(DateOnly localDate, TimeOnly scheduledTime, TimeZoneInfo zone);
}
```

All DateTime values are UTC (`DateTimeKind.Utc`).

### `Exercise/ExerciseVerification.cs` (pure, static)

```csharp
public static class ExerciseVerification
{
    public const double MinimumConfidence = 0.35;
    // Validation errors: exercise.target_not_met, exercise.low_confidence, exercise.too_fast, exercise.not_started
    public static Result Validate(ExerciseType type, int target, int achievedCount, double confidence, DateTime? startedAtUtc, DateTime completedAtUtc);
}
```

Rules: `achievedCount >= target`; `confidence >= 0.35`; `startedAtUtc` not null;
elapsed = completedAtUtc - startedAtUtc; for Reps `elapsed.TotalSeconds >= MinSecondsPerUnit * target`;
for Seconds `elapsed.TotalSeconds >= target * MinSecondsPerUnit`.

### `Exercise/ExerciseStreak.cs` (pure, static)

```csharp
public static class ExerciseStreak
{
    // Consecutive local dates ending at `today` (or yesterday if today has no completion yet)
    // on which every occurrence for the user that reached a terminal state was Completed.
    // Days with no occurrences at all break the streak only if they are after the first occurrence ever.
    public static int CurrentStreakDays(IEnumerable<(DateOnly LocalDate, ExerciseOccurrenceStatus Status)> occurrences, DateOnly today);
}
```

### `Entities/ExerciseGoal.cs`

```csharp
public sealed class ExerciseGoal : BaseEntity
{
    public Guid CoupleId { get; private set; }
    public Guid AssignedUserId { get; private set; }
    public Guid CreatedByUserId { get; private set; }
    public ExerciseType ExerciseType { get; private set; }
    public string Title { get; private set; }          // <= 80 chars, defaults to catalog DisplayName
    public int Target { get; private set; }            // reps or seconds by measure
    public TimeOnly ScheduledTime { get; private set; } // local wall clock
    public string TimeZoneId { get; private set; }     // IANA id, <= 100 chars
    public int GraceMinutes { get; private set; }      // 5..180, default 30
    public bool IsActive { get; private set; }
    public DateTime NextDueAtUtc { get; private set; } // server-maintained alarm pointer

    public static ExerciseGoal Create(Guid coupleId, Guid assignedUserId, Guid createdByUserId, ExerciseType type, string? title, int target, TimeOnly scheduledTime, string timeZoneId, int graceMinutes, DateTime utcNow);
    public void Update(Guid assignedUserId, ExerciseType type, string? title, int target, TimeOnly scheduledTime, string timeZoneId, int graceMinutes, DateTime utcNow); // recomputes NextDueAtUtc from utcNow
    public void Deactivate(DateTime utcNow);
    public void Reactivate(DateTime utcNow); // recomputes NextDueAtUtc
    public void AdvanceNextDue(DateTime nextDueAtUtc, DateTime utcNow);
}
```

`Create`/`Update` set `NextDueAtUtc = ExerciseSchedule.NextDueUtc(scheduledTime, zone, utcNow)`.
Creating a goal whose time already passed today therefore first fires tomorrow.
Entities validate invariants with `ArgumentException` (programmer errors); request validation lives in Application.

### `Entities/ExerciseOccurrence.cs`

```csharp
public sealed class ExerciseOccurrence : BaseEntity
{
    public Guid GoalId { get; private set; }
    public Guid CoupleId { get; private set; }
    public Guid UserId { get; private set; }            // assigned partner
    public ExerciseType ExerciseType { get; private set; } // snapshot from goal
    public string Title { get; private set; }
    public int Target { get; private set; }
    public DateOnly LocalDate { get; private set; }
    public DateTime ScheduledAtUtc { get; private set; }
    public DateTime DeadlineUtc { get; private set; }   // ScheduledAtUtc + grace
    public ExerciseOccurrenceStatus Status { get; private set; }
    public DateTime AlarmStartedAtUtc { get; private set; }
    public DateTime? StartedAtUtc { get; private set; }  // camera session began
    public DateTime? CompletedAtUtc { get; private set; }
    public int AchievedCount { get; private set; }
    public double Confidence { get; private set; }
    public long StateVersion { get; private set; }      // concurrency token
    public bool IsAlarmActive => Status is ExerciseOccurrenceStatus.Alarming or ExerciseOccurrenceStatus.InProgress;

    public static ExerciseOccurrence Open(ExerciseGoal goal, DateOnly localDate, DateTime scheduledAtUtc, DateTime utcNow);      // Alarming
    public static ExerciseOccurrence OpenMissed(ExerciseGoal goal, DateOnly localDate, DateTime scheduledAtUtc, DateTime utcNow); // Missed (catch-up after downtime)
    public void Start(DateTime utcNow);                   // Alarming -> InProgress; no-op if already InProgress; throws otherwise
    public void RecordProgress(int achievedCount, double confidence, DateTime utcNow); // InProgress only; achievedCount clamped to >= current
    public void Complete(int achievedCount, double confidence, DateTime utcNow);       // InProgress -> Completed
    public void Miss(DateTime utcNow);                    // Alarming/InProgress -> Missed
    public void Cancel(DateTime utcNow);                  // Alarming/InProgress -> Cancelled (goal deleted)
}
```

Every mutation bumps `StateVersion` and `UpdatedAtUtc`.

## Application (`src/Twogether.Application/Features/Exercise`)

```csharp
public sealed record ExerciseGoalDto(Guid Id, Guid CoupleId, Guid AssignedUserId, string AssignedUserName, Guid CreatedByUserId, ExerciseType ExerciseType, string Title, int Target, ExerciseMeasure Measure, string ScheduledTime /* "HH:mm" */, string TimeZoneId, int GraceMinutes, bool IsActive, DateTime NextDueAtUtc, DateTime CreatedAtUtc);
public sealed record ExerciseOccurrenceDto(Guid Id, Guid GoalId, Guid CoupleId, Guid UserId, string UserName, ExerciseType ExerciseType, string Title, int Target, ExerciseMeasure Measure, string LocalDate /* yyyy-MM-dd */, DateTime ScheduledAtUtc, DateTime DeadlineUtc, ExerciseOccurrenceStatus Status, DateTime AlarmStartedAtUtc, DateTime? StartedAtUtc, DateTime? CompletedAtUtc, int AchievedCount, double Confidence, long StateVersion);
public sealed record ExerciseTodayDto(DateTime GeneratedAtUtc, IReadOnlyList<ExerciseGoalDto> Goals, IReadOnlyList<ExerciseOccurrenceDto> Occurrences, DateTime? NextDueAtUtc, Guid? NextDueGoalId);
public sealed record ExercisePartnerStatsDto(Guid UserId, string UserName, int CurrentStreakDays, int CompletedCount, int MissedCount, double CompletionRate);
public sealed record ExerciseHistoryDto(int Days, IReadOnlyList<ExerciseOccurrenceDto> Occurrences, IReadOnlyList<ExercisePartnerStatsDto> Stats);
public sealed record ExerciseProgressDto(Guid OccurrenceId, Guid UserId, int AchievedCount, int Target, double Confidence, long StateVersion);
public sealed record ExerciseDefinitionDto(ExerciseType Type, string DisplayName, ExerciseMeasure Measure, int DefaultTarget, int MinTarget, int MaxTarget, string Cue);

public sealed record CreateExerciseGoalRequest(Guid AssignedUserId, ExerciseType ExerciseType, string? Title, int Target, string ScheduledTime, string TimeZoneId, int GraceMinutes = 30);
public sealed record UpdateExerciseGoalRequest(Guid AssignedUserId, ExerciseType ExerciseType, string? Title, int Target, string ScheduledTime, string TimeZoneId, int GraceMinutes, bool IsActive);
public sealed record CompleteExerciseRequest(long ExpectedStateVersion, int AchievedCount, double Confidence);

public static class ExerciseGoalRules
{
    // Validation errors: exercise.invalid_target, exercise.invalid_time, exercise.invalid_time_zone, exercise.invalid_grace, exercise.invalid_title
    public static Result<TimeOnly> ParseScheduledTime(string value);           // "HH:mm" or "HH:mm:ss"
    public static Result Validate(ExerciseType type, string? title, int target, string timeZoneId, int graceMinutes);
}

public static class ExerciseMapper
{
    public static ExerciseGoalDto ToDto(ExerciseGoal goal, string assignedUserName);
    public static ExerciseOccurrenceDto ToDto(ExerciseOccurrence occurrence, string userName);
    public static ExerciseDefinitionDto ToDto(ExerciseDefinition definition);
}

public static class ExerciseStats
{
    public static ExercisePartnerStatsDto Compute(Guid userId, string userName, IReadOnlyList<ExerciseOccurrence> occurrences, DateOnly today);
}
```

`IApplicationDbContext` gains `DbSet<ExerciseGoal> ExerciseGoals` and `DbSet<ExerciseOccurrence> ExerciseOccurrences`.

## Infrastructure

- `Persistence/Configurations/ExerciseGoalConfiguration.cs`: table `exercise_goals`; `ExerciseType` string(40); `Title` varchar(80); `TimeZoneId` varchar(100); `ScheduledTime` column type `time`; indexes `(CoupleId, IsActive)` and `(IsActive, NextDueAtUtc)`.
- `Persistence/Configurations/ExerciseOccurrenceConfiguration.cs`: table `exercise_occurrences`; `ExerciseType` and `Status` string(40); `Title` varchar(80); `StateVersion` concurrency token; unique index `(GoalId, LocalDate)`; indexes `(CoupleId, LocalDate)`, `(Status, DeadlineUtc)`.
- `ApplicationDbContext` adds both DbSets.
- `DatabaseSchemaUpgrade.Sql` appends `CREATE TABLE IF NOT EXISTS` for both tables with the exact EF column names (quoted PascalCase) and the indexes above. Types: uuid, character varying(n), integer, boolean, timestamp with time zone, date, time without time zone, double precision, bigint. Note the SQL literal is a raw string passed through `ExecuteSqlRawAsync` with format args, so literal braces must be doubled (`'{{}}'`).

## Api

### `Controllers/ExerciseController.cs` (route `api/exercise`, `[Authorize]`, thin)

| Method | Route | Body | Returns | Notes |
|---|---|---|---|---|
| GET | `catalog` | | `ExerciseDefinitionDto[]` | anonymous-safe but keep authorized |
| GET | `goals` | | `ExerciseGoalDto[]` | all goals of the couple, active first; `?includeInactive=true` includes inactive |
| POST | `goals` | `CreateExerciseGoalRequest` | `ExerciseGoalDto` | 422 on rule failure; 400 `exercise.no_couple` if no active couple; 422 `exercise.invalid_assignee` if assignee not in couple. Notifies the other partner (`ExerciseGoalChanged`), sends `ExerciseGoalsChanged` to couple group |
| PUT | `goals/{id}` | `UpdateExerciseGoalRequest` | `ExerciseGoalDto` | same checks; toggling `IsActive=false` cancels today's active occurrence |
| DELETE | `goals/{id}` | | 204 | deactivates; cancels an active occurrence; sends `ExerciseGoalsChanged` (and `ExerciseCancelled` with the occurrence when one was cancelled) |
| GET | `today` | | `ExerciseTodayDto` | goals (active), occurrences: every occurrence that is alarming/inProgress plus every occurrence whose `LocalDate` equals the goal's current local date (computed per goal via its time zone). `NextDueAtUtc` is the min `NextDueAtUtc` of active goals |
| GET | `history?days=30` | | `ExerciseHistoryDto` | days clamped 1..365; occurrences with `ScheduledAtUtc >= now - days`, newest first; stats for each partner |
| POST | `occurrences/{id}/start` | | `ExerciseOccurrenceDto` | 403 unless caller is the assigned user; 409 `exercise.not_active` unless alarming/inProgress. Sends `ExerciseStarted` to couple group |
| POST | `occurrences/{id}/complete` | `CompleteExerciseRequest` | `ExerciseOccurrenceDto` | 403 unless assigned user; 409 `exercise.not_active`; 409 `exercise.version_conflict` on StateVersion mismatch or `DbUpdateConcurrencyException`; 422 from `ExerciseVerification`. On success: notification `ExerciseCompleted` to the partner, `ExerciseCompleted` event to couple group |

`CoupleId` and `UserId` come only from `ICurrentUser`. Request bodies never carry a couple id.
Progress is reported through the hub, not REST.

### `Hubs/CoupleHub.cs` addition

```csharp
public async Task ReportExerciseProgress(Guid occurrenceId, int achievedCount, double confidence)
```

Caller must be the occurrence's assigned user and the occurrence must be `InProgress`
(silently return otherwise, like `ReportDraft`). Persists `RecordProgress`, swallows
`DbUpdateConcurrencyException`, then sends `ExerciseProgress` (`ExerciseProgressDto`)
to `OthersInGroup(couple)`.

### `Background/ExerciseAlarmWorker.cs`

Same shape and database-presence guard as `GameTimeoutWorker`, 1-second `PeriodicTimer`.
Each tick, in one scope:

1. **Open due alarms.** `ExerciseGoals` where `IsActive && NextDueAtUtc <= now`. For each goal:
   `zone = ResolveTimeZone(goal.TimeZoneId)`, `localDate = LocalDate(goal.NextDueAtUtc, zone)`.
   If an occurrence `(GoalId, localDate)` already exists, only advance the pointer.
   Else if `now > goal.NextDueAtUtc + GraceMinutes` (server was down): `OpenMissed`, no notification.
   Else `Open`, add `Notification(ExerciseDue)` for the assigned user, send `ExerciseAlarmStarted` (`ExerciseOccurrenceDto`) to the couple group and `NotificationCreated` to the user group.
   Always `goal.AdvanceNextDue(NextDueUtc(goal.ScheduledTime, zone, goal.NextDueAtUtc.AddMinutes(1)), now)`.
2. **Expire.** `ExerciseOccurrences` where status alarming/inProgress and `DeadlineUtc <= now`:
   `Miss`, add `Notification(ExerciseMissed)` for both partners, send `ExerciseMissed` to the couple group.

Save once per step; on `DbUpdateConcurrencyException` log and continue (the next tick retries).
Register with `AddHostedService<ExerciseAlarmWorker>()` in `Program.cs`.

### SignalR events (couple group unless noted)

| Event | Payload |
|---|---|
| `ExerciseAlarmStarted` | `ExerciseOccurrenceDto` |
| `ExerciseStarted` | `ExerciseOccurrenceDto` |
| `ExerciseProgress` | `ExerciseProgressDto` (others in group only) |
| `ExerciseCompleted` | `ExerciseOccurrenceDto` |
| `ExerciseMissed` | `ExerciseOccurrenceDto` |
| `ExerciseCancelled` | `ExerciseOccurrenceDto` |
| `ExerciseGoalsChanged` | `ExerciseGoalDto[]` (active goals) |
| `NotificationCreated` | existing `NotificationDto` (user group) |

## Frontend (`frontend/src`)

### `lib/api.ts` additions

```ts
export type ExerciseType = "squat" | "pushUp" | "jumpingJack" | "plank" | "armRaise" | "highKnees" | "sitUp";
export type ExerciseMeasure = "reps" | "seconds";
export type ExerciseOccurrenceStatus = "alarming" | "inProgress" | "completed" | "missed" | "cancelled";
export type ExerciseDefinition = { type: ExerciseType; displayName: string; measure: ExerciseMeasure; defaultTarget: number; minTarget: number; maxTarget: number; cue: string };
export type ExerciseGoal = { id: string; coupleId: string; assignedUserId: string; assignedUserName: string; createdByUserId: string; exerciseType: ExerciseType; title: string; target: number; measure: ExerciseMeasure; scheduledTime: string; timeZoneId: string; graceMinutes: number; isActive: boolean; nextDueAtUtc: string; createdAtUtc: string };
export type ExerciseOccurrence = { id: string; goalId: string; coupleId: string; userId: string; userName: string; exerciseType: ExerciseType; title: string; target: number; measure: ExerciseMeasure; localDate: string; scheduledAtUtc: string; deadlineUtc: string; status: ExerciseOccurrenceStatus; alarmStartedAtUtc: string; startedAtUtc: string | null; completedAtUtc: string | null; achievedCount: number; confidence: number; stateVersion: number };
export type ExerciseToday = { generatedAtUtc: string; goals: ExerciseGoal[]; occurrences: ExerciseOccurrence[]; nextDueAtUtc: string | null; nextDueGoalId: string | null };
export type ExercisePartnerStats = { userId: string; userName: string; currentStreakDays: number; completedCount: number; missedCount: number; completionRate: number };
export type ExerciseHistory = { days: number; occurrences: ExerciseOccurrence[]; stats: ExercisePartnerStats[] };
export type ExerciseProgressEvent = { occurrenceId: string; userId: string; achievedCount: number; target: number; confidence: number; stateVersion: number };
export type ExerciseGoalInput = { assignedUserId: string; exerciseType: ExerciseType; title: string | null; target: number; scheduledTime: string; timeZoneId: string; graceMinutes: number };

export const getExerciseCatalog: () => Promise<ExerciseDefinition[]>;
export const getExerciseGoals: (includeInactive?: boolean) => Promise<ExerciseGoal[]>;
export const createExerciseGoal: (input: ExerciseGoalInput) => Promise<ExerciseGoal>;
export const updateExerciseGoal: (id: string, input: ExerciseGoalInput & { isActive: boolean }) => Promise<ExerciseGoal>;
export const deleteExerciseGoal: (id: string) => Promise<void>;
export const getExerciseToday: () => Promise<ExerciseToday>;
export const getExerciseHistory: (days?: number) => Promise<ExerciseHistory>;
export const startExerciseOccurrence: (id: string) => Promise<ExerciseOccurrence>;
export const completeExerciseOccurrence: (id: string, body: { expectedStateVersion: number; achievedCount: number; confidence: number }) => Promise<ExerciseOccurrence>;
```

### `lib/sounds.ts` additions

```ts
export type AlarmMode = "ring" | "soft"; // "soft" = quieter metronome while the camera session is open
export function startAlarm(mode?: AlarmMode): void; // idempotent; switching mode restarts the pattern
export function stopAlarm(): void;
export function isAlarmRinging(): boolean;
```

Looping Web Audio pattern (no audio files). Must survive tab visibility changes
and must be fully silent after `stopAlarm()`.

### `lib/exercise/catalog.ts`

Client mirror of `ExerciseCatalog` for labels, cues, measure and default targets,
keyed by `ExerciseType`; also `formatTarget(target, measure)` ("15 reps", "30 s").

### `lib/exercise/pose.ts`

```ts
export type Landmark = { x: number; y: number; z: number; visibility: number };
export const WASM_BASE = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@1.0.1/wasm";
export const MODEL_URL = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task";
export function loadPoseLandmarker(): Promise<import("@mediapipe/tasks-vision").PoseLandmarker>; // singleton, runningMode "VIDEO", numPoses 1
export function angleDegrees(a: Landmark, b: Landmark, c: Landmark): number; // angle at b
export const POSE = { nose: 0, leftShoulder: 11, rightShoulder: 12, leftElbow: 13, rightElbow: 14, leftWrist: 15, rightWrist: 16, leftHip: 23, rightHip: 24, leftKnee: 25, rightKnee: 26, leftAnkle: 27, rightAnkle: 28 } as const;
```

`@mediapipe/tasks-vision@1.0.1` is installed; read `node_modules/@mediapipe/tasks-vision/vision.d.ts`
for the real API (`FilesetResolver.forVisionTasks`, `PoseLandmarker.createFromOptions`,
`detectForVideo(video, timestampMs)` returning `{ landmarks: Landmark[][] }`, `PoseLandmarker.POSE_CONNECTIONS`).
Import the package only inside a dynamic `import()` from client code so SSR never touches it.

### `lib/exercise/counters.ts` (pure, no DOM)

```ts
export type CounterState = { count: number; phase: string; confidence: number; feedback: string | null; inPosition: boolean };
export type RepCounter = { update(landmarks: Landmark[], timestampMs: number): CounterState; reset(): void; state(): CounterState };
export function createCounter(type: ExerciseType, target: number): RepCounter;
```

Heuristics (image coordinates: y grows downward; use the average of left/right joints and require visibility >= 0.5 on the joints a rule needs; `confidence` is the mean visibility of those joints):

- **squat**: knee angle (hip-knee-ankle). down < 100°, up > 160°. Count on down→up.
- **pushUp**: elbow angle (shoulder-elbow-wrist). down < 95°, up > 155°; torso near horizontal (|shoulder.y - hip.y| < 0.25 × |shoulder.x - hip.x| + 0.1). Count on down→up.
- **jumpingJack**: open = both wrists above the nose (wrist.y < nose.y) AND ankle distance > 1.6 × hip width; closed = wrists below shoulders AND ankle distance < 1.2 × hip width. Count on open→closed.
- **plank**: shoulder-hip-ankle angle in 155°..205° AND torso horizontal (as pushUp). `count` = whole seconds accumulated while in position (timestamps from `update`); leaving position pauses, does not reset.
- **armRaise**: up = both wrists above the nose; down = both wrists below the shoulders. Count on up→down.
- **highKnees**: a knee rising above the hip midline (knee.y < hip.y) counts once per leg lift; alternate legs not required; debounce 250 ms per leg.
- **sitUp**: hip angle (shoulder-hip-knee). up < 95°, down > 140°. Count on up→down... count a rep when the trunk returns down after reaching up.

Feedback strings: "Step back so your whole body is in frame" when required joints are not visible; type-specific cues otherwise (e.g. "Lower until your thighs are parallel").
Debounce every transition with a 300 ms minimum phase duration to reject jitter.

### Components (`app/exercise/`)

- `ExerciseAlarmOverlay.tsx` — `{ occurrence: ExerciseOccurrence; now: number; onStart(): void }`. Full-screen fixed overlay above everything (`z-index` above modals), pulsing alarm visual, local time, exercise title and target, time left until the deadline, a single primary action "Start the camera". No close button. Short text: "The alarm stops once you finish N reps on camera."
- `LiveExerciseSession.tsx` — `{ occurrence: ExerciseOccurrence; onProgress(count: number, confidence: number): void; onComplete(count: number, confidence: number): Promise<void>; onExit(): void }`. Requests `getUserMedia({ video: { facingMode: "user" } })`, shows the mirrored video with a canvas skeleton overlay, loads the landmarker (loading state and retry), runs `detectForVideo` in a `requestAnimationFrame` loop with monotonic timestamps, feeds `createCounter`, shows a large count / target, progress ring, confidence meter and cue text, calls `onProgress` at most once per second and whenever the count changes, calls `onComplete` once when `count >= target`, stops all tracks on unmount. Camera denied or insecure context → explanatory message (camera needs HTTPS or localhost). "Leave for now" calls `onExit` (the alarm goes back to full volume).
- `GoalsView.tsx` — list of goals (both partners, grouped by partner), today's status per goal, create/edit/delete form (exercise type select from catalog, target with catalog bounds, time input, assignee radio, grace minutes), history list and partner stats cards. Time zone defaults to `Intl.DateTimeFormat().resolvedOptions().timeZone`.
- `PartnerLiveCard.tsx` — the partner's current occurrence: ringing / performing with live count / done / missed.

### `app/page.tsx` integration

- Navigation: `Home, Goals, Messages, Games, Cycle, Map` (+ Profile). Icons from lucide-react (`Dumbbell`, or `Activity` if missing). Update `.mobile-nav` grid to 7 columns.
- New state: `exerciseToday`, `exerciseHistory`, `exerciseCatalog`, `liveOccurrenceId` (camera session open), `partnerProgress`.
- `loadData` also fetches `getExerciseToday`, `getExerciseHistory(30)`, `getExerciseCatalog`.
- Hub handlers for every event in the table above; `onreconnected` re-fetches today.
- **Alarm effect**: `mine = occurrences.filter(o => o.userId === user.id && (o.status === "alarming" || o.status === "inProgress"))`. If any: `startAlarm(liveOccurrenceId ? "soft" : "ring")`, show `ExerciseAlarmOverlay` for the earliest unless the live session is open, call `notifyBrowser` once per occurrence. Else `stopAlarm()`.
- **Local fallback timer**: when `exerciseToday.nextDueAtUtc` is within the next 24 h, set a timeout for `+1500 ms` that re-fetches today (covers a missed hub event).
- Start: `startExerciseOccurrence(id)` then open `LiveExerciseSession`. Progress: `connection.invoke("ReportExerciseProgress", id, count, confidence)`. Complete: `completeExerciseOccurrence(id, { expectedStateVersion, achievedCount, confidence })`; on success replace the occurrence, close the session, `stopAlarm()`, `playSound("celebrate")`, show celebration. On 422/409 show the error and keep the session open.
- `HomeView` becomes goals-first: "Today's goals" cards (mine and partner's with status), next alarm countdown, exercise streaks for both partners (from history stats), partner live card, then the existing hero/quick access/love note.
- Fix pre-existing mismatch: game type value `onGame` → `iCallOn` wherever the frontend sends or compares it.
- `layout.tsx` metadata title: "Twogether — couple goals".

### Styles

New classes in `globals.css`, mobile-first, same tokens. Alarm overlay uses a strong pulsing pink/amber animation; the live session fills the viewport on phones.

## Repo fixes bundled with this change

- `GameType.OnGame` → `GameType.ICallOn` in `GameController`, `GameHub`, `GameTimeoutWorker`, `BackendBehaviorTests`.
- `.github/workflows/dotnet.yml`: `dotnet-version: 10.0.x`.

## Verification

```powershell
dotnet build Twogether.slnx
dotnet test Twogether.slnx
cd frontend
npm.cmd run lint
npm.cmd run build
```

Unit tests to add (`tests/Twogether.UnitTests/ExerciseTests.cs`): schedule next-due across midnight and a DST gap; goal created after today's time fires tomorrow; occurrence state machine (start idempotent, complete requires InProgress, miss/cancel); verification rejects too-fast, low-confidence and under-target; streak counting; stats completion rate.
