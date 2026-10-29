using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Pregnancy;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;
using Twogether.Application.Features.Notifications;

namespace Twogether.Api.Controllers;

/// <summary>
/// Pregnancy mode. The pregnant partner owns the record; the other partner reads it, and adds
/// appointments, only while it is shared. A record the caller may not see answers 404, so its
/// existence never leaks.
/// </summary>
[Authorize]
public sealed class PregnancyController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    IHubContext<CoupleHub> hub,
    ILogger<PregnancyController> logger) : ApiControllerBase
{
    private static ApiError NoCouple => new("pregnancy.no_couple", Loc.T("Link your partner before starting pregnancy mode."));
    private static ApiError NotOwner => new("pregnancy.not_owner", Loc.T("Only the person who is pregnant can change this."));
    private static ApiError NotAppointmentAuthor => new("pregnancy.not_appointment_author", Loc.T("Only the person who is pregnant, or whoever added this appointment, can change it."));
    private static ApiError Ended => new("pregnancy.ended", Loc.T("This pregnancy has ended."));
    public const string ChangedEvent = "PregnancyChanged";

    /// <summary>Active pregnancies I can see: my own, and my partner's when shared. With includeEnded, my ended ones too, so I can delete them.</summary>
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<PregnancyDto>>> List([FromQuery] string? date, [FromQuery] bool includeEnded = false, CancellationToken cancellationToken = default)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var today = ParseDay(date);
        if (today is null) return UnprocessableEntity(new ApiError("pregnancy.invalid_date", Loc.T("Date must look like yyyy-MM-dd.")));
        var couple = await ActiveCoupleAsync(cancellationToken);
        var partnerId = couple is null ? null : PartnerOf(couple, userId);
        var coupleId = couple?.Id;
        var visible = await db.Pregnancies.AsNoTracking()
            .Where(item => (item.UserId == userId && (includeEnded || item.Status == PregnancyStatus.Active))
                || (item.Status == PregnancyStatus.Active && partnerId != null && item.UserId == partnerId && item.CoupleId == coupleId && item.ShareWithPartner))
            .OrderBy(item => item.Status).ThenByDescending(item => item.CreatedAtUtc)
            .ToListAsync(cancellationToken);
        var result = new List<PregnancyDto>();
        foreach (var pregnancy in visible) result.Add(await DtoAsync(pregnancy, userId, today.Value, cancellationToken));
        return Ok(result);
    }

    [HttpPost]
    public async Task<ActionResult<PregnancyDto>> Start(StartPregnancyRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var now = clock.UtcNow;
        var today = DateOnly.FromDateTime(now);
        var due = PregnancyRules.ResolveDueDate(request.DueDate, request.LastPeriodStart, today);
        if (due.IsFailure) return UnprocessableEntity(new ApiError(due.Error.Code, due.Error.Message));
        if (await db.Pregnancies.AnyAsync(item => item.UserId == userId && item.Status == PregnancyStatus.Active, cancellationToken))
            return Conflict(new ApiError("pregnancy.already_active", Loc.T("Pregnancy mode is already on.")));

        var pregnancy = Pregnancy.Start(couple.Id, userId, due.Value, request.ShareWithPartner, now);
        db.Pregnancies.Add(pregnancy);
        var notification = request.ShareWithPartner ? await SharedNotificationAsync(couple, pregnancy, now, cancellationToken) : null;
        if (notification is not null) db.Notifications.Add(notification);
        try { await db.SaveChangesAsync(cancellationToken); }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            return Conflict(new ApiError("pregnancy.already_active", Loc.T("Pregnancy mode is already on.")));
        }

        if (notification is not null) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        await ChangedAsync(couple, pregnancy, "started", partnerMaySee: pregnancy.ShareWithPartner, cancellationToken);
        return Ok(await DtoAsync(pregnancy, userId, today, cancellationToken));
    }

    [HttpPut("{pregnancyId:guid}")]
    public async Task<ActionResult<PregnancyDto>> Update(Guid pregnancyId, UpdatePregnancyRequest request, CancellationToken cancellationToken)
    {
        var access = await AccessAsync(pregnancyId, track: true, cancellationToken);
        if (access is null) return NotFound();
        if (!access.IsOwner) return StatusCode(StatusCodes.Status403Forbidden, NotOwner);
        if (access.Pregnancy.Status != PregnancyStatus.Active) return Conflict(Ended);
        var now = clock.UtcNow;
        var today = DateOnly.FromDateTime(now);
        var due = PregnancyRules.ResolveUpdatedDueDate(request.DueDate, access.Pregnancy.DueDate, today);
        if (due.IsFailure) return UnprocessableEntity(new ApiError(due.Error.Code, due.Error.Message));

        var wasShared = access.Pregnancy.ShareWithPartner;
        if (wasShared && !request.ShareWithPartner) await ForgetSharedNoticeAsync(access.Pregnancy, cancellationToken);
        access.Pregnancy.Update(due.Value, request.ShareWithPartner, now);
        var notification = !wasShared && request.ShareWithPartner && access.Couple is not null
            ? await SharedNotificationAsync(access.Couple, access.Pregnancy, now, cancellationToken)
            : null;
        if (notification is not null) db.Notifications.Add(notification);
        await db.SaveChangesAsync(cancellationToken);

        if (notification is not null) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        // A partner who could see it before must hear that it is gone, so their app drops it.
        await ChangedAsync(access.Couple, access.Pregnancy, request.ShareWithPartner ? "updated" : "hidden", partnerMaySee: wasShared || request.ShareWithPartner, cancellationToken);
        return Ok(await DtoAsync(access.Pregnancy, access.Pregnancy.UserId, today, cancellationToken));
    }

    [HttpPost("{pregnancyId:guid}/end")]
    public async Task<ActionResult<PregnancyDto>> End(Guid pregnancyId, CancellationToken cancellationToken)
    {
        var access = await AccessAsync(pregnancyId, track: true, cancellationToken);
        if (access is null) return NotFound();
        if (!access.IsOwner) return StatusCode(StatusCodes.Status403Forbidden, NotOwner);
        if (access.Pregnancy.Status != PregnancyStatus.Active) return Conflict(Ended);
        var now = clock.UtcNow;
        // Ending stops sharing, so the notice that it was shared goes with it.
        var wasShared = access.Pregnancy.ShareWithPartner;
        if (wasShared) await ForgetSharedNoticeAsync(access.Pregnancy, cancellationToken);
        access.Pregnancy.End(now);
        await db.SaveChangesAsync(cancellationToken);
        await ChangedAsync(access.Couple, access.Pregnancy, "ended", partnerMaySee: wasShared, cancellationToken);
        return Ok(await DtoAsync(access.Pregnancy, access.Pregnancy.UserId, DateOnly.FromDateTime(now), cancellationToken));
    }

    /// <summary>Deletes the pregnancy and everything under it. Appointments and kicks go by database cascade.</summary>
    [HttpDelete("{pregnancyId:guid}")]
    public async Task<IActionResult> Delete(Guid pregnancyId, CancellationToken cancellationToken)
    {
        var access = await AccessAsync(pregnancyId, track: true, cancellationToken);
        if (access is null) return NotFound();
        if (!access.IsOwner) return StatusCode(StatusCodes.Status403Forbidden, NotOwner);
        var wasShared = access.Pregnancy.ShareWithPartner;
        await ForgetSharedNoticeAsync(access.Pregnancy, cancellationToken);
        db.Pregnancies.Remove(access.Pregnancy);
        await db.SaveChangesAsync(cancellationToken);
        logger.LogInformation("Pregnancy {PregnancyId} deleted by its owner.", pregnancyId);
        await ChangedAsync(access.Couple, access.Pregnancy, "deleted", partnerMaySee: wasShared, cancellationToken);
        return NoContent();
    }

    [HttpGet("{pregnancyId:guid}/appointments")]
    public async Task<ActionResult<IReadOnlyList<PregnancyAppointmentDto>>> Appointments(Guid pregnancyId, CancellationToken cancellationToken)
    {
        var access = await AccessAsync(pregnancyId, track: false, cancellationToken);
        if (access is null) return NotFound();
        var items = await db.PregnancyAppointments.AsNoTracking()
            .Where(item => item.PregnancyId == pregnancyId)
            .OrderBy(item => item.AtUtc)
            .ToListAsync(cancellationToken);
        return Ok(items.Select(PregnancyMapper.ToDto).ToList());
    }

    [HttpPost("{pregnancyId:guid}/appointments")]
    public async Task<ActionResult<PregnancyAppointmentDto>> AddAppointment(Guid pregnancyId, PregnancyAppointmentRequest request, CancellationToken cancellationToken)
    {
        var access = await AccessAsync(pregnancyId, track: false, cancellationToken);
        if (access is null) return NotFound();
        if (access.Pregnancy.Status != PregnancyStatus.Active) return Conflict(Ended);
        var now = clock.UtcNow;
        var rules = PregnancyRules.ValidateAppointment(request.Title, request.AtUtc, request.Location, request.Notes, now);
        if (rules.IsFailure) return UnprocessableEntity(new ApiError(rules.Error.Code, rules.Error.Message));
        var appointment = PregnancyAppointment.Create(pregnancyId, currentUser.UserId!.Value, request.Title, request.AtUtc, request.Location, request.Notes, now);
        db.PregnancyAppointments.Add(appointment);
        await db.SaveChangesAsync(cancellationToken);
        await ChangedAsync(access.Couple, access.Pregnancy, "appointments", partnerMaySee: access.Pregnancy.ShareWithPartner, cancellationToken);
        return Ok(PregnancyMapper.ToDto(appointment));
    }

    [HttpPut("appointments/{appointmentId:guid}")]
    public async Task<ActionResult<PregnancyAppointmentDto>> UpdateAppointment(Guid appointmentId, PregnancyAppointmentRequest request, CancellationToken cancellationToken)
    {
        var appointment = await db.PregnancyAppointments.SingleOrDefaultAsync(item => item.Id == appointmentId, cancellationToken);
        if (appointment is null) return NotFound();
        var access = await AccessAsync(appointment.PregnancyId, track: false, cancellationToken);
        if (access is null) return NotFound();
        if (!CanManage(access, appointment)) return StatusCode(StatusCodes.Status403Forbidden, NotAppointmentAuthor);
        if (access.Pregnancy.Status != PregnancyStatus.Active) return Conflict(Ended);
        var now = clock.UtcNow;
        var rules = PregnancyRules.ValidateAppointment(request.Title, request.AtUtc, request.Location, request.Notes, now);
        if (rules.IsFailure) return UnprocessableEntity(new ApiError(rules.Error.Code, rules.Error.Message));
        appointment.Update(request.Title, request.AtUtc, request.Location, request.Notes, now);
        await db.SaveChangesAsync(cancellationToken);
        await ChangedAsync(access.Couple, access.Pregnancy, "appointments", partnerMaySee: access.Pregnancy.ShareWithPartner, cancellationToken);
        return Ok(PregnancyMapper.ToDto(appointment));
    }

    [HttpDelete("appointments/{appointmentId:guid}")]
    public async Task<IActionResult> DeleteAppointment(Guid appointmentId, CancellationToken cancellationToken)
    {
        var appointment = await db.PregnancyAppointments.SingleOrDefaultAsync(item => item.Id == appointmentId, cancellationToken);
        if (appointment is null) return NotFound();
        var access = await AccessAsync(appointment.PregnancyId, track: false, cancellationToken);
        if (access is null) return NotFound();
        if (!CanManage(access, appointment)) return StatusCode(StatusCodes.Status403Forbidden, NotAppointmentAuthor);
        db.PregnancyAppointments.Remove(appointment);
        await db.SaveChangesAsync(cancellationToken);
        await ChangedAsync(access.Couple, access.Pregnancy, "appointments", partnerMaySee: access.Pregnancy.ShareWithPartner, cancellationToken);
        return NoContent();
    }

    [HttpGet("{pregnancyId:guid}/kicks")]
    public async Task<ActionResult<IReadOnlyList<KickSessionDto>>> Kicks(Guid pregnancyId, [FromQuery] int days = 7, CancellationToken cancellationToken = default)
    {
        var access = await AccessAsync(pregnancyId, track: false, cancellationToken);
        if (access is null) return NotFound();
        var since = clock.UtcNow.AddDays(-Math.Clamp(days, 1, 30));
        var items = await db.KickSessions.AsNoTracking()
            .Where(item => item.PregnancyId == pregnancyId && item.StartedAtUtc >= since)
            .OrderByDescending(item => item.StartedAtUtc)
            .ToListAsync(cancellationToken);
        return Ok(items.Select(PregnancyMapper.ToDto).ToList());
    }

    [HttpPost("{pregnancyId:guid}/kicks")]
    public async Task<ActionResult<KickSessionDto>> StartKicks(Guid pregnancyId, CancellationToken cancellationToken)
    {
        var access = await AccessAsync(pregnancyId, track: false, cancellationToken);
        if (access is null) return NotFound();
        if (!access.IsOwner) return StatusCode(StatusCodes.Status403Forbidden, NotOwner);
        if (access.Pregnancy.Status != PregnancyStatus.Active) return Conflict(Ended);
        var now = clock.UtcNow;
        // One open session at a time: starting again closes any session the app abandoned.
        var open = await db.KickSessions.Where(item => item.PregnancyId == pregnancyId && item.EndedAtUtc == null).ToListAsync(cancellationToken);
        foreach (var stale in open) stale.Record(stale.Count, end: true, now);
        var session = KickSession.Start(pregnancyId, now);
        db.KickSessions.Add(session);
        await db.SaveChangesAsync(cancellationToken);
        return Ok(PregnancyMapper.ToDto(session));
    }

    [HttpPut("kicks/{sessionId:guid}")]
    public async Task<ActionResult<KickSessionDto>> RecordKicks(Guid sessionId, UpdateKickSessionRequest request, CancellationToken cancellationToken)
    {
        var session = await db.KickSessions.SingleOrDefaultAsync(item => item.Id == sessionId, cancellationToken);
        if (session is null) return NotFound();
        var access = await AccessAsync(session.PregnancyId, track: false, cancellationToken);
        if (access is null) return NotFound();
        if (!access.IsOwner) return StatusCode(StatusCodes.Status403Forbidden, NotOwner);
        if (request.Count is < 0 or > KickSession.MaxCount)
            return UnprocessableEntity(new ApiError("pregnancy.invalid_kicks", Loc.T("Count must be between 0 and {max}.", ("max", KickSession.MaxCount))));
        if (!session.IsOpen) return Conflict(new ApiError("pregnancy.session_closed", Loc.T("This kick session has already ended.")));
        session.Record(request.Count, request.End, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        if (!session.IsOpen) await ChangedAsync(access.Couple, access.Pregnancy, "kicks", partnerMaySee: access.Pregnancy.ShareWithPartner, cancellationToken);
        return Ok(PregnancyMapper.ToDto(session));
    }

    private async Task<PregnancyDto> DtoAsync(Pregnancy pregnancy, Guid viewerId, DateOnly today, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var next = await db.PregnancyAppointments.AsNoTracking()
            .Where(item => item.PregnancyId == pregnancy.Id && item.AtUtc >= now)
            .OrderBy(item => item.AtUtc)
            .FirstOrDefaultAsync(cancellationToken);
        var name = await db.Users.AsNoTracking().Where(user => user.Id == pregnancy.UserId).Select(user => user.DisplayName).SingleOrDefaultAsync(cancellationToken) ?? Loc.T("Your partner");
        return PregnancyMapper.ToDto(pregnancy, name, pregnancy.UserId == (currentUser.UserId ?? viewerId), today, next);
    }

    /// <summary>
    /// The owner sees their pregnancy whatever happened to the couple; the partner only while linked, shared and
    /// active (ending stops sharing, and this also covers pregnancies ended before it did).
    /// </summary>
    private async Task<PregnancyAccess?> AccessAsync(Guid pregnancyId, bool track, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return null;
        var userId = currentUser.UserId.Value;
        var query = db.Pregnancies.Where(item => item.Id == pregnancyId);
        var pregnancy = await (track ? query : query.AsNoTracking()).SingleOrDefaultAsync(cancellationToken);
        if (pregnancy is null) return null;
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (pregnancy.UserId == userId) return new PregnancyAccess(pregnancy, true, couple?.Id == pregnancy.CoupleId ? couple : null);
        var visible = couple is not null && PartnerOf(couple, userId) == pregnancy.UserId && pregnancy.IsSharedWith(couple.Id);
        return visible ? new PregnancyAccess(pregnancy, false, couple) : null;
    }

    private async Task<Notification?> SharedNotificationAsync(Couple couple, Pregnancy pregnancy, DateTime now, CancellationToken cancellationToken)
    {
        if (PartnerOf(couple, pregnancy.UserId) is not { } partnerId) return null;
        using var _ = Loc.Use(await db.LanguageOfAsync(partnerId, cancellationToken));
        var name = await db.Users.AsNoTracking().Where(user => user.Id == pregnancy.UserId).Select(user => user.DisplayName).SingleOrDefaultAsync(cancellationToken) ?? Loc.T("Your partner");
        return Notification.Create(partnerId, couple.Id, NotificationType.PregnancyUpdated, Loc.T("Pregnancy mode"),
            Loc.T("{name} shared pregnancy mode with you.", ("name", name)), PregnancyNotices.SharedData(pregnancy.Id), now);
    }

    /// <summary>
    /// Hidden means hidden: the partner's "shared pregnancy mode with you" goes from their inbox, and a push not sent
    /// yet is never sent (the dispatcher only sends rows that still exist).
    /// </summary>
    private Task ForgetSharedNoticeAsync(Pregnancy pregnancy, CancellationToken cancellationToken)
    {
        var data = PregnancyNotices.SharedData(pregnancy.Id);
        return db.Notifications
            .Where(item => item.Type == NotificationType.PregnancyUpdated && item.UserId != pregnancy.UserId && item.DataJson == data)
            .ExecuteDeleteAsync(cancellationToken);
    }

    private async Task ChangedAsync(Couple? couple, Pregnancy pregnancy, string change, bool partnerMaySee, CancellationToken cancellationToken)
    {
        var payload = new PregnancyChangedDto(pregnancy.Id, change);
        await hub.Clients.Group(CoupleHub.UserGroupName(pregnancy.UserId)).SendAsync(ChangedEvent, payload, cancellationToken);
        if (partnerMaySee && couple is not null && PartnerOf(couple, pregnancy.UserId) is { } partnerId)
            await hub.Clients.Group(CoupleHub.UserGroupName(partnerId)).SendAsync(ChangedEvent, payload, cancellationToken);
    }

    private async Task<Couple?> ActiveCoupleAsync(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return null;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        return couple is not null && (couple.UserAId == currentUser.UserId.Value || couple.UserBId == currentUser.UserId.Value) ? couple : null;
    }

    private DateOnly? ParseDay(string? date)
    {
        if (string.IsNullOrWhiteSpace(date)) return DateOnly.FromDateTime(clock.UtcNow);
        return DateOnly.TryParseExact(date.Trim(), "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out var day) ? day : null;
    }

    private static Guid? PartnerOf(Couple couple, Guid userId) => couple.UserAId == userId ? couple.UserBId : couple.UserAId;

    private sealed record PregnancyAccess(Pregnancy Pregnancy, bool IsOwner, Couple? Couple);

    /// <summary>The pregnancy is the owner's record: a partner it is shared with changes only what they added.</summary>
    private bool CanManage(PregnancyAccess access, PregnancyAppointment appointment)
        => access.IsOwner || appointment.CreatedByUserId == currentUser.UserId;
}
