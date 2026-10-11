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
    Twogether.Application.Features.Billing.IEntitlementService entitlements,
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
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var limit = (await entitlements.ForUserAsync(currentUser.UserId.Value, cancellationToken)).Limits.MemoriesBytes;
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(new MemoryUsageDto(0, 0, limit));
        var ready = db.Memories.AsNoTracking().Where(item => item.CoupleId == couple.Id && item.Status == MemoryStatus.Ready);
        return Ok(new MemoryUsageDto(await ready.CountAsync(cancellationToken), await ready.SumAsync(item => item.SizeBytes + item.ThumbnailBytes, cancellationToken), limit));
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
            .SumAsync(item => item.SizeBytes + item.ThumbnailBytes, cancellationToken);
        var entitlement = await entitlements.ForUserAsync(currentUser.UserId.Value, cancellationToken);
        if (used + request.SizeBytes > entitlement.Limits.MemoriesBytes)
            return Conflict(new ApiError("memories.quota_exceeded", entitlement.Plan == Plan.Free
                ? "Your shared album is full. Heartpact Plus gives you more room, or remove some memories."
                : "Your shared album is full. Remove some memories to add more."));

        var memory = Memory.Reserve(couple.Id, currentUser.UserId.Value, kind.Value, request.SizeBytes, request.Caption, request.TakenAtUtc, request.Source, now);
        memory.OpenUploadWindow(now.Add(settings.UploadUrlLifetime));
        db.Memories.Add(memory);
        await db.SaveChangesAsync(cancellationToken);
        var url = storage.PresignUpload(memory.ObjectKey, memory.ContentType, settings.UploadUrlLifetime);
        var thumbnail = MemoryRules.ThumbnailType(request.ThumbnailContentType) is { } thumbnailType ? ThumbnailSlot(memory, thumbnailType, now) : null;
        return Ok(new MemoryUploadDto(memory.Id, url.ToString(), "PUT", new Dictionary<string, string> { ["Content-Type"] = memory.ContentType }, now.Add(settings.UploadUrlLifetime), thumbnail));
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
        // A thumbnail uploaded alongside is picked up here; a missing or bad one never fails the photo.
        try
        {
            await VerifyThumbnailAsync(memory, cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            logger.LogWarning(exception, "Could not check the thumbnail of memory {MemoryId}; the app can add it later.", memory.Id);
        }
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

    /// <summary>
    /// After a breakup the shared album closes for both people. Each can still download the
    /// photos they uploaded themselves until <see cref="MediaSettings.BreakupRetention"/> runs
    /// out; then the cleanup worker deletes the whole album. The partner's uploads stay theirs.
    /// </summary>
    [HttpGet("archive")]
    public async Task<ActionResult<MemoryArchivePageDto>> Archive([FromQuery] string? cursor, [FromQuery] int limit = 30, CancellationToken cancellationToken = default)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        if (!storage.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable);
        limit = Math.Clamp(limit, 1, 100);
        var after = MemoryCursor.Decode(cursor);
        if (!string.IsNullOrWhiteSpace(cursor) && after is null) return UnprocessableEntity(new ApiError("memories.invalid_cursor", "That page cursor is not valid."));

        var cutoff = clock.UtcNow - settings.BreakupRetention;
        var query = db.Memories.AsNoTracking()
            .Where(item => item.UploadedByUserId == userId && item.Status == MemoryStatus.Ready)
            .Join(db.Couples.AsNoTracking().Where(couple => couple.Status == CoupleStatus.Dissolved && couple.DissolvedAtUtc > cutoff),
                memory => memory.CoupleId, couple => couple.Id, (memory, couple) => new { Memory = memory, EndedAt = couple.DissolvedAtUtc!.Value });
        if (after is not null)
            query = query.Where(row => row.Memory.SortAtUtc < after.SortAtUtc || (row.Memory.SortAtUtc == after.SortAtUtc && row.Memory.Id.CompareTo(after.Id) < 0));
        var page = await query.OrderByDescending(row => row.Memory.SortAtUtc).ThenByDescending(row => row.Memory.Id).Take(limit + 1).ToListAsync(cancellationToken);

        var name = await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => user.DisplayName).SingleAsync(cancellationToken);
        var names = new Dictionary<Guid, string> { [userId] = name };
        var items = page.Take(limit)
            .Select(row => new ArchivedMemoryDto(ToDto(row.Memory, names), row.EndedAt, row.EndedAt + settings.BreakupRetention))
            .ToList();
        var next = page.Count > limit ? new MemoryCursor(page[limit - 1].Memory.SortAtUtc, page[limit - 1].Memory.Id).Encode() : null;
        return Ok(new MemoryArchivePageDto(items, next));
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

    /// <summary>
    /// A slot to add or replace the thumbnail of a memory you uploaded: a video's poster frame,
    /// or a photo added before the app made thumbnails. Confirm it with thumbnail/complete.
    /// </summary>
    [HttpPost("{memoryId:guid}/thumbnail")]
    public async Task<ActionResult<ThumbnailUploadDto>> ReserveThumbnail(Guid memoryId, ReserveThumbnailRequest request, CancellationToken cancellationToken)
    {
        var (memory, error) = await OwnReadyMemoryAsync(memoryId, cancellationToken);
        if (memory is null) return error!;
        if (MemoryRules.ThumbnailType(request.ContentType) is not { } type)
            return UnprocessableEntity(new ApiError(MemoryRequestRules.InvalidThumbnailType.Code, MemoryRequestRules.InvalidThumbnailType.Message));
        var now = clock.UtcNow;
        memory.OpenUploadWindow(now.Add(settings.UploadUrlLifetime));
        await db.SaveChangesAsync(cancellationToken);
        return Ok(ThumbnailSlot(memory, type, now));
    }

    [HttpPost("{memoryId:guid}/thumbnail/complete")]
    public async Task<ActionResult<MemoryDto>> CompleteThumbnail(Guid memoryId, CancellationToken cancellationToken)
    {
        var (memory, error) = await OwnReadyMemoryAsync(memoryId, cancellationToken);
        if (memory is null) return error!;
        var outcome = await VerifyThumbnailAsync(memory, cancellationToken);
        if (outcome == ThumbnailOutcome.Missing) return Conflict(new ApiError("memories.thumbnail_not_uploaded", "The thumbnail has not finished uploading yet."));
        // Saved either way: a rejected upload overwrote and removed any earlier thumbnail.
        await db.SaveChangesAsync(cancellationToken);
        if (outcome == ThumbnailOutcome.Rejected)
            return UnprocessableEntity(new ApiError("memories.thumbnail_rejected", $"Thumbnails must be JPEG or WebP and at most {MemoryRules.MaxThumbnailBytes / 1024} KB."));
        var couple = await db.Couples.AsNoTracking().SingleAsync(item => item.Id == memory.CoupleId, cancellationToken);
        await ChangedAsync(couple, memory.Id, "updated", cancellationToken);
        return Ok(ToDto(memory, await NamesAsync(couple, cancellationToken)));
    }

    private ThumbnailUploadDto ThumbnailSlot(Memory memory, string contentType, DateTime now)
    {
        var url = storage.PresignUpload(memory.ThumbnailKey, contentType, settings.UploadUrlLifetime);
        return new ThumbnailUploadDto(url.ToString(), "PUT", new Dictionary<string, string> { ["Content-Type"] = contentType }, MemoryRules.MaxThumbnailBytes, now.Add(settings.UploadUrlLifetime));
    }

    private enum ThumbnailOutcome { Missing, Attached, Rejected }

    /// <summary>
    /// Checks the object at the thumbnail key; the caller saves. A good one is attached. A bad one
    /// is removed from storage, and because it overwrote any earlier thumbnail at the same key, the
    /// memory is left with none.
    /// </summary>
    private async Task<ThumbnailOutcome> VerifyThumbnailAsync(Memory memory, CancellationToken cancellationToken)
    {
        var stored = await storage.HeadAsync(memory.ThumbnailKey, cancellationToken);
        if (stored is null) return ThumbnailOutcome.Missing;
        var type = MemoryRules.ThumbnailType(stored.ContentType);
        if (type is null || stored.SizeBytes <= 0 || stored.SizeBytes > MemoryRules.MaxThumbnailBytes)
        {
            logger.LogWarning("Rejected thumbnail for memory {MemoryId}: {StoredType}, {StoredBytes} bytes.", memory.Id, stored.ContentType, stored.SizeBytes);
            await storage.DeleteAsync(memory.ThumbnailKey, cancellationToken);
            memory.DetachThumbnail(clock.UtcNow);
            return ThumbnailOutcome.Rejected;
        }
        memory.AttachThumbnail(type, stored.SizeBytes, clock.UtcNow);
        return ThumbnailOutcome.Attached;
    }

    private async Task<(Memory? Memory, ActionResult? Error)> OwnReadyMemoryAsync(Guid memoryId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return (null, Unauthorized());
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return (null, BadRequest(NoCouple));
        if (!storage.IsConfigured) return (null, StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable));
        var memory = await db.Memories.SingleOrDefaultAsync(item => item.Id == memoryId && item.CoupleId == couple.Id && item.Status == MemoryStatus.Ready, cancellationToken);
        if (memory is null) return (null, NotFound());
        if (memory.UploadedByUserId != currentUser.UserId.Value) return (null, StatusCode(StatusCodes.Status403Forbidden, NotUploader));
        return (memory, null);
    }

    /// <summary>Delete one of your own photos from an ended couple's album now, instead of waiting for the window to close.</summary>
    [HttpDelete("archive/{memoryId:guid}")]
    public async Task<IActionResult> DeleteArchived(Guid memoryId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var memory = await db.Memories.SingleOrDefaultAsync(item => item.Id == memoryId
            && item.UploadedByUserId == userId
            && item.Status != MemoryStatus.Deleting
            && db.Couples.Any(couple => couple.Id == item.CoupleId && couple.Status == CoupleStatus.Dissolved), cancellationToken);
        if (memory is null) return NotFound();
        memory.Describe(null, null, clock.UtcNow);
        memory.MarkDeleting(clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        if (storage.IsConfigured) await TryDeleteAsync(memory, cancellationToken);
        return NoContent();
    }

    private async Task TryDeleteAsync(Memory memory, CancellationToken cancellationToken)
    {
        try
        {
            await storage.DeleteAsync(memory.ObjectKey, cancellationToken);
            await storage.DeleteAsync(memory.ThumbnailKey, cancellationToken);
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
            clock.UtcNow.Add(lifetime),
            memory.HasThumbnail ? storage.PresignDownload(memory.ThumbnailKey, lifetime).ToString() : null);
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
