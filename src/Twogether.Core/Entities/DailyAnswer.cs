using Twogether.Core.Seedwork;
using Twogether.Shared.Guards;

namespace Twogether.Core.Entities;

public sealed class DailyAnswer : BaseEntity
{
    public const int MaxBodyLength = 1000;

    private DailyAnswer() { }

    public Guid DailyQuestionId { get; private set; }
    public Guid CoupleId { get; private set; }
    public Guid UserId { get; private set; }
    public string Body { get; private set; } = string.Empty;

    public static DailyAnswer Create(Guid dailyQuestionId, Guid coupleId, Guid userId, string body, DateTime utcNow)
    {
        var answer = new DailyAnswer { DailyQuestionId = dailyQuestionId, CoupleId = coupleId, UserId = userId, CreatedAtUtc = utcNow };
        answer.Edit(body, utcNow);
        return answer;
    }

    public void Edit(string body, DateTime utcNow)
    {
        var clean = Guard.NotNullOrWhiteSpace(body, nameof(body));
        if (clean.Length > MaxBodyLength) throw new ArgumentException("Answer is too long.", nameof(body));
        Body = clean;
        UpdatedAtUtc = utcNow;
    }
}
