using System.Diagnostics;
using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Food;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class FoodController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    IHubContext<CoupleHub> hub,
    IFoodPhotoEstimator estimator,
    FoodSettings settings,
    ILogger<FoodController> logger) : ApiControllerBase
{
    private static readonly ApiError NoCouple = new("food.no_couple", "Link your partner before logging food.");
    private static readonly JsonSerializerOptions ResultJson = new(JsonSerializerDefaults.Web);

    [HttpGet("targets")]
    public async Task<ActionResult<NutritionTargetDto>> Targets(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var target = await db.NutritionTargets.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        return Ok(target is null ? FoodMapper.DefaultTargets(currentUser.UserId.Value) : FoodMapper.ToDto(target));
    }

    [HttpPut("targets")]
    public async Task<ActionResult<NutritionTargetDto>> UpsertTargets(UpsertNutritionTargetRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var rules = FoodRules.ValidateTarget(request.DailyCalories, request.ProteinGrams, request.CarbsGrams, request.FatGrams, request.ShareLevel);
        if (rules.IsFailure) return Problem(rules.Error);
        var target = await db.NutritionTargets.SingleOrDefaultAsync(item => item.UserId == currentUser.UserId.Value, cancellationToken);
        if (target is null)
        {
            target = NutritionTarget.Create(currentUser.UserId.Value, couple.Id, clock.UtcNow);
            db.NutritionTargets.Add(target);
        }
        target.Update(request.DailyCalories, request.ProteinGrams, request.CarbsGrams, request.FatGrams, request.ShareLevel, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return Ok(FoodMapper.ToDto(target));
    }

    /// <summary>One day for me, plus whatever my partner shares of theirs.</summary>
    [HttpGet("day")]
    public async Task<ActionResult<FoodDayDto>> Day([FromQuery] string? date, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var localDate = string.IsNullOrWhiteSpace(date) ? DateOnly.FromDateTime(clock.UtcNow) : FoodRules.ParseLocalDate(date) is { IsSuccess: true } parsed ? parsed.Value : default;
        if (localDate == default) return UnprocessableEntity(new ApiError("food.invalid_date", "Date must look like yyyy-MM-dd."));

        var couple = await ActiveCoupleAsync(cancellationToken);
        var targets = await db.NutritionTargets.AsNoTracking().Where(item => couple != null ? item.CoupleId == couple.Id : item.UserId == userId).ToListAsync(cancellationToken);
        var mine = targets.FirstOrDefault(item => item.UserId == userId);
        var myTargets = mine is null ? FoodMapper.DefaultTargets(userId) : FoodMapper.ToDto(mine);
        var entries = await db.FoodEntries.AsNoTracking()
            .Where(item => item.UserId == userId && item.LocalDate == localDate)
            .OrderBy(item => item.LoggedAtUtc)
            .ToListAsync(cancellationToken);
        var totals = FoodMapper.Totals(entries);
        var scansUsed = await ScansUsedTodayAsync(userId, cancellationToken);

        FoodPartnerDayDto? partner = null;
        if (couple is not null && PartnerOf(couple, userId) is { } partnerId)
        {
            var partnerName = await db.Users.AsNoTracking().Where(user => user.Id == partnerId).Select(user => user.DisplayName).SingleOrDefaultAsync(cancellationToken) ?? "Your partner";
            var theirs = targets.FirstOrDefault(item => item.UserId == partnerId);
            var share = theirs?.ShareLevel ?? FoodShareLevel.TotalsOnly;
            var partnerEntries = share == FoodShareLevel.None
                ? []
                : await db.FoodEntries.AsNoTracking().Where(item => item.UserId == partnerId && item.LocalDate == localDate).OrderBy(item => item.LoggedAtUtc).ToListAsync(cancellationToken);
            partner = new FoodPartnerDayDto(
                partnerId,
                partnerName,
                share,
                share == FoodShareLevel.None ? null : (theirs?.DailyCalories ?? NutritionTarget.DefaultDailyCalories),
                share == FoodShareLevel.None ? null : FoodMapper.Totals(partnerEntries),
                share == FoodShareLevel.FullDetail ? partnerEntries.Select(FoodMapper.ToDto).ToList() : []);
        }

        return Ok(new FoodDayDto(
            FoodMapper.Wire(localDate),
            myTargets,
            totals,
            myTargets.DailyCalories - totals.Calories,
            entries.Select(FoodMapper.ToDto).ToList(),
            partner,
            scansUsed,
            settings.DailyScanLimit));
    }

    [HttpPost("entries")]
    public async Task<ActionResult<FoodEntryDto>> CreateEntry(CreateFoodEntryRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var date = FoodRules.ParseLocalDate(request.LocalDate);
        if (date.IsFailure) return Problem(date.Error);
        var rules = FoodRules.ValidateEntry(request.MealType, request.Description, request.Calories, request.ProteinGrams, request.CarbsGrams, request.FatGrams);
        if (rules.IsFailure) return Problem(rules.Error);

        // An entry built from a scan keeps the estimate's range and confidence, but only if the
        // scan is the caller's own and the accepted calories still sit inside the range.
        FoodScan? scan = null;
        if (request.ScanId is { } scanId)
        {
            scan = await db.FoodScans.AsNoTracking().SingleOrDefaultAsync(item => item.Id == scanId && item.UserId == currentUser.UserId.Value && item.Succeeded, cancellationToken);
            if (scan is null) return UnprocessableEntity(new ApiError("food.scan_not_found", "That scan does not exist or is not yours."));
        }
        var estimate = scan?.ResultJson is null ? null : JsonSerializer.Deserialize<FoodEstimateSnapshot>(scan.ResultJson, ResultJson);
        var keepRange = estimate is not null && estimate.CaloriesLow <= request.Calories && request.Calories <= estimate.CaloriesHigh;

        var entry = FoodEntry.Create(couple.Id, currentUser.UserId.Value, date.Value, request.MealType, request.Description.Trim(), request.Calories,
            request.ProteinGrams, request.CarbsGrams, request.FatGrams, clock.UtcNow,
            scan is null ? FoodEntrySource.Manual : FoodEntrySource.PhotoEstimate,
            keepRange ? estimate!.CaloriesLow : null,
            keepRange ? estimate!.CaloriesHigh : null,
            estimate?.Confidence,
            scan?.Id);
        db.FoodEntries.Add(entry);
        await db.SaveChangesAsync(cancellationToken);
        await NotifyDayAsync(couple, entry.UserId, entry.LocalDate, cancellationToken);
        return Ok(FoodMapper.ToDto(entry));
    }

    [HttpPut("entries/{entryId:guid}")]
    public async Task<ActionResult<FoodEntryDto>> UpdateEntry(Guid entryId, UpdateFoodEntryRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var entry = await db.FoodEntries.SingleOrDefaultAsync(item => item.Id == entryId && item.UserId == currentUser.UserId.Value, cancellationToken);
        if (entry is null) return NotFound();
        var date = FoodRules.ParseLocalDate(request.LocalDate);
        if (date.IsFailure) return Problem(date.Error);
        var rules = FoodRules.ValidateEntry(request.MealType, request.Description, request.Calories, request.ProteinGrams, request.CarbsGrams, request.FatGrams);
        if (rules.IsFailure) return Problem(rules.Error);

        var previousDate = entry.LocalDate;
        entry.Update(date.Value, request.MealType, request.Description.Trim(), request.Calories, request.ProteinGrams, request.CarbsGrams, request.FatGrams, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        await NotifyDayAsync(couple, entry.UserId, entry.LocalDate, cancellationToken);
        if (previousDate != entry.LocalDate) await NotifyDayAsync(couple, entry.UserId, previousDate, cancellationToken);
        return Ok(FoodMapper.ToDto(entry));
    }

    [HttpDelete("entries/{entryId:guid}")]
    public async Task<IActionResult> DeleteEntry(Guid entryId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var entry = await db.FoodEntries.SingleOrDefaultAsync(item => item.Id == entryId && item.UserId == currentUser.UserId.Value, cancellationToken);
        if (entry is null) return NotFound();
        db.FoodEntries.Remove(entry);
        await db.SaveChangesAsync(cancellationToken);
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is not null) await NotifyDayAsync(couple, entry.UserId, entry.LocalDate, cancellationToken);
        return NoContent();
    }

    /// <summary>Daily totals for both partners (the partner's only when shared), for charts.</summary>
    [HttpGet("history")]
    public async Task<ActionResult<FoodHistoryDto>> History([FromQuery] int days = 14, CancellationToken cancellationToken = default)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        days = Math.Clamp(days, 1, 90);
        var userId = currentUser.UserId.Value;
        var since = DateOnly.FromDateTime(clock.UtcNow).AddDays(-days);
        var couple = await ActiveCoupleAsync(cancellationToken);
        var users = new List<Guid> { userId };
        var targets = await db.NutritionTargets.AsNoTracking().Where(item => couple != null ? item.CoupleId == couple.Id : item.UserId == userId).ToListAsync(cancellationToken);
        if (couple is not null && PartnerOf(couple, userId) is { } partnerId
            && (targets.FirstOrDefault(item => item.UserId == partnerId)?.ShareLevel ?? FoodShareLevel.TotalsOnly) != FoodShareLevel.None)
            users.Add(partnerId);

        var rows = await db.FoodEntries.AsNoTracking()
            .Where(item => users.Contains(item.UserId) && item.LocalDate > since)
            .GroupBy(item => new { item.UserId, item.LocalDate })
            .Select(group => new
            {
                group.Key.UserId,
                group.Key.LocalDate,
                Calories = group.Sum(item => item.Calories),
                Protein = group.Sum(item => item.ProteinGrams ?? 0),
                Carbs = group.Sum(item => item.CarbsGrams ?? 0),
                Fat = group.Sum(item => item.FatGrams ?? 0)
            })
            .ToListAsync(cancellationToken);
        var items = rows
            .OrderBy(row => row.LocalDate).ThenBy(row => row.UserId)
            .Select(row => new FoodHistoryDayDto(row.UserId, FoodMapper.Wire(row.LocalDate), row.Calories, Math.Round(row.Protein, 1), Math.Round(row.Carbs, 1), Math.Round(row.Fat, 1),
                targets.FirstOrDefault(item => item.UserId == row.UserId)?.DailyCalories ?? NutritionTarget.DefaultDailyCalories))
            .ToList();
        return Ok(new FoodHistoryDto(days, items));
    }

    /// <summary>
    /// Estimates calories from a photo. The photo is sent to the model and discarded; only the
    /// estimate and token counts are stored. Bounded by a per-user daily quota.
    /// </summary>
    [HttpPost("scans")]
    public async Task<ActionResult<FoodScanResultDto>> Scan(FoodScanRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        if (!estimator.IsConfigured)
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError("food.estimation_unavailable", "Photo estimates are not set up on this server. Log the meal by hand."));
        var photo = FoodRules.ValidateImage(request.ImageBase64, request.MediaType, settings.MaxImageBytes);
        if (photo.IsFailure) return Problem(photo.Error);
        var used = await ScansUsedTodayAsync(userId, cancellationToken);
        if (used >= settings.DailyScanLimit)
            return StatusCode(StatusCodes.Status429TooManyRequests, new ApiError("food.scan_limit", $"You have used today's {settings.DailyScanLimit} photo scans. Log this one by hand."));

        var watch = Stopwatch.StartNew();
        var result = await estimator.EstimateAsync(photo.Value, FoodRules.CleanHint(request.Portion, request.Hint), cancellationToken);
        watch.Stop();
        var now = clock.UtcNow;
        var latency = (int)Math.Min(watch.ElapsedMilliseconds, int.MaxValue);

        if (result.IsFailure)
        {
            db.FoodScans.Add(FoodScan.Fail(userId, couple.Id, estimator.ModelName, photo.Value.MediaType, photo.Value.Bytes.Length, result.Error.Code, latency, now));
            await db.SaveChangesAsync(cancellationToken);
            logger.LogWarning("Food scan failed for user {UserId}: {Code} after {LatencyMs} ms.", userId, result.Error.Code, latency);
            return Problem(result.Error);
        }

        var estimate = result.Value;
        if (!estimate.IsFood)
        {
            db.FoodScans.Add(FoodScan.Fail(userId, couple.Id, estimator.ModelName, photo.Value.MediaType, photo.Value.Bytes.Length, "food.not_food", latency, now));
            await db.SaveChangesAsync(cancellationToken);
            return UnprocessableEntity(new ApiError("food.not_food", "That does not look like food. Try a photo of the meal."));
        }

        var snapshot = new FoodEstimateSnapshot(estimate.Description, estimate.Calories, estimate.CaloriesLow, estimate.CaloriesHigh, estimate.ProteinGrams, estimate.CarbsGrams, estimate.FatGrams, estimate.Confidence,
            estimate.Items.Select(item => new FoodEstimateItemSnapshot(item.Name, item.Portion, item.Calories)).ToList());
        var scan = FoodScan.Succeed(userId, couple.Id, estimator.ModelName, photo.Value.MediaType, photo.Value.Bytes.Length, estimate.InputTokens, estimate.OutputTokens, latency,
            JsonSerializer.Serialize(snapshot, ResultJson), now);
        db.FoodScans.Add(scan);
        await db.SaveChangesAsync(cancellationToken);
        logger.LogInformation("Food scan {ScanId} for user {UserId}: {Model}, {InputTokens} in / {OutputTokens} out, {LatencyMs} ms, confidence {Confidence}.",
            scan.Id, userId, estimator.ModelName, estimate.InputTokens, estimate.OutputTokens, latency, estimate.Confidence);
        return Ok(FoodMapper.ToDto(scan.Id, estimate, estimator.ModelName, Math.Max(0, settings.DailyScanLimit - used - 1)));
    }

    private async Task NotifyDayAsync(Couple couple, Guid userId, DateOnly localDate, CancellationToken cancellationToken)
    {
        var share = await db.NutritionTargets.AsNoTracking().Where(item => item.UserId == userId).Select(item => (FoodShareLevel?)item.ShareLevel).SingleOrDefaultAsync(cancellationToken) ?? FoodShareLevel.TotalsOnly;
        if (share == FoodShareLevel.None) return;
        var entries = await db.FoodEntries.AsNoTracking().Where(item => item.UserId == userId && item.LocalDate == localDate).ToListAsync(cancellationToken);
        await FoodEvents.DayUpdatedAsync(hub, couple.Id, new FoodDayUpdatedDto(userId, FoodMapper.Wire(localDate), FoodMapper.Totals(entries), share), cancellationToken);
    }

    private Task<int> ScansUsedTodayAsync(Guid userId, CancellationToken cancellationToken)
    {
        var midnight = clock.UtcNow.Date;
        return db.FoodScans.AsNoTracking().CountAsync(item => item.UserId == userId && item.CreatedAtUtc >= midnight, cancellationToken);
    }

    private async Task<Couple?> ActiveCoupleAsync(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return null;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        return couple is not null && (couple.UserAId == currentUser.UserId.Value || couple.UserBId == currentUser.UserId.Value) ? couple : null;
    }

    private static Guid? PartnerOf(Couple couple, Guid userId) => couple.UserAId == userId ? couple.UserBId : couple.UserAId;

    private ActionResult Problem(Error error) => error.Type switch
    {
        ErrorType.Validation => UnprocessableEntity(new ApiError(error.Code, error.Message)),
        ErrorType.NotFound => NotFound(new ApiError(error.Code, error.Message)),
        ErrorType.Conflict => Conflict(new ApiError(error.Code, error.Message)),
        ErrorType.Network => StatusCode(StatusCodes.Status502BadGateway, new ApiError(error.Code, error.Message)),
        _ => BadRequest(new ApiError(error.Code, error.Message))
    };

    /// <summary>Shape of FoodScan.ResultJson. Kept private to the controller that writes and reads it.</summary>
    private sealed record FoodEstimateSnapshot(string Description, int Calories, int CaloriesLow, int CaloriesHigh, double ProteinGrams, double CarbsGrams, double FatGrams, double Confidence, IReadOnlyList<FoodEstimateItemSnapshot> Items);
    private sealed record FoodEstimateItemSnapshot(string Name, string Portion, int Calories);
}
