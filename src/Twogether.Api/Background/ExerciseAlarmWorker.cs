using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
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
/// </summary>
public sealed class ExerciseAlarmWorker(
    IServiceScopeFactory scopeFactory,
    IHubContext<CoupleHub> hub,
    IDateTimeProvider clock,
    ILogger<ExerciseAlarmWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(ExerciseAlarmWorker), stoppingToken);
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                await TickAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (DbUpdateConcurrencyException exception)
            {
                // A partner edited a goal or finished an exercise in the same second. Next tick re-reads.
                logger.LogInformation(exception, "Exercise alarm tick lost a concurrency race; retrying next tick.");
            }
            catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
            {
                // Another instance opened the same occurrence first; the unique index did its job.
                logger.LogInformation(exception, "Exercise alarm tick hit a duplicate occurrence; retrying next tick.");
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Exercise alarm tick failed.");
            }
        }
    }

    private async Task TickAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var now = clock.UtcNow;
        await OpenDueAlarmsAsync(db, now, cancellationToken);
        await ExpireAsync(db, now, cancellationToken);
    }

    private async Task OpenDueAlarmsAsync(IApplicationDbContext db, DateTime now, CancellationToken cancellationToken)
    {
        // Goals stay tracked: AdvanceNextDue below must reach the database, so no
        // AsNoTracking anywhere in this query (it would switch the whole query off).
        var due = await db.ExerciseGoals
            .Where(goal => goal.IsActive && goal.NextDueAtUtc <= now
                && db.Couples.Any(couple => couple.Id == goal.CoupleId && couple.Status == CoupleStatus.Active))
            .ToListAsync(cancellationToken);
        if (due.Count == 0) return;

        var opened = new List<(ExerciseOccurrence Occurrence, Notification Notification)>();
        foreach (var goal in due)
        {
            var zone = ExerciseSchedule.ResolveTimeZone(goal.TimeZoneId);
            var scheduledAtUtc = goal.NextDueAtUtc;
            var localDate = ExerciseSchedule.LocalDate(scheduledAtUtc, zone);
            var exists = await db.ExerciseOccurrences.AsNoTracking().AnyAsync(item => item.GoalId == goal.Id && item.LocalDate == localDate, cancellationToken);
            var windowClosed = now > scheduledAtUtc.AddMinutes(goal.GraceMinutes);
            if (!exists && !windowClosed)
            {
                // Downtime never counts against anyone: a window the server slept through is skipped.
                var occurrence = ExerciseOccurrence.Open(goal, localDate, scheduledAtUtc, now);
                db.ExerciseOccurrences.Add(occurrence);
                Notification notification;
                using (Loc.Use(await db.LanguageOfAsync(goal.AssignedUserId, cancellationToken)))
                    notification = Notification.Create(goal.AssignedUserId, goal.CoupleId, NotificationType.ExerciseDue, Loc.T("Time to move"),
                        Loc.T("{dare}: {target}. The alarm stops when the camera counts you in.", ("dare", ExerciseText.Title(goal.Title, goal.ExerciseType)),
                            ("target", ExerciseText.Target(goal.Target, goal.ExerciseType))),
                        System.Text.Json.JsonSerializer.Serialize(new { occurrenceId = occurrence.Id, goalId = goal.Id }), now);
                db.Notifications.Add(notification);
                opened.Add((occurrence, notification));
            }
            else if (windowClosed && !exists)
            {
                logger.LogInformation("Skipped exercise goal {GoalId} for {LocalDate}: its window closed while the server was down.", goal.Id, localDate);
            }
            goal.AdvanceNextDue(ExerciseSchedule.NextDueUtc(goal.ScheduledTime, zone, scheduledAtUtc.AddMinutes(1)), now);
        }
        await db.SaveChangesAsync(cancellationToken);

        if (opened.Count == 0) return;
        var names = await NamesAsync(db, opened.Select(item => item.Occurrence.UserId), cancellationToken);
        foreach (var (occurrence, notification) in opened)
        {
            await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.AlarmStarted, ExerciseMapper.ToDto(occurrence, names.GetValueOrDefault(occurrence.UserId, "Your partner")), cancellationToken);
            await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        }
        logger.LogInformation("Opened {Count} exercise alarm(s).", opened.Count);
    }

    private async Task ExpireAsync(IApplicationDbContext db, DateTime now, CancellationToken cancellationToken)
    {
        var expired = await db.ExerciseOccurrences
            .Where(item => (item.Status == ExerciseOccurrenceStatus.Alarming && item.DeadlineUtc <= now)
                || (item.Status == ExerciseOccurrenceStatus.InProgress && item.CompleteByUtc != null && item.CompleteByUtc <= now))
            .ToListAsync(cancellationToken);
        if (expired.Count == 0) return;

        var coupleIds = expired.Select(item => item.CoupleId).Distinct().ToList();
        var couples = await db.Couples.AsNoTracking().Where(couple => coupleIds.Contains(couple.Id)).ToDictionaryAsync(couple => couple.Id, cancellationToken);
        var names = await NamesAsync(db, couples.Values.SelectMany(Members), cancellationToken);
        var languages = await db.LanguagesOfAsync(couples.Values.SelectMany(Members), cancellationToken);
        var notifications = new List<Notification>();
        foreach (var occurrence in expired)
        {
            occurrence.Miss(now);
            if (!couples.TryGetValue(occurrence.CoupleId, out var couple)) continue;
            var data = System.Text.Json.JsonSerializer.Serialize(new { occurrenceId = occurrence.Id, goalId = occurrence.GoalId });
            foreach (var userId in Members(couple))
            {
                using var _ = Loc.Use(languages.GetValueOrDefault(userId));
                var dare = ExerciseText.Title(occurrence.Title, occurrence.ExerciseType);
                var body = userId == occurrence.UserId
                    ? Loc.T("You missed {dare} today.", ("dare", dare))
                    : Loc.T("{name} missed {dare} today.", ("name", names.GetValueOrDefault(occurrence.UserId) ?? Loc.T("Your partner")), ("dare", dare));
                notifications.Add(Notification.Create(userId, occurrence.CoupleId, NotificationType.ExerciseMissed, Loc.T("Goal missed"), body, data, now));
            }
        }
        foreach (var notification in notifications) db.Notifications.Add(notification);
        await db.SaveChangesAsync(cancellationToken);

        foreach (var occurrence in expired)
            await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Missed, ExerciseMapper.ToDto(occurrence, names.GetValueOrDefault(occurrence.UserId, "Your partner")), cancellationToken);
        foreach (var notification in notifications)
            await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        logger.LogInformation("Marked {Count} exercise alarm(s) missed.", expired.Count);
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
