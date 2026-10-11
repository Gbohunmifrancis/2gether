namespace Twogether.Core.Enums;

/// <summary>What a person can switch off. Couple and Alarm cannot be switched off.</summary>
public enum NotificationCategory
{
    /// <summary>Connected, ended. Always pushed: they change what the app can do.</summary>
    Couple,
    /// <summary>The exercise alarm itself. Never muted or quieted: both partners agreed to it.</summary>
    Alarm,
    Messages,
    Games,
    /// <summary>Goal set or changed, done, missed, cancelled.</summary>
    Exercise,
    Cycle,
    Pregnancy,
    DailyQuestions
}
