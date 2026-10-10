using System.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Memories;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Memories;

namespace Twogether.Api.Controllers;

/// <summary>
/// The couple's shared album. Uploads go straight from the phone to object storage on a
/// short-lived signed URL; the API only hands out slots, verifies what arrived, and signs
/// view URLs for the couple that owns each memory.
/// </summary>
[Authorize]
public sealed class MemoriesController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    IMediaStorage storage,
    MediaSettings settings,
    IHubContext<CoupleHub> hub,
    ILogger<MemoriesController> logger) : ApiControllerBase
{
    public const string ChangedEvent = "MemoriesChanged";
    private static readonly ApiError NoCouple = new("memories.no_couple", "Link your partner to start your shared album.");
    private static readonly ApiError Unavailable = new("memories.storage_unavailable", "Photo storage is not set up on this server.");
    private static readonly ApiError NotUploader = new("memories.not_uploader", "Only the person who added this memory can change it.");

    [HttpGet("usage")]
    public async Task<ActionResult<MemoryUsageDto>> Usage(CancellationToken cancellationToken)
    {
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(new MemoryUsageDto(0, 0, settings.MaxBytesPerCouple));
        var ready = db.Memories.AsNoTracking().Where(item => item.CoupleId == couple.Id && item.Status == MemoryStatus.Ready);
        return Ok(new MemoryUsageDto(await ready.CountAsync(cancellationToken), await ready.SumAsync(item => item.SizeBytes, cancellationToken), settings.MaxBytesPerCouple));
    }

    /// <summary>Reserves a slot and returns a signed PUT URL. The client must send the Content-Type it declared.</summary>
    [HttpPost("uploads")]
    public async Task<ActionResult<MemoryUploadDto>> Reserve(ReserveMemoryRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        if (!storage.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable);
        var now = clock.UtcNow;
        var kind = MemoryRequestRules.ValidateReserve(request, now);
        if (kind.IsFailure) return UnprocessableEntity(new ApiError(kind.Error.Code, kind.Error.Message));

        // Pending slots count against the quota at their declared size, so parallel uploads cannot overshoot it.
        var used = await db.Memories.AsNoTracking()
            .Where(item => item.CoupleId == couple.Id && item.Status != MemoryStatus.Deleting)
            .SumAsync(item => item.SizeBytes, cancellationToken);
        if (used + request.SizeBytes > settings.MaxBytesPerCouple)
            return Conflict(new ApiError("memories.quota_exceeded", "Your shared album is full. Remove some memories to add more."));

        var memory = Memory.Reserve(couple.Id, currentUser.UserId.Value, kind.Value, request.SizeBytes, request.Caption, request.TakenAtUtc, request.Source, now);
        db.Memories.Add(memory);
        await db.SaveChangesAsync(cancellationToken);
        var url = storage.PresignUpload(memory.ObjectKey, memory.ContentType, settings.UploadUrlLifetime);
        return Ok(new MemoryUploadDto(memory.Id, url.ToString(), "PUT", new Dictionary<string, string> { ["Content-Type"] = memory.ContentType }, now.Add(settings.UploadUrlLifetime)));
    }

    /// <summary>
    /// Confirms an upload. The stored object must exist, match the declared type, and fit the
    /// type's size cap; anything else is deleted from storage and rejected. Repeating it is safe.
    /// </summary>
    [HttpPost("{memoryId:guid}/complete")]
    public async Task<ActionResult<MemoryDto>> Complete(Guid memoryId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        if (!storage.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable);
        var memory = await db.Memories.SingleOrDefaultAsync(item => item.Id == memoryId && item.CoupleId == couple.Id && item.Status != MemoryStatus.Deleting, cancellationToken);
        if (memory is null) return NotFound();
        if (memory.UploadedByUserId != currentUser.UserId.Value) return StatusCode(StatusCodes.Status403Forbidden, NotUploader);
        var names = await NamesAsync(couple, cancellationToken);
        if (memory.Status == MemoryStatus.Ready) return Ok(ToDto(memory, names));

        var stored = await storage.HeadAsync(memory.ObjectKey, cancellationToken);
        if (stored is null) return Conflict(new ApiError("memories.not_uploaded", "The file has not finished uploading yet."));
        var kind = MemoryRules.Kind(memory.ContentType)!;
        var typeMatches = string.Equals(stored.ContentType?.Split(';')[0].Trim(), memory.ContentType, StringComparison.OrdinalIgnoreCase);
        if (!typeMatches || stored.SizeBytes <= 0 || stored.SizeBytes > kind.MaxBytes)
        {
            logger.LogWarning("Rejected memory {MemoryId}: stored {StoredType} {StoredBytes} bytes, declared {DeclaredType}.", memory.Id, stored.ContentType, stored.SizeBytes, memory.ContentType);
            memory.MarkDeleting(clock.UtcNow);
            await db.SaveChangesAsync(cancellationToken);
            await TryDeleteAsync(memory, cancellationToken);
            return UnprocessableEntity(new ApiError("memories.upload_rejected", "The uploaded file did not match what was declared. Try again."));
        }

        memory.MarkReady(stored.SizeBytes, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        await ChangedAsync(couple, memory.Id, "added", cancellationToken);
        return Ok(ToDto(memory, names));
    }

    /// <summary>Newest first, keyset-paged. Unlinked users get an empty album.</summary>
    [HttpGet]
    public async Task<ActionResult<MemoryPageDto>> List([FromQuery] string? cursor, [FromQuery] int limit = 30, CancellationToken cancellationToken = default)
    {
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(new MemoryPageDto([], null));
        if (!storage.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable);
        limit = Math.Clamp(limit, 1, 100);
        var after = MemoryCursor.Decode(cursor);
        if (!string.IsNullOrWhiteSpace(cursor) && after is null) return UnprocessableEntity(new ApiError("memories.invalid_cursor", "That page cursor is not valid."));
        var query = db.Memories.AsNoTracking().Where(item => item.CoupleId == couple.Id && item.Status == MemoryStatus.Ready);
        if (after is not null)
            query = query.Where(item => item.SortAtUtc < after.SortAtUtc || (item.SortAtUtc == after.SortAtUtc && item.Id.CompareTo(after.Id) < 0));
        var page = await query.OrderByDescending(item => item.SortAtUtc).ThenByDescending(item => item.Id).Take(limit + 1).ToListAsync(cancellationToken);
        var names = await NamesAsync(couple, cancellationToken);
        var items = page.Take(limit).Select(item => ToDto(item, names)).ToList();
        var next = page.Count > limit ? new MemoryCursor(page[limit - 1].SortAtUtc, page[limit - 1].Id).Encode() : null;
        return Ok(new MemoryPageDto(items, next));
    }

    /// <summary>Memories from this calendar day in earlier years, by the date taken (or added).</summary>
    [HttpGet("on-this-day")]
    public async Task<ActionResult<IReadOnlyList<MemoryDto>>> OnThisDay([FromQuery] string? date, CancellationToken cancellationToken)
    {
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(Array.Empty<MemoryDto>());
        if (!storage.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable);
        var today = DateOnly.FromDateTime(clock.UtcNow);
        if (!string.IsNullOrWhiteSpace(date) && !DateOnly.TryParseExact(date.Trim(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out today))
            return UnprocessableEntity(new ApiError("memories.invalid_date", "Date must look like yyyy-MM-dd."));
        var month = today.Month;
        var candidates = await db.Memories.AsNoTracking()
            .Where(item => item.CoupleId == couple.Id && item.Status == MemoryStatus.Ready && item.SortAtUtc.Month == month && item.SortAtUtc.Year < today.Year)
            .ToListAsync(cancellationToken);
        var names = await NamesAsync(couple, cancellationToken);
        return Ok(candidates
            .Where(item => MemoryRules.IsOnThisDay(DateOnly.FromDateTime(item.SortAtUtc), today))
            .OrderByDescending(item => item.SortAtUtc)
            .Select(item => ToDto(item, names))
            .ToList());
    }

    [HttpPatch("{memoryId:guid}")]
    public async Task<ActionResult<MemoryDto>> Update(Guid memoryId, UpdateMemoryRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var memory = await db.Memories.SingleOrDefaultAsync(item => item.Id == memoryId && item.CoupleId == couple.Id && item.Status == MemoryStatus.Ready, cancellationToken);
        if (memory is null) return NotFound();
        if (memory.UploadedByUserId != currentUser.UserId.Value) return StatusCode(StatusCodes.Status403Forbidden, NotUploader);
        var rules = MemoryRequestRules.ValidateDescription(request.Caption, request.TakenAtUtc, clock.UtcNow);
        if (rules.IsFailure) return UnprocessableEntity(new ApiError(rules.Error.Code, rules.Error.Message));
        memory.Describe(request.Caption, request.TakenAtUtc, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        await ChangedAsync(couple, memory.Id, "updated", cancellationToken);
        return Ok(ToDto(memory, await NamesAsync(couple, cancellationToken)));
    }

    /// <summary>Hides the memory at once, then removes the file. A failed removal is retried by the cleanup worker.</summary>
    [HttpDelete("{memoryId:guid}")]
    public async Task<IActionResult> Delete(Guid memoryId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var memory = await db.Memories.SingleOrDefaultAsync(item => item.Id == memoryId && item.CoupleId == couple.Id && item.Status != MemoryStatus.Deleting, cancellationToken);
        if (memory is null) return NotFound();
        if (memory.UploadedByUserId != currentUser.UserId.Value) return StatusCode(StatusCodes.Status403Forbidden, NotUploader);
        memory.MarkDeleting(clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        if (storage.IsConfigured) await TryDeleteAsync(memory, cancellationToken);
        await ChangedAsync(couple, memory.Id, "deleted", cancellationToken);
        return NoContent();
    }

    private async Task TryDeleteAsync(Memory memory, CancellationToken cancellationToken)
    {
        try
        {
            await storage.DeleteAsync(memory.ObjectKey, cancellationToken);
            db.Memories.Remove(memory);
            await db.SaveChangesAsync(cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            logger.LogWarning(exception, "Could not remove memory {MemoryId} from storage yet; the cleanup worker will retry.", memory.Id);
        }
    }

    private MemoryDto ToDto(Memory memory, IReadOnlyDictionary<Guid, string> names)
    {
        var lifetime = settings.DownloadUrlLifetime;
        return new MemoryDto(
            memory.Id,
            memory.UploadedByUserId,
            names.GetValueOrDefault(memory.UploadedByUserId, "Your partner"),
            memory.ContentType,
            memory.IsVideo,
            memory.SizeBytes,
            memory.Caption,
            memory.TakenAtUtc,
            memory.SortAtUtc,
            memory.Source,
            memory.CreatedAtUtc,
            storage.PresignDownload(memory.ObjectKey, lifetime).ToString(),
            clock.UtcNow.Add(lifetime));
    }

    private Task ChangedAsync(Couple couple, Guid memoryId, string change, CancellationToken cancellationToken)
        => hub.Clients.Group(CoupleHub.GroupName(couple.Id)).SendAsync(ChangedEvent, new { memoryId, change }, cancellationToken);

    private async Task<Dictionary<Guid, string>> NamesAsync(Couple couple, CancellationToken cancellationToken)
    {
        var ids = couple.UserBId is { } b ? new[] { couple.UserAId, b } : [couple.UserAId];
        return await db.Users.AsNoTracking().Where(user => ids.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.DisplayName, cancellationToken);
    }

    private async Task<Couple?> ActiveCoupleAsync(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return null;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        return couple is not null && (couple.UserAId == currentUser.UserId.Value || couple.UserBId == currentUser.UserId.Value) ? couple : null;
    }
}
