using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>The question a couple got on one local day. Created lazily on the first read.</summary>
public sealed class DailyQuestion : BaseEntity
{
    public const int MaxKeyLength = 40;

    private DailyQuestion() { }

    public Guid CoupleId { get; private set; }
    public DateOnly LocalDate { get; private set; }
    public string QuestionKey { get; private set; } = string.Empty;

    public static DailyQuestion Create(Guid coupleId, DateOnly localDate, string questionKey, DateTime utcNow)
    {
        if (string.IsNullOrWhiteSpace(questionKey) || questionKey.Length > MaxKeyLength) throw new ArgumentException("Invalid question key.", nameof(questionKey));
        return new DailyQuestion { CoupleId = coupleId, LocalDate = localDate, QuestionKey = questionKey, CreatedAtUtc = utcNow, UpdatedAtUtc = utcNow };
    }
}
