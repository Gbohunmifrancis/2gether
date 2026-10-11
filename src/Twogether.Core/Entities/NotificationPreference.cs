using Twogether.Core.Enums;
using Twogether.Core.Notifications;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>One row per user, created the first time they change anything. No row means the defaults.</summary>
public sealed class NotificationPreference : BaseEntity
{
    public static readonly TimeOnly DefaultQuietStart = new(22, 0);
    public static readonly TimeOnly DefaultQuietEnd = new(7, 0);

    private NotificationPreference() { }

    public Guid UserId { get; private set; }
    public bool MessagesEnabled { get; private set; } = true;
    public bool GamesEnabled { get; private set; } = true;
    public bool ExerciseEnabled { get; private set; } = true;
    public bool CycleEnabled { get; private set; } = true;
    public bool PregnancyEnabled { get; private set; } = true;
    public bool DailyQuestionsEnabled { get; private set; } = true;
    public bool QuietHoursEnabled { get; private set; }
    public TimeOnly QuietStartLocal { get; private set; } = DefaultQuietStart;
    public TimeOnly QuietEndLocal { get; private set; } = DefaultQuietEnd;

    public static NotificationPreference Create(Guid userId, DateTime utcNow) => new()
    {
        UserId = userId,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Update(
        bool messages, bool games, bool exercise, bool cycle, bool pregnancy, bool dailyQuestions,
        bool quietHoursEnabled, TimeOnly quietStartLocal, TimeOnly quietEndLocal, DateTime utcNow)
    {
        if (quietStartLocal == quietEndLocal) throw new ArgumentException("Quiet hours must start and end at different times.");
        MessagesEnabled = messages;
        GamesEnabled = games;
        ExerciseEnabled = exercise;
        CycleEnabled = cycle;
        PregnancyEnabled = pregnancy;
        DailyQuestionsEnabled = dailyQuestions;
        QuietHoursEnabled = quietHoursEnabled;
        QuietStartLocal = quietStartLocal;
        QuietEndLocal = quietEndLocal;
        UpdatedAtUtc = utcNow;
    }

    public bool Allows(NotificationCategory category) => category switch
    {
        NotificationCategory.Messages => MessagesEnabled,
        NotificationCategory.Games => GamesEnabled,
        NotificationCategory.Exercise => ExerciseEnabled,
        NotificationCategory.Cycle => CycleEnabled,
        NotificationCategory.Pregnancy => PregnancyEnabled,
        NotificationCategory.DailyQuestions => DailyQuestionsEnabled,
        _ => true
    };

    public bool IsQuietAt(TimeOnly localTime) => QuietHoursEnabled && NotificationRouting.IsWithin(localTime, QuietStartLocal, QuietEndLocal);
}
