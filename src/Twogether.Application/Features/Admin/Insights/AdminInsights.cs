using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Billing;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Admin.Insights;

/// <summary>
/// The insight pages of the admin console (docs/admin/contract-v2.md): funnel, retention, rhythm, dares, games, growth,
/// world and top couples. Measured people only (<see cref="AdminScope"/>). Counts, timings, scores and country codes:
/// nothing here reads what couples wrote, photographed, logged, answered, or where they are. Every query is bounded by
/// its window and aggregated in the database where the providers allow; medians are taken in memory from narrow rows.
/// </summary>
public sealed partial class AdminInsights(IApplicationDbContext db, IDateTimeProvider clock, AdminSettings settings, BillingSettings billing)
{
    private readonly MeasuredScope measured = new(db, settings);

    private ActivityLog Activity => new(db, measured);

    /// <summary>Couples with Plus now, paid (the buyer's current couple) or given; measured couples only.</summary>
    private async Task<HashSet<Guid>> PlusCoupleIdsAsync(DateTime now, CancellationToken ct)
    {
        var coupleIds = measured.CoupleIds;
        var paying = await db.StoreSubscriptions
            .Where(item => (item.Status == StoreSubscriptionStatus.Active || item.Status == StoreSubscriptionStatus.Canceled || item.Status == StoreSubscriptionStatus.InGracePeriod) && item.ExpiresAtUtc > now)
            .Join(db.Users, subscription => subscription.UserId, user => user.Id, (subscription, user) => user.CoupleId)
            .Where(coupleId => coupleId != null && coupleIds.Contains(coupleId.Value))
            .Select(coupleId => coupleId!.Value)
            .Distinct().ToListAsync(ct);
        var granted = await db.PlusGrants.Where(item => item.RevokedAtUtc == null && item.ExpiresAtUtc > now && coupleIds.Contains(item.CoupleId))
            .Select(item => item.CoupleId).Distinct().ToListAsync(ct);
        return [.. paying, .. granted];
    }

    /// <summary>
    /// Current exercise streaks (<see cref="ExerciseStreak"/>) of the given people, from their occurrences since
    /// <paramref name="sinceDay"/>. "Today" is each person's own local date, from the time zone of their latest active
    /// goal (as the app does), else UTC. A streak can't be longer than the history read.
    /// </summary>
    private async Task<Dictionary<Guid, int>> StreaksAsync(IQueryable<Guid> users, DateOnly sinceDay, DateTime now, CancellationToken ct)
    {
        var occurrences = await db.ExerciseOccurrences.Where(item => item.LocalDate >= sinceDay && users.Contains(item.UserId))
            .Select(item => new { item.UserId, item.LocalDate, item.Status }).ToListAsync(ct);
        var zones = (await db.ExerciseGoals.Where(goal => goal.IsActive && users.Contains(goal.AssignedUserId))
                .Select(goal => new { goal.AssignedUserId, goal.TimeZoneId, goal.UpdatedAtUtc }).ToListAsync(ct))
            .GroupBy(goal => goal.AssignedUserId)
            .ToDictionary(group => group.Key, group => group.OrderByDescending(goal => goal.UpdatedAtUtc).First().TimeZoneId);
        var resolved = new Dictionary<string, TimeZoneInfo>();
        DateOnly TodayOf(Guid userId)
        {
            if (!zones.TryGetValue(userId, out var zoneId)) return DateOnly.FromDateTime(now);
            if (!resolved.TryGetValue(zoneId, out var zone)) resolved[zoneId] = zone = ExerciseSchedule.ResolveTimeZone(zoneId);
            return ExerciseSchedule.LocalDate(now, zone);
        }
        return occurrences.GroupBy(item => item.UserId).ToDictionary(
            group => group.Key,
            group => ExerciseStreak.CurrentStreakDays(group.Select(item => (item.LocalDate, item.Status)), TodayOf(group.Key)));
    }
}
