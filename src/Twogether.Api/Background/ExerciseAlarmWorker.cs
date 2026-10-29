using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Exercise;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Shared.Localization;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Api.Background;

/// <summary>
/// Owns the alarm clock. Every second it opens occurrences for goals whose time has come and
/// marks occurrences missed when their window closes. Clients never decide either on their own.
/// Each goal and each expiry is handled in its own scope and save, so one that fails (a race, a
/// bad row) is retried next tick without holding up the others.
/// </summary>
public sealed class ExerciseAlarmWorker(
    IServiceScopeFactory scopeFactory,
    IHubContext<CoupleHub> hub,
    IDateTimeProvider clock,
    ILogger<ExerciseAlarmWorker> logger,
    WorkerHeartbeats? heartbeats = null) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(1);
    public const string HeartbeatName = "exerciseAlarms";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        heartbeats?.Register(HeartbeatName, Interval);
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(ExerciseAlarmWorker), stoppingToken);
        using var timer = new PeriodicTimer(Interval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                await TickAsync(stoppingToken);
                heartbeats?.Ok(HeartbeatName, Interval);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Exercise alarm tick failed.");
                heartbeats?.Failed(HeartbeatName, Interval, exception);
            }
        }
    }

    private async Task TickAsync(CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        List<Guid> due;
        List<Guid> expired;
        await using (var scope = scopeFactory.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
            due = await db.ExerciseGoals.AsNoTracking()
                .Where(goal => goal.IsActive && goal.NextDueAtUtc <= now
                    && db.Couples.Any(couple => couple.Id == goal.CoupleId && couple.Status == CoupleStatus.Active))
                .Select(goal => goal.Id)
                .ToListAsync(cancellationToken);
            expired = await db.ExerciseOccurrences.AsNoTracking()
                .Where(item => (item.Status == ExerciseOccurrenceStatus.Alarming && item.DeadlineUtc <= now)
                    || (item.Status == ExerciseOccurrenceStatus.InProgress && item.CompleteByUtc != null && item.CompleteByUtc <= now))
                .Select(item => item.Id)
                .ToListAsync(cancellationToken);
        }

        var opened = 0;
        foreach (var goalId in due)
            if (await IsolatedAsync("goal", goalId, db => OpenDueAlarmAsync(db, goalId, now, cancellationToken), cancellationToken)) opened++;
        var missed = 0;
        foreach (var occurrenceId in expired)
            if (await IsolatedAsync("occurrence", occurrenceId, db => ExpireAsync(db, occurrenceId, now, cancellationToken), cancellationToken)) missed++;
        if (opened > 0) logger.LogInformation("Handled {Count} due exercise goal(s).", opened);
        if (missed > 0) logger.LogInformation("Marked {Count} exercise alarm(s) missed.", missed);
    }

    /// <summary>Runs one unit of work in its own scope. Returns false when it failed; the next tick tries again.</summary>
    private async Task<bool> IsolatedAsync(string what, Guid id, Func<IApplicationDbContext, Task<bool>> work, CancellationToken cancellationToken)
    {
        try
        {
            await using var scope = scopeFactory.CreateAsyncScope();
            return await work(scope.ServiceProvider.GetRequiredService<IApplicationDbContext>());
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (DbUpdateConcurrencyException exception)
        {
            // A partner edited the goal or finished the exercise in the same second. Next tick re-reads.
            logger.LogInformation(exception, "Exercise alarm worker lost a concurrency race on {What} {Id}; retrying next tick.", what, id);
        }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            // Another instance opened the same occurrence first; the unique index did its job.
            logger.LogInformation(exception, "Exercise alarm worker hit a duplicate occurrence on {What} {Id}; retrying next tick.", what, id);
        }
        catch (Exception exception)
        {
            logger.LogError(exception, "Exercise alarm worker failed on {What} {Id}; the others go on.", what, id);
        }
        return false;
    }

    private async Task<bool> OpenDueAlarmAsync(IApplicationDbContext db, Guid goalId, DateTime now, CancellationToken cancellationToken)
    {
        // Tracked: AdvanceNextDue below must reach the database (and its xmin guards against the controller).
        var goal = await db.ExerciseGoals
            .Where(item => item.Id == goalId && item.IsActive && item.NextDueAtUtc <= now
                && db.Couples.Any(couple => couple.Id == item.CoupleId && couple.Status == CoupleStatus.Active))
            .SingleOrDefaultAsync(cancellationToken);
        if (goal is null) return false;

        var zone = ExerciseSchedule.ResolveTimeZone(goal.TimeZoneId);
        var scheduledAtUtc = goal.NextDueAtUtc;
        var localDate = ExerciseSchedule.LocalDate(scheduledAtUtc, zone);
        var existing = await db.ExerciseOccurrences.AsNoTracking().SingleOrDefaultAsync(item => item.GoalId == goal.Id && item.LocalDate == localDate, cancellationToken);
        ExerciseOccurrence? occurrence = null;
        Notification? notification = null;
        switch (ExerciseAlarmRules.Decide(existing, scheduledAtUtc, goal.GraceMinutes, now))
        {
            case DueAction.Open:
                occurrence = ExerciseOccurrence.Open(goal, localDate, scheduledAtUtc, now);
                db.ExerciseOccurrences.Add(occurrence);
                notification = await DueNotificationAsync(db, goal, occurrence.Id, now, cancellationToken);
                break;
            case DueAction.RingStartedEarly:
                // The dare was started early and left: the phone's own alarm should ring now, and this push makes sure it does.
                notification = await DueNotificationAsync(db, goal, existing!.Id, now, cancellationToken);
                break;
            case DueAction.SkipDowntime:
                // Downtime never counts against anyone: a window the server slept through is skipped.
                logger.LogInformation("Skipped exercise goal {GoalId} for {LocalDate}: its window closed while the server was down.", goal.Id, localDate);
                break;
        }
        if (notification is not null) db.Notifications.Add(notification);
        goal.AdvanceNextDue(ExerciseSchedule.NextDueUtc(goal.ScheduledTime, zone, scheduledAtUtc.AddMinutes(1)), now);
        await db.SaveChangesAsync(cancellationToken);

        if (occurrence is not null)
        {
            var names = await NamesAsync(db, [occurrence.UserId], cancellationToken);
            await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.AlarmStarted, ExerciseMapper.ToDto(occurrence, names.GetValueOrDefault(occurrence.UserId) ?? Loc.T("Your partner")), cancellationToken);
        }
        if (notification is not null) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        return true;
    }

    private static async Task<Notification> DueNotificationAsync(IApplicationDbContext db, ExerciseGoal goal, Guid occurrenceId, DateTime now, CancellationToken cancellationToken)
    {
        using (Loc.Use(await db.LanguageOfAsync(goal.AssignedUserId, cancellationToken)))
            return Notification.Create(goal.AssignedUserId, goal.CoupleId, NotificationType.ExerciseDue, Loc.T("Time to move"),
                Loc.T("{dare}: {target}. The alarm stops when the camera counts you in.", ("dare", ExerciseText.Title(goal.Title, goal.ExerciseType)),
                    ("target", ExerciseText.Target(goal.Target, goal.ExerciseType))),
                System.Text.Json.JsonSerializer.Serialize(new { occurrenceId, goalId = goal.Id }), now);
    }

    private async Task<bool> ExpireAsync(IApplicationDbContext db, Guid occurrenceId, DateTime now, CancellationToken cancellationToken)
    {
        var occurrence = await db.ExerciseOccurrences
            .Where(item => item.Id == occurrenceId
                && ((item.Status == ExerciseOccurrenceStatus.Alarming && item.DeadlineUtc <= now)
                    || (item.Status == ExerciseOccurrenceStatus.InProgress && item.CompleteByUtc != null && item.CompleteByUtc <= now)))
            .SingleOrDefaultAsync(cancellationToken);
        if (occurrence is null) return false;

        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == occurrence.CoupleId, cancellationToken);
        var members = couple is null ? [] : Members(couple).ToList();
        var names = await NamesAsync(db, members, cancellationToken);
        var languages = await db.LanguagesOfAsync(members, cancellationToken);
        var notifications = new List<Notification>();
        occurrence.Miss(now);
        var data = System.Text.Json.JsonSerializer.Serialize(new { occurrenceId = occurrence.Id, goalId = occurrence.GoalId });
        foreach (var userId in members)
        {
            using var _ = Loc.Use(languages.GetValueOrDefault(userId));
            var dare = ExerciseText.Title(occurrence.Title, occurrence.ExerciseType);
            var body = userId == occurrence.UserId
                ? Loc.T("You missed {dare} today.", ("dare", dare))
                : Loc.T("{name} missed {dare} today.", ("name", names.GetValueOrDefault(occurrence.UserId) ?? Loc.T("Your partner")), ("dare", dare));
            notifications.Add(Notification.Create(userId, occurrence.CoupleId, NotificationType.ExerciseMissed, Loc.T("Dare missed"), body, data, now));
        }
        foreach (var notification in notifications) db.Notifications.Add(notification);
        await db.SaveChangesAsync(cancellationToken);

        await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Missed, ExerciseMapper.ToDto(occurrence, (names.GetValueOrDefault(occurrence.UserId) ?? Loc.T("Your partner"))), cancellationToken);
        foreach (var notification in notifications)
            await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        return true;
    }

    private static async Task<Dictionary<Guid, string>> NamesAsync(IApplicationDbContext db, IEnumerable<Guid> userIds, CancellationToken cancellationToken)
    {
        var ids = userIds.Distinct().ToList();
        return await db.Users.AsNoTracking().Where(user => ids.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.DisplayName, cancellationToken);
    }

    private static IEnumerable<Guid> Members(Couple couple)
    {
        yield return couple.UserAId;
        if (couple.UserBId.HasValue) yield return couple.UserBId.Value;
    }
}
