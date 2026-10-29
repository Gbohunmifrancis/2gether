using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Live;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Ops;

public enum AlertSeverity { Warn, Bad }

public sealed record AlertDto(string Key, AlertSeverity Severity, string Title, string Detail, DateTime SinceUtc, string? Link);

/// <summary>A rule that holds right now, before the cooldown decides whether it is news.</summary>
public sealed record AlertCandidate(string Key, AlertSeverity Severity, string Title, string Detail, string? Link);

public sealed record FoodScanBurst(Guid UserId, string DisplayName, int Scans);

/// <summary>What the alert rules look at, gathered in one go.</summary>
public sealed record AlertSignals(
    int SignupsLast15Minutes,
    IReadOnlyList<FoodScanBurst> FoodScanBursts,
    int SubscriptionsNewlyInTrouble,
    int VerifyFailuresLastHour,
    int PushSentLastHour,
    int PushFailedLastHour,
    IReadOnlyList<WorkerHealthDto> Workers,
    long StorageBytes,
    decimal? StorageBudgetGb);

/// <summary>The alert rules (docs/admin/contract-v2.md), as plain functions of the signals.</summary>
public static class AlertRules
{
    public const int SignupWave = 10;
    public const int FoodScanFlood = 20;
    public const int VerifyFailures = 3;
    public const double PushFailureShare = 0.2;
    public const int PushMinimum = 10;
    public const double StorageWarnShare = 0.8;
    public const decimal BytesPerGb = 1_000_000_000m;

    public static IReadOnlyList<AlertCandidate> Evaluate(AlertSignals signals)
    {
        var alerts = new List<AlertCandidate>();
        if (signals.SignupsLast15Minutes >= SignupWave)
            alerts.Add(new("signupWave", AlertSeverity.Warn, "Sign-up wave", $"{signals.SignupsLast15Minutes} sign-ups in the last 15 minutes.", "/users"));
        foreach (var burst in signals.FoodScanBursts.Where(item => item.Scans >= FoodScanFlood))
            alerts.Add(new($"foodScanFlood:{burst.UserId}", AlertSeverity.Warn, "Food-scan flood", $"{burst.DisplayName} made {burst.Scans} food scans in 10 minutes.", $"/users/{burst.UserId}"));
        if (signals.SubscriptionsNewlyInTrouble > 0 || signals.VerifyFailuresLastHour >= VerifyFailures)
        {
            var parts = new List<string>();
            if (signals.SubscriptionsNewlyInTrouble > 0) parts.Add($"{signals.SubscriptionsNewlyInTrouble} subscription(s) went on hold or into a grace period in the last hour");
            if (signals.VerifyFailuresLastHour >= VerifyFailures) parts.Add($"{signals.VerifyFailuresLastHour} purchase verifications failed in the last hour");
            alerts.Add(new("paymentTrouble", AlertSeverity.Bad, "Payment trouble", string.Join("; ", parts) + ".", "/payments"));
        }
        var attempted = signals.PushSentLastHour + signals.PushFailedLastHour;
        if (attempted >= PushMinimum && (double)signals.PushFailedLastHour / attempted >= PushFailureShare)
            alerts.Add(new("pushFailing", AlertSeverity.Bad, "Push failing", $"{signals.PushFailedLastHour} of {attempted} pushes failed in the last hour.", "/system"));
        foreach (var worker in signals.Workers)
        {
            if (worker.Status == WorkerStatus.Failing)
                alerts.Add(new($"worker:{worker.Name}", AlertSeverity.Bad, $"Worker failing: {worker.Name}", worker.LastError ?? "Its last run failed.", "/system"));
            else if (worker.Status == WorkerStatus.Late)
                alerts.Add(new($"worker:{worker.Name}", AlertSeverity.Warn, $"Worker late: {worker.Name}",
                    worker.LastRunUtc is { } last ? $"No run since {last:yyyy-MM-dd HH:mm} UTC; it runs every {worker.EverySeconds} s." : "It hasn't run since the server started.", "/system"));
        }
        if (signals.StorageBudgetGb is { } budget && budget > 0)
        {
            var share = signals.StorageBytes / (budget * BytesPerGb);
            if (share >= (decimal)StorageWarnShare)
                alerts.Add(new("storageBudget", share >= 1 ? AlertSeverity.Bad : AlertSeverity.Warn, "Album storage",
                    $"{Math.Round(share * 100)}% of the {budget:0.##} GB budget is used ({signals.StorageBytes / BytesPerGb:0.##} GB).", "/system"));
        }
        return alerts;
    }
}

/// <summary>
/// Which alerts are raised, held in memory (per instance). A rule that holds is raised once, then not
/// again for <see cref="Cooldown"/>; it stays listed for that hour even if it stops holding, so a flapping
/// rule reads as one alert. Each raise also goes on the live feed as an "alert" event.
/// </summary>
public sealed class AdminAlertMonitor
{
    public static readonly TimeSpan Cooldown = TimeSpan.FromMinutes(60);
    /// <summary>A rule seen holding within this long before is still the same episode.</summary>
    private static readonly TimeSpan Continuity = TimeSpan.FromMinutes(5);

    private sealed record State(AlertCandidate Alert, DateTime SinceUtc, DateTime RaisedAtUtc, DateTime LastHeldUtc);

    private readonly object gate = new();
    private readonly Dictionary<string, State> states = new(StringComparer.Ordinal);
    private readonly List<LiveEvent> raised = [];
    private DateTime? evaluatedAtUtc;
    private IReadOnlyList<AlertDto> current = [];

