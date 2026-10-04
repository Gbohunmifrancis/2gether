# Couple Goals: daily exercise alarms verified by computer vision

This document is the design and wire contract for the Couple Goals feature.
It is the source of truth for names used across Core, Application,
Infrastructure, Api and the Next.js frontend. Change it before changing the code.

## Product

Twogether becomes a couple goals app. The couple keeps a list of exercise goals.
Each goal belongs to exactly one partner and has a set time every day. When that
time arrives the server opens an **occurrence** for the day and the assigned
partner's device starts **ringing an alarm**. The alarm cannot be dismissed by the
person it is for. It stops only when the assigned partner opens the camera,
performs the exercise live in front of it, and the on-device pose detector counts
the target number of reps (or seconds held). The server validates the completion,
records it, and tells both partners the goal is done. If the grace window passes
without a completion the occurrence is marked **missed** and both partners are told.

The other partner can watch progress live (rep count, confidence), sees
completions and misses as notifications, and both partners see streaks and a
history. Either partner may create goals for either partner. Only the *other*
partner can let someone off for the day (cancel a ringing occurrence); the
assigned partner cannot delete, pause or reschedule a goal while its alarm is
ringing for them.

**Scope assumption.** "Rebuild" is read as: goals become the primary experience
(branding, Home, first tabs) and the existing features (love notes, games, cycle,
map) stay reachable as secondary tabs. Nothing is deleted. If the user wants the
old features removed, that is a follow-up.

### Alarm reliability on the web

This is a web app, so the alarm rings only while Twogether is open in a browser
tab: foreground on any device, or a backgrounded tab on desktop and Android. It
cannot ring from a closed tab or a locked iPhone. The product is honest about
this: the readiness card on Home says so, the app ships a web manifest so it can
be added to the home screen, and the client holds a screen wake lock while an
alarm is active. Web Push notifications that reach a closed app are **phase 2**
(they need VAPID keys, a service worker and a subscription endpoint) and are not
part of this change.

## Server owns the alarm

Per repo rules, the server owns timers and state. The alarm is "on" on a device
exactly when that user has an occurrence whose status is `alarming` or
`inProgress`. The client never decides that an alarm should start or stop on its
own; it derives it from server state (REST snapshot plus SignalR events), and
re-fetches the snapshot on reconnect, when the tab becomes visible, and at local
midnight. The single exception is the offline rule in the frontend section.

## Core (`src/Twogether.Core`)

### Enums (`Enums/`)

```csharp
public enum ExerciseType { Squat, PushUp, JumpingJack, Plank, ArmRaise, HighKnees, SitUp }
public enum ExerciseMeasure { Reps, Seconds }
public enum ExerciseOccurrenceStatus { Alarming, InProgress, Completed, Missed, Cancelled }
```

`NotificationType` gains: `ExerciseDue, ExerciseCompleted, ExerciseMissed, ExerciseCancelled, ExerciseGoalChanged`.

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

Values (the client mirrors these exactly in `lib/exercise/catalog.ts`):

| Type | DisplayName | Measure | Default | Min | Max | MinSecondsPerUnit |
|---|---|---|---|---|---|---|
| Squat | Squats | Reps | 15 | 1 | 200 | 0.8 |
| PushUp | Push-ups | Reps | 10 | 1 | 200 | 0.7 |
| JumpingJack | Jumping jacks | Reps | 20 | 1 | 300 | 0.4 |
| Plank | Plank hold | Seconds | 30 | 5 | 600 | 0.9 (fraction of target seconds that must elapse) |
| ArmRaise | Overhead arm raises | Reps | 15 | 1 | 300 | 0.5 |
| HighKnees | High knees | Reps | 30 | 1 | 400 | 0.25 |
| SitUp | Sit-ups | Reps | 10 | 1 | 200 | 0.8 |

Rep minimums are deliberately at or below what the client counter can physically
produce (two debounced phase changes per rep), so the server never rejects a count
the client could legitimately have reached.

### `Exercise/ExerciseSchedule.cs` (pure, static)

```csharp
public static class ExerciseSchedule
{
    // Resolves an IANA or Windows id; returns TimeZoneInfo.Utc when unknown.
    public static TimeZoneInfo ResolveTimeZone(string? timeZoneId);
    public static DateOnly LocalDate(DateTime utc, TimeZoneInfo zone);
    // Earliest UTC instant >= fromUtc whose local wall clock in `zone` is `scheduledTime`.
    public static DateTime NextDueUtc(TimeOnly scheduledTime, TimeZoneInfo zone, DateTime fromUtc);
    // The UTC instant for `scheduledTime` on a given local date.
    public static DateTime ToUtc(DateOnly localDate, TimeOnly scheduledTime, TimeZoneInfo zone);
}
```

