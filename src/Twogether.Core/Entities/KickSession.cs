using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// One kick-counting session: start, tap per movement, stop. The client sends the absolute
/// count, so a retried request can never double-count.
/// </summary>
public sealed class KickSession : BaseEntity
{
    public const int MaxCount = 200;
    public static readonly TimeSpan MaxDuration = TimeSpan.FromHours(3);

    private KickSession() { }

    public Guid PregnancyId { get; private set; }
    public DateTime StartedAtUtc { get; private set; }
    public DateTime? EndedAtUtc { get; private set; }
    public int Count { get; private set; }

    public bool IsOpen => EndedAtUtc is null;

    public static KickSession Start(Guid pregnancyId, DateTime utcNow) => new()
    {
        PregnancyId = pregnancyId,
        StartedAtUtc = utcNow,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Record(int count, bool end, DateTime utcNow)
    {
        if (!IsOpen) throw new InvalidOperationException("This session has already ended.");
        if (count is < 0 or > MaxCount) throw new ArgumentOutOfRangeException(nameof(count), count, $"Count must be between 0 and {MaxCount}.");
        // A late retry carrying an older, lower count is harmless: the count only goes up.
        Count = Math.Max(Count, count);
        // A session left open (app closed mid-count) is capped instead of running for days.
        var cap = StartedAtUtc + MaxDuration;
        if (end || utcNow >= cap) EndedAtUtc = utcNow < cap ? utcNow : cap;
        UpdatedAtUtc = utcNow;
    }
}
