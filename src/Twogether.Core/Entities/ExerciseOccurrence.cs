using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// One day's alarm for one goal. Exists only while the alarm is ringing or once it has been
/// resolved; the worker never opens one for a time the server slept through.
/// </summary>
public sealed class ExerciseOccurrence : BaseEntity
{
    private ExerciseOccurrence() { }

    public const int MaxCallOffNoteLength = 140;

    public Guid GoalId { get; private set; }
    public Guid CoupleId { get; private set; }
    /// <summary>The assigned partner.</summary>
    public Guid UserId { get; private set; }
    public ExerciseType ExerciseType { get; private set; }
    public string Title { get; private set; } = string.Empty;
    public int Target { get; private set; }
    public DateOnly LocalDate { get; private set; }
    public DateTime ScheduledAtUtc { get; private set; }
    /// <summary>Scheduled time plus grace. An alarming occurrence is missed here.</summary>
    public DateTime DeadlineUtc { get; private set; }
    /// <summary>Set by Start. An in-progress occurrence is missed here.</summary>
    public DateTime? CompleteByUtc { get; private set; }
    public ExerciseOccurrenceStatus Status { get; private set; } = ExerciseOccurrenceStatus.Alarming;
    public DateTime AlarmStartedAtUtc { get; private set; }
    /// <summary>When the latest camera session began.</summary>
    public DateTime? StartedAtUtc { get; private set; }
    public DateTime? CompletedAtUtc { get; private set; }
    public int AchievedCount { get; private set; }
    public double Confidence { get; private set; }
    /// <summary>What the camera noticed in the session that completed it (see <see cref="ExerciseCheckCounts"/>).</summary>
    public int FormFaults { get; private set; }
    public int FastReps { get; private set; }
    public int ChallengesPassed { get; private set; }
    public int ChallengesMissed { get; private set; }
    public int PhoneMovedReps { get; private set; }
    public int OutOfFrameSeconds { get; private set; }
    public ExerciseIntegrity Integrity { get; private set; } = ExerciseIntegrity.NotChecked;

    public ExerciseCheckCounts Checks => new(FormFaults, FastReps, ChallengesPassed, ChallengesMissed, PhoneMovedReps, OutOfFrameSeconds);
    /// <summary>Concurrency token. Bumped by every status transition, never by progress.</summary>
    public long StateVersion { get; private set; }
    /// <summary>When the assigned partner asked to be let off today. Only the partner can grant it, by cancelling.</summary>
    public DateTime? CallOffRequestedAtUtc { get; private set; }
    /// <summary>Optional note with the request; trimmed, empty is null.</summary>
    public string? CallOffNote { get; private set; }

    public bool IsAlarmActive => Status is ExerciseOccurrenceStatus.Alarming or ExerciseOccurrenceStatus.InProgress;