DST rules for `ToUtc`: build a `DateTimeKind.Unspecified` wall clock; if
`zone.IsInvalidTime(wallClock)` (spring-forward gap) advance minute by minute to
the first valid wall clock; if `zone.IsAmbiguousTime(wallClock)` (fall-back) use
the **largest** offset from `zone.GetAmbiguousTimeOffsets` (the earlier instant);
otherwise `zone.GetUtcOffset`. Never call `ConvertTimeToUtc` on the raw wall
clock. All returned DateTime values have `DateTimeKind.Utc`.

### `Exercise/ExerciseVerification.cs` (pure, static)

```csharp
public static class ExerciseVerification
{
    public const double MinimumConfidence = 0.35;
    public static readonly TimeSpan CompletionAllowance = TimeSpan.FromMinutes(10);
    // Validation errors: exercise.target_not_met, exercise.low_confidence, exercise.too_fast, exercise.not_started
    public static Result Validate(ExerciseType type, int target, int achievedCount, double confidence, DateTime? startedAtUtc, DateTime completedAtUtc);
}
```

Rules: `achievedCount >= target`; `confidence >= 0.35`; `startedAtUtc` not null;
elapsed = completedAtUtc - startedAtUtc; `elapsed.TotalSeconds >= MinSecondsPerUnit * target`
(for Seconds types MinSecondsPerUnit is the fraction of the target that must elapse).

### `Exercise/ExerciseStreak.cs` (pure, static)

```csharp
public static class ExerciseStreak
{
    public static int CurrentStreakDays(IEnumerable<(DateOnly LocalDate, ExerciseOccurrenceStatus Status)> occurrences, DateOnly today);
}
```

Rule: ignore `Cancelled` occurrences entirely. A day is *good* when it has at least
one `Completed` occurrence and no `Missed` occurrence. Start at `today`; if `today`
has no terminal (Completed/Missed) occurrence yet, start at yesterday. Count
consecutive good days backwards; a day with no occurrences ends the streak.

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

`Create`/`Update`/`Reactivate` set `NextDueAtUtc = ExerciseSchedule.NextDueUtc(scheduledTime, zone, utcNow)`.
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
    public DateTime DeadlineUtc { get; private set; }   // ScheduledAtUtc + grace; alarming expires here
    public DateTime? CompleteByUtc { get; private set; } // set by Start: max(DeadlineUtc, utcNow + CompletionAllowance); inProgress expires here
    public ExerciseOccurrenceStatus Status { get; private set; }
    public DateTime AlarmStartedAtUtc { get; private set; }
    public DateTime? StartedAtUtc { get; private set; }  // camera session began (latest start)
    public DateTime? CompletedAtUtc { get; private set; }
    public int AchievedCount { get; private set; }
    public double Confidence { get; private set; }
    public long StateVersion { get; private set; }      // concurrency token
    public bool IsAlarmActive => Status is ExerciseOccurrenceStatus.Alarming or ExerciseOccurrenceStatus.InProgress;

    public static ExerciseOccurrence Open(ExerciseGoal goal, DateOnly localDate, DateTime scheduledAtUtc, DateTime utcNow); // Alarming
    public void Start(DateTime utcNow);                   // Alarming/InProgress -> InProgress; resets AchievedCount=0, Confidence=0, StartedAtUtc=utcNow, CompleteByUtc; bumps version; throws otherwise
    public void RecordProgress(int achievedCount, double confidence, DateTime utcNow); // InProgress only; achievedCount clamped to >= current; does NOT bump StateVersion
    public void Complete(int achievedCount, double confidence, DateTime utcNow);       // InProgress -> Completed; bumps version
    public void Miss(DateTime utcNow);                    // Alarming/InProgress -> Missed; bumps version
    public void Cancel(DateTime utcNow);                  // Alarming/InProgress -> Cancelled; bumps version
}
```

`StateVersion` is bumped by `Start`, `Complete`, `Miss` and `Cancel` only. Progress is
advisory and must not invalidate the completion that follows it. Every mutation sets
`UpdatedAtUtc`. Re-starting an in-progress occurrence forfeits earlier progress on purpose
(the camera session always counts from zero, and the plausibility check measures from the
latest start).

## Application (`src/Twogether.Application/Features/Exercise`)

```csharp
public sealed record ExerciseGoalDto(Guid Id, Guid CoupleId, Guid AssignedUserId, string AssignedUserName, Guid CreatedByUserId, ExerciseType ExerciseType, string Title, int Target, ExerciseMeasure Measure, string ScheduledTime /* "HH:mm" */, string TimeZoneId, int GraceMinutes, bool IsActive, DateTime NextDueAtUtc, DateTime CreatedAtUtc);
public sealed record ExerciseOccurrenceDto(Guid Id, Guid GoalId, Guid CoupleId, Guid UserId, string UserName, ExerciseType ExerciseType, string Title, int Target, ExerciseMeasure Measure, string LocalDate /* yyyy-MM-dd */, DateTime ScheduledAtUtc, DateTime DeadlineUtc, DateTime? CompleteByUtc, ExerciseOccurrenceStatus Status, DateTime AlarmStartedAtUtc, DateTime? StartedAtUtc, DateTime? CompletedAtUtc, int AchievedCount, double Confidence, long StateVersion);
public sealed record ExerciseTodayDto(DateTime GeneratedAtUtc, IReadOnlyList<ExerciseGoalDto> Goals, IReadOnlyList<ExerciseOccurrenceDto> Occurrences, DateTime? NextDueAtUtc, Guid? NextDueGoalId);
public sealed record ExercisePartnerStatsDto(Guid UserId, string UserName, int CurrentStreakDays, int CompletedCount, int MissedCount, double CompletionRate);
public sealed record ExerciseHistoryDto(int Days, IReadOnlyList<ExerciseOccurrenceDto> Occurrences, IReadOnlyList<ExercisePartnerStatsDto> Stats);
public sealed record ExerciseProgressDto(Guid OccurrenceId, Guid UserId, int AchievedCount, int Target, double Confidence);
public sealed record ExerciseDefinitionDto(ExerciseType Type, string DisplayName, ExerciseMeasure Measure, int DefaultTarget, int MinTarget, int MaxTarget, double MinSecondsPerUnit, string Cue);

