using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Cycle;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Cycle;

/// <summary>Where today sits in someone's cycle, with the last period start it counts from (logged flow, else the profile).</summary>
public sealed record CycleOutlook(CycleForecast Forecast, DateOnly LastPeriodStart);

public static class CycleSharing
{
    /// <summary>Flow logged longer ago than this no longer moves the last period start; the profile's date stands.</summary>
    public const int LookBackDays = 400;

    /// <summary>
    /// A share level is chosen for one partner. When a couple ends, or a new one links, it goes back to nothing, so
    /// the next partner never inherits what the last one was shown. Tracked: the caller's SaveChanges stores it.
    /// </summary>
    public static async Task StopSharingAsync(IApplicationDbContext db, IReadOnlyCollection<Guid> userIds, DateTime utcNow, CancellationToken cancellationToken)
    {
        var profiles = await db.CycleProfiles.Where(item => userIds.Contains(item.UserId) && item.ShareLevelWithPartner != ShareLevel.None).ToListAsync(cancellationToken);
        foreach (var profile in profiles) profile.SetShareLevel(ShareLevel.None, utcNow);
    }

    /// <summary>A partner sees logs from the day the couple linked, never what came before them.</summary>
    public static DateOnly SharedLogsSince(Core.Entities.Couple couple) => DateOnly.FromDateTime(couple.LinkedAtUtc ?? couple.CreatedAtUtc);

    /// <summary>
    /// The forecast for one person, anchored on the last period their logs show. With <paramref name="mayShowLate"/> off,
    /// a missed period rolls forward as before: for a partner's view that must not hint at a private pregnancy.
    /// </summary>
    public static async Task<CycleOutlook?> OutlookAsync(IApplicationDbContext db, CycleProfile profile, DateOnly today, bool mayShowLate, CancellationToken cancellationToken)
    {
        var since = today.AddDays(-LookBackDays);
        var flowDays = await db.CycleDayLogs.AsNoTracking()
            .Where(item => item.UserId == profile.UserId && item.LogDate >= since && item.LogDate <= today
                && (item.FlowLevel == FlowLevel.Light || item.FlowLevel == FlowLevel.Medium || item.FlowLevel == FlowLevel.Heavy))
            .Select(item => item.LogDate)
            .ToListAsync(cancellationToken);
        return Outlook(profile, flowDays, today, mayShowLate);
    }

    /// <summary>The legacy home card's line and days until the next period: no number when there is nothing to count from.</summary>
    public static (string Summary, int? DaysUntilPeriod) Summary(CycleForecast? forecast, bool pregnant, DateOnly today)
    {
        if (pregnant) return (Loc.T("Cycle predictions paused"), null);
        if (forecast is null) return (Loc.T("No cycle tracked yet"), null);
        if (forecast.DaysLate is { } late)
            return (late == 0 ? Loc.T("Period expected today") : Loc.Plural(late, "Period late by {count} day", "Period late by {count} days"), 0);
        var days = forecast.NextPeriodStart.DayNumber - today.DayNumber;
        return (Loc.Plural(days, "{count} day until period", "{count} days until period"), days);
    }

    public static CycleOutlook? Outlook(CycleProfile profile, IReadOnlyCollection<DateOnly> flowDays, DateOnly today, bool mayShowLate)
    {
        var cycle = profile.AverageCycleLengthDays;
        if (CyclePeriods.LastStart(profile.LastPeriodStartDate, flowDays, cycle, profile.AveragePeriodLengthDays, today) is not { } start) return null;
        // Lateness only means something for someone who logs their periods: flow logged this cycle or the one before.
        var logsFlow = mayShowLate && flowDays.Any(day => day >= start.AddDays(-cycle));
        return new CycleOutlook(CyclePrediction.Predict(start, cycle, profile.AveragePeriodLengthDays, today, logsFlow), start);
    }
}
