using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

public sealed class CycleProfile : BaseEntity
{
    private CycleProfile() { }

    public Guid UserId { get; private set; }
    public int AverageCycleLengthDays { get; private set; } = 28;
    public int AveragePeriodLengthDays { get; private set; } = 5;
    public ShareLevel ShareLevelWithPartner { get; private set; } = ShareLevel.None;
    public DateOnly? LastPeriodStartDate { get; private set; }

    public static CycleProfile Create(Guid userId, DateTime utcNow) => new()
    {
        UserId = userId,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void SetShareLevel(ShareLevel level, DateTime utcNow)
    {
        ShareLevelWithPartner = level;
        UpdatedAtUtc = utcNow;
    }

    public void SetCycleSettings(int averageCycleLengthDays, int averagePeriodLengthDays, DateOnly? lastPeriodStartDate, DateTime utcNow)
    {
        AverageCycleLengthDays = averageCycleLengthDays;
        AveragePeriodLengthDays = averagePeriodLengthDays;
        LastPeriodStartDate = lastPeriodStartDate;
        UpdatedAtUtc = utcNow;
    }
}
