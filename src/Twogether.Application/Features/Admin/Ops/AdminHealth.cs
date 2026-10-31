using System.Diagnostics;
using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Auth;
using Twogether.Application.Features.Food;
using Twogether.Application.Features.Memories;
using Twogether.Application.Features.Push;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Ops;

public enum ProbeStatus { Up, Degraded, Down, Off }

public sealed record ProbeDto(string Name, ProbeStatus Status, int? LatencyMs, string? Detail);
public sealed record QueueDto(string Name, int Waiting, DateTime? OldestUtc);
public sealed record FoodScanCostDto(int Days, long InputTokens, long OutputTokens, decimal? EstimatedCost, string Currency);

public sealed record HealthDto(
    DateTime NowUtc,
    IReadOnlyList<ProbeDto> Probes,
    IReadOnlyList<WorkerHealthDto> Workers,
    IReadOnlyList<QueueDto> Queues,
    long StorageBytes,
    FoodScanCostDto FoodScanCost,
    IReadOnlyList<string> NotMonitored);

/// <summary>
/// Is everything up, and is the background work keeping up. Probes are cheap and bounded by
/// <see cref="ProbeTimeout"/>; queues are counts of rows waiting for a worker. Ops figures cover every
/// account (internal ones cost storage and pushes too).
/// </summary>
public sealed class AdminHealth(
    IApplicationDbContext db,
    IDateTimeProvider clock,
    AdminSettings settings,
    WorkerHeartbeats heartbeats,
    IMediaStorage storage,
    IPushSender push,
    IEmailSender email,
    IFoodPhotoEstimator food)
{
    public static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(5);
    /// <summary>A database round trip slower than this is degraded.</summary>
    public const int SlowDatabaseMs = 500;
    public const int SlowStorageMs = 1500;

    /// <summary>What nothing here watches yet. Kept honest: add to it rather than imply coverage.</summary>
    public static readonly IReadOnlyList<string> NotMonitored =
    [
        "Other API instances: workers, alerts and verify failures are this instance's own, kept in memory since its last start",
        "API request rates, errors and latency (no request metrics are collected)",
        "SignalR hubs: connections and message delivery",
        "Push delivery to phones (only the provider accepting a message is recorded)",
        "Email delivery (a send is not confirmed)",
        "Google Play real-time notifications arriving (only failures to apply one are logged)",
        "Bachs webhooks arriving (only bad signatures and failures to apply one are logged)",
        "The storage bucket's own size and bill (storage bytes are what the database counts)",
        "Database size, connections and backups",
        "CPU, memory and disk of the API host",
    ];

    public async Task<HealthDto> GetAsync(int days, CancellationToken ct)
    {
        days = Math.Clamp(days, 1, 365);
        var now = clock.UtcNow;
        var hourAgo = now.AddHours(-1);
        var probes = new List<ProbeDto> { await DatabaseProbeAsync(ct), await StorageProbeAsync(ct) };

        var push = await db.Notifications.AsNoTracking()
            .Where(item => item.UpdatedAtUtc > hourAgo && (item.PushState == PushState.Sent || item.PushState == PushState.Failed))
            .GroupBy(item => item.PushState).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);
        var pushSent = push.FirstOrDefault(row => row.Key == PushState.Sent)?.Count ?? 0;
        var pushFailed = push.FirstOrDefault(row => row.Key == PushState.Failed)?.Count ?? 0;
        probes.Add(Push(pushSent, pushFailed));
        probes.Add(new ProbeDto("email", email.IsConfigured ? ProbeStatus.Up : ProbeStatus.Off, null, email.IsConfigured ? "configured; sends are not confirmed" : null));

        var scans = await db.FoodScans.AsNoTracking().Where(item => item.CreatedAtUtc > hourAgo && item.ErrorCode != FoodScan.PendingCode)
            .GroupBy(item => item.Succeeded).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);
        probes.Add(FoodModel(scans.FirstOrDefault(row => row.Key)?.Count ?? 0, scans.FirstOrDefault(row => !row.Key)?.Count ?? 0));

        var costFrom = DateOnly.FromDateTime(now).AddDays(-(days - 1)).ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);
        var tokens = await db.FoodScans.AsNoTracking().Where(item => item.CreatedAtUtc >= costFrom)
            .GroupBy(_ => 1).Select(group => new { Input = group.Sum(item => (long)item.InputTokens), Output = group.Sum(item => (long)item.OutputTokens) })
            .SingleOrDefaultAsync(ct);

        return new HealthDto(
            now,
            probes,
            heartbeats.Snapshot(),
            await QueuesAsync(now, ct),
            await StorageBytesAsync(db, ct),
            Cost(days, tokens?.Input ?? 0, tokens?.Output ?? 0, settings),
            NotMonitored);
    }

    public static FoodScanCostDto Cost(int days, long input, long output, AdminSettings settings)
        => new(days, input, output,
            settings.FoodScanPricePerMillionInput is { } inPrice && settings.FoodScanPricePerMillionOutput is { } outPrice
                ? Math.Round(input / 1_000_000m * inPrice + output / 1_000_000m * outPrice, 2)
                : null,
            settings.Currency);

    /// <summary>Every album object the database knows of, thumbnails included, until the cleanup worker removes it.</summary>
    public static async Task<long> StorageBytesAsync(IApplicationDbContext db, CancellationToken ct)
        => await db.Memories.AsNoTracking().SumAsync(item => (long?)(item.SizeBytes + item.ThumbnailBytes), ct) ?? 0;

    private async Task<ProbeDto> DatabaseProbeAsync(CancellationToken ct)
    {
        var watch = Stopwatch.StartNew();
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(ProbeTimeout);
            await db.Users.AsNoTracking().Select(user => user.Id).FirstOrDefaultAsync(timeout.Token);
            var ms = (int)watch.ElapsedMilliseconds;
            return new ProbeDto("database", ms > SlowDatabaseMs ? ProbeStatus.Degraded : ProbeStatus.Up, ms, ms > SlowDatabaseMs ? "slow round trip" : null);
        }
        catch (Exception exception) when (!ct.IsCancellationRequested)
        {
            return new ProbeDto("database", ProbeStatus.Down, (int)watch.ElapsedMilliseconds, exception.GetType().Name);
        }
    }

    private async Task<ProbeDto> StorageProbeAsync(CancellationToken ct)
    {
        if (!storage.IsConfigured) return new ProbeDto("storage", ProbeStatus.Off, null, "not configured: photo albums are off");
        var watch = Stopwatch.StartNew();
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(ProbeTimeout);
            // A HEAD of a key that never exists: one cheap authenticated round trip, nothing read.
            await storage.HeadAsync("health/probe", timeout.Token);
            var ms = (int)watch.ElapsedMilliseconds;
            return new ProbeDto("storage", ms > SlowStorageMs ? ProbeStatus.Degraded : ProbeStatus.Up, ms, ms > SlowStorageMs ? "slow round trip" : null);
        }
        catch (Exception exception) when (!ct.IsCancellationRequested)
        {
            return new ProbeDto("storage", ProbeStatus.Down, (int)watch.ElapsedMilliseconds, exception.GetType().Name);
        }
    }

    private ProbeDto Push(int sent, int failed)
    {
        if (!push.IsConfigured) return new ProbeDto("push", ProbeStatus.Off, null, "not configured: notifications stay in the app");
        var attempted = sent + failed;
        var failing = attempted >= AlertRules.PushMinimum && (double)failed / attempted >= AlertRules.PushFailureShare;
        return new ProbeDto("push", failing ? ProbeStatus.Degraded : ProbeStatus.Up, null,
            attempted == 0 ? $"{push.Name}; nothing sent in the last hour" : $"{push.Name}; {failed} of {attempted} failed in the last hour");
    }

    private ProbeDto FoodModel(int succeeded, int failed)
    {
        if (!food.IsConfigured) return new ProbeDto("foodModel", ProbeStatus.Off, null, "not configured: photo estimates are off");
        var attempted = succeeded + failed;
        // Half the scans failing is the estimator, not the photos.
        var failing = attempted >= 5 && failed * 2 >= attempted;
        return new ProbeDto("foodModel", failing ? ProbeStatus.Degraded : ProbeStatus.Up, null,
            attempted == 0 ? "configured; no scans in the last hour" : $"{failed} of {attempted} scans failed in the last hour");
    }

    private async Task<IReadOnlyList<QueueDto>> QueuesAsync(DateTime now, CancellationToken ct)
    {
        var notifications = db.Notifications.AsNoTracking();
        var memories = db.Memories.AsNoTracking();
        var occurrences = db.ExerciseOccurrences.AsNoTracking();
        return
        [
            await QueueAsync("pushOutbox", notifications.Where(item => item.PushState == PushState.Pending).Select(item => item.CreatedAtUtc), ct),
            await QueueAsync("photosBeingDeleted", memories.Where(item => item.Status == MemoryStatus.Deleting).Select(item => item.UpdatedAtUtc), ct),
            await QueueAsync("abandonedUploads", memories.Where(item => item.Status == MemoryStatus.Pending && item.CreatedAtUtc < now.AddHours(-1)).Select(item => item.CreatedAtUtc), ct),
            await QueueAsync("uploadRechecks", memories.Where(item => item.Status == MemoryStatus.Ready && item.UploadWindowEndsAtUtc != null && item.UploadWindowEndsAtUtc <= now).Select(item => item.UploadWindowEndsAtUtc!.Value), ct),
            await QueueAsync("foodScansInFlight", db.FoodScans.AsNoTracking().Where(item => !item.Succeeded && item.ErrorCode == FoodScan.PendingCode).Select(item => item.CreatedAtUtc), ct),
            // The alarm worker opens these every second; a backlog means it isn't keeping up.
            await QueueAsync("alarmsDue", db.ExerciseGoals.AsNoTracking().Where(goal => goal.IsActive && goal.NextDueAtUtc <= now.AddMinutes(-1)
                && db.Couples.Any(couple => couple.Id == goal.CoupleId && couple.Status == CoupleStatus.Active)).Select(goal => goal.NextDueAtUtc), ct),
            await QueueAsync("daresPastDeadline", occurrences.Where(item => (item.Status == ExerciseOccurrenceStatus.Alarming && item.DeadlineUtc <= now.AddMinutes(-1))
                || (item.Status == ExerciseOccurrenceStatus.InProgress && item.CompleteByUtc != null && item.CompleteByUtc <= now.AddMinutes(-1))).Select(item => item.DeadlineUtc), ct),
            // Card payments made in the app, waiting to be reported to Google (it wants them within 24 hours).
            await QueueAsync("googleReports", db.GoogleExternalTransactions.AsNoTracking().Where(item => item.TransactionTimeUtc != null && item.ReportedAtUtc == null).Select(item => item.TransactionTimeUtc!.Value), ct),
            await QueueAsync("googleReportsOverdue", db.GoogleExternalTransactions.AsNoTracking()
                .Where(item => item.TransactionTimeUtc != null && item.ReportedAtUtc == null && item.TransactionTimeUtc < now.AddHours(-12)).Select(item => item.TransactionTimeUtc!.Value), ct),
        ];
    }

    private static async Task<QueueDto> QueueAsync(string name, IQueryable<DateTime> waitingSince, CancellationToken ct)
    {
        var count = await waitingSince.CountAsync(ct);
        DateTime? oldest = count == 0 ? null : await waitingSince.OrderBy(at => at).FirstAsync(ct);
        return new QueueDto(name, count, oldest is { } at ? DateTime.SpecifyKind(at, DateTimeKind.Utc) : null);
    }
}