public sealed record CreateExerciseGoalRequest(Guid AssignedUserId, ExerciseType ExerciseType, string? Title, int Target, string ScheduledTime, string TimeZoneId, int GraceMinutes = 30);
public sealed record UpdateExerciseGoalRequest(Guid AssignedUserId, ExerciseType ExerciseType, string? Title, int Target, string ScheduledTime, string TimeZoneId, int GraceMinutes, bool IsActive);
public sealed record CompleteExerciseRequest(int AchievedCount, double Confidence);

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
    // Cancelled occurrences are ignored by streak, MissedCount and CompletionRate.
    // CompletionRate = Completed / (Completed + Missed), 0 when there are none.
    public static ExercisePartnerStatsDto Compute(Guid userId, string userName, IReadOnlyList<ExerciseOccurrence> occurrences, DateOnly today);
}
```

"Today" for stats is `ExerciseSchedule.LocalDate(now, zone)` where `zone` is the time
zone of that user's most recently updated active goal, else of their most recent
occurrence's goal, else UTC. (`User.TimeZoneId` is never set by the app and stays "UTC".)

`IApplicationDbContext` gains `DbSet<ExerciseGoal> ExerciseGoals` and `DbSet<ExerciseOccurrence> ExerciseOccurrences`.

## Infrastructure

- `Persistence/Configurations/ExerciseGoalConfiguration.cs`: table `exercise_goals`; `ExerciseType` string(40); `Title` varchar(80); `TimeZoneId` varchar(100); `ScheduledTime` column type `time`; `builder.UseXminAsConcurrencyToken()` so the worker and the controller cannot silently overwrite each other; indexes `(CoupleId, IsActive)` and `(IsActive, NextDueAtUtc)`.
- `Persistence/Configurations/ExerciseOccurrenceConfiguration.cs`: table `exercise_occurrences`; `ExerciseType` and `Status` string(40); `Title` varchar(80); `StateVersion` concurrency token; unique index `(GoalId, LocalDate)`; indexes `(CoupleId, LocalDate)`, `(Status, DeadlineUtc)`.
- `ApplicationDbContext` adds both DbSets.
- `DatabaseSchemaUpgrade.Sql` appends `CREATE TABLE IF NOT EXISTS` for both tables with the exact EF column names (quoted PascalCase) and the indexes above. Types: uuid, character varying(n), integer, boolean, timestamp with time zone, date, time without time zone, double precision, bigint. Constraint and index names must equal EF's defaults so a database created by `EnsureCreatedAsync` does not get duplicates: `PK_exercise_goals`, `PK_exercise_occurrences`, `IX_exercise_goals_CoupleId_IsActive`, `IX_exercise_goals_IsActive_NextDueAtUtc`, `IX_exercise_occurrences_GoalId_LocalDate` (UNIQUE), `IX_exercise_occurrences_CoupleId_LocalDate`, `IX_exercise_occurrences_Status_DeadlineUtc`. The SQL literal is a raw string passed through `ExecuteSqlRawAsync` with format args, so any literal brace must be doubled (none are needed for these tables).

## Api

### `Controllers/ExerciseController.cs` (route `api/exercise`, `[Authorize]`, thin)

General rules for this controller:

- `CoupleId` and `UserId` come only from `ICurrentUser`. Request bodies never carry a couple id.
- `GET goals`, `GET today` and `GET history` return `200` with empty collections (today: Goals `[]`, Occurrences `[]`, NextDueAtUtc `null`, NextDueGoalId `null`) when the caller has no couple or the couple is not `Active`. `GET catalog` never depends on the couple. The frontend logs the user out if `loadData` throws, so these must never 4xx for an unlinked user.
- Every `Notification` row written by this feature is followed by `NotificationCreated` (`NotificationDto`) to `CoupleHub.UserGroupName(notification.UserId)`.
- **Alarm lock**: while a goal has an occurrence in `alarming`/`inProgress`, the *assigned* user receives `409 exercise.alarm_active` on `DELETE goals/{id}`, on `PUT` with `IsActive=false`, and on `PUT` that changes `AssignedUserId`, `ExerciseType`, `Target` or `ScheduledTime`. The other partner may still do those things; they cancel the occurrence (below).
- `DbUpdateConcurrencyException` anywhere → `409 exercise.version_conflict`.

| Method | Route | Body | Returns | Notes |
|---|---|---|---|---|
| GET | `catalog` | | `ExerciseDefinitionDto[]` | |
| GET | `goals` | | `ExerciseGoalDto[]` | all goals of the couple, active first then by ScheduledTime; `?includeInactive=true` includes inactive |
| POST | `goals` | `CreateExerciseGoalRequest` | `ExerciseGoalDto` | 422 on rule failure; 400 `exercise.no_couple` if no active couple; 422 `exercise.invalid_assignee` if assignee not in couple. Notification `ExerciseGoalChanged` to the other partner; `ExerciseGoalsChanged` to couple group |
| PUT | `goals/{id}` | `UpdateExerciseGoalRequest` | `ExerciseGoalDto` | same checks plus alarm lock. Cancels today's alarming/inProgress occurrence (sending `ExerciseCancelled` and Notification `ExerciseCancelled` to the assigned user) whenever `IsActive` becomes false or `AssignedUserId`, `ExerciseType`, `Target` or `ScheduledTime` changes. Notification `ExerciseGoalChanged` to the other partner; `ExerciseGoalsChanged` to couple group |
| DELETE | `goals/{id}` | | 204 | deactivates (alarm lock applies); cancels an active occurrence as above; `ExerciseGoalsChanged` |
| GET | `today` | | `ExerciseTodayDto` | goals (active), occurrences: every occurrence that is alarming/inProgress plus every occurrence whose `LocalDate` equals the goal's current local date (computed per goal via its time zone). `NextDueAtUtc`/`NextDueGoalId` from the active goal with the smallest `NextDueAtUtc` |
| GET | `history?days=30` | | `ExerciseHistoryDto` | days clamped 1..365; occurrences with `ScheduledAtUtc >= now - days`, newest first; stats for each partner |
| POST | `occurrences/{id}/start` | | `ExerciseOccurrenceDto` | 403 unless caller is the assigned user; 409 `exercise.not_active` unless alarming/inProgress or if `DeadlineUtc <= now` while alarming. Calls `Start` (also when already inProgress: progress resets). Sends `ExerciseStarted` to couple group |
| POST | `occurrences/{id}/complete` | `CompleteExerciseRequest` | `ExerciseOccurrenceDto` | 403 unless assigned user; 409 `exercise.not_active` unless inProgress; 422 with the `ExerciseVerification` error. On success: Notification `ExerciseCompleted` to the partner, `ExerciseCompleted` event to couple group |
| POST | `occurrences/{id}/cancel` | | `ExerciseOccurrenceDto` | **partner only**: 403 for the assigned user; 409 `exercise.not_active` unless alarming/inProgress. `Cancel`, Notification `ExerciseCancelled` to the assigned user ("<Partner> let you off today"), `ExerciseCancelled` event to couple group |

Progress is reported through the hub, not REST.

### `CoupleController.BreakUp`

Before dissolving the couple: deactivate every `ExerciseGoal` of the couple and `Cancel`
its alarming/inProgress occurrences, sending `ExerciseCancelled` for each. The worker also
ignores goals whose couple is not `Active`, so a missed path can never ring forever.

### `Hubs/CoupleHub.cs` addition

```csharp
public async Task ReportExerciseProgress(Guid occurrenceId, int achievedCount, double confidence)
```

Caller must be the occurrence's assigned user and the occurrence must be `InProgress`
(silently return otherwise, like `ReportDraft`). Clamp inputs (0..10000, 0..1). Persists
`RecordProgress`, swallows `DbUpdateConcurrencyException`, then sends `ExerciseProgress`
(`ExerciseProgressDto`) to `OthersInGroup(couple)`.

### `Background/ExerciseAlarmWorker.cs`

Same shape as `GameTimeoutWorker`, 1-second `PeriodicTimer`. Database guard: on startup
try `Database.CanConnectAsync()` once; on failure log once and retry every 30 s until it
succeeds, then start ticking. (Apply the same guard to `GameTimeoutWorker`, replacing its
configuration-key check, which never triggers because appsettings.json always supplies a
connection string.)

Each tick, in one scope:

1. **Open due alarms.** `ExerciseGoals` joined to `Couples` where couple `Status == Active`,
   `IsActive && NextDueAtUtc <= now`. For each goal: `zone = ResolveTimeZone(goal.TimeZoneId)`,
   `localDate = LocalDate(goal.NextDueAtUtc, zone)`.
   - If an occurrence `(GoalId, localDate)` already exists: only advance the pointer.
   - Else if `now > goal.NextDueAtUtc + GraceMinutes` (server was down): **no occurrence**, only advance the pointer. Downtime never counts against anyone.
   - Else `Open`, add `Notification(ExerciseDue)` for the assigned user.
   - Always `goal.AdvanceNextDue(NextDueUtc(goal.ScheduledTime, zone, goal.NextDueAtUtc.AddMinutes(1)), now)`.
   Save once. Only after the save succeeds send `ExerciseAlarmStarted` (`ExerciseOccurrenceDto`) to the couple group and `NotificationCreated` to the user group for each opened occurrence.
2. **Expire.** `ExerciseOccurrences` where (`Status == Alarming && DeadlineUtc <= now`) or (`Status == InProgress && CompleteByUtc <= now`): `Miss`, add `Notification(ExerciseMissed)` for both partners. Save once; then send `ExerciseMissed` to the couple group and `NotificationCreated` to each partner.

Error handling: `DbUpdateConcurrencyException` → log Information and continue (next tick
retries). `DbUpdateException` whose inner `PostgresException.SqlState == "23505"` (another
instance opened the same occurrence) → log Information and continue. Anything else → the
existing error log. Events are never sent for state that was not saved.

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
export type ExerciseDefinition = { type: ExerciseType; displayName: string; measure: ExerciseMeasure; defaultTarget: number; minTarget: number; maxTarget: number; minSecondsPerUnit: number; cue: string };
export type ExerciseGoal = { id: string; coupleId: string; assignedUserId: string; assignedUserName: string; createdByUserId: string; exerciseType: ExerciseType; title: string; target: number; measure: ExerciseMeasure; scheduledTime: string; timeZoneId: string; graceMinutes: number; isActive: boolean; nextDueAtUtc: string; createdAtUtc: string };
export type ExerciseOccurrence = { id: string; goalId: string; coupleId: string; userId: string; userName: string; exerciseType: ExerciseType; title: string; target: number; measure: ExerciseMeasure; localDate: string; scheduledAtUtc: string; deadlineUtc: string; completeByUtc: string | null; status: ExerciseOccurrenceStatus; alarmStartedAtUtc: string; startedAtUtc: string | null; completedAtUtc: string | null; achievedCount: number; confidence: number; stateVersion: number };
export type ExerciseToday = { generatedAtUtc: string; goals: ExerciseGoal[]; occurrences: ExerciseOccurrence[]; nextDueAtUtc: string | null; nextDueGoalId: string | null };
export type ExercisePartnerStats = { userId: string; userName: string; currentStreakDays: number; completedCount: number; missedCount: number; completionRate: number };
export type ExerciseHistory = { days: number; occurrences: ExerciseOccurrence[]; stats: ExercisePartnerStats[] };
export type ExerciseProgressEvent = { occurrenceId: string; userId: string; achievedCount: number; target: number; confidence: number };
export type ExerciseGoalInput = { assignedUserId: string; exerciseType: ExerciseType; title: string | null; target: number; scheduledTime: string; timeZoneId: string; graceMinutes: number };

export const getExerciseCatalog: () => Promise<ExerciseDefinition[]>;
export const getExerciseGoals: (includeInactive?: boolean) => Promise<ExerciseGoal[]>;
export const createExerciseGoal: (input: ExerciseGoalInput) => Promise<ExerciseGoal>;
export const updateExerciseGoal: (id: string, input: ExerciseGoalInput & { isActive: boolean }) => Promise<ExerciseGoal>;
export const deleteExerciseGoal: (id: string) => Promise<void>;
export const getExerciseToday: () => Promise<ExerciseToday>;
export const getExerciseHistory: (days?: number) => Promise<ExerciseHistory>;
export const startExerciseOccurrence: (id: string) => Promise<ExerciseOccurrence>;
export const completeExerciseOccurrence: (id: string, body: { achievedCount: number; confidence: number }) => Promise<ExerciseOccurrence>;
export const cancelExerciseOccurrence: (id: string) => Promise<ExerciseOccurrence>;
```

