using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Cycle;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class CycleController(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, IHubContext<CoupleHub>? hub = null) : ApiControllerBase
{
    [HttpGet("profile")]
    public async Task<ActionResult<CycleProfileDto>> GetProfile(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var owner = await db.Users.AsNoTracking().AnyAsync(item => item.Id == currentUser.UserId.Value && item.CycleOwner, cancellationToken);
        if (!owner) return Ok(null);
        var profile = await db.CycleProfiles.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        return Ok(profile is null ? null : ToProfile(profile));
    }

    /// <summary>Shares nothing until the owner chooses, and never changes a level already chosen.</summary>
    [HttpPost("claim")]
    public async Task<ActionResult<CycleProfileDto>> Claim(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var actor = await db.Users.SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (actor is null) return NotFound();
        if (actor.Gender != Gender.Female)
            return Forbid();
        // Says nothing about why: that the partner tracks a cycle is theirs to share.
        if (currentUser.CoupleId.HasValue && await db.Users.AsNoTracking().AnyAsync(item => item.CoupleId == currentUser.CoupleId.Value && item.Id != actor.Id && item.CycleOwner, cancellationToken))
            return Conflict(new ApiError("cycle.unavailable", Loc.T("Cycle tracking isn't available on this account.")));
        actor.ClaimCycleOwnership(clock.UtcNow);
        var profile = await db.CycleProfiles.SingleOrDefaultAsync(item => item.UserId == actor.Id, cancellationToken);
        if (profile is null) { profile = CycleProfile.Create(actor.Id, clock.UtcNow); db.CycleProfiles.Add(profile); }
        await db.SaveChangesAsync(cancellationToken);
        return Ok(ToProfile(profile));
    }

    [HttpPut("profile")]
    public async Task<ActionResult<CycleProfileDto>> UpdateProfile(UpdateCycleProfileRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var actor = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (actor is null || !actor.CycleOwner) return Forbid();
        var rules = CycleRules.ValidateProfile(request.AverageCycleLengthDays, request.AveragePeriodLengthDays, request.LastPeriodStartDate, request.ShareLevel, clock.UtcNow);
        if (rules.IsFailure) return UnprocessableEntity(new ApiError(rules.Error.Code, rules.Error.Message));
        var profile = await db.CycleProfiles.SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        if (profile is null) { profile = CycleProfile.Create(currentUser.UserId.Value, clock.UtcNow); db.CycleProfiles.Add(profile); }
        var wasShared = profile.ShareLevelWithPartner != ShareLevel.None;
        profile.SetCycleSettings(request.AverageCycleLengthDays, request.AveragePeriodLengthDays, request.LastPeriodStartDate, clock.UtcNow);
        profile.SetShareLevel(request.ShareLevel, clock.UtcNow);
        // The partner hears once, when sharing goes from nothing to something; other saves and level changes say nothing.
        var notice = !wasShared && profile.ShareLevelWithPartner != ShareLevel.None ? await SharedNoticeAsync(actor, cancellationToken) : null;
        if (notice is not null) db.Notifications.Add(notice);
        await db.SaveChangesAsync(cancellationToken);
        if (notice is not null && hub is not null) await ExerciseEvents.NotificationAsync(hub, notice, cancellationToken);
        return Ok(ToProfile(profile));
    }

    [HttpGet("logs")]
    public async Task<ActionResult<IReadOnlyList<CycleDayLogDto>>> GetLogs([FromQuery] DateOnly? from, [FromQuery] DateOnly? to, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var owner = await db.Users.AsNoTracking().AnyAsync(item => item.Id == currentUser.UserId.Value && item.CycleOwner, cancellationToken);
        if (!owner) return Ok(Array.Empty<CycleDayLogDto>());
        var start = from ?? DateOnly.FromDateTime(clock.UtcNow.AddDays(-90));
        var end = to ?? DateOnly.FromDateTime(clock.UtcNow);
        var logs = await db.CycleDayLogs.AsNoTracking().Where(log => log.UserId == currentUser.UserId.Value && log.LogDate >= start && log.LogDate <= end).OrderByDescending(log => log.LogDate).ToListAsync(cancellationToken);
        return Ok(logs.Select(ToLog).ToList());
    }

    [HttpPut("logs/{date}")]
    public async Task<ActionResult<CycleDayLogDto>> UpsertLog(DateOnly date, CycleDayLogRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var actor = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == userId, cancellationToken);
        if (actor is null || !actor.CycleOwner) return Forbid();
        var rules = CycleRules.ValidateLog(date, request.FlowLevel, request.Mood, request.Symptoms, request.Notes, clock.UtcNow);
        if (rules.IsFailure) return UnprocessableEntity(new ApiError(rules.Error.Code, rules.Error.Message));
        var log = await db.CycleDayLogs.SingleOrDefaultAsync(item => item.UserId == userId && item.LogDate == date, cancellationToken);
        if (log is not null) log.Update(request.FlowLevel, request.Symptoms, request.Mood, request.HadSex, request.ProtectionUsed, request.Notes, clock.UtcNow);
        else
        {
            log = CycleDayLog.Create(userId, date, request.FlowLevel, request.Symptoms, request.Mood, request.HadSex, request.ProtectionUsed, request.Notes, clock.UtcNow);
            db.CycleDayLogs.Add(log);
        }
        try { await db.SaveChangesAsync(cancellationToken); }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            // Two saves of a new day raced and the other inserted it first: this one is an update of that row.
            db.CycleDayLogs.Remove(log);
            log = await db.CycleDayLogs.SingleAsync(item => item.UserId == userId && item.LogDate == date, cancellationToken);
            log.Update(request.FlowLevel, request.Symptoms, request.Mood, request.HadSex, request.ProtectionUsed, request.Notes, clock.UtcNow);
            await db.SaveChangesAsync(cancellationToken);
        }
        return Ok(ToLog(log));
    }

    [HttpGet("prediction")]
    public async Task<ActionResult<CyclePredictionDto>> GetPrediction([FromQuery] DateOnly? date, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var owner = await db.Users.AsNoTracking().AnyAsync(item => item.Id == currentUser.UserId.Value && item.CycleOwner, cancellationToken);
        if (!owner) return Ok(new CyclePredictionDto(null, null, null));
        var profile = await db.CycleProfiles.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        if (profile is null) return Ok(new CyclePredictionDto(null, null, null));
        // Period predictions are meaningless during a pregnancy; show none rather than a wrong date.
        if (await PregnantAsync(currentUser.UserId.Value, null, cancellationToken)) return Ok(new CyclePredictionDto(null, null, null));
        var outlook = await CycleSharing.OutlookAsync(db, profile, Today(date), mayShowLate: true, cancellationToken);
        return Ok(outlook is null ? new CyclePredictionDto(null, null, null) : ToPrediction(outlook));
    }

    [HttpGet("shared")]
    public async Task<ActionResult<SharedCycleDto>> GetShared([FromQuery] DateOnly? date, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue || !currentUser.CoupleId.HasValue) return NotFound();
        var userId = currentUser.UserId.Value;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        if (couple is null || (couple.UserAId != userId && couple.UserBId != userId)) return NotFound();
        var partnerId = couple.UserAId == userId ? couple.UserBId : couple.UserAId;
        if (!partnerId.HasValue) return NotFound();
        var nothing = new SharedCycleDto(null, null, Array.Empty<SharedCycleDayLogDto>());
        var partnerOwnsCycle = await db.Users.AsNoTracking().AnyAsync(item => item.Id == partnerId.Value && item.CycleOwner, cancellationToken);
        if (!partnerOwnsCycle) return Ok(nothing);
        var profile = await db.CycleProfiles.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == partnerId.Value, cancellationToken);
        if (profile is null || profile.ShareLevelWithPartner == ShareLevel.None) return Ok(nothing);
        // Hide the partner's predictions only when they share the pregnancy with this couple too: predictions that
        // vanish without explanation would reveal a pregnancy they chose to keep private. For the same reason a
        // private pregnancy keeps the old roll-forward instead of a growing "late by" count.
        var prediction = new CyclePredictionDto(null, null, null);
        if (!await PregnantAsync(partnerId.Value, couple.Id, cancellationToken))
        {
            var privatelyPregnant = await PregnantAsync(partnerId.Value, null, cancellationToken);
            var outlook = await CycleSharing.OutlookAsync(db, profile, Today(date), mayShowLate: !privatelyPregnant, cancellationToken);
            if (outlook is not null) prediction = ToPrediction(outlook);
        }
        var since = CycleSharing.SharedLogsSince(couple);
        var sharedLogs = profile.ShareLevelWithPartner == ShareLevel.FullDetail
            ? await db.CycleDayLogs.AsNoTracking().Where(item => item.UserId == partnerId.Value && item.LogDate >= since).OrderByDescending(item => item.LogDate).Take(120).ToListAsync(cancellationToken)
            : [];
        var logs = sharedLogs.Select(ToSharedLog).ToList();
        return Ok(new SharedCycleDto(ToProfile(profile), prediction, logs));
    }

    /// <summary>"Maya is sharing their cycle with you", to the owner's current partner, in their language. Not added.</summary>
    private async Task<Notification?> SharedNoticeAsync(User owner, CancellationToken cancellationToken)
    {
        if (owner.CoupleId is not { } coupleId) return null;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, cancellationToken);
        var partnerId = couple is null ? null : couple.UserAId == owner.Id ? couple.UserBId : couple.UserBId == owner.Id ? couple.UserAId : null;
        if (partnerId is not { } partner) return null;
        using var _ = Loc.Use(await db.LanguageOfAsync(partner, cancellationToken));
        (string, object?) name = ("name", owner.DisplayName);
        return Notification.Create(partner, coupleId, NotificationType.CycleShared, Loc.T("{name} is sharing their cycle with you", name),
            Loc.T("You'll see what {name} chose to share in Us → {name}'s cycle.", name),
            System.Text.Json.JsonSerializer.Serialize(new { userId = owner.Id }), clock.UtcNow);
    }

    /// <summary>The caller's own calendar date when the app sends it (time zones differ); otherwise today in UTC.</summary>
    private DateOnly Today(DateOnly? date)
    {
        var utcToday = DateOnly.FromDateTime(clock.UtcNow);
        return date is { } local && Math.Abs(local.DayNumber - utcToday.DayNumber) <= 1 ? local : utcToday;
    }

    private static CyclePredictionDto ToPrediction(CycleOutlook outlook)
    {
        var forecast = outlook.Forecast;
        return new(forecast.NextPeriodStart, forecast.FertileWindowStart, forecast.NextPeriodEnd,
            forecast.FertileWindowEnd, forecast.CycleStart, forecast.CycleDay, outlook.LastPeriodStart, forecast.DaysLate);
    }

    /// <summary>An active pregnancy; with <paramref name="sharedWithCouple"/>, only one shared with that couple.</summary>
    private Task<bool> PregnantAsync(Guid userId, Guid? sharedWithCouple, CancellationToken cancellationToken)
        => db.Pregnancies.AsNoTracking().AnyAsync(item => item.UserId == userId && item.Status == PregnancyStatus.Active
            && (sharedWithCouple == null || (item.ShareWithPartner && item.CoupleId == sharedWithCouple)), cancellationToken);

    private static CycleProfileDto ToProfile(CycleProfile item) => new(item.UserId, item.AverageCycleLengthDays, item.AveragePeriodLengthDays, item.ShareLevelWithPartner, item.LastPeriodStartDate);
    private static CycleDayLogDto ToLog(CycleDayLog item) => new(item.LogDate, item.FlowLevel, item.Symptoms, item.Mood, item.HadSex, item.ProtectionUsed, item.Notes);
    private static SharedCycleDayLogDto ToSharedLog(CycleDayLog item) => new(item.LogDate, item.FlowLevel, item.Symptoms, item.Mood, item.Notes);
}

