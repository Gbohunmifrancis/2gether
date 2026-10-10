using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Core.Seedwork;
using Twogether.Shared.Guards;

namespace Twogether.Core.Entities;

/// <summary>
/// A recurring daily exercise alarm for one partner. The server keeps NextDueAtUtc as the
/// alarm pointer; the worker opens an occurrence when it passes and advances it.
/// </summary>
public sealed class ExerciseGoal : BaseEntity
{
    public const int MaxTitleLength = 80;
    public const int MaxTimeZoneIdLength = 100;
    public const int MinGraceMinutes = 5;
    public const int MaxGraceMinutes = 180;
    public const int DefaultGraceMinutes = 30;

    private ExerciseGoal() { }

    public Guid CoupleId { get; private set; }
    public Guid AssignedUserId { get; private set; }
    public Guid CreatedByUserId { get; private set; }
    public ExerciseType ExerciseType { get; private set; }
    public string Title { get; private set; } = string.Empty;
    /// <summary>Reps or seconds, by the exercise's measure.</summary>
    public int Target { get; private set; }
    /// <summary>Local wall clock in <see cref="TimeZoneId"/>.</summary>
    public TimeOnly ScheduledTime { get; private set; }
    public string TimeZoneId { get; private set; } = "UTC";
    /// <summary>How long the alarm rings before the day counts as missed.</summary>
    public int GraceMinutes { get; private set; } = DefaultGraceMinutes;
    public bool IsActive { get; private set; } = true;
    public DateTime NextDueAtUtc { get; private set; }

    public static ExerciseGoal Create(Guid coupleId, Guid assignedUserId, Guid createdByUserId, ExerciseType type, string? title, int target, TimeOnly scheduledTime, string timeZoneId, int graceMinutes, DateTime utcNow)
    {
        var goal = new ExerciseGoal
        {
            CoupleId = coupleId,
            CreatedByUserId = createdByUserId,
            CreatedAtUtc = utcNow,
            UpdatedAtUtc = utcNow
        };
        goal.Apply(assignedUserId, type, title, target, scheduledTime, timeZoneId, graceMinutes, utcNow);
        return goal;
    }

    /// <summary>Replaces every editable field and recomputes the alarm pointer from now.</summary>
    public void Update(Guid assignedUserId, ExerciseType type, string? title, int target, TimeOnly scheduledTime, string timeZoneId, int graceMinutes, DateTime utcNow)
        => Apply(assignedUserId, type, title, target, scheduledTime, timeZoneId, graceMinutes, utcNow);

    public void Deactivate(DateTime utcNow)
    {
        IsActive = false;
        UpdatedAtUtc = utcNow;
    }

    public void Reactivate(DateTime utcNow)
    {
        IsActive = true;
        NextDueAtUtc = ExerciseSchedule.NextDueUtc(ScheduledTime, ExerciseSchedule.ResolveTimeZone(TimeZoneId), utcNow);
        UpdatedAtUtc = utcNow;
    }

    public void AdvanceNextDue(DateTime nextDueAtUtc, DateTime utcNow)
    {
        if (nextDueAtUtc <= NextDueAtUtc) throw new ArgumentException("The alarm pointer only moves forward.", nameof(nextDueAtUtc));
        NextDueAtUtc = nextDueAtUtc;
        UpdatedAtUtc = utcNow;
    }

    private void Apply(Guid assignedUserId, ExerciseType type, string? title, int target, TimeOnly scheduledTime, string timeZoneId, int graceMinutes, DateTime utcNow)
    {
        var definition = ExerciseCatalog.Get(type);
        if (assignedUserId == Guid.Empty) throw new ArgumentException("An assignee is required.", nameof(assignedUserId));
        if (target < definition.MinTarget || target > definition.MaxTarget)
            throw new ArgumentOutOfRangeException(nameof(target), target, $"Target must be between {definition.MinTarget} and {definition.MaxTarget}.");
        if (graceMinutes is < MinGraceMinutes or > MaxGraceMinutes)
            throw new ArgumentOutOfRangeException(nameof(graceMinutes), graceMinutes, $"Grace must be between {MinGraceMinutes} and {MaxGraceMinutes} minutes.");
        var zoneId = Guard.NotNullOrWhiteSpace(timeZoneId, nameof(timeZoneId));
        if (zoneId.Length > MaxTimeZoneIdLength) throw new ArgumentException("Time zone id is too long.", nameof(timeZoneId));
        var cleanTitle = string.IsNullOrWhiteSpace(title) ? definition.DisplayName : title.Trim();
        if (cleanTitle.Length > MaxTitleLength) throw new ArgumentException("Title is too long.", nameof(title));

        AssignedUserId = assignedUserId;
        ExerciseType = type;
        Title = cleanTitle;
        Target = target;
        ScheduledTime = new TimeOnly(scheduledTime.Hour, scheduledTime.Minute);
        TimeZoneId = zoneId;
        GraceMinutes = graceMinutes;
        NextDueAtUtc = ExerciseSchedule.NextDueUtc(ScheduledTime, ExerciseSchedule.ResolveTimeZone(zoneId), utcNow);
        UpdatedAtUtc = utcNow;
    }
}
