using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Application.Features.Config;

/// <summary>What the apps ask for at start, and what the admin dashboard switches: <c>{ "christmas": true }</c>.</summary>
public sealed record FeatureFlagsDto(bool Christmas);

/// <summary>
/// Server-wide feature flags, one <c>app_settings</c> row each. A flag nobody has ever set has no row and is off, so
/// nothing needs seeding and a new database starts with everything off.
/// </summary>
public sealed class FeatureFlagService(IApplicationDbContext db, IDateTimeProvider clock)
{
    public const string ChristmasKey = "feature.christmas";

    public async Task<FeatureFlagsDto> GetAsync(CancellationToken cancellationToken)
    {
        // One primary-key read: this runs on every app start.
        var christmas = await db.AppSettings.AsNoTracking().Where(item => item.Key == ChristmasKey).Select(item => item.Value).FirstOrDefaultAsync(cancellationToken);
        return new FeatureFlagsDto(IsOn(christmas));
    }

    public async Task SetAsync(FeatureFlagsDto flags, CancellationToken cancellationToken)
        => await UpsertAsync(ChristmasKey, flags.Christmas ? "true" : "false", cancellationToken);

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