public sealed record UpdateCycleProfileRequest(int AverageCycleLengthDays, int AveragePeriodLengthDays, DateOnly? LastPeriodStartDate, ShareLevel ShareLevel);
public sealed record CycleDayLogRequest(FlowLevel FlowLevel, IReadOnlyList<string>? Symptoms, Mood? Mood, bool? HadSex, bool? ProtectionUsed, string? Notes);
public sealed record CycleProfileDto(Guid UserId, int AverageCycleLengthDays, int AveragePeriodLengthDays, ShareLevel ShareLevel, DateOnly? LastPeriodStartDate);
public sealed record CycleDayLogDto(DateOnly LogDate, FlowLevel FlowLevel, IReadOnlyList<string> Symptoms, Mood? Mood, bool? HadSex, bool? ProtectionUsed, string? Notes);
/// <summary>What a partner sees of a logged day at "everything": no sex or protection, which no screen shows them.</summary>
public sealed record SharedCycleDayLogDto(DateOnly LogDate, FlowLevel FlowLevel, IReadOnlyList<string> Symptoms, Mood? Mood, string? Notes);
/// <summary>
/// PeriodEnd is the last day of the next period. The fertile window and cycle day describe the cycle that contains today.
/// LastPeriodStart is the real last start (logged flow, else the profile); DaysLate is set while a due period hasn't been logged.
/// </summary>
public sealed record CyclePredictionDto(DateOnly? NextPeriodStart, DateOnly? FertileWindowStart, DateOnly? PeriodEnd, DateOnly? FertileWindowEnd = null, DateOnly? CycleStart = null, int? CycleDay = null,
    DateOnly? LastPeriodStart = null, int? DaysLate = null);
public sealed record SharedCycleDto(CycleProfileDto? Profile, CyclePredictionDto? Prediction, IReadOnlyList<SharedCycleDayLogDto> Logs);
