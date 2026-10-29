using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Push;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Notifications;

namespace Twogether.Api.Background;

/// <summary>
/// Sends pushes for new notifications. Claims a batch with a lease, sends to every active device
/// of each recipient, then records one decision per notification. At least once: the app drops
/// duplicates by notification id.
/// </summary>
public sealed class PushDispatchWorker(
    IServiceScopeFactory scopeFactory,
    IPushSender sender,
    IDateTimeProvider clock,
    ILogger<PushDispatchWorker> logger,
    WorkerHeartbeats? heartbeats = null) : BackgroundService
{
    private const int BatchSize = 100;
    private static readonly TimeSpan Lease = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(2);
    public const string HeartbeatName = "pushDispatch";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        heartbeats?.Register(HeartbeatName, Interval);
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(PushDispatchWorker), stoppingToken);
        logger.LogInformation("Push dispatch running with provider {Provider} (configured: {Configured}).", sender.Name, sender.IsConfigured);
        using var timer = new PeriodicTimer(Interval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                // Drain: keep going while full batches come back, so a burst does not wait 2 s per 100.
                while (await DispatchBatchAsync(stoppingToken) == BatchSize) { }
                heartbeats?.Ok(HeartbeatName, Interval);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                // Claimed rows keep their lease and come back once it expires.
                logger.LogError(exception, "Push dispatch batch failed.");
                heartbeats?.Failed(HeartbeatName, Interval, exception);
            }
        }
    }

    public async Task<int> DispatchBatchAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var outbox = scope.ServiceProvider.GetRequiredService<IPushOutbox>();
        var now = clock.UtcNow;
        var ids = await outbox.ClaimAsync(now, Lease, BatchSize, cancellationToken);
        if (ids.Count == 0) return 0;

        var notifications = await db.Notifications.Where(item => ids.Contains(item.Id)).ToListAsync(cancellationToken);
        if (!sender.IsConfigured)
        {
            // Push is off: settle the rows so turning it on later never sends a backlog.
            foreach (var notification in notifications) notification.SkipPush(now);
            await db.SaveChangesAsync(cancellationToken);
            return ids.Count;
        }

        var due = new List<Notification>();
        foreach (var notification in notifications)
        {
            if (PushPolicy.IsStale(notification, now)) notification.SkipPush(now);
            else due.Add(notification);
        }
        var userIds = due.Select(item => item.UserId).Distinct().ToList();
        var preferences = await db.NotificationPreferences.AsNoTracking()
            .Where(item => userIds.Contains(item.UserId))
            .ToDictionaryAsync(item => item.UserId, cancellationToken);
        var zones = await db.Users.AsNoTracking()
            .Where(item => userIds.Contains(item.Id))
            .ToDictionaryAsync(item => item.Id, item => item.TimeZoneId, cancellationToken);
        var routes = new Dictionary<Guid, PushRoute>();
        foreach (var notification in due.ToList())
        {
            var route = NotificationRouting.Route(notification.Type, preferences.GetValueOrDefault(notification.UserId), zones.GetValueOrDefault(notification.UserId), now);
            if (route == PushRoute.Muted)
            {
                notification.SkipPush(now);
                due.Remove(notification);
            }
            else routes[notification.Id] = route;
        }
        var devices = await db.DeviceRegistrations
            .Where(item => userIds.Contains(item.UserId) && item.DisabledAtUtc == null)
            .ToListAsync(cancellationToken);

        var planned = due
            .SelectMany(notification => devices.Where(device => device.UserId == notification.UserId)
                .Select(device => (Notification: notification, Device: device, Message: PushPolicy.Build(notification, device.Token, device.TokenKind, now, routes[notification.Id]))))
            .ToList();
        var results = planned.Count == 0 ? [] : await sender.SendAsync(planned.Select(item => item.Message).ToList(), cancellationToken);
        if (results.Count != planned.Count) throw new InvalidOperationException("The push provider returned the wrong number of results.");

        int sent = 0, retried = 0, skipped = 0, failed = 0;
        foreach (var notification in due)
        {
            var mine = planned.Select((item, index) => (item, result: results[index])).Where(pair => pair.item.Notification == notification).ToList();
            foreach (var (item, result) in mine.Where(pair => pair.result.Outcome == PushOutcome.InvalidToken))
                item.Device.Disable("unregistered", now);
            switch (PushPolicy.Decide(mine.Select(pair => pair.result.Outcome).ToList()))
            {
                case PushDecision.Sent: notification.MarkPushSent(now); sent++; break;
                case PushDecision.Skip: notification.SkipPush(now); skipped++; break;
                case PushDecision.Fail: notification.FailPush(now); failed++; break;
                case PushDecision.Retry:
                    notification.RetryPush(now + PushPolicy.RetryDelay(notification.PushAttempts), PushPolicy.MaxAttempts, now);
                    retried++;
                    break;
            }
        }
        await db.SaveChangesAsync(cancellationToken);
        logger.LogInformation("Push batch: {Claimed} claimed, {Sent} sent ({Quiet} quietly), {Retried} retrying, {Skipped} skipped, {Failed} failed, {Dropped} stale or muted.",
            ids.Count, sent, routes.Count(pair => pair.Value == PushRoute.Quiet), retried, skipped, failed, notifications.Count - due.Count);
        return ids.Count;
    }
}
