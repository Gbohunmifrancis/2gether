namespace Twogether.Application.Features.Admin.Live;

public enum LiveKind { SignedUp, CoupleLinked, CoupleEnded, DareCompleted, DareMissed, GameFinished, PlusStarted, PlusEnded, AccountDeleted, Alert }

public enum LiveSeverity { Info, Good, Warn, Bad }

/// <summary>
/// One thing that just happened, for the admin live feed. Account metadata only: who (display name),
/// and at most an exercise, game type, product id, country code or alert text. Never content.
/// </summary>
/// <param name="Id">Stable across polls, for de-duplication: "{kind}:{row id}".</param>
public sealed record LiveEvent(
    string Id,
    LiveKind Kind,
    DateTime AtUtc,
    Guid? UserId,
    string? DisplayName,
    string? PartnerName,
    string? Detail,
    LiveSeverity Severity);

public sealed record PulseDto(
    DateTime NowUtc,
    int OnlineNow,
    int ActiveToday,
    int RingingNow,
    int OnCameraNow,
    int OpenGames,
    int HeartsToday,
    int SignupsToday,
    int CouplesLinkedToday,
    int PlusStartedToday,
    IReadOnlyList<LiveEvent> Recent);
