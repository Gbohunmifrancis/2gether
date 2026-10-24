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
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class ExerciseController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    IHubContext<CoupleHub> hub,
    IPlayIntegrityVerifier integrity,
    ILogger<ExerciseController> logger) : ApiControllerBase
{
    private static ApiError NoCouple => new("exercise.no_couple", Loc.T("Link your partner before setting goals."));
    private static ApiError AlarmActive => new("exercise.alarm_active", Loc.T("This goal is ringing for you right now. Finish it on camera, or ask your partner to let you off today."));
    private static ApiError NotActive => new("exercise.not_active", Loc.T("This alarm is no longer active."));
    private static ApiError NotAssignee => new("exercise.not_assignee", Loc.T("Only the person this alarm is for can do that."));
    private static ApiError NotDueToday => new("exercise.not_due_today", Loc.T("Today's dare has already rung or is done. Start it from the alarm, or try again tomorrow."));
    private static ApiError AlreadyAsked => new("exercise.already_asked", Loc.T("You already asked to call off today."));
    private static ApiError VersionConflict => new("exercise.version_conflict", Loc.T("Someone else just changed this. Refresh and try again."));

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
        if (!IsMember(couple, request.AssignedUserId)) return UnprocessableEntity(new ApiError("exercise.invalid_assignee", Loc.T("Goals can only be assigned to you or your partner.")));

        var now = clock.UtcNow;
        var goal = ExerciseGoal.Create(couple.Id, request.AssignedUserId, currentUser.UserId.Value, request.ExerciseType, request.Title, request.Target, time.Value, request.TimeZoneId.Trim(), request.GraceMinutes, now);
        db.ExerciseGoals.Add(goal);
        var names = await NamesAsync(couple, cancellationToken);
        var notification = await GoalChangedNotificationAsync(couple, names, goal, GoalChange.Set, now, cancellationToken);
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
        if (!IsMember(couple, request.AssignedUserId)) return UnprocessableEntity(new ApiError("exercise.invalid_assignee", Loc.T("Goals can only be assigned to you or your partner.")));

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
            cancelled = await CancelledNotificationAsync(couple, names, ringing, now, cancellationToken);
            db.Notifications.Add(cancelled);
        }

        goal.Update(request.AssignedUserId, request.ExerciseType, request.Title, request.Target, time.Value, request.TimeZoneId.Trim(), request.GraceMinutes, now);
        if (!request.IsActive) goal.Deactivate(now);
        else if (!goal.IsActive) goal.Reactivate(now);
        var changed = await GoalChangedNotificationAsync(couple, names, goal, request.IsActive ? GoalChange.Updated : GoalChange.Paused, now, cancellationToken);
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
            cancelled = await CancelledNotificationAsync(couple, names, ringing, now, cancellationToken);
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

        // Advisory: what the camera noticed and whether the app is genuine are stored for the partner, never a reason to refuse.
        var sent = request.Checks?.ToCounts() ?? default;
        var checks = sent.Clamped();
        // The token was requested for the numbers as the app sent them, before any clamping.
        var integrityVerdict = await integrity.VerifyAsync(request.IntegrityToken, PlayIntegrityRules.RequestHash(occurrence.Id, request.AchievedCount, sent), cancellationToken);
        if (integrityVerdict is ExerciseIntegrity.Failed or ExerciseIntegrity.Missing)
            logger.LogInformation("Exercise completion {OccurrenceId} has Play Integrity verdict {Verdict}", occurrence.Id, integrityVerdict);
        occurrence.Complete(achieved, confidence, now, checks, integrityVerdict);
        var settled = await SettleGoalAsync(occurrence, now, cancellationToken);
        var names = await NamesAsync(couple, cancellationToken);
        var partnerId = PartnerOf(couple, occurrence.UserId);
        Notification? notification = null;
        if (partnerId is { } partner)
        {
            using (Loc.Use(await db.LanguageOfAsync(partner, cancellationToken)))
            {
                var flagged = occurrence.Checks.Summary(occurrence.Integrity);
                var done = Loc.T("{name} finished {dare}: {target}.", ("name", Name(names, occurrence.UserId)),
                    ("dare", ExerciseText.Title(occurrence.Title, occurrence.ExerciseType)), ("target", ExerciseText.Target(occurrence.Target, occurrence.ExerciseType)));
                notification = Notification.Create(partner, couple.Id, NotificationType.ExerciseCompleted, flagged is null ? Loc.T("Dare done") : Loc.T("Dare done, worth a look"),
                    flagged is null ? done : done + " " + flagged,
                    System.Text.Json.JsonSerializer.Serialize(new { occurrenceId = occurrence.Id, goalId = occurrence.GoalId }), now);
            }
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
        var notification = await CancelledNotificationAsync(couple, names, occurrence, now, cancellationToken);
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
            using (Loc.Use(await db.LanguageOfAsync(partner, cancellationToken)))
                notification = Notification.Create(partner, couple.Id, NotificationType.ExerciseCallOffAsked,
                    Loc.T("{name} asks to call off today's {dare}", ("name", asker), ("dare", ExerciseText.InSentence(occurrence.Title, occurrence.ExerciseType))),
                    note.Value ?? Loc.T("Let {name} off today, or keep the alarm ringing.", ("name", asker)),
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

    private enum GoalChange { Set, Updated, Paused }

    private async Task<Notification?> GoalChangedNotificationAsync(Couple couple, Dictionary<Guid, string> names, ExerciseGoal goal, GoalChange change, DateTime now, CancellationToken cancellationToken)
    {
        var actorId = currentUser.UserId!.Value;
        var partnerId = PartnerOf(couple, actorId);
        if (partnerId is null) return null;
        using var _ = Loc.Use(await db.LanguageOfAsync(partnerId.Value, cancellationToken));
        (string, object?)[] args = [("name", Name(names, actorId)), ("dare", ExerciseText.Title(goal.Title, goal.ExerciseType)),
            ("target", ExerciseText.Target(goal.Target, goal.ExerciseType)), ("time", goal.ScheduledTime.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture))];
        // One whole sentence per case, so every language can word it its own way.
        var forYou = goal.AssignedUserId == partnerId;
        var forThemselves = goal.AssignedUserId == actorId;
        var (title, body) = change switch
        {
            GoalChange.Set => (Loc.T("Dare set"), forYou ? Loc.T("{name} set {dare} for you: {target} at {time}.", args)
                : forThemselves ? Loc.T("{name} set {dare} for themselves: {target} at {time}.", args) : Loc.T("{name} set {dare}: {target} at {time}.", args)),
            GoalChange.Updated => (Loc.T("Dare updated"), forYou ? Loc.T("{name} updated {dare} for you: {target} at {time}.", args)
                : forThemselves ? Loc.T("{name} updated {dare} for themselves: {target} at {time}.", args) : Loc.T("{name} updated {dare}: {target} at {time}.", args)),
            _ => (Loc.T("Dare paused"), forYou ? Loc.T("{name} paused {dare} for you: {target} at {time}.", args)
                : forThemselves ? Loc.T("{name} paused {dare} for themselves: {target} at {time}.", args) : Loc.T("{name} paused {dare}: {target} at {time}.", args)),
        };
        return Notification.Create(partnerId.Value, couple.Id, NotificationType.ExerciseGoalChanged, title, body,
            System.Text.Json.JsonSerializer.Serialize(new { goalId = goal.Id }), now);
    }

    private async Task<Notification> CancelledNotificationAsync(Couple couple, Dictionary<Guid, string> names, ExerciseOccurrence occurrence, DateTime now, CancellationToken cancellationToken)
    {
        var actorId = currentUser.UserId!.Value;
        using var _ = Loc.Use(await db.LanguageOfAsync(occurrence.UserId, cancellationToken));
        var dare = ExerciseText.Title(occurrence.Title, occurrence.ExerciseType);
        var body = actorId == occurrence.UserId
            ? Loc.T("{dare} was changed while it was ringing, so today does not count.", ("dare", dare))
            : Loc.T("{name} let you off {dare} today.", ("name", Name(names, actorId)), ("dare", dare));
        return Notification.Create(occurrence.UserId, couple.Id, NotificationType.ExerciseCancelled, Loc.T("Skipped today"), body,
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
    private static string Name(Dictionary<Guid, string> names, Guid userId) => names.GetValueOrDefault(userId) ?? Loc.T("Your partner");
}
