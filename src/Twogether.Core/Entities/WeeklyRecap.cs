using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// A couple's week, counted and written up once: one row per couple per week (a unique index on the couple and the
/// Sunday the week ends), claimed before anything is counted or sent, so two API instances never write or push it
/// twice. The counts are kept with the words, which are one short recap per partner in their own language.
/// </summary>
public sealed class WeeklyRecap : BaseEntity
{
    private WeeklyRecap() { }

    public const int MaxAttempts = 3;
    /// <summary>A claim older than this was abandoned (the instance stopped mid-run), so another may take it.</summary>
    public static readonly TimeSpan ClaimLease = TimeSpan.FromMinutes(10);
    public static readonly TimeSpan RetryAfter = TimeSpan.FromMinutes(10);

    public Guid CoupleId { get; private set; }
    /// <summary>The local Sunday the week ends on (at 18:00 in <see cref="TimeZoneId"/>).</summary>
    public DateOnly WeekEnding { get; private set; }
    public string TimeZoneId { get; private set; } = "UTC";
    public DateTime PeriodStartUtc { get; private set; }
    public DateTime PeriodEndUtc { get; private set; }
    public AiNoteState State { get; private set; } = AiNoteState.Pending;
    public int Attempts { get; private set; }
    public DateTime ClaimedAtUtc { get; private set; }
    /// <summary>The week's counts (WeeklyRecapStats as JSON).</summary>
    public string StatsJson { get; private set; } = "{}";
    /// <summary>One recap per partner (WeeklyRecapNote list as JSON), each in that partner's language.</summary>
    public string NotesJson { get; private set; } = "[]";

    public static WeeklyRecap Claim(Guid coupleId, DateOnly weekEnding, string timeZoneId, DateTime periodStartUtc, DateTime periodEndUtc, DateTime utcNow) => new()
    {
        CoupleId = coupleId,
        WeekEnding = weekEnding,
        TimeZoneId = timeZoneId,
        PeriodStartUtc = periodStartUtc,
        PeriodEndUtc = periodEndUtc,
        State = AiNoteState.Pending,
        Attempts = 1,
        ClaimedAtUtc = utcNow,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public bool CanRetryAt(DateTime utcNow) => Attempts < MaxAttempts && State switch
    {
        AiNoteState.Failed => utcNow - UpdatedAtUtc >= RetryAfter,
        AiNoteState.Pending => utcNow - ClaimedAtUtc >= ClaimLease,
        _ => false
    };

    public void Skip(string statsJson, DateTime utcNow)
    {
        if (State != AiNoteState.Pending) throw new InvalidOperationException("Only a claimed recap can be skipped.");
        StatsJson = statsJson;
        State = AiNoteState.Skipped;
        UpdatedAtUtc = utcNow;
    }

    public void Complete(string statsJson, string notesJson, DateTime utcNow)
    {
        if (State != AiNoteState.Pending) throw new InvalidOperationException("Only a claimed recap can be written.");
        StatsJson = statsJson;
        NotesJson = notesJson;
        State = AiNoteState.Ready;
        UpdatedAtUtc = utcNow;
    }

    public void Fail(DateTime utcNow)
    {
        if (State != AiNoteState.Pending) throw new InvalidOperationException("Only a claimed recap can fail.");
        State = AiNoteState.Failed;
        UpdatedAtUtc = utcNow;
    }
}
