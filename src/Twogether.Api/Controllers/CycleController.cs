using Twogether.Core.Cycle;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

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
            return Conflict(new ApiError("cycle.owner_exists", Loc.T("Your partner already owns the shared cycle calendar.")));
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
            return UnprocessableEntity(new ApiError("cycle.invalid_profile", Loc.T("Cycle lengths are outside the supported range.")));
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

    private const int MaxNotesLength = 2000;
    private const int MaxSymptoms = 30;
    private const int MaxSymptomLength = 50;

    [HttpPut("logs/{date}")]
    public async Task<ActionResult<CycleDayLogDto>> UpsertLog(DateOnly date, CycleDayLogRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var actor = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (actor is null || !actor.CycleOwner) return Forbid();
        if (request.Notes?.Length > MaxNotesLength)
            return UnprocessableEntity(new ApiError("cycle.invalid_notes", Loc.T("Notes are at most {max} characters.", ("max", MaxNotesLength))));
        if (request.Symptoms is { } symptoms && (symptoms.Count > MaxSymptoms || symptoms.Any(symptom => string.IsNullOrWhiteSpace(symptom) || symptom.Length > MaxSymptomLength)))
            return UnprocessableEntity(new ApiError("cycle.invalid_symptoms", Loc.T("Up to {symptoms} symptoms of at most {characters} characters each.", ("symptoms", MaxSymptoms), ("characters", MaxSymptomLength))));
        var log = await db.CycleDayLogs.SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value && item.LogDate == date, cancellationToken);
        if (log is null) { log = CycleDayLog.Create(currentUser.UserId.Value, date, request.FlowLevel, request.Symptoms, request.Mood, request.HadSex, request.ProtectionUsed, request.Notes, clock.UtcNow); db.CycleDayLogs.Add(log); }
        else log.Update(request.FlowLevel, request.Symptoms, request.Mood, request.HadSex, request.ProtectionUsed, request.Notes, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return Ok(ToLog(log));
    }

    [HttpGet("prediction")]
    public async Task<ActionResult<CyclePredictionDto>> GetPrediction([FromQuery] DateOnly? date, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var owner = await db.Users.AsNoTracking().AnyAsync(item => item.Id == currentUser.UserId.Value && item.CycleOwner, cancellationToken);
        if (!owner) return Ok(new CyclePredictionDto(null, null, null));
        var profile = await db.CycleProfiles.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        if (profile?.LastPeriodStartDate is not { } start) return Ok(new CyclePredictionDto(null, null, null));
        // Period predictions are meaningless during a pregnancy; show none rather than a wrong date.
        if (await PregnantAsync(currentUser.UserId.Value, cancellationToken)) return Ok(new CyclePredictionDto(null, null, null));
        return Ok(ToPrediction(CyclePrediction.Predict(start, profile.AverageCycleLengthDays, profile.AveragePeriodLengthDays, Today(date))));
    }

    [HttpGet("shared")]
    public async Task<ActionResult<SharedCycleDto>> GetShared([FromQuery] DateOnly? date, CancellationToken cancellationToken)
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
        // Hide the partner's predictions only when they share the pregnancy too: predictions that
        // vanish without explanation would reveal a pregnancy they chose to keep private.
        var prediction = profile.LastPeriodStartDate is { } start && !await PregnantAsync(partnerId.Value, cancellationToken, sharedOnly: true)
            ? ToPrediction(CyclePrediction.Predict(start, profile.AverageCycleLengthDays, profile.AveragePeriodLengthDays, Today(date)))
            : new CyclePredictionDto(null, null, null);
        var sharedLogs = profile.ShareLevelWithPartner == ShareLevel.FullDetail
            ? await db.CycleDayLogs.AsNoTracking().Where(item => item.UserId == partnerId.Value).OrderByDescending(item => item.LogDate).Take(120).ToListAsync(cancellationToken)
            : [];
        var logs = sharedLogs.Select(ToLog).ToList();
        return Ok(new SharedCycleDto(ToProfile(profile), prediction, logs));
    }

    /// <summary>The caller's own calendar date when the app sends it (time zones differ); otherwise today in UTC.</summary>
    private DateOnly Today(DateOnly? date)
    {
        var utcToday = DateOnly.FromDateTime(clock.UtcNow);
        return date is { } local && Math.Abs(local.DayNumber - utcToday.DayNumber) <= 1 ? local : utcToday;
    }

    private static CyclePredictionDto ToPrediction(CycleForecast forecast) => new(
        forecast.NextPeriodStart, forecast.FertileWindowStart, forecast.NextPeriodEnd,
        forecast.FertileWindowEnd, forecast.CycleStart, forecast.CycleDay);

    private Task<bool> PregnantAsync(Guid userId, CancellationToken cancellationToken, bool sharedOnly = false)
        => db.Pregnancies.AsNoTracking().AnyAsync(item => item.UserId == userId && item.Status == PregnancyStatus.Active && (!sharedOnly || item.ShareWithPartner), cancellationToken);

    private static CycleProfileDto ToProfile(CycleProfile item) => new(item.UserId, item.AverageCycleLengthDays, item.AveragePeriodLengthDays, item.ShareLevelWithPartner, item.LastPeriodStartDate);
    private static CycleDayLogDto ToLog(CycleDayLog item) => new(item.LogDate, item.FlowLevel, item.Symptoms, item.Mood, item.HadSex, item.ProtectionUsed, item.Notes);
}

public sealed record UpdateCycleProfileRequest(int AverageCycleLengthDays, int AveragePeriodLengthDays, DateOnly? LastPeriodStartDate, ShareLevel ShareLevel);
public sealed record CycleDayLogRequest(FlowLevel FlowLevel, IReadOnlyList<string>? Symptoms, Mood? Mood, bool? HadSex, bool? ProtectionUsed, string? Notes);
public sealed record CycleProfileDto(Guid UserId, int AverageCycleLengthDays, int AveragePeriodLengthDays, ShareLevel ShareLevel, DateOnly? LastPeriodStartDate);
public sealed record CycleDayLogDto(DateOnly LogDate, FlowLevel FlowLevel, IReadOnlyList<string> Symptoms, Mood? Mood, bool? HadSex, bool? ProtectionUsed, string? Notes);
/// <summary>PeriodEnd is the last day of the next period. The fertile window and cycle day describe the cycle that contains today.</summary>
public sealed record CyclePredictionDto(DateOnly? NextPeriodStart, DateOnly? FertileWindowStart, DateOnly? PeriodEnd, DateOnly? FertileWindowEnd = null, DateOnly? CycleStart = null, int? CycleDay = null);
public sealed record SharedCycleDto(CycleProfileDto? Profile, CyclePredictionDto? Prediction, IReadOnlyList<CycleDayLogDto> Logs);
