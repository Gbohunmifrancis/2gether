using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Core.Notifications;

/// <summary>Pure rules deciding how, or whether, a notification reaches the phone.</summary>
public static class NotificationRouting
{
    public static NotificationCategory CategoryOf(NotificationType type) => type switch
    {
        NotificationType.CoupleConnected or NotificationType.Breakup => NotificationCategory.Couple,
        NotificationType.ExerciseDue => NotificationCategory.Alarm,
        NotificationType.Message => NotificationCategory.Messages,
        NotificationType.GameInvite or NotificationType.GameStarted or NotificationType.GameEnded or NotificationType.GameTurn => NotificationCategory.Games,
        NotificationType.ExerciseCompleted or NotificationType.ExerciseMissed or NotificationType.ExerciseCancelled or NotificationType.ExerciseGoalChanged
            or NotificationType.ExerciseCallOffAsked => NotificationCategory.Exercise,
        NotificationType.CycleUpdated => NotificationCategory.Cycle,
        NotificationType.PregnancyUpdated => NotificationCategory.Pregnancy,
        NotificationType.DailyQuestionAnswered => NotificationCategory.DailyQuestions,
        NotificationType.MemoriesSynced or NotificationType.AlbumSyncReminder => NotificationCategory.Album,
        // A new type must be placed deliberately; a test walks every value.
        _ => throw new ArgumentOutOfRangeException(nameof(type), type, "Notification type has no category.")
    };

    public static bool CanMute(NotificationCategory category) => category is not (NotificationCategory.Couple or NotificationCategory.Alarm);

    /// <summary>
    /// The alarm always rings. Otherwise a switched-off category is not pushed, and quiet hours
    /// in the person's own time zone deliver silently instead of holding the push back, so it is
    /// waiting in the shade in the morning rather than expiring overnight.
    /// </summary>
    public static PushRoute Route(NotificationType type, NotificationPreference? preference, string? timeZoneId, DateTime utcNow)
    {
        var category = CategoryOf(type);
        if (category == NotificationCategory.Alarm || preference is null) return PushRoute.Normal;
        if (CanMute(category) && !preference.Allows(category)) return PushRoute.Muted;
        return preference.IsQuietAt(LocalTime(utcNow, timeZoneId)) ? PushRoute.Quiet : PushRoute.Normal;
    }

    /// <summary>Start inclusive, end exclusive; 22:00 to 07:00 runs across midnight.</summary>
    public static bool IsWithin(TimeOnly time, TimeOnly start, TimeOnly end)
        => start < end ? time >= start && time < end : time >= start || time < end;

    public static TimeOnly LocalTime(DateTime utcNow, string? timeZoneId)
        => TimeOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(utcNow, DateTimeKind.Utc), ExerciseSchedule.ResolveTimeZone(timeZoneId)));
}
