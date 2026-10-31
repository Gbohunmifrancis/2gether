using System.Globalization;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Billing;

/// <summary>
/// When to warn a couple that a plan limit is near (docs/billing.md, Alerts): the shared album at 80% and 95% of its
/// room, and Plus a few days before it ends without renewing. Pure, so the rules are tested on their own.
/// </summary>
public static class PlanAlerts
{
    public static readonly int[] AlbumLevels = [80, 95];
    /// <summary>How long a level stays told: the same level isn't sent again within it, a higher one is.</summary>
    public static readonly TimeSpan AlbumQuiet = TimeSpan.FromDays(30);
    /// <summary>How far ahead of the end Plus warns, and how long before it warns again (a new grant, a re-cancel).</summary>
    public static readonly TimeSpan PlusWarning = TimeSpan.FromDays(3);
    public static readonly TimeSpan PlusQuiet = TimeSpan.FromDays(7);

    /// <summary>The highest level the album has reached (80 or 95), or null below 80% or with no limit.</summary>
    public static int? AlbumLevel(long usedBytes, long limitBytes)
        => limitBytes <= 0 ? null : AlbumLevels.Where(level => usedBytes * 100 >= limitBytes * level).Select(level => (int?)level).LastOrDefault();

    /// <summary>A level is told once: only when it's higher than any told recently.</summary>
    public static bool ShouldTellAlbum(int level, IEnumerable<int> recentlyTold) => level > recentlyTold.DefaultIfEmpty(0).Max();

    /// <summary>When Plus ends, if it ends within the warning window and won't renew by itself; otherwise null.</summary>
    public static DateTime? PlusEndsSoon(EntitlementDto entitlement, DateTime utcNow)
    {
        if (entitlement.Plan != Plan.Plus) return null;
        DateTime? end = entitlement.Subscription is { } paid
            ? paid.AutoRenewing ? null : paid.ExpiresAtUtc
            : entitlement.Complimentary ? entitlement.ComplimentaryUntilUtc : null;
        return end is { } at && at > utcNow && at - utcNow <= PlusWarning ? at : null;
    }

    /// <summary>"450 MB", "1.8 GB": the album's sizes as people read them.</summary>
    public static string Size(long bytes) => bytes >= 1024L * 1024 * 1024
        ? (bytes / (1024d * 1024 * 1024)).ToString(bytes >= 10L * 1024 * 1024 * 1024 ? "0" : "0.#", CultureInfo.CurrentCulture) + " GB"
        : Math.Max(1, (long)Math.Round(bytes / (1024d * 1024))).ToString(CultureInfo.CurrentCulture) + " MB";
}
