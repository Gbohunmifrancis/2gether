using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Shared.Results;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class ExerciseController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    IHubContext<CoupleHub> hub,
    ILogger<ExerciseController> logger) : ApiControllerBase
{
    private static readonly ApiError NoCouple = new("exercise.no_couple", "Link your partner before setting goals.");
    private static readonly ApiError AlarmActive = new("exercise.alarm_active", "This goal is ringing for you right now. Finish it on camera, or ask your partner to let you off today.");
    private static readonly ApiError NotActive = new("exercise.not_active", "This alarm is no longer active.");
    private static readonly ApiError NotAssignee = new("exercise.not_assignee", "Only the person this alarm is for can do that.");
    private static readonly ApiError NotDueToday = new("exercise.not_due_today", "Today's dare has already rung or is done. Start it from the alarm, or try again tomorrow.");
    private static readonly ApiError AlreadyAsked = new("exercise.already_asked", "You already asked to call off today.");
    private static readonly ApiError VersionConflict = new("exercise.version_conflict", "Someone else just changed this. Refresh and try again.");

    [HttpGet("catalog")]
    public ActionResult<IReadOnlyList<ExerciseDefinitionDto>> Catalog()
        => Ok(ExerciseCatalog.All.Select(ExerciseMapper.ToDto).ToList());

    [HttpGet("goals")]
    public async Task<ActionResult<IReadOnlyList<ExerciseGoalDto>>> Goals([FromQuery] bool includeInactive, CancellationToken cancellationToken)
    {
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(Array.Empty<ExerciseGoalDto>());
        var names = await NamesAsync(couple, cancellationToken);
        var goals = await db.ExerciseGoals.AsNoTracking()
            .Where(goal => goal.CoupleId == couple.Id && (includeInactive || goal.IsActive))
            .OrderByDescending(goal => goal.IsActive).ThenBy(goal => goal.ScheduledTime)
            .ToListAsync(cancellationToken);
        return Ok(goals.Select(goal => ExerciseMapper.ToDto(goal, Name(names, goal.AssignedUserId))).ToList());
    }

    [HttpPost("goals")]
    public async Task<ActionResult<ExerciseGoalDto>> CreateGoal(CreateExerciseGoalRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var time = ExerciseGoalRules.ParseScheduledTime(request.ScheduledTime);
        if (time.IsFailure) return Problem(time.Error);
        var rules = ExerciseGoalRules.Validate(request.ExerciseType, request.Title, request.Target, request.TimeZoneId, request.GraceMinutes);
        if (rules.IsFailure) return Problem(rules.Error);
        if (!IsMember(couple, request.AssignedUserId)) return UnprocessableEntity(new ApiError("exercise.invalid_assignee", "Goals can only be assigned to you or your partner."));

        var now = clock.UtcNow;
        var goal = ExerciseGoal.Create(couple.Id, request.AssignedUserId, currentUser.UserId.Value, request.ExerciseType, request.Title, request.Target, time.Value, request.TimeZoneId.Trim(), request.GraceMinutes, now);
        db.ExerciseGoals.Add(goal);
        var names = await NamesAsync(couple, cancellationToken);
        var notification = GoalChangedNotification(couple, names, goal, "set", now);
        if (notification is not null) db.Notifications.Add(notification);
        await db.SaveChangesAsync(cancellationToken);

        if (notification is not null) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        await BroadcastGoalsAsync(couple, names, cancellationToken);
        return Ok(ExerciseMapper.ToDto(goal, Name(names, goal.AssignedUserId)));
    }

    [HttpPut("goals/{goalId:guid}")]
    public async Task<ActionResult<ExerciseGoalDto>> UpdateGoal(Guid goalId, UpdateExerciseGoalRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var goal = await db.ExerciseGoals.SingleOrDefaultAsync(item => item.Id == goalId && item.CoupleId == couple.Id, cancellationToken);
        if (goal is null) return NotFound();
        var time = ExerciseGoalRules.ParseScheduledTime(request.ScheduledTime);
        if (time.IsFailure) return Problem(time.Error);
        var rules = ExerciseGoalRules.Validate(request.ExerciseType, request.Title, request.Target, request.TimeZoneId, request.GraceMinutes);
        if (rules.IsFailure) return Problem(rules.Error);
        if (!IsMember(couple, request.AssignedUserId)) return UnprocessableEntity(new ApiError("exercise.invalid_assignee", "Goals can only be assigned to you or your partner."));

        var now = clock.UtcNow;
        var deactivating = goal.IsActive && !request.IsActive;
        var reshaped = goal.AssignedUserId != request.AssignedUserId
            || goal.ExerciseType != request.ExerciseType
            || goal.Target != request.Target
            || goal.ScheduledTime != time.Value;
        var ringing = await ActiveOccurrenceAsync(goal.Id, cancellationToken);
        if (ringing is not null && goal.AssignedUserId == currentUser.UserId.Value && (deactivating || reshaped))
            return Conflict(AlarmActive);

        var names = await NamesAsync(couple, cancellationToken);
        Notification? cancelled = null;
        if (ringing is not null && (deactivating || reshaped))
        {
            ringing.Cancel(now);
            cancelled = CancelledNotification(couple, names, ringing, now);
            db.Notifications.Add(cancelled);
        }

        goal.Update(request.AssignedUserId, request.ExerciseType, request.Title, request.Target, time.Value, request.TimeZoneId.Trim(), request.GraceMinutes, now);
        if (!request.IsActive) goal.Deactivate(now);
        else if (!goal.IsActive) goal.Reactivate(now);
        var changed = GoalChangedNotification(couple, names, goal, request.IsActive ? "updated" : "paused", now);
        if (changed is not null) db.Notifications.Add(changed);

        var conflict = await TrySaveAsync(cancellationToken);
        if (conflict is not null) return conflict;

        if (ringing is not null && cancelled is not null)
        {
            await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Cancelled, ExerciseMapper.ToDto(ringing, Name(names, ringing.UserId)), cancellationToken);
            await ExerciseEvents.NotificationAsync(hub, cancelled, cancellationToken);
        }
        if (changed is not null) await ExerciseEvents.NotificationAsync(hub, changed, cancellationToken);
        await BroadcastGoalsAsync(couple, names, cancellationToken);
        return Ok(ExerciseMapper.ToDto(goal, Name(names, goal.AssignedUserId)));
    }

    [HttpDelete("goals/{goalId:guid}")]
    public async Task<IActionResult> DeleteGoal(Guid goalId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var goal = await db.ExerciseGoals.SingleOrDefaultAsync(item => item.Id == goalId && item.CoupleId == couple.Id, cancellationToken);
        if (goal is null) return NotFound();
        if (!goal.IsActive) return NoContent();

        var now = clock.UtcNow;
        var ringing = await ActiveOccurrenceAsync(goal.Id, cancellationToken);
        if (ringing is not null && goal.AssignedUserId == currentUser.UserId.Value) return Conflict(AlarmActive);

        var names = await NamesAsync(couple, cancellationToken);
        Notification? cancelled = null;
        if (ringing is not null)
        {
            ringing.Cancel(now);
            cancelled = CancelledNotification(couple, names, ringing, now);
            db.Notifications.Add(cancelled);
        }
        goal.Deactivate(now);
        var conflict = await TrySaveAsync(cancellationToken);
        if (conflict is not null) return conflict;

        if (ringing is not null && cancelled is not null)
        {
            await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Cancelled, ExerciseMapper.ToDto(ringing, Name(names, ringing.UserId)), cancellationToken);
            await ExerciseEvents.NotificationAsync(hub, cancelled, cancellationToken);
        }
        await BroadcastGoalsAsync(couple, names, cancellationToken);
        return NoContent();
    }

    [HttpGet("today")]
    public async Task<ActionResult<ExerciseTodayDto>> Today(CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(new ExerciseTodayDto(now, [], [], null, null));
        var names = await NamesAsync(couple, cancellationToken);
        var goals = await db.ExerciseGoals.AsNoTracking()
            .Where(goal => goal.CoupleId == couple.Id && goal.IsActive)
            .OrderBy(goal => goal.ScheduledTime)
            .ToListAsync(cancellationToken);

        // "Today" differs per goal because each goal has its own time zone. Pull a day either
        // side of the UTC date and decide per goal in memory; it is at most a handful of rows.
        var utcDate = DateOnly.FromDateTime(now);
        var window = await db.ExerciseOccurrences.AsNoTracking()
            .Where(item => item.CoupleId == couple.Id
                && (item.Status == ExerciseOccurrenceStatus.Alarming
                    || item.Status == ExerciseOccurrenceStatus.InProgress
                    || (item.LocalDate >= utcDate.AddDays(-1) && item.LocalDate <= utcDate.AddDays(1))))
            .ToListAsync(cancellationToken);
        var localToday = goals.ToDictionary(goal => goal.Id, goal => ExerciseSchedule.LocalDate(now, ExerciseSchedule.ResolveTimeZone(goal.TimeZoneId)));
        var occurrences = window
            .Where(item => item.IsAlarmActive || (localToday.TryGetValue(item.GoalId, out var today) && item.LocalDate == today))
            .OrderBy(item => item.ScheduledAtUtc)
            .Select(item => ExerciseMapper.ToDto(item, Name(names, item.UserId)))
            .ToList();
        var next = goals.MinBy(goal => goal.NextDueAtUtc);
        return Ok(new ExerciseTodayDto(
            now,
            goals.Select(goal => ExerciseMapper.ToDto(goal, Name(names, goal.AssignedUserId))).ToList(),
            occurrences,
            next?.NextDueAtUtc,
            next?.Id));
    }

    [HttpGet("history")]
    public async Task<ActionResult<ExerciseHistoryDto>> History([FromQuery] int days = 30, CancellationToken cancellationToken = default)
    {
        days = Math.Clamp(days, 1, 365);
        var now = clock.UtcNow;
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(new ExerciseHistoryDto(days, [], []));
        var names = await NamesAsync(couple, cancellationToken);
        var since = now.AddDays(-days);
        var occurrences = await db.ExerciseOccurrences.AsNoTracking()
            .Where(item => item.CoupleId == couple.Id && item.ScheduledAtUtc >= since)
            .OrderByDescending(item => item.ScheduledAtUtc)
            .ToListAsync(cancellationToken);
        var goals = await db.ExerciseGoals.AsNoTracking()
            .Where(goal => goal.CoupleId == couple.Id)
            .OrderByDescending(goal => goal.UpdatedAtUtc)
            .ToListAsync(cancellationToken);

        var stats = new List<ExercisePartnerStatsDto>();
        foreach (var userId in Members(couple))
        {
            var today = ExerciseStats.TodayFor(userId, goals, occurrences, now);
            stats.Add(ExerciseStats.Compute(userId, Name(names, userId), occurrences, today));
        }
        return Ok(new ExerciseHistoryDto(days, occurrences.Select(item => ExerciseMapper.ToDto(item, Name(names, item.UserId))).ToList(), stats));
    }

    [HttpPost("occurrences/{occurrenceId:guid}/start")]
    public async Task<ActionResult<ExerciseOccurrenceDto>> Start(Guid occurrenceId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var occurrence = await db.ExerciseOccurrences.SingleOrDefaultAsync(item => item.Id == occurrenceId && item.CoupleId == couple.Id, cancellationToken);
        if (occurrence is null) return NotFound();
        if (occurrence.UserId != currentUser.UserId.Value) return Forbid();
        var now = clock.UtcNow;
        if (!occurrence.IsAlarmActive || (occurrence.Status == ExerciseOccurrenceStatus.Alarming && occurrence.DeadlineUtc <= now))
            return Conflict(NotActive);

        occurrence.Start(now);
        var conflict = await TrySaveAsync(cancellationToken);
        if (conflict is not null) return conflict;
        var names = await NamesAsync(couple, cancellationToken);
        var dto = ExerciseMapper.ToDto(occurrence, Name(names, occurrence.UserId));
        await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Started, dto, cancellationToken);
        return Ok(dto);
    }

    /// <summary>
    /// Assigned user only: does today's dare before it rings. Opens the day's occurrence with its
    /// real due time and starts it at once; the alarm pointer stays, so giving up still lets it ring.
    /// </summary>
    [HttpPost("goals/{goalId:guid}/start-early")]
    public async Task<ActionResult<ExerciseOccurrenceDto>> StartEarly(Guid goalId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var goal = await db.ExerciseGoals.AsNoTracking().SingleOrDefaultAsync(item => item.Id == goalId && item.CoupleId == couple.Id && item.IsActive, cancellationToken);
        if (goal is null) return NotFound();
        if (goal.AssignedUserId != currentUser.UserId.Value) return StatusCode(StatusCodes.Status403Forbidden, NotAssignee);
        var now = clock.UtcNow;
        if (!goal.IsDueLaterToday(now)) return Conflict(NotDueToday);

        var localDate = goal.NextDueLocalDate();
        var occurrence = await db.ExerciseOccurrences.SingleOrDefaultAsync(item => item.GoalId == goal.Id && item.LocalDate == localDate, cancellationToken);
        if (occurrence is null)
        {
            occurrence = ExerciseOccurrence.OpenEarly(goal, now);
            db.ExerciseOccurrences.Add(occurrence);
        }
        else if (occurrence.IsAlarmActive) occurrence.Start(now);
        else return Conflict(NotActive);

        var conflict = await TrySaveAsync(cancellationToken);
        if (conflict is not null) return conflict;
        var names = await NamesAsync(couple, cancellationToken);
        var dto = ExerciseMapper.ToDto(occurrence, Name(names, occurrence.UserId));
        await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Started, dto, cancellationToken);
        return Ok(dto);
    }

    [HttpPost("occurrences/{occurrenceId:guid}/complete")]
    public async Task<ActionResult<ExerciseOccurrenceDto>> Complete(Guid occurrenceId, CompleteExerciseRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var occurrence = await db.ExerciseOccurrences.SingleOrDefaultAsync(item => item.Id == occurrenceId && item.CoupleId == couple.Id, cancellationToken);
        if (occurrence is null) return NotFound();
        if (occurrence.UserId != currentUser.UserId.Value) return Forbid();
        if (occurrence.Status != ExerciseOccurrenceStatus.InProgress) return Conflict(NotActive);

        var now = clock.UtcNow;
        var achieved = Math.Clamp(request.AchievedCount, 0, 10000);
        var confidence = Math.Clamp(request.Confidence, 0, 1);
        var verdict = ExerciseVerification.Validate(occurrence.ExerciseType, occurrence.Target, achieved, confidence, occurrence.StartedAtUtc, now);
        if (verdict.IsFailure)
        {
            logger.LogInformation("Exercise completion rejected for occurrence {OccurrenceId}: {Code}", occurrence.Id, verdict.Error.Code);
            return Problem(verdict.Error);
        }

        occurrence.Complete(achieved, confidence, now);
        var settled = await SettleGoalAsync(occurrence, now, cancellationToken);
        var names = await NamesAsync(couple, cancellationToken);
        var partnerId = PartnerOf(couple, occurrence.UserId);
        Notification? notification = null;
        if (partnerId is { } partner)
        {
            notification = Notification.Create(partner, couple.Id, NotificationType.ExerciseCompleted, "Goal done",
                $"{Name(names, occurrence.UserId)} finished {occurrence.Title}: {FormatTarget(occurrence)}.",
                System.Text.Json.JsonSerializer.Serialize(new { occurrenceId = occurrence.Id, goalId = occurrence.GoalId }), now);
            db.Notifications.Add(notification);
        }
        var conflict = await TrySaveAsync(cancellationToken);
        if (conflict is not null) return conflict;

        var dto = ExerciseMapper.ToDto(occurrence, Name(names, occurrence.UserId));
        await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Completed, dto, cancellationToken);
        if (notification is not null) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        if (settled) await BroadcastGoalsAsync(couple, names, cancellationToken);
        return Ok(dto);
    }

    /// <summary>Partner only: lets the assigned user off for today. They cannot do it themselves.</summary>
    [HttpPost("occurrences/{occurrenceId:guid}/cancel")]
    public async Task<ActionResult<ExerciseOccurrenceDto>> Cancel(Guid occurrenceId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var occurrence = await db.ExerciseOccurrences.SingleOrDefaultAsync(item => item.Id == occurrenceId && item.CoupleId == couple.Id, cancellationToken);
        if (occurrence is null) return NotFound();
        if (occurrence.UserId == currentUser.UserId.Value) return Forbid();
        if (!occurrence.IsAlarmActive) return Conflict(NotActive);

        var now = clock.UtcNow;
        occurrence.Cancel(now);
        var settled = await SettleGoalAsync(occurrence, now, cancellationToken);
        var names = await NamesAsync(couple, cancellationToken);
        var notification = CancelledNotification(couple, names, occurrence, now);
        db.Notifications.Add(notification);
        var conflict = await TrySaveAsync(cancellationToken);
        if (conflict is not null) return conflict;

        var dto = ExerciseMapper.ToDto(occurrence, Name(names, occurrence.UserId));
        await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Cancelled, dto, cancellationToken);
        await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        if (settled) await BroadcastGoalsAsync(couple, names, cancellationToken);
        return Ok(dto);
    }

    /// <summary>
    /// Assigned user only: asks the partner to call off today. Advisory; the partner grants it
    /// with <c>cancel</c>. Asked once per occurrence.
    /// </summary>
    [HttpPost("occurrences/{occurrenceId:guid}/ask-off")]
    public async Task<ActionResult<ExerciseOccurrenceDto>> AskOff(Guid occurrenceId, AskCallOffRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var occurrence = await db.ExerciseOccurrences.SingleOrDefaultAsync(item => item.Id == occurrenceId && item.CoupleId == couple.Id, cancellationToken);
        if (occurrence is null) return NotFound();
        if (occurrence.UserId != currentUser.UserId.Value) return StatusCode(StatusCodes.Status403Forbidden, NotAssignee);
        var now = clock.UtcNow;
        if (!occurrence.IsAlarmActive
            || (occurrence.Status == ExerciseOccurrenceStatus.Alarming && occurrence.DeadlineUtc <= now)
            || (occurrence.Status == ExerciseOccurrenceStatus.InProgress && occurrence.CompleteByUtc <= now))
            return Conflict(NotActive);
        if (occurrence.CallOffRequestedAtUtc.HasValue) return Conflict(AlreadyAsked);
        var note = ExerciseGoalRules.CallOffNote(request.Note);
        if (note.IsFailure) return Problem(note.Error);

        occurrence.RequestCallOff(note.Value, now);
        var names = await NamesAsync(couple, cancellationToken);
        var partnerId = PartnerOf(couple, occurrence.UserId);
        Notification? notification = null;
        if (partnerId is { } partner)
        {
            var asker = Name(names, occurrence.UserId);
            notification = Notification.Create(partner, couple.Id, NotificationType.ExerciseCallOffAsked,
                $"{asker} asks to call off today's {InSentence(occurrence)}",
                note.Value ?? $"Let {asker} off today, or keep the alarm ringing.",
                System.Text.Json.JsonSerializer.Serialize(new { occurrenceId = occurrence.Id, goalId = occurrence.GoalId }), now);
            db.Notifications.Add(notification);
        }
        var conflict = await TrySaveAsync(cancellationToken);
        if (conflict is not null) return conflict;

        var dto = ExerciseMapper.ToDto(occurrence, Name(names, occurrence.UserId));
        await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.CallOffAsked, dto, cancellationToken);
        if (notification is not null) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        return Ok(dto);
    }

    private async Task<Couple?> ActiveCoupleAsync(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return null;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        return couple is not null && IsMember(couple, currentUser.UserId.Value) ? couple : null;
    }

    private Task<ExerciseOccurrence?> ActiveOccurrenceAsync(Guid goalId, CancellationToken cancellationToken)
        => db.ExerciseOccurrences.SingleOrDefaultAsync(item => item.GoalId == goalId
            && (item.Status == ExerciseOccurrenceStatus.Alarming || item.Status == ExerciseOccurrenceStatus.InProgress), cancellationToken);

    /// <summary>
    /// Settled before it rang: move the goal's pointer past the occurrence so that day's alarm is
    /// dropped. The goal is tracked, so its xmin token makes a race with the worker a 409.
    /// </summary>
    private async Task<bool> SettleGoalAsync(ExerciseOccurrence occurrence, DateTime now, CancellationToken cancellationToken)
    {
        var goal = await db.ExerciseGoals.SingleOrDefaultAsync(item => item.Id == occurrence.GoalId && item.CoupleId == occurrence.CoupleId, cancellationToken);
        if (goal is null || !goal.SettleOccurrence(occurrence.ScheduledAtUtc, now)) return false;
        logger.LogInformation("Exercise goal {GoalId} settled before it rang; next alarm {NextDueAtUtc:o}.", goal.Id, goal.NextDueAtUtc);
        return true;
    }

    private async Task<Dictionary<Guid, string>> NamesAsync(Couple couple, CancellationToken cancellationToken)
    {
        var ids = Members(couple).ToList();
        return await db.Users.AsNoTracking().Where(user => ids.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.DisplayName, cancellationToken);
    }

    private async Task BroadcastGoalsAsync(Couple couple, Dictionary<Guid, string> names, CancellationToken cancellationToken)
    {
        var active = await db.ExerciseGoals.AsNoTracking()
            .Where(goal => goal.CoupleId == couple.Id && goal.IsActive)
            .OrderBy(goal => goal.ScheduledTime)
            .ToListAsync(cancellationToken);
        await ExerciseEvents.GoalsChangedAsync(hub, couple.Id, active.Select(goal => ExerciseMapper.ToDto(goal, Name(names, goal.AssignedUserId))).ToList(), cancellationToken);
    }

    private async Task<ActionResult?> TrySaveAsync(CancellationToken cancellationToken)
    {
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return null;
        }
        catch (DbUpdateConcurrencyException exception)
        {
            logger.LogInformation(exception, "Exercise write lost a concurrency race for user {UserId}.", currentUser.UserId);
            return Conflict(VersionConflict);
        }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            // The worker (or another device) opened the same day's occurrence first.
            logger.LogInformation(exception, "Exercise write hit a duplicate occurrence for user {UserId}.", currentUser.UserId);
            return Conflict(VersionConflict);
        }
    }

    private Notification? GoalChangedNotification(Couple couple, Dictionary<Guid, string> names, ExerciseGoal goal, string verb, DateTime now)
    {
        var actorId = currentUser.UserId!.Value;
        var partnerId = PartnerOf(couple, actorId);
        if (partnerId is null) return null;
        var target = goal.AssignedUserId == partnerId ? "for you" : goal.AssignedUserId == actorId ? "for themselves" : "";
        var body = $"{Name(names, actorId)} {verb} {goal.Title} {target}: {FormatTarget(goal.Target, goal.ExerciseType)} at {goal.ScheduledTime:HH\\:mm}.".Replace("  ", " ");
        return Notification.Create(partnerId.Value, couple.Id, NotificationType.ExerciseGoalChanged, "Goal " + verb, body,
            System.Text.Json.JsonSerializer.Serialize(new { goalId = goal.Id }), now);
    }

    private Notification CancelledNotification(Couple couple, Dictionary<Guid, string> names, ExerciseOccurrence occurrence, DateTime now)
    {
        var actorId = currentUser.UserId!.Value;
        var body = actorId == occurrence.UserId
            ? $"{occurrence.Title} was changed while it was ringing, so today does not count."
            : $"{Name(names, actorId)} let you off {occurrence.Title} today.";
        return Notification.Create(occurrence.UserId, couple.Id, NotificationType.ExerciseCancelled, "Skipped today", body,
            System.Text.Json.JsonSerializer.Serialize(new { occurrenceId = occurrence.Id, goalId = occurrence.GoalId }), now);
    }

    private ActionResult Problem(Error error) => error.Type switch
    {
        ErrorType.Validation => UnprocessableEntity(new ApiError(error.Code, error.Message)),
        ErrorType.NotFound => NotFound(new ApiError(error.Code, error.Message)),
        ErrorType.Conflict => Conflict(new ApiError(error.Code, error.Message)),
        ErrorType.Forbidden => StatusCode(StatusCodes.Status403Forbidden, new ApiError(error.Code, error.Message)),
        _ => BadRequest(new ApiError(error.Code, error.Message))
    };

    private static IEnumerable<Guid> Members(Couple couple)
    {
        yield return couple.UserAId;
        if (couple.UserBId.HasValue) yield return couple.UserBId.Value;
    }

    private static bool IsMember(Couple couple, Guid userId) => couple.UserAId == userId || couple.UserBId == userId;
    private static Guid? PartnerOf(Couple couple, Guid userId) => couple.UserAId == userId ? couple.UserBId : couple.UserAId;
    private static string Name(Dictionary<Guid, string> names, Guid userId) => names.GetValueOrDefault(userId, "Your partner");
    /// <summary>"today's sit-ups": a catalog name reads lowercased mid-sentence; a title someone typed stays as written.</summary>
    private static string InSentence(ExerciseOccurrence occurrence)
        => occurrence.Title == ExerciseCatalog.Get(occurrence.ExerciseType).DisplayName ? occurrence.Title.ToLowerInvariant() : occurrence.Title;
    private static string FormatTarget(ExerciseOccurrence occurrence) => FormatTarget(occurrence.Target, occurrence.ExerciseType);
    private static string FormatTarget(int target, ExerciseType type)
        => ExerciseCatalog.MeasureOf(type) == ExerciseMeasure.Seconds ? $"{target} s" : $"{target} reps";
}
