namespace Twogether.Application.Features.Dashboard;

public sealed record DashboardSnapshotDto(
    DateTime GeneratedAtUtc,
    string CurrentUserName,
    string PartnerName,
    bool PartnerOnline,
    int NotificationCount,
    int ConnectionStreakDays,
    string CycleSummary,
    int CycleDaysUntilPeriod,
    string HeroDescription,
    string LatestNote,
    string LatestNoteAuthor,
    IReadOnlyList<DashboardGameDto> Games,
    DashboardExerciseDto? Exercise = null,
    DashboardFoodDto? Food = null);

/// <summary>Exercise at a glance for both partners. Null on the snapshot when there is no active couple.</summary>
public sealed record DashboardExerciseDto(
    int MyStreakDays,
    int PartnerStreakDays,
    int MyActiveAlarms,
    int PartnerActiveAlarms,
    int MyCompletedToday,
    int PartnerCompletedToday,
    int ActiveGoals,
    DateTime? NextAlarmAtUtc,
    string? NextAlarmTitle,
    Guid? NextAlarmUserId);

/// <summary>Today's calories for me, and for my partner only when their share level allows it.</summary>
public sealed record DashboardFoodDto(
    string LocalDate,
    int CaloriesToday,
    int DailyCalories,
    int? PartnerCaloriesToday,
    int? PartnerDailyCalories);

public sealed record DashboardGameDto(
    string Id,
    string Title,
    string Subtitle,
    string Icon,
    string Tone);
