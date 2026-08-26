using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class CycleController(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock) : ApiControllerBase
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

    [HttpPost("claim")]
    public async Task<ActionResult<CycleProfileDto>> Claim(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var actor = await db.Users.SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (actor is null) return NotFound();
        if (actor.Gender != Gender.Female)
            return Forbid();
        if (currentUser.CoupleId.HasValue && await db.Users.AsNoTracking().AnyAsync(item => item.CoupleId == currentUser.CoupleId.Value && item.Id != actor.Id && item.CycleOwner, cancellationToken))
            return Conflict(new ApiError("cycle.owner_exists", "Your partner already owns the shared cycle calendar."));
        actor.ClaimCycleOwnership(clock.UtcNow);
        var profile = await db.CycleProfiles.SingleOrDefaultAsync(item => item.UserId == actor.Id, cancellationToken);
        if (profile is null) { profile = CycleProfile.Create(actor.Id, clock.UtcNow); db.CycleProfiles.Add(profile); }
        profile.SetShareLevel(ShareLevel.PredictionsOnly, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return Ok(ToProfile(profile));
    }

    [HttpPut("profile")]
    public async Task<ActionResult<CycleProfileDto>> UpdateProfile(UpdateCycleProfileRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var actor = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (actor is null || !actor.CycleOwner) return Forbid();
        if (request.AverageCycleLengthDays is < 15 or > 60 || request.AveragePeriodLengthDays is < 1 or > 14)
            return UnprocessableEntity(new ApiError("cycle.invalid_profile", "Cycle lengths are outside the supported range."));
        var profile = await db.CycleProfiles.SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        if (profile is null) { profile = CycleProfile.Create(currentUser.UserId.Value, clock.UtcNow); db.CycleProfiles.Add(profile); }
        profile.SetCycleSettings(request.AverageCycleLengthDays, request.AveragePeriodLengthDays, request.LastPeriodStartDate, clock.UtcNow);
        profile.SetShareLevel(request.ShareLevel, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
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
        var actor = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (actor is null || !actor.CycleOwner) return Forbid();
        var log = await db.CycleDayLogs.SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value && item.LogDate == date, cancellationToken);
        if (log is null) { log = CycleDayLog.Create(currentUser.UserId.Value, date, request.FlowLevel, request.Symptoms, request.Mood, request.HadSex, request.ProtectionUsed, request.Notes, clock.UtcNow); db.CycleDayLogs.Add(log); }
        else log.Update(request.FlowLevel, request.Symptoms, request.Mood, request.HadSex, request.ProtectionUsed, request.Notes, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return Ok(ToLog(log));
    }

    [HttpGet("prediction")]
    public async Task<ActionResult<CyclePredictionDto>> GetPrediction(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var owner = await db.Users.AsNoTracking().AnyAsync(item => item.Id == currentUser.UserId.Value && item.CycleOwner, cancellationToken);
        if (!owner) return Ok(new CyclePredictionDto(null, null, null));
        var profile = await db.CycleProfiles.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        if (profile?.LastPeriodStartDate is not { } start) return Ok(new CyclePredictionDto(null, null, null));
        var next = start.AddDays(profile.AverageCycleLengthDays);
        return Ok(new CyclePredictionDto(next, next.AddDays(-5), next.AddDays(profile.AveragePeriodLengthDays - 1)));
    }

    [HttpGet("shared")]
    public async Task<ActionResult<SharedCycleDto>> GetShared(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue || !currentUser.CoupleId.HasValue) return NotFound();
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        if (couple is null) return NotFound();
        var partnerId = couple.UserAId == currentUser.UserId.Value ? couple.UserBId : couple.UserAId;
        if (!partnerId.HasValue) return NotFound();
        var partnerOwnsCycle = await db.Users.AsNoTracking().AnyAsync(item => item.Id == partnerId.Value && item.CycleOwner, cancellationToken);
        if (!partnerOwnsCycle) return Ok(new SharedCycleDto(null, null, Array.Empty<CycleDayLogDto>()));
        var profile = await db.CycleProfiles.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == partnerId.Value, cancellationToken);
        if (profile is null || profile.ShareLevelWithPartner == ShareLevel.None) return Ok(new SharedCycleDto(null, null, Array.Empty<CycleDayLogDto>()));
        var prediction = profile.LastPeriodStartDate is { } start
            ? new CyclePredictionDto(start.AddDays(profile.AverageCycleLengthDays), start.AddDays(profile.AverageCycleLengthDays - 5), start.AddDays(profile.AverageCycleLengthDays + profile.AveragePeriodLengthDays - 1))
            : new CyclePredictionDto(null, null, null);
        var sharedLogs = profile.ShareLevelWithPartner == ShareLevel.FullDetail
            ? await db.CycleDayLogs.AsNoTracking().Where(item => item.UserId == partnerId.Value).OrderByDescending(item => item.LogDate).Take(120).ToListAsync(cancellationToken)
            : [];
        var logs = sharedLogs.Select(ToLog).ToList();
        return Ok(new SharedCycleDto(ToProfile(profile), prediction, logs));
    }

    private static CycleProfileDto ToProfile(CycleProfile item) => new(item.UserId, item.AverageCycleLengthDays, item.AveragePeriodLengthDays, item.ShareLevelWithPartner, item.LastPeriodStartDate);
    private static CycleDayLogDto ToLog(CycleDayLog item) => new(item.LogDate, item.FlowLevel, item.Symptoms, item.Mood, item.HadSex, item.ProtectionUsed, item.Notes);
}

public sealed record UpdateCycleProfileRequest(int AverageCycleLengthDays, int AveragePeriodLengthDays, DateOnly? LastPeriodStartDate, ShareLevel ShareLevel);
public sealed record CycleDayLogRequest(FlowLevel FlowLevel, IReadOnlyList<string>? Symptoms, Mood? Mood, bool? HadSex, bool? ProtectionUsed, string? Notes);
public sealed record CycleProfileDto(Guid UserId, int AverageCycleLengthDays, int AveragePeriodLengthDays, ShareLevel ShareLevel, DateOnly? LastPeriodStartDate);
public sealed record CycleDayLogDto(DateOnly LogDate, FlowLevel FlowLevel, IReadOnlyList<string> Symptoms, Mood? Mood, bool? HadSex, bool? ProtectionUsed, string? Notes);
public sealed record CyclePredictionDto(DateOnly? NextPeriodStart, DateOnly? FertileWindowStart, DateOnly? PeriodEnd);
public sealed record SharedCycleDto(CycleProfileDto? Profile, CyclePredictionDto? Prediction, IReadOnlyList<CycleDayLogDto> Logs);
