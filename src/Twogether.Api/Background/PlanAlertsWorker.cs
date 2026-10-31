using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Api.Background;

/// <summary>
/// Warns a couple, once, when a plan limit is near (docs/billing.md, Alerts): the shared album at 80% and again at 95%
/// of its room, and Plus three days before it ends without renewing. Hourly; what was already told is read back from
/// the notifications, so it needs no state of its own, and another instance racing it at worst sends a duplicate.
/// The daily limits (photo estimates, dares) are shown in the app where they apply.
/// </summary>
public sealed class PlanAlertsWorker(
    IServiceScopeFactory scopeFactory,
    IHubContext<CoupleHub> hub,
    IDateTimeProvider clock,
    BillingSettings billing,
    ILogger<PlanAlertsWorker> logger,
    WorkerHeartbeats? heartbeats = null) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromHours(1);
    private static readonly TimeSpan StartupDelay = TimeSpan.FromMinutes(2);
    public const string HeartbeatName = "planAlerts";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        heartbeats?.Register(HeartbeatName, Interval);
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(PlanAlertsWorker), stoppingToken);
        try
        {
            await Task.Delay(StartupDelay, stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        using var timer = new PeriodicTimer(Interval);
        do
        {
            try
            {
                var sent = await RunAsync(stoppingToken);
                if (sent > 0) logger.LogInformation("Sent {Count} plan alerts.", sent);
                heartbeats?.Ok(HeartbeatName, Interval);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Plan alerts run failed.");
                heartbeats?.Failed(HeartbeatName, Interval, exception);
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    /// <summary>One pass over both alerts; returns how many notifications it saved.</summary>
    public async Task<int> RunAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var now = clock.UtcNow;
        var entitlements = new EntitlementService(db, clock, billing);
        var notifications = new List<Notification>();

        // The album: only couples past 80% of the smallest plan's room can be near their own limit.
        var smallest = Math.Min(billing.Free.MemoriesBytes, billing.Plus.MemoriesBytes);
        var floor = smallest * PlanAlerts.AlbumLevels[0] / 100;
        var albums = await db.Memories.AsNoTracking()
            .Where(item => item.Status == MemoryStatus.Ready)
            .GroupBy(item => item.CoupleId)
            .Select(group => new { CoupleId = group.Key, Used = group.Sum(item => item.SizeBytes + item.ThumbnailBytes) })
            .Where(item => item.Used >= floor)
            .ToListAsync(cancellationToken);
        foreach (var album in albums)
        {
            var couple = await ActiveCoupleAsync(db, album.CoupleId, cancellationToken);
            if (couple is null) continue;
            var plan = await entitlements.ForUserAsync(couple.UserAId, cancellationToken);
            if (PlanAlerts.AlbumLevel(album.Used, plan.Limits.MemoriesBytes) is not { } level) continue;
            var told = await RecentAsync(db, couple.Id, NotificationType.AlbumAlmostFull, now - PlanAlerts.AlbumQuiet, cancellationToken);
            if (!PlanAlerts.ShouldTellAlbum(level, told.Select(LevelOf))) continue;
            foreach (var userId in Members(couple))
            {
                using var _ = Loc.Use(await db.LanguageOfAsync(userId, cancellationToken));
                var used = Loc.T("{used} of {limit} used.", ("used", PlanAlerts.Size(album.Used)), ("limit", PlanAlerts.Size(plan.Limits.MemoriesBytes)));
                var advice = plan.Plan == Plan.Free
                    ? Loc.T("Heartpact Plus gives you {plus} for both of you, or delete a few shared photos.", ("plus", PlanAlerts.Size(billing.Plus.MemoriesBytes)))
                    : Loc.T("Delete a few shared photos to make room for new ones.");
                notifications.Add(Notification.Create(userId, couple.Id, NotificationType.AlbumAlmostFull,
                    level >= 95 ? Loc.T("Your shared album is almost full") : Loc.T("Your shared album is filling up"),
                    $"{used} {advice}", JsonSerializer.Serialize(new { level }), now));
            }
        }

        // Plus ending: a cancelled subscription or a complimentary grant running out within three days.
        var soon = now + PlanAlerts.PlusWarning;
        var payers = await db.StoreSubscriptions.AsNoTracking()
            .Where(item => !item.AutoRenewing && item.ExpiresAtUtc > now && item.ExpiresAtUtc <= soon)
            .Select(item => item.UserId).Distinct().ToListAsync(cancellationToken);
        var payerCouples = await db.Users.AsNoTracking().Where(user => payers.Contains(user.Id) && user.CoupleId != null)
            .Select(user => user.CoupleId!.Value).ToListAsync(cancellationToken);
        var grantCouples = await db.PlusGrants.AsNoTracking()
            .Where(item => item.RevokedAtUtc == null && item.ExpiresAtUtc > now && item.ExpiresAtUtc <= soon)
            .Select(item => item.CoupleId).ToListAsync(cancellationToken);
        foreach (var coupleId in payerCouples.Concat(grantCouples).Distinct())
        {
            var couple = await ActiveCoupleAsync(db, coupleId, cancellationToken);
            if (couple is null) continue;
            var plan = await entitlements.ForUserAsync(couple.UserAId, cancellationToken);
            if (PlanAlerts.PlusEndsSoon(plan, now) is not { } end) continue;
            if ((await RecentAsync(db, couple.Id, NotificationType.PlusEnding, now - PlanAlerts.PlusQuiet, cancellationToken)).Count > 0) continue;
            var days = Math.Max(1, (int)Math.Ceiling((end - now).TotalDays));
            foreach (var userId in Members(couple))
            {
                using var _ = Loc.Use(await db.LanguageOfAsync(userId, cancellationToken));
                var body = Loc.Plural(days, "Plus ends in {count} day. Then you're both on the free plan: one daily dare each, fewer photo estimates and less album room.",
                    "Plus ends in {count} days. Then you're both on the free plan: one daily dare each, fewer photo estimates and less album room.");
                if (plan.Subscription is not null) body += " " + Loc.T("To keep it, turn renewal back on in Google Play.");
                notifications.Add(Notification.Create(userId, couple.Id, NotificationType.PlusEnding, Loc.T("Heartpact Plus ends soon"), body,
                    JsonSerializer.Serialize(new { endsAtUtc = end }), now));
            }
        }

        if (notifications.Count == 0) return 0;
        foreach (var notification in notifications) db.Notifications.Add(notification);
        await db.SaveChangesAsync(cancellationToken);
        foreach (var notification in notifications) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        return notifications.Count;
    }

    private static Task<Couple?> ActiveCoupleAsync(IApplicationDbContext db, Guid coupleId, CancellationToken cancellationToken)
        => db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, cancellationToken);

    private static IEnumerable<Guid> Members(Couple couple) => new[] { couple.UserAId, couple.UserBId }.OfType<Guid>().Distinct();

    private static Task<List<string?>> RecentAsync(IApplicationDbContext db, Guid coupleId, NotificationType type, DateTime since, CancellationToken cancellationToken)
        => db.Notifications.AsNoTracking().Where(item => item.CoupleId == coupleId && item.Type == type && item.CreatedAtUtc >= since)
            .Select(item => item.DataJson).ToListAsync(cancellationToken);

    private static int LevelOf(string? json)
    {
        try
        {
            return json is null ? 0 : JsonDocument.Parse(json).RootElement.TryGetProperty("level", out var level) && level.TryGetInt32(out var value) ? value : 0;
        }
        catch (JsonException)
        {
            return 0;
        }
    }
}
