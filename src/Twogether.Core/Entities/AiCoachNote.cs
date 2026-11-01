using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// The coach's short note on one finished dare, written once and kept: the person who did it and their partner both
/// read this row. One row per occurrence (a unique index), so two phones asking at once can't both pay for it: the one
/// that inserts the row writes the note, the other is told it is on its way.
/// </summary>
public sealed class AiCoachNote : BaseEntity
{
    private AiCoachNote() { }

    public const int MaxTextLength = 400;
    public const int MaxAttempts = 3;
    /// <summary>A claim older than this was abandoned (the request died mid-call), so another may take it.</summary>
    public static readonly TimeSpan ClaimLease = TimeSpan.FromMinutes(2);
    /// <summary>How long a failed note waits before it may be tried again.</summary>
    public static readonly TimeSpan RetryAfter = TimeSpan.FromSeconds(30);

    public Guid OccurrenceId { get; private set; }
    public Guid CoupleId { get; private set; }
    /// <summary>Who did the dare, and so whom the note speaks to.</summary>
    public Guid UserId { get; private set; }
    /// <summary>The language it is written in: the person who did the dare's.</summary>
    public string Language { get; private set; } = "en";
    public AiNoteState State { get; private set; } = AiNoteState.Pending;
    public string? Text { get; private set; }
    public int Attempts { get; private set; }
    public DateTime ClaimedAtUtc { get; private set; }

    public static AiCoachNote Claim(Guid occurrenceId, Guid coupleId, Guid userId, string language, DateTime utcNow) => new()
    {
        OccurrenceId = occurrenceId,
        CoupleId = coupleId,
        UserId = userId,
        Language = string.IsNullOrWhiteSpace(language) ? "en" : language.Trim(),
        State = AiNoteState.Pending,
        Attempts = 1,
        ClaimedAtUtc = utcNow,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    /// <summary>Whether a new attempt may start now: a failure that has waited, or a claim nobody finished.</summary>
    public bool CanRetryAt(DateTime utcNow) => Attempts < MaxAttempts && State switch
    {
        AiNoteState.Failed => utcNow - UpdatedAtUtc >= RetryAfter,
        AiNoteState.Pending => utcNow - ClaimedAtUtc >= ClaimLease,
        _ => false
    };

    /// <summary>Given up for good: every attempt failed.</summary>
    public bool IsExhausted => State == AiNoteState.Failed && Attempts >= MaxAttempts;

    public void Complete(string text, DateTime utcNow)
    {
        if (State != AiNoteState.Pending) throw new InvalidOperationException("Only a claimed note can be written.");
        var clean = text.Trim();
        if (clean.Length == 0 || clean.Length > MaxTextLength) throw new ArgumentException("A note is 1 to 400 characters.", nameof(text));
        Text = clean;
        State = AiNoteState.Ready;
        UpdatedAtUtc = utcNow;
    }

    public void Fail(DateTime utcNow)
    {
        if (State != AiNoteState.Pending) throw new InvalidOperationException("Only a claimed note can fail.");
        State = AiNoteState.Failed;
        UpdatedAtUtc = utcNow;
    }
}