    public static ExerciseOccurrence Open(ExerciseGoal goal, DateOnly localDate, DateTime scheduledAtUtc, DateTime utcNow) => new()
    {
        GoalId = goal.Id,
        CoupleId = goal.CoupleId,
        UserId = goal.AssignedUserId,
        ExerciseType = goal.ExerciseType,
        Title = goal.Title,
        Target = goal.Target,
        LocalDate = localDate,
        ScheduledAtUtc = scheduledAtUtc,
        DeadlineUtc = scheduledAtUtc.AddMinutes(goal.GraceMinutes),
        AlarmStartedAtUtc = utcNow,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    /// <summary>
    /// Opens today's occurrence before its alarm rings because the assigned partner is doing the
    /// dare early. It keeps the real due time (so the deadline is due + grace) and starts the
    /// camera session at once. The goal's pointer is not moved: if the partner gives up, the alarm
    /// still rings at the due time.
    /// </summary>
    public static ExerciseOccurrence OpenEarly(ExerciseGoal goal, DateTime utcNow)
    {
        if (!goal.IsDueLaterToday(utcNow)) throw new InvalidOperationException("Only an active goal that has not rung yet today can be done early.");
        var occurrence = Open(goal, goal.NextDueLocalDate(), goal.NextDueAtUtc, utcNow);
        occurrence.Start(utcNow);
        return occurrence;
    }

    /// <summary>
    /// Opens (or re-opens) the camera session. Progress always restarts from zero because the
    /// counter on the device does, and the plausibility check measures from this instant.
    /// </summary>
    public void Start(DateTime utcNow)
    {
        if (!IsAlarmActive) throw new InvalidOperationException("Only a ringing or in-progress exercise can be started.");
        Status = ExerciseOccurrenceStatus.InProgress;
        StartedAtUtc = utcNow;
        AchievedCount = 0;
        Confidence = 0;
        RecordChecks(default, ExerciseIntegrity.NotChecked);
        var allowance = utcNow + ExerciseVerification.CompletionAllowance;
        CompleteByUtc = allowance > DeadlineUtc ? allowance : DeadlineUtc;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>Advisory live count from the device. Never lowers the count and never bumps the version.</summary>
    public void RecordProgress(int achievedCount, double confidence, DateTime utcNow)
    {
        if (Status != ExerciseOccurrenceStatus.InProgress) throw new InvalidOperationException("Progress can only be recorded while the exercise is in progress.");
        AchievedCount = Math.Max(AchievedCount, achievedCount);
        Confidence = confidence;
        UpdatedAtUtc = utcNow;
    }

    public void Complete(int achievedCount, double confidence, DateTime utcNow, ExerciseCheckCounts checks = default, ExerciseIntegrity integrity = ExerciseIntegrity.NotChecked)
    {
        if (Status != ExerciseOccurrenceStatus.InProgress) throw new InvalidOperationException("Only an in-progress exercise can be completed.");
        Status = ExerciseOccurrenceStatus.Completed;
        AchievedCount = achievedCount;
        Confidence = confidence;
        RecordChecks(checks, integrity);
        CompletedAtUtc = utcNow;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    private void RecordChecks(ExerciseCheckCounts checks, ExerciseIntegrity integrity)
    {
        var clean = checks.Clamped();
        FormFaults = clean.FormFaults;
        FastReps = clean.FastReps;
        ChallengesPassed = clean.ChallengesPassed;
        ChallengesMissed = clean.ChallengesMissed;
        PhoneMovedReps = clean.PhoneMovedReps;
        OutOfFrameSeconds = clean.OutOfFrameSeconds;
        Integrity = integrity;
    }

    public void Miss(DateTime utcNow)
    {
        if (!IsAlarmActive) throw new InvalidOperationException("Only a ringing or in-progress exercise can be missed.");
        Status = ExerciseOccurrenceStatus.Missed;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    public void Cancel(DateTime utcNow)
    {
        if (!IsAlarmActive) throw new InvalidOperationException("Only a ringing or in-progress exercise can be cancelled.");
        Status = ExerciseOccurrenceStatus.Cancelled;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>
    /// The assigned partner asks to be let off today. Advisory, like progress: it does not bump
    /// the version, so it cannot make a completion or the partner's cancel lose a race.
    /// </summary>
    public void RequestCallOff(string? note, DateTime utcNow)
    {
        if (!IsAlarmActive) throw new InvalidOperationException("Only a ringing or in-progress exercise can be called off.");
        if (CallOffRequestedAtUtc.HasValue) throw new InvalidOperationException("Calling off today has already been requested.");
        var clean = NormalizeCallOffNote(note);
        if (clean is not null && clean.Length > MaxCallOffNoteLength)
            throw new ArgumentException($"The note must be at most {MaxCallOffNoteLength} characters.", nameof(note));
        CallOffRequestedAtUtc = utcNow;
        CallOffNote = clean;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>Trimmed; empty or whitespace becomes null.</summary>
    public static string? NormalizeCallOffNote(string? note) => string.IsNullOrWhiteSpace(note) ? null : note.Trim();
}