The CV foundation defines these same types in `lib/exercise/types.ts`; `api.ts` re-exports
them from there so there is one definition.

### `lib/sounds.ts` additions

```ts
export type AlarmMode = "ring" | "soft"; // "soft" = quieter metronome while the camera session is open
export function startAlarm(mode?: AlarmMode): void; // idempotent; switching mode restarts the pattern
export function stopAlarm(): void;
export function isAlarmArmed(): boolean;   // startAlarm was called and stopAlarm was not
export function isAlarmRinging(): boolean; // audio is actually running
```

Looping Web Audio pattern (no audio files). Autoplay policy: `startAlarm` starts the loop
immediately if `ctx.state === "running"`; otherwise it *arms* and registers one-shot window
`pointerdown`/`keydown`/`touchend` listeners that call `resume()` and start the loop. While
armed it also listens to `visibilitychange` (visible) and the context's `statechange`
(iOS "interrupted") and calls `resume()` + restarts the loop. `stopAlarm()` removes the
listeners and leaves the context fully silent.

### `lib/exercise/catalog.ts`

Client mirror of `ExerciseCatalog` (displayName, measure, defaultTarget, min/max,
minSecondsPerUnit, cue) keyed by `ExerciseType`; `formatTarget(target, measure)` ("15 reps", "30 s");
`minimumSeconds(type, target)` = `minSecondsPerUnit × target`.

