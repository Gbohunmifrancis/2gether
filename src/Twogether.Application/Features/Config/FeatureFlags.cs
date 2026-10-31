using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Application.Features.Config;

/// <summary>
/// Every flag, as the admin dashboard reads and switches them: <c>{ "christmas": true, "bachsInApp": false }</c>.
/// BachsInApp lets the app offer card payment (Bachs) next to Google Play where Google's billing choice allows it.
/// </summary>
public sealed record FeatureFlagsDto(bool Christmas, bool BachsInApp = false);

/// <summary>What the apps ask for at start (<c>GET api/config</c>): only what changes how every app looks, nothing personal.</summary>
public sealed record PublicConfigDto(bool Christmas);

/// <summary>A change to some flags; a flag left null stays as it is.</summary>
public sealed record FeatureFlagsUpdate(bool? Christmas = null, bool? BachsInApp = null)
{
    public bool IsEmpty => Christmas is null && BachsInApp is null;
}

/// <summary>
/// Server-wide feature flags, one <c>app_settings</c> row each. A flag nobody has ever set has no row and is off, so
/// nothing needs seeding and a new database starts with everything off.
/// </summary>
public sealed class FeatureFlagService(IApplicationDbContext db, IDateTimeProvider clock)
{
    public const string ChristmasKey = "feature.christmas";
    public const string BachsInAppKey = "feature.bachsInApp";

    public async Task<FeatureFlagsDto> GetAsync(CancellationToken cancellationToken)
    {
        var rows = await db.AppSettings.AsNoTracking().Where(item => item.Key == ChristmasKey || item.Key == BachsInAppKey)
            .Select(item => new { item.Key, item.Value }).ToListAsync(cancellationToken);
        return new FeatureFlagsDto(IsOn(rows.FirstOrDefault(row => row.Key == ChristmasKey)?.Value), IsOn(rows.FirstOrDefault(row => row.Key == BachsInAppKey)?.Value));
    }

    public async Task<PublicConfigDto> GetPublicAsync(CancellationToken cancellationToken)
    {
        // One primary-key read: this runs on every app start.
        var christmas = await db.AppSettings.AsNoTracking().Where(item => item.Key == ChristmasKey).Select(item => item.Value).FirstOrDefaultAsync(cancellationToken);
        return new PublicConfigDto(IsOn(christmas));
    }

    public async Task SetAsync(FeatureFlagsUpdate update, CancellationToken cancellationToken)
    {
        if (update.Christmas is { } christmas) await UpsertAsync(ChristmasKey, christmas ? "true" : "false", cancellationToken);
        if (update.BachsInApp is { } bachsInApp) await UpsertAsync(BachsInAppKey, bachsInApp ? "true" : "false", cancellationToken);
    }

    public static bool IsOn(string? stored) => string.Equals(stored, "true", StringComparison.OrdinalIgnoreCase);

    private async Task UpsertAsync(string key, string value, CancellationToken cancellationToken)
    {
        // Update first; insert only when the flag has never been set. Two admins creating it at once: the loser's
        // insert hits the primary key, and its update then lands on the winner's row.
        if (await UpdateAsync(key, value, cancellationToken) > 0) return;
        try
        {
            db.AppSettings.Add(AppSetting.Create(key, value, clock.UtcNow));
            await db.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException)
        {
            foreach (var entry in ((DbContext)db).ChangeTracker.Entries<AppSetting>().ToList()) entry.State = EntityState.Detached;
            await UpdateAsync(key, value, cancellationToken);
        }
    }

    private Task<int> UpdateAsync(string key, string value, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        return db.AppSettings.Where(item => item.Key == key)
            .ExecuteUpdateAsync(set => set.SetProperty(item => item.Value, value).SetProperty(item => item.UpdatedAtUtc, now), cancellationToken);
    }
}
