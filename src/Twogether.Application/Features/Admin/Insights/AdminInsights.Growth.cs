using Microsoft.EntityFrameworkCore;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Insights;

public sealed partial class AdminInsights
{
    /// <summary>Near the album limit: this share of the Free plan's room is used.</summary>
    public const double NearAlbumLimitShare = 0.8;

    /// <summary>
    /// Plus over the window. Ending: subscriptions that won't renew and complimentary grants, ending within 7 and 30
    /// days. Renewals: the store row keeps only its latest period, so a subscription counts as renewed when its current
    /// period began in the window after its first one, and as lapsed when it ended in the window without coming back
    /// (expired, on hold, paused); the rate is renewed over both, null when nothing was due. The path follows the people
    /// who signed up in the window: linked, hit the Free daily scan limit on some day, their couple's album near the Free
    /// limit, Plus. Plus by country: couples on Plus now, in each country either partner is in.
    /// </summary>
    public async Task<GrowthDto> GrowthAsync(int days, CancellationToken ct)
    {
        days = InsightsMath.ClampDays(days);
        var now = clock.UtcNow;
        var fromUtc = InsightsMath.WindowStart(now, days);
        var userIds = measured.UserIds;
        var coupleIds = measured.CoupleIds;
        var subscriptions = db.StoreSubscriptions.Where(item => userIds.Contains(item.UserId));

        var granting = subscriptions.Where(item => (item.Status == StoreSubscriptionStatus.Active || item.Status == StoreSubscriptionStatus.Canceled || item.Status == StoreSubscriptionStatus.InGracePeriod) && item.ExpiresAtUtc > now);
        var in7 = now.AddDays(7);
        var in30 = now.AddDays(30);
        var endingSubscriptions = await granting.Where(item => !item.AutoRenewing && item.ExpiresAtUtc <= in30).Select(item => item.ExpiresAtUtc).ToListAsync(ct);
        var endingGrants = await db.PlusGrants.Where(item => item.RevokedAtUtc == null && item.ExpiresAtUtc > now && item.ExpiresAtUtc <= in30 && coupleIds.Contains(item.CoupleId)).Select(item => item.ExpiresAtUtc).ToListAsync(ct);
        var ending = endingSubscriptions.Concat(endingGrants).ToList();

        var current = await granting.Select(item => new { item.ProductId, item.CreatedAtUtc, item.ExpiresAtUtc }).ToListAsync(ct);
        var renewed = current.Count(item =>
        {
            var periodStart = item.ExpiresAtUtc - Period(item.ProductId);
            return periodStart >= fromUtc && periodStart <= now && periodStart > item.CreatedAtUtc.AddDays(1);
        });
        var lapsed = await subscriptions.CountAsync(item => (item.Status == StoreSubscriptionStatus.Expired || item.Status == StoreSubscriptionStatus.OnHold || item.Status == StoreSubscriptionStatus.Paused)
            && item.ExpiresAtUtc >= fromUtc && item.ExpiresAtUtc <= now, ct);

        // The path, for the people who signed up in the window.
        var cohortQuery = measured.Users.Where(user => user.CreatedAtUtc >= fromUtc);
        var cohortIds = cohortQuery.Select(user => user.Id);
        var cohort = await cohortQuery.Select(user => new { user.Id, user.CreatedAtUtc }).ToListAsync(ct);
        var couplesQuery = measured.Couples.Where(couple => cohortIds.Contains(couple.UserAId) || (couple.UserBId != null && cohortIds.Contains(couple.UserBId.Value)));
        var couples = await couplesQuery.Select(couple => new { couple.Id, couple.UserAId, couple.UserBId, couple.LinkedAtUtc }).ToListAsync(ct);
        var scanLimit = billing.Free.FoodScansPerDay;
        var hitScanLimit = (await db.FoodScans.Where(item => item.CreatedAtUtc >= fromUtc && cohortIds.Contains(item.UserId))
                .GroupBy(item => new { item.UserId, item.CreatedAtUtc.Date }).Where(group => group.Count() >= scanLimit)
                .Select(group => group.Key.UserId).Distinct().ToListAsync(ct))
            .ToHashSet();
        var nearAlbum = (long)(billing.Free.MemoriesBytes * NearAlbumLimitShare);
        var coupleIdsOfCohort = couplesQuery.Select(couple => couple.Id);
        var fullAlbums = (await db.Memories.Where(item => item.Status != MemoryStatus.Deleting && coupleIdsOfCohort.Contains(item.CoupleId))
                .GroupBy(item => item.CoupleId).Where(group => group.Sum(item => item.SizeBytes + item.ThumbnailBytes) >= nearAlbum)
                .Select(group => group.Key).ToListAsync(ct))
            .ToHashSet();
        var bought = await subscriptions.Where(item => item.Status != StoreSubscriptionStatus.Pending && item.Status != StoreSubscriptionStatus.PendingPurchaseCanceled
                && (cohortIds.Contains(item.UserId) || couplesQuery.Any(couple => couple.UserAId == item.UserId || couple.UserBId == item.UserId)))
            .GroupBy(item => item.UserId).Select(group => new { group.Key, At = group.Min(item => item.CreatedAtUtc) })
            .ToDictionaryAsync(item => item.Key, item => item.At, ct);
        var given = await db.PlusGrants.Where(item => coupleIdsOfCohort.Contains(item.CoupleId))
            .GroupBy(item => item.CoupleId).Select(group => new { group.Key, At = group.Min(item => item.CreatedAtUtc) })
            .ToDictionaryAsync(item => item.Key, item => item.At, ct);

        var couplesOf = couples.SelectMany(couple => new[] { (UserId: couple.UserAId, Couple: couple), (UserId: couple.UserBId ?? Guid.Empty, Couple: couple) })
            .Where(item => item.UserId != Guid.Empty).ToLookup(item => item.UserId, item => item.Couple);
        int linked = 0, scans = 0, album = 0, plus = 0;
        var daysToPlus = new List<double>();
        foreach (var person in cohort)
        {
            var mine = couplesOf[person.Id].ToList();
            var together = mine.Where(couple => couple.LinkedAtUtc != null && couple.UserBId != null).ToList();
            if (together.Count > 0) linked++;
            if (hitScanLimit.Contains(person.Id)) scans++;
            if (mine.Any(couple => fullAlbums.Contains(couple.Id))) album++;
            // Plus from their own purchase, or (once linked) their partner's or a grant to the couple.
            DateTime?[] starts =
            [
                bought.TryGetValue(person.Id, out var own) ? own : null,
                .. together.Select(couple => bought.TryGetValue(couple.UserAId == person.Id ? couple.UserBId!.Value : couple.UserAId, out var at) ? Later(at, couple.LinkedAtUtc!.Value) : (DateTime?)null),
                .. together.Select(couple => given.TryGetValue(couple.Id, out var at) ? Later(at, couple.LinkedAtUtc!.Value) : (DateTime?)null),
            ];
            if (starts.Min() is not { } plusAt) continue;
            plus++;
            daysToPlus.Add(Math.Max(0, (plusAt - person.CreatedAtUtc).TotalDays));
        }

        var plusCouples = (await PlusCoupleIdsAsync(now, ct)).ToList();
        var plusByCountry = await measured.Users
            .Where(user => user.CountryCode != null && user.CoupleId != null && plusCouples.Contains(user.CoupleId.Value))
            .GroupBy(user => user.CountryCode!).Select(group => new { group.Key, Count = group.Select(user => user.CoupleId).Distinct().Count() })
            .OrderByDescending(item => item.Count).ToListAsync(ct);

        return new GrowthDto(
            days,
            ending.Count(at => at <= in7), ending.Count,
            renewed, lapsed, InsightsMath.Rate(renewed, renewed + lapsed),
            [
                new GrowthStepDto("signedUp", cohort.Count),
                new GrowthStepDto("linked", linked),
                new GrowthStepDto("hitScanLimit", scans),
                new GrowthStepDto("nearAlbumLimit", album),
                new GrowthStepDto("plus", plus),
            ],
            InsightsMath.Round(InsightsMath.Median(daysToPlus)),
            plusByCountry.Select(item => new NamedCount(item.Key.ToUpperInvariant(), item.Count)).ToList());
    }

    private static DateTime Later(DateTime first, DateTime second) => first > second ? first : second;

    /// <summary>
    /// A product's billing period, from its id (heartpact_plus_yearly, heartpact_plus_monthly). The store row doesn't say,
    /// and a little over the calendar period keeps a renewal from looking like the first period.
    /// </summary>
    public static TimeSpan Period(string productId) => productId.Contains("year", StringComparison.OrdinalIgnoreCase) || productId.Contains("annual", StringComparison.OrdinalIgnoreCase)
        ? TimeSpan.FromDays(366)
        : TimeSpan.FromDays(31);
}
