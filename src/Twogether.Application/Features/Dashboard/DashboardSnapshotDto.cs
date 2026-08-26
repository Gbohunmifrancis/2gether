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
    IReadOnlyList<DashboardGameDto> Games);

public sealed record DashboardGameDto(
    string Id,
    string Title,
    string Subtitle,
    string Icon,
    string Tone);
