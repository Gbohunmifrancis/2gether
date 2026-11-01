using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Config;

/// <summary>
/// Every flag, as the admin dashboard reads and switches them: <c>{ "christmas": true, "bachsInApp": false, ... }</c>.
/// BachsInApp lets the app offer card payment (Bachs) next to Google Play where Google's billing choice allows it. The
/// four AI switches (docs/ai.md) start on: a switch nobody has touched is on.
/// </summary>
public sealed record FeatureFlagsDto(bool Christmas, bool BachsInApp = false, bool AiDareIdeas = true, bool AiCoach = true, bool AiWeeklyRecap = true, bool AiPlanner = true);

/// <summary>What the apps ask for at start (<c>GET api/config</c>): only what changes how every app looks, nothing personal.</summary>
public sealed record PublicConfigDto(bool Christmas);

/// <summary>A change to some flags; a flag left null stays as it is.</summary>
public sealed record FeatureFlagsUpdate(bool? Christmas = null, bool? BachsInApp = null, bool? AiDareIdeas = null, bool? AiCoach = null, bool? AiWeeklyRecap = null, bool? AiPlanner = null)
{
    public bool IsEmpty => Christmas is null && BachsInApp is null && AiDareIdeas is null && AiCoach is null && AiWeeklyRecap is null && AiPlanner is null;
}

/// <summary>The AI switches alone, as the AI features read them.</summary>
public sealed record AiSwitches(bool DareIdeas, bool Coach, bool WeeklyRecap, bool Planner)
{
    public bool IsOn(AiFeature feature) => feature switch
    {
        AiFeature.DareIdeas => DareIdeas,
        AiFeature.Coach => Coach,
        AiFeature.WeeklyRecap => WeeklyRecap,
        AiFeature.Plan => Planner,
        _ => false
    };
}

/// <summary>
/// Server-wide feature flags, one <c>app_settings</c> row each. A flag nobody has ever set has no row: off, except the AI
/// switches, which are on until an admin turns them off. Nothing needs seeding.
/// </summary>
public sealed class FeatureFlagService(IApplicationDbContext db, IDateTimeProvider clock)
{
    public const string ChristmasKey = "feature.christmas";
    public const string BachsInAppKey = "feature.bachsInApp";
    public const string AiDareIdeasKey = "feature.aiDareIdeas";
    public const string AiCoachKey = "feature.aiCoach";
    public const string AiWeeklyRecapKey = "feature.aiWeeklyRecap";
    public const string AiPlannerKey = "feature.aiPlanner";

    private static readonly string[] AllKeys = [ChristmasKey, BachsInAppKey, AiDareIdeasKey, AiCoachKey, AiWeeklyRecapKey, AiPlannerKey];
    private static readonly string[] AiKeys = [AiDareIdeasKey, AiCoachKey, AiWeeklyRecapKey, AiPlannerKey];

    public async Task<FeatureFlagsDto> GetAsync(CancellationToken cancellationToken)
    {
        var rows = await db.AppSettings.AsNoTracking().Where(item => AllKeys.Contains(item.Key))
            .ToDictionaryAsync(item => item.Key, item => item.Value, cancellationToken);
        var ai = Ai(rows);
        return new FeatureFlagsDto(IsOn(rows.GetValueOrDefault(ChristmasKey)), IsOn(rows.GetValueOrDefault(BachsInAppKey)), ai.DareIdeas, ai.Coach, ai.WeeklyRecap, ai.Planner);
    }

    /// <summary>The four AI switches in one read.</summary>
    public async Task<AiSwitches> GetAiAsync(CancellationToken cancellationToken)
        => Ai(await db.AppSettings.AsNoTracking().Where(item => AiKeys.Contains(item.Key)).ToDictionaryAsync(item => item.Key, item => item.Value, cancellationToken));

    public async Task<PublicConfigDto> GetPublicAsync(CancellationToken cancellationToken)
    {
        // One primary-key read: this runs on every app start.
        var christmas = await db.AppSettings.AsNoTracking().Where(item => item.Key == ChristmasKey).Select(item => item.Value).FirstOrDefaultAsync(cancellationToken);
        return new PublicConfigDto(IsOn(christmas));
    }

    public async Task SetAsync(FeatureFlagsUpdate update, CancellationToken cancellationToken)
    {
        foreach (var (key, value) in new (string, bool?)[]
                 {
                     (ChristmasKey, update.Christmas), (BachsInAppKey, update.BachsInApp), (AiDareIdeasKey, update.AiDareIdeas),
                     (AiCoachKey, update.AiCoach), (AiWeeklyRecapKey, update.AiWeeklyRecap), (AiPlannerKey, update.AiPlanner)
                 })
            if (value is { } on) await UpsertAsync(key, on ? "true" : "false", cancellationToken);
    }

    public static bool IsOn(string? stored) => string.Equals(stored, "true", StringComparison.OrdinalIgnoreCase);

    /// <summary>A switch that starts on: only a stored "false" (or anything but "true") turns it off.</summary>
    public static bool IsOnByDefault(string? stored) => stored is null || IsOn(stored);

    private static AiSwitches Ai(IReadOnlyDictionary<string, string> rows) => new(
        IsOnByDefault(rows.GetValueOrDefault(AiDareIdeasKey)), IsOnByDefault(rows.GetValueOrDefault(AiCoachKey)),
        IsOnByDefault(rows.GetValueOrDefault(AiWeeklyRecapKey)), IsOnByDefault(rows.GetValueOrDefault(AiPlannerKey)));

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
