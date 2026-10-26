using Microsoft.EntityFrameworkCore;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Insights;

public sealed partial class AdminInsights
{
    public static readonly IReadOnlyList<string> FunnelKeys = ["signedUp", "invitedOrJoined", "linked", "firstDareSet", "firstDareDone", "firstHeart", "activeWeek2", "plus"];

    /// <summary>
    /// People who signed up in the window and how far they got, in order: a person counts at a step only when they
    /// reached it and every step before it. The dare steps are their couple's (set by either, done by either), since a
    /// dare is something two people do; firstHeart is when both partners have completed a dare the other set. The median
    /// is over the people at the step, of the hours since they reached the one before (never negative).
    /// </summary>
    public async Task<FunnelDto> FunnelAsync(int days, CancellationToken ct)
    {
        days = InsightsMath.ClampDays(days);
        var now = clock.UtcNow;
        var fromUtc = InsightsMath.WindowStart(now, days);
        var cohortQuery = measured.Users.Where(user => user.CreatedAtUtc >= fromUtc);
        var cohortIds = cohortQuery.Select(user => user.Id);
        var cohort = await cohortQuery.Select(user => new { user.Id, user.CreatedAtUtc }).ToListAsync(ct);

        var invited = await db.PartnerInvites.Where(item => cohortIds.Contains(item.InviterUserId))
            .GroupBy(item => item.InviterUserId).Select(group => new { group.Key, At = group.Min(item => item.CreatedAtUtc) })
            .ToDictionaryAsync(item => item.Key, item => item.At, ct);
        var couplesQuery = measured.Couples.Where(couple => cohortIds.Contains(couple.UserAId) || (couple.UserBId != null && cohortIds.Contains(couple.UserBId.Value)));
        var coupleIds = couplesQuery.Select(couple => couple.Id);
        var couples = await couplesQuery.Select(couple => new { couple.Id, couple.UserAId, couple.UserBId, couple.LinkedAtUtc }).ToListAsync(ct);
        var firstDareSet = await db.ExerciseGoals.Where(goal => coupleIds.Contains(goal.CoupleId))
            .GroupBy(goal => goal.CoupleId).Select(group => new { group.Key, At = group.Min(goal => goal.CreatedAtUtc) })
            .ToDictionaryAsync(item => item.Key, item => item.At, ct);
        var completions = db.ExerciseOccurrences.Where(item => item.Status == ExerciseOccurrenceStatus.Completed && item.CompletedAtUtc != null && coupleIds.Contains(item.CoupleId));
        var firstDareDone = await completions
            .GroupBy(item => item.CoupleId).Select(group => new { group.Key, At = group.Min(item => item.CompletedAtUtc!.Value) })
            .ToDictionaryAsync(item => item.Key, item => item.At, ct);
        // Each partner's first completion of a dare the other one set.
        var firstFromPartner = await completions
            .Join(db.ExerciseGoals, item => item.GoalId, goal => goal.Id, (item, goal) => new { item.CoupleId, item.UserId, item.CompletedAtUtc, goal.CreatedByUserId })
            .Where(item => item.CreatedByUserId != item.UserId)
            .GroupBy(item => new { item.CoupleId, item.UserId }).Select(group => new { group.Key.CoupleId, group.Key.UserId, At = group.Min(item => item.CompletedAtUtc!.Value) })
            .ToListAsync(ct);
        var subscriptions = await db.StoreSubscriptions
            .Where(item => item.Status != StoreSubscriptionStatus.Pending && item.Status != StoreSubscriptionStatus.PendingPurchaseCanceled
                && (cohortIds.Contains(item.UserId) || couplesQuery.Any(couple => couple.UserAId == item.UserId || couple.UserBId == item.UserId)))
            .GroupBy(item => item.UserId).Select(group => new { group.Key, At = group.Min(item => item.CreatedAtUtc) })
            .ToDictionaryAsync(item => item.Key, item => item.At, ct);
        var grants = await db.PlusGrants.Where(item => coupleIds.Contains(item.CoupleId))
            .GroupBy(item => item.CoupleId).Select(group => new { group.Key, At = group.Min(item => item.CreatedAtUtc) })
            .ToDictionaryAsync(item => item.Key, item => item.At, ct);
        var active = await Activity.ActiveDaysAsync(DateOnly.FromDateTime(fromUtc), DateOnly.FromDateTime(now), ct, cohortIds);

        var hearts = firstFromPartner.GroupBy(item => item.CoupleId).ToDictionary(group => group.Key, group => group.ToDictionary(item => item.UserId, item => item.At));
        var couplesOf = couples.SelectMany(couple => new[] { (UserId: couple.UserAId, Couple: couple), (UserId: couple.UserBId ?? Guid.Empty, Couple: couple) })
            .Where(item => item.UserId != Guid.Empty).ToLookup(item => item.UserId, item => item.Couple);

        static DateTime? Earliest(IEnumerable<DateTime?> times) => times.Min();
        static DateTime? Latest(DateTime? first, DateTime? second) => first is null || second is null ? null : first > second ? first : second;

        var reached = FunnelKeys.Select(_ => 0).ToArray();
        var gaps = FunnelKeys.Select(_ => new List<double>()).ToArray();
        foreach (var person in cohort)
        {
            var mine = couplesOf[person.Id].ToList();
            var linkedCouples = mine.Where(couple => couple.LinkedAtUtc != null && couple.UserBId != null).ToList();
            var signupDay = DateOnly.FromDateTime(person.CreatedAtUtc);
            var week2 = Enumerable.Range(7, 7).Select(offset => signupDay.AddDays(offset)).FirstOrDefault(day => active.Contains((person.Id, day)));
            var times = new DateTime?[]
            {
                person.CreatedAtUtc,
                Earliest([invited.TryGetValue(person.Id, out var invitedAt) ? invitedAt : null, .. mine.Where(couple => couple.UserBId == person.Id).Select(couple => couple.LinkedAtUtc)]),
                Earliest(linkedCouples.Select(couple => couple.LinkedAtUtc)),
                Earliest(linkedCouples.Select(couple => firstDareSet.TryGetValue(couple.Id, out var at) ? at : (DateTime?)null)),
                Earliest(linkedCouples.Select(couple => firstDareDone.TryGetValue(couple.Id, out var at) ? at : (DateTime?)null)),
                Earliest(linkedCouples.Select(couple => hearts.TryGetValue(couple.Id, out var byUser)
                    ? Latest(byUser.TryGetValue(couple.UserAId, out var a) ? a : null, byUser.TryGetValue(couple.UserBId!.Value, out var b) ? b : null)
                    : null)),
                week2 == default ? null : InsightsMath.Utc(week2),
                Earliest([
                    subscriptions.TryGetValue(person.Id, out var boughtAt) ? boughtAt : null,
                    // A partner's subscription, or a grant, gives the couple Plus from the day they linked.
                    .. linkedCouples.Select(couple => subscriptions.TryGetValue(couple.UserAId == person.Id ? couple.UserBId!.Value : couple.UserAId, out var at) ? Latest(at, couple.LinkedAtUtc) : null),
                    .. linkedCouples.Select(couple => grants.TryGetValue(couple.Id, out var at) ? Latest(at, couple.LinkedAtUtc) : null),
                ]),
            };
            for (var step = 0; step < times.Length && times[step] is { } at; step++)
            {
                reached[step]++;
                if (step > 0) gaps[step].Add(Math.Max(0, (at - times[step - 1]!.Value).TotalHours));
            }
        }
        return new FunnelDto(days, FunnelKeys.Select((key, step) => new FunnelStepDto(key, reached[step], step == 0 ? null : InsightsMath.Round(InsightsMath.Median(gaps[step])))).ToList());
    }
}