### `lib/exercise/status.ts`

```ts
export type GoalTodayStatus = "upcoming" | "tomorrow" | "alarming" | "inProgress" | "completed" | "missed" | "cancelled";
export function goalTodayStatus(goal: ExerciseGoal, occurrences: ExerciseOccurrence[], nowMs: number): { status: GoalTodayStatus; label: string; occurrence: ExerciseOccurrence | null };
```

Labels: "Rings at 07:00", "First alarm tomorrow 07:00", "Ringing now", "Doing it now · 8/15",
"Done 07:12", "Missed", "Skipped today". Show the goal's time zone abbreviation when it differs
from the viewer's.

### `lib/exercise/pose.ts`

```ts
export type Landmark = { x: number; y: number; z: number; visibility: number };
export const WASM_BASE = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@1.0.1/wasm";
export const MODEL_URL = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task";
export function loadPoseLandmarker(): Promise<import("@mediapipe/tasks-vision").PoseLandmarker>; // singleton, runningMode "VIDEO", numPoses 1; rejects reset the singleton so retry works
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

`confidence` is the running mean of the per-rep (plank: per-second) confidences since
`reset()`, where a rep's confidence is the mean visibility of the joints its rule needs at
the moment it was counted. It is not the current frame.

Heuristics (image coordinates: y grows downward; use the average of left/right joints and require visibility >= 0.5 on the joints a rule needs):

- **squat**: knee angle (hip-knee-ankle). down < 100°, up > 160°. Count on down→up.
- **pushUp**: elbow angle (shoulder-elbow-wrist). down < 95°, up > 155°; torso near horizontal (|shoulder.y - hip.y| < 0.25 × |shoulder.x - hip.x| + 0.1). Count on down→up.
- **jumpingJack**: open = both wrists above the nose (wrist.y < nose.y) AND ankle distance > 1.6 × hip width; closed = wrists below shoulders AND ankle distance < 1.2 × hip width. Count on open→closed.
- **plank**: shoulder-hip-ankle angle in 155°..205° AND torso horizontal (as pushUp). `count` = whole seconds accumulated while in position (timestamps from `update`); leaving position pauses, does not reset.
- **armRaise**: up = both wrists above the nose; down = both wrists below the shoulders. Count on up→down.
- **highKnees**: a knee rising above the hip midline (knee.y < hip.y) counts once per leg lift; alternate legs not required; debounce 250 ms per leg.
- **sitUp**: hip angle (shoulder-hip-knee). up < 95°, down > 140°. Count a rep when the trunk returns down after reaching up.

Feedback strings: "Step back so your whole body is in frame" when required joints are not visible; type-specific cues otherwise (e.g. "Lower until your thighs are parallel").
Debounce every transition with a 300 ms minimum phase duration to reject jitter.

### Components (`app/exercise/`)

- `ExerciseAlarmOverlay.tsx` — `{ occurrence: ExerciseOccurrence; queue: ExerciseOccurrence[]; now: number; ringing: boolean; onStart(occurrence: ExerciseOccurrence): void }`. Full-screen fixed overlay above everything (`z-index` above modals), pulsing alarm visual, local time, exercise title and target, time left until the deadline, a single primary action "Start the camera". No close button. Copy: "The alarm stops once you finish " + `formatTarget(target, measure)` + " on camera." When `queue.length > 1` show "and N more after this" with a small list to start a different one first. When `ringing` is false (audio not yet unlocked) the whole overlay is a tap target labelled "Tap to hear the alarm". Shows "Reconnecting…" when the page passes `offline`.
- `LiveExerciseSession.tsx` — `{ occurrence: ExerciseOccurrence; serverMessage: string | null; onProgress(count: number, confidence: number): void; onComplete(count: number, confidence: number): Promise<void>; onExit(): void }`. Requests `getUserMedia({ video: { facingMode: "user" } })`, shows the mirrored video (`playsInline`, `muted`, `autoPlay`) with a canvas skeleton overlay sized to the video's intrinsic size, loads the landmarker (loading state and retry), runs `detectForVideo` in a `requestAnimationFrame` loop with monotonic timestamps, feeds `createCounter`, shows a large count / target, progress ring, confidence meter, cue text and a countdown to `completeByUtc`. Calls `onProgress` whenever the count changes and at most once per second otherwise. **Completion gate**: calls `onComplete` only when `count >= target` AND elapsed since mount `>= minimumSeconds(type, target)`; until then shows "Hold on… N s". If `onComplete` rejects (422), keep counting and call `onComplete` again on every count change and at least every 2 s while `count >= target`, showing `serverMessage` as the cue. Stops all tracks and closes the landmarker on unmount. Camera denied or insecure context → explanatory message (camera needs HTTPS or localhost). "Leave for now" calls `onExit` and states "Your reps so far won't count" (the alarm returns to full volume).
- `GoalsView.tsx` — list of goals grouped by partner with `goalTodayStatus` labels; create/edit/delete form (exercise type select from catalog, target with catalog bounds, time input, assignee radio, grace minutes); hides delete/deactivate for the assigned user's ringing goal with the hint "Finish it on camera, or ask <partner> to skip today."; a "Let <partner> off today" button on the partner's ringing occurrence (calls `cancelExerciseOccurrence`); a soft warning when a second goal for the same partner lands within 15 minutes of another; history list and partner stats cards. Time zone defaults to `Intl.DateTimeFormat().resolvedOptions().timeZone`. Includes `AlarmReadinessCard` and `GoalsEmptyState` (below).
- `PartnerLiveCard.tsx` — the partner's current occurrence: ringing / performing with live count / done / missed / skipped.
- `AlarmReadinessCard.tsx` — three check rows: Notifications (button → `Notification.requestPermission()`), Sound ("Test alarm": 3 s `startAlarm("ring")` from the click then `stopAlarm()`), Camera ("Test camera": `getUserMedia` then stop tracks); plus the sentence "Alarms ring only while Twogether is open. Add it to your home screen and open it before <earliest goal time>." Shown on Home until all three are granted; a "Hide" link remembers dismissal in localStorage.
- `GoalsEmptyState.tsx` — shown on Home and Goals when the couple has no active goals: "Set your first goal" CTA that opens the create form with catalog defaults, assignee = me, time = next full hour. For unlinked users shows "Link your partner to start setting goals" instead.

### `app/page.tsx` integration

- Navigation: desktop sidebar `Home, Goals, Messages, Games, Cycle, Map, Profile`. Mobile bottom nav stays at 5 items: `Home, Goals, Messages, Games, More`; `More` opens a small sheet with Cycle, Map, Profile, Settings, Sign out. Icon for Goals: `Dumbbell` from lucide-react.
- New state: `exerciseToday`, `exerciseHistory`, `exerciseCatalog`, `liveOccurrenceId` (camera session open), `liveServerMessage`, `partnerProgress`, `alarmOffline`.
- `loadData` also fetches `getExerciseToday`, `getExerciseHistory(30)`, `getExerciseCatalog`, each with a `.catch` fallback (empty today / empty history / `[]`) so a failure can never log the user out.
- Hub handlers for every event in the table above (upsert occurrence by id; replace goals on `ExerciseGoalsChanged`); `onreconnected`, `visibilitychange` → visible, and a timer at the next local midnight all re-fetch today.
- **Alarm effect** (pure function of server state): `mine = occurrences.filter(o => o.userId === user.id && (o.status === "alarming" || o.status === "inProgress"))` sorted by `scheduledAtUtc`. If any: `startAlarm(liveOccurrenceId ? "soft" : "ring")`, request `navigator.wakeLock` (if available), show `ExerciseAlarmOverlay` for the first (with the rest as `queue`) unless the live session is open, and `notifyBrowser` once per occurrence id. Else `stopAlarm()` and release the wake lock. The `addNotification` handler skips its sound and toast for type `exerciseDue` while the overlay is showing.
- **Local fallback timer**: when `exerciseToday.nextDueAtUtc` is within the next 24 h, set a timeout for that instant `+1500 ms` that re-fetches today (covers a missed hub event). When it is within 10 minutes and the tab is visible, call `loadPoseLandmarker()` once to pre-warm the model.
- **Offline rule**: if `now >= deadlineUtc + 60 s` for the ringing occurrence and the today re-fetch keeps failing, set `alarmOffline`, drop the alarm to `soft` and show "Reconnecting…" on the overlay; server state wins as soon as a fetch succeeds.
- Start: `startExerciseOccurrence(id)` then open `LiveExerciseSession` (also used to re-enter after leaving; the server resets progress). Progress: `connection.invoke("ReportExerciseProgress", id, count, confidence)` (fire-and-forget, errors ignored); do not send a progress report for the count that triggers `onComplete`. Complete: `completeExerciseOccurrence(id, { achievedCount, confidence })`. On success replace the occurrence, close the session, `playSound("celebrate")`, show the celebration; call `stopAlarm()` only if no other `mine` occurrence remains, otherwise the overlay for the next one appears after 2 s. On 422 set `liveServerMessage` and keep the session open (the component retries). On 409 re-fetch today: if the occurrence is still inProgress retry once, otherwise close the session with a one-line reason. When the live occurrence becomes `missed` or `cancelled` (hub event or re-fetch), close the session and show "Time ran out" / "<Partner> let you off today".
- `HomeView` becomes goals-first: `AlarmReadinessCard` (until dismissed/complete), "Today's goals" cards for both partners with `goalTodayStatus`, next alarm countdown, streak cards for both partners from history stats, `PartnerLiveCard`, then the existing hero, quick access and love note. `GoalsEmptyState` when there are no active goals.
- `layout.tsx` metadata title "Twogether — couple goals"; link `public/manifest.webmanifest` (name, short_name, start_url "/", display "standalone", theme/background colours from the CSS tokens, an inline-SVG data-URI icon is acceptable).

### Styles

New classes in `globals.css`, mobile-first, same tokens. Alarm overlay uses a strong pulsing pink/amber animation; the live session fills the viewport on phones; the mobile nav keeps 5 columns.

## Verification

```powershell
dotnet build Twogether.slnx
dotnet test Twogether.slnx
cd frontend
npm.cmd run lint
npm.cmd run build
```

Unit tests to add (`tests/Twogether.UnitTests/ExerciseTests.cs`): schedule next-due across midnight; a spring-forward gap advances to the first valid minute; an ambiguous fall-back time resolves to the earlier instant and advancing the pointer from it does not yield the same local date twice; a goal created after today's time fires tomorrow; occurrence state machine (start resets progress, complete requires InProgress, miss/cancel, RecordProgress does not bump StateVersion while Start/Complete/Miss/Cancel do); verification rejects too-fast, low-confidence, under-target and not-started; streak counting ignores Cancelled and ends on an empty day; stats completion rate.