    public bool IsFresh(DateTime utcNow, TimeSpan maxAge)
    {
        lock (gate) return evaluatedAtUtc is { } at && utcNow - at < maxAge;
    }

    public IReadOnlyList<AlertDto> Current
    {
        get { lock (gate) return current; }
    }

    public IReadOnlyList<AlertDto> Apply(IReadOnlyList<AlertCandidate> holding, DateTime utcNow)
    {
        lock (gate)
        {
            foreach (var alert in holding)
            {
                states.TryGetValue(alert.Key, out var state);
                if (state is null || utcNow - state.RaisedAtUtc >= Cooldown)
                {
                    var since = state is not null && utcNow - state.LastHeldUtc <= Continuity ? state.SinceUtc : utcNow;
                    states[alert.Key] = new State(alert, since, utcNow, utcNow);
                    raised.Add(new LiveEvent($"alert:{alert.Key}:{utcNow.Ticks}", LiveKind.Alert, utcNow, null, null, null,
                        $"{alert.Title}: {alert.Detail}", alert.Severity == AlertSeverity.Bad ? LiveSeverity.Bad : LiveSeverity.Warn));
                }
                else states[alert.Key] = state with { Alert = alert, LastHeldUtc = utcNow };
            }
            var holdingKeys = holding.Select(item => item.Key).ToHashSet(StringComparer.Ordinal);
            foreach (var key in states.Where(pair => !holdingKeys.Contains(pair.Key) && utcNow - pair.Value.RaisedAtUtc >= Cooldown).Select(pair => pair.Key).ToList())
                states.Remove(key);
            raised.RemoveAll(item => utcNow - item.AtUtc > AdminLiveFeed.RecentWindow);
            evaluatedAtUtc = utcNow;
            current = states.Values
                .OrderBy(state => state.Alert.Severity == AlertSeverity.Bad ? 0 : 1).ThenByDescending(state => state.SinceUtc).ThenBy(state => state.Alert.Key, StringComparer.Ordinal)
                .Select(state => new AlertDto(state.Alert.Key, state.Alert.Severity, state.Alert.Title, state.Alert.Detail, state.SinceUtc, state.Alert.Link))
                .ToList();
            return current;
        }
    }

    public IReadOnlyList<LiveEvent> RaisedSince(DateTime sinceUtc)
    {
        lock (gate) return raised.Where(item => item.AtUtc > sinceUtc).ToList();
    }
}

/// <summary>Gathers the signals and runs the rules, at most every <see cref="MaxAge"/> however many dashboards ask.</summary>
public sealed class AdminAlerts(IApplicationDbContext db, IDateTimeProvider clock, AdminSettings settings, AdminAlertMonitor monitor, WorkerHeartbeats heartbeats, OpsSignals signals)
{
    public static readonly TimeSpan MaxAge = TimeSpan.FromSeconds(30);

    public async Task<IReadOnlyList<AlertDto>> CurrentAsync(CancellationToken ct)
    {
        var now = clock.UtcNow;
        if (monitor.IsFresh(now, MaxAge)) return monitor.Current;
        return monitor.Apply(AlertRules.Evaluate(await GatherAsync(now, ct)), now);
    }

    public async Task<AlertSignals> GatherAsync(DateTime now, CancellationToken ct)
    {
        var scope = new MeasuredScope(db, settings);
        var users = scope.Users.AsNoTracking();
        var signupsFrom = now.AddMinutes(-15);
        var signups = await users.CountAsync(user => user.CreatedAtUtc > signupsFrom && user.CreatedAtUtc <= now, ct);
        var scansFrom = now.AddMinutes(-10);
        var bursts = await db.FoodScans.AsNoTracking()
            .Where(item => item.CreatedAtUtc > scansFrom && users.Any(user => user.Id == item.UserId))
            .GroupBy(item => item.UserId)
            .Select(group => new { UserId = group.Key, Scans = group.Count() })
            .Where(row => row.Scans >= AlertRules.FoodScanFlood)
            .ToListAsync(ct);
        var burstIds = bursts.Select(row => row.UserId).ToList();
        var names = burstIds.Count == 0 ? [] : await db.Users.AsNoTracking().Where(user => burstIds.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.DisplayName, ct);
        var hourAgo = now.AddHours(-1);
        // The store is the source of truth and every sync rewrites the row, so a recent update in one of
        // these states is a subscription that just got into trouble (or was re-read while in it).
        var troubled = await db.StoreSubscriptions.AsNoTracking().CountAsync(item =>
            (item.Status == StoreSubscriptionStatus.OnHold || item.Status == StoreSubscriptionStatus.InGracePeriod) && item.UpdatedAtUtc > hourAgo, ct);
        var push = await db.Notifications.AsNoTracking()
            .Where(item => item.UpdatedAtUtc > hourAgo && (item.PushState == PushState.Sent || item.PushState == PushState.Failed))
            .GroupBy(item => item.PushState).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);
        return new AlertSignals(
            signups,
            bursts.Select(row => new FoodScanBurst(row.UserId, names.GetValueOrDefault(row.UserId) ?? "Someone", row.Scans)).ToList(),
            troubled,
            signals.VerifyFailuresSince(hourAgo),
            push.FirstOrDefault(row => row.Key == PushState.Sent)?.Count ?? 0,
            push.FirstOrDefault(row => row.Key == PushState.Failed)?.Count ?? 0,
            heartbeats.Snapshot(),
            settings.StorageBudgetGb is null ? 0 : await AdminHealth.StorageBytesAsync(db, ct),
            settings.StorageBudgetGb);
    }
}
