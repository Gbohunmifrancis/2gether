namespace Twogether.Core.Enums;

public enum NotificationType
{
    Message,
    GameInvite,
    GameStarted,
    GameEnded,
    CoupleConnected,
    Breakup,
    CycleUpdated,
    ExerciseDue,
    ExerciseCompleted,
    ExerciseMissed,
    ExerciseCancelled,
    ExerciseGoalChanged,
    PregnancyUpdated,
    DailyQuestionAnswered,
    /// <summary>To the partner, once per finished sync: "Maya added 12 photos to your album".</summary>
    MemoriesSynced,
    /// <summary>To the person with photos waiting on their phone; at most once a day.</summary>
    AlbumSyncReminder,
    /// <summary>To the partner: the person whose alarm is ringing asks to be let off today.</summary>
    ExerciseCallOffAsked,
    /// <summary>To the partner whose turn it now is in a turn-by-turn game, when they are not in the app; not again until read.</summary>
    GameTurn,
    /// <summary>To both: a dare past the free plan's limit was paused as it came due (Plus had ended).</summary>
    ExercisePausedByPlan,
    /// <summary>To both: the shared album passed 80% or 95% of the plan's room.</summary>
    AlbumAlmostFull,
    /// <summary>To both: Plus ends in a few days and won't renew (cancelled, or a complimentary grant running out).</summary>
    PlusEnding,
    /// <summary>To both, once: Plus newly started for the couple (a first purchase, or a gift from Heartpact). Never for a renewal.</summary>
    PlusStarted,
    /// <summary>To the partner: the other started sharing their location (not a resume or heartbeat); at most once per 12 hours.</summary>
    LocationShared,
    /// <summary>To the partner: the cycle owner went from sharing nothing to sharing something with them.</summary>
    CycleShared,
    /// <summary>To both, Sunday evening in the couple's time zone: the week's recap is ready (docs/ai.md). Once per couple per week.</summary>
    WeeklyRecap,
    /// <summary>To the partner, once per dare: the camera started counting ("Maya started their squats"); opens the live view.</summary>
    ExerciseStarted
}
