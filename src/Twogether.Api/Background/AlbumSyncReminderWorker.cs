using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Memories;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Memories;
using Twogether.Core.Notifications;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Localization;
using Twogether.Application.Features.Notifications;

namespace Twogether.Api.Background;

/// <summary>
/// Nudges people whose phone has had photos waiting to sync for a while, at most once a day.
/// Nothing uploads by itself: the reminder only asks, and the app holds it for Wi-Fi.
/// Several API instances can run this at once; a conditional claim lets exactly one send.
/// </summary>
public sealed class AlbumSyncReminderWorker(
    IServiceScopeFactory scopeFactory,
    IHubContext<CoupleHub> hub,
    IDateTimeProvider clock,
    ILogger<AlbumSyncReminderWorker> logger) : BackgroundService
{
    private const int BatchSize = 100;
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan StartupDelay = TimeSpan.FromMinutes(1);

    public sealed record RunSummary(int Candidates, int Sent, int SkippedCategoryOff, int SkippedQuietHours, int SkippedNotDue, int SkippedClaimedElsewhere, int Failed);

    private sealed record Candidate(AlbumSyncState State, Guid CoupleId, Guid? PartnerId, string TimeZoneId);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(AlbumSyncReminderWorker), stoppingToken);
        try
        {
            // Shortly after startup rather than at once, so a deploy does not compete with warm-up.
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
                await RunAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Album sync reminder run failed.");
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    public async Task<RunSummary> RunAsync(CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        int candidates = 0, sent = 0, categoryOff = 0, quiet = 0, notDue = 0, claimedElsewhere = 0, failed = 0;
        Guid? after = null;
        while (true)
        {
            await using var scope = scopeFactory.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
            var page = await CandidatesAsync(db, now, after, cancellationToken);
            if (page.Count == 0) break;
            // Keyset paging: people skipped for quiet hours stay due, and must not be fetched again this run.
            after = page[^1].State.UserId;
            candidates += page.Count;

            var userIds = page.Select(item => item.State.UserId).ToList();
            var preferences = await db.NotificationPreferences.AsNoTracking()
                .Where(item => userIds.Contains(item.UserId))
                .ToDictionaryAsync(item => item.UserId, cancellationToken);
            var partnerIds = page.Where(item => item.PartnerId.HasValue).Select(item => item.PartnerId!.Value).Distinct().ToList();
            var partnerNames = await db.Users.AsNoTracking()
                .Where(item => partnerIds.Contains(item.Id))
                .ToDictionaryAsync(item => item.Id, item => item.DisplayName, cancellationToken);

            var notices = new List<Notification>();
            foreach (var candidate in page)
            {
                if (!AlbumSyncRules.IsDueForReminder(candidate.State, now)) { notDue++; continue; }
                // Left unclaimed in both cases, so a later run tries again.
                switch (NotificationRouting.Route(NotificationType.AlbumSyncReminder, preferences.GetValueOrDefault(candidate.State.UserId), candidate.TimeZoneId, now))
                {
                    case PushRoute.Muted: categoryOff++; continue;
                    case PushRoute.Quiet: quiet++; continue;
                }
                try
                {
                    var partnerName = candidate.PartnerId is { } partner ? partnerNames.GetValueOrDefault(partner, "your partner") : "your partner";
                    var notice = await TryRemindAsync(db, candidate, partnerName, now, cancellationToken);
                    if (notice is null) claimedElsewhere++;
                    else
                    {
                        sent++;
                        notices.Add(notice);
                    }
                }
                catch (Exception exception) when (exception is not OperationCanceledException)
                {
                    failed++;
                    db.ChangeTracker.Clear();
                    logger.LogWarning(exception, "Could not send the album sync reminder to user {UserId}; will retry next run.", candidate.State.UserId);
                }
            }

            foreach (var notice in notices)
            {
                try
                {
                    await ExerciseEvents.NotificationAsync(hub, notice, cancellationToken);
                }
                catch (Exception exception) when (exception is not OperationCanceledException)
                {
                    // The row is saved and the push goes out anyway; only the live in-app copy is lost.
                    logger.LogWarning(exception, "Could not announce album sync reminder {NotificationId} live.", notice.Id);
                }
            }
            if (page.Count < BatchSize) break;
        }

        var summary = new RunSummary(candidates, sent, categoryOff, quiet, notDue, claimedElsewhere, failed);
        logger.LogInformation(
            "Album sync reminders: {Candidates} candidate(s), {Sent} sent; skipped {CategoryOff} album category off, {QuietHours} in quiet hours, {NotDue} not due, {ClaimedElsewhere} claimed by another instance; {Failed} failed.",
            summary.Candidates, summary.Sent, summary.SkippedCategoryOff, summary.SkippedQuietHours, summary.SkippedNotDue, summary.SkippedClaimedElsewhere, summary.Failed);
        return summary;
    }

    /// <summary>The time rules from <see cref="AlbumSyncRules"/>, in SQL, for people in an active couple.</summary>
    private static Task<List<Candidate>> CandidatesAsync(ApplicationDbContext db, DateTime now, Guid? after, CancellationToken cancellationToken)
    {
        var waitingSince = now - AlbumSyncRules.WaitingBeforeReminder;
        var reportedAfter = now - AlbumSyncRules.ReportFreshness;
        var promptedBefore = now - AlbumSyncRules.ReminderInterval;
        var states = db.AlbumSyncStates.AsNoTracking()
            .Where(state => state.PendingCount > 0
                && state.PendingSinceUtc <= waitingSince
                && state.ReportedAtUtc >= reportedAfter
                && (state.LastPromptedAtUtc == null || state.LastPromptedAtUtc <= promptedBefore));
        if (after is { } last) states = states.Where(state => state.UserId.CompareTo(last) > 0);
        return (from state in states
                join user in db.Users.AsNoTracking() on state.UserId equals user.Id
                join couple in db.Couples.AsNoTracking() on user.CoupleId equals (Guid?)couple.Id
                where couple.Status == CoupleStatus.Active
                orderby state.UserId
                select new Candidate(state, couple.Id, couple.UserAId == user.Id ? couple.UserBId : couple.UserAId, user.TimeZoneId))
            .Take(BatchSize)
            .ToListAsync(cancellationToken);
    }

    /// <summary>
    /// Claims the reminder with a conditional update and writes the notification (the push
    /// outbox) in the same transaction. Null when another instance claimed it first, or the
    /// person synced in the meantime.
    /// </summary>
    private static async Task<Notification?> TryRemindAsync(ApplicationDbContext db, Candidate candidate, string partnerName, DateTime now, CancellationToken cancellationToken)
    {
        var userId = candidate.State.UserId;
        var promptedBefore = now - AlbumSyncRules.ReminderInterval;
        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        var claimed = await db.AlbumSyncStates
            .Where(state => state.UserId == userId && state.PendingCount > 0
                && (state.LastPromptedAtUtc == null || state.LastPromptedAtUtc <= promptedBefore))
            .ExecuteUpdateAsync(set => set.SetProperty(state => state.LastPromptedAtUtc, now), cancellationToken);
        if (claimed != 1) return null;

        var pending = candidate.State.PendingCount;
        using var _ = Loc.Use(await db.LanguageOfAsync(userId, cancellationToken));
        var notice = Notification.Create(userId, candidate.CoupleId, NotificationType.AlbumSyncReminder, MemorySyncText.ReminderTitle,
            MemorySyncText.ReminderBody(pending, partnerName),
            JsonSerializer.Serialize(new { pendingCount = pending, preferWifi = true }), now);
        db.Notifications.Add(notice);
        await db.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return notice;
    }
}
