using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Common;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Memories;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Memories;

namespace Twogether.Api.Controllers;

/// <summary>
/// Offline-first sync. Photos stay on the phone until the person taps Sync; then the app sends
/// its queue in batches keyed by its own ids, uploads the bytes on the usual signed links, and
/// completes them. A retried batch finds what it already made, so nothing is added twice.
/// </summary>
public sealed partial class MemoriesController
{
    private static readonly ApiError SyncInProgress = new("memories.sync_in_progress", "Another sync from your account is running. Try again in a moment.");
    private static readonly ApiError ClientIdTaken = new("memories.client_id_taken", "This photo was already shared in an earlier album.");

    /// <summary>What the phone has waiting. Called whenever its queue changes and on app start.</summary>
    [HttpPut("sync/pending")]
    public async Task<IActionResult> ReportPending(ReportPendingRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var valid = MemorySyncRules.ValidatePending(request);
        if (valid.IsFailure) return UnprocessableEntity(new ApiError(valid.Error.Code, valid.Error.Message));
        var userId = currentUser.UserId.Value;
        for (var attempt = 0; ; attempt++)
        {
            var state = await SyncStateAsync(userId, cancellationToken);
            state.Report(request.PendingCount, request.PendingBytes, clock.UtcNow);
            try
            {
                await db.SaveChangesAsync(cancellationToken);
                return NoContent();
            }
            catch (DbUpdateException exception) when (attempt == 0 && IsUniqueViolation(exception))
            {
                // Two reports raced to create the row: the other one won, so this becomes an update.
                foreach (var entry in db.AlbumSyncStates.Local.ToList()) ((DbContext)db).Entry(entry).State = EntityState.Detached;
            }
        }
    }

    [HttpGet("sync")]
    public async Task<ActionResult<AlbumSyncDto>> SyncStatus(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var state = await db.AlbumSyncStates.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == userId, cancellationToken);
        var limit = (await entitlements.ForUserAsync(userId, cancellationToken)).Limits.MemoriesBytes;
        var usage = await UsageAsync(await ActiveCoupleAsync(cancellationToken), limit, cancellationToken);
        return Ok(new AlbumSyncDto(state?.PendingCount ?? 0, state?.PendingBytes ?? 0, state?.PendingSinceUtc, state?.LastSyncedAtUtc, usage, AlbumSyncRules.MaxBatchItems));
    }

    /// <summary>
    /// Takes up to 50 photos from the phone's queue and answers per item: upload (with links),
    /// done, removed, or rejected. One bad or over-quota item never fails the others.
    /// </summary>
    [HttpPost("sync")]
    public async Task<ActionResult<SyncMemoriesResponse>> Sync(SyncMemoriesRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        if (!storage.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable);
        var batch = MemorySyncRules.ValidateBatch(request);
        if (batch.IsFailure) return UnprocessableEntity(new ApiError(batch.Error.Code, batch.Error.Message));

        var userId = currentUser.UserId.Value;
        var items = request.Items!;
        var now = clock.UtcNow;
        var clientIds = items.Select(item => (Guid?)item.ClientId).ToList();
        var existing = await db.Memories
            .Where(item => item.UploadedByUserId == userId && clientIds.Contains(item.ClientId))
            .ToDictionaryAsync(item => item.ClientId!.Value, cancellationToken);
        var entitlement = await entitlements.ForUserAsync(userId, cancellationToken);
        var used = await UsedBytesAsync(couple, cancellationToken);
        var quotaExceeded = false;

        // Links are signed only after the save, so none is handed out for a row that did not stick.
        var outcomes = new List<(SyncMemoryItem Item, SyncItemStatus Status, Memory? Memory, ApiError? Error)>(items.Count);
        foreach (var item in items)
        {
            if (existing.TryGetValue(item.ClientId, out var memory))
            {
                if (memory.CoupleId != couple.Id) outcomes.Add((item, SyncItemStatus.Rejected, null, ClientIdTaken));
                else if (memory.Status == MemoryStatus.Ready) outcomes.Add((item, SyncItemStatus.Done, memory, null));
                else if (memory.Status == MemoryStatus.Deleting) outcomes.Add((item, SyncItemStatus.Removed, memory, null));
                else
                {
                    // Reserved by an earlier attempt and not completed: fresh links, longer window.
                    memory.OpenUploadWindow(now.Add(settings.UploadUrlLifetime));
                    outcomes.Add((item, SyncItemStatus.Upload, memory, null));
                }
                continue;
            }

            var kind = MemoryRequestRules.ValidateReserve(item.ToReserveRequest(), now);
            if (kind.IsFailure)
            {
                outcomes.Add((item, SyncItemStatus.Rejected, null, new ApiError(kind.Error.Code, kind.Error.Message)));
                continue;
            }
            // Cumulative: once the album is full, this item and every later new one wait for room.
            if (quotaExceeded || used + item.SizeBytes > entitlement.Limits.MemoriesBytes)
            {
                quotaExceeded = true;
                outcomes.Add((item, SyncItemStatus.Rejected, null, QuotaExceeded(entitlement.Plan)));
                continue;
            }
            used += item.SizeBytes;
            var reserved = Memory.Reserve(couple.Id, userId, kind.Value, item.SizeBytes, item.Caption, item.TakenAtUtc, item.Source, now, item.ClientId);
            reserved.OpenUploadWindow(now.Add(settings.UploadUrlLifetime));
            db.Memories.Add(reserved);
            outcomes.Add((item, SyncItemStatus.Upload, reserved, null));
        }

        (await SyncStateAsync(userId, cancellationToken)).StartSync(now);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException exception) when (IsUniqueViolation(exception))
        {
            logger.LogInformation(exception, "Album sync for user {UserId} raced another sync.", userId);
            return Conflict(SyncInProgress);
        }

        var results = outcomes.Select(outcome => new SyncItemResultDto(
            outcome.Item.ClientId,
            outcome.Status,
            outcome.Status is SyncItemStatus.Rejected ? null : outcome.Memory!.Id,
            outcome.Status == SyncItemStatus.Upload ? UploadSlot(outcome.Memory!, outcome.Item.ThumbnailContentType, now) : null,
            outcome.Error is { } error ? new MemorySyncErrorDto(error.Code, error.Message) : null)).ToList();
        logger.LogInformation("Album sync batch for user {UserId}: {Items} item(s), {Upload} to upload, {Done} done, {Removed} removed, {Rejected} rejected.",
            userId, results.Count, results.Count(item => item.Status == SyncItemStatus.Upload), results.Count(item => item.Status == SyncItemStatus.Done),
            results.Count(item => item.Status == SyncItemStatus.Removed), results.Count(item => item.Status == SyncItemStatus.Rejected));
        return Ok(new SyncMemoriesResponse(results, await UsageAsync(couple, entitlement.Limits.MemoriesBytes, cancellationToken)));
    }

    /// <summary>
    /// Completes up to 50 uploads with the same check as POST {id}/complete. On the last batch
    /// (finished) the partner gets one notification for the whole sync.
    /// </summary>
    [HttpPost("sync/complete")]
    public async Task<ActionResult<CompleteSyncResponse>> CompleteSync(CompleteSyncRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        if (!storage.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, Unavailable);
        var batch = MemorySyncRules.ValidateComplete(request);
        if (batch.IsFailure) return UnprocessableEntity(new ApiError(batch.Error.Code, batch.Error.Message));

        var userId = currentUser.UserId.Value;
        var ids = request.MemoryIds!;
        // Someone else's memory, another couple's, or one being deleted all read as not found.
        var memories = await db.Memories
            .Where(item => ids.Contains(item.Id) && item.CoupleId == couple.Id && item.UploadedByUserId == userId && item.Status != MemoryStatus.Deleting)
            .ToDictionaryAsync(item => item.Id, cancellationToken);
        var outcomes = new List<(Guid Id, Memory? Memory, UploadOutcome? Outcome)>(ids.Count);
        foreach (var id in ids)
        {
            if (!memories.TryGetValue(id, out var memory)) outcomes.Add((id, null, null));
            else outcomes.Add((id, memory, await VerifyUploadAsync(memory, cancellationToken)));
        }
        var added = outcomes.Where(item => item.Outcome == UploadOutcome.BecameReady).Select(item => item.Memory!).ToList();

        var now = clock.UtcNow;
        var names = await NamesAsync(couple, cancellationToken);
        Notification? notice = null;
        if (request.Finished)
        {
            var state = await SyncStateAsync(userId, cancellationToken);
            // Everything this sync made ready, across all its batches. The ones made ready just now
            // are not saved yet, so they are added on top of what the database already holds.
            var synced = added.Count;
            if (state.SyncStartedAtUtc is { } started)
                synced += await db.Memories.CountAsync(item => item.CoupleId == couple.Id && item.UploadedByUserId == userId
                    && item.Status == MemoryStatus.Ready && item.ReadyAtUtc >= started, cancellationToken);
            var partnerId = couple.UserAId == userId ? couple.UserBId : couple.UserAId;
            if (synced > 0 && partnerId is { } partner)
            {
                // The notification row is the push outbox: saved together with the memories it announces.
                notice = Notification.Create(partner, couple.Id, NotificationType.MemoriesSynced, MemorySyncText.SyncedTitle,
                    MemorySyncText.SyncedBody(names.GetValueOrDefault(userId, "Your partner"), synced),
                    JsonSerializer.Serialize(new { count = synced }), now);
                db.Notifications.Add(notice);
            }
            state.FinishSync(now);
        }

        try
        {
            await db.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException exception) when (IsUniqueViolation(exception))
        {
            logger.LogInformation(exception, "Album sync completion for user {UserId} raced another sync.", userId);
            return Conflict(SyncInProgress);
        }

        foreach (var (_, memory, outcome) in outcomes)
            if (outcome == UploadOutcome.Rejected) await TryDeleteAsync(memory!, cancellationToken);
        foreach (var memory in added) await ChangedAsync(couple, memory.Id, "added", cancellationToken);
        if (notice is not null) await ExerciseEvents.NotificationAsync(hub, notice, cancellationToken);

        var items = outcomes.Select(item => item.Outcome switch
        {
            null => new SyncCompleteItemDto(item.Id, SyncCompleteStatus.NotFound),
            UploadOutcome.NotUploaded => new SyncCompleteItemDto(item.Id, SyncCompleteStatus.NotUploaded, Error: new MemorySyncErrorDto(NotUploaded.Code, NotUploaded.Message)),
            UploadOutcome.Rejected => new SyncCompleteItemDto(item.Id, SyncCompleteStatus.Rejected, Error: new MemorySyncErrorDto(UploadRejected.Code, UploadRejected.Message)),
            _ => new SyncCompleteItemDto(item.Id, SyncCompleteStatus.Ready, ToDto(item.Memory!, names))
        }).ToList();
        logger.LogInformation("Album sync completion for user {UserId}: {Items} item(s), {Added} newly ready, finished {Finished}, partner notified {Notified}.",
            userId, items.Count, added.Count, request.Finished, notice is not null);
        return Ok(new CompleteSyncResponse(items));
    }

    /// <summary>The caller's tracked sync state, added (unsaved) when they have none yet.</summary>
    private async Task<AlbumSyncState> SyncStateAsync(Guid userId, CancellationToken cancellationToken)
    {
        var state = await db.AlbumSyncStates.SingleOrDefaultAsync(item => item.UserId == userId, cancellationToken);
        if (state is not null) return state;
        state = AlbumSyncState.Create(userId, clock.UtcNow);
        db.AlbumSyncStates.Add(state);
        return state;
    }

    private static bool IsUniqueViolation(DbUpdateException exception)
        => exception.InnerException is PostgresException { SqlState: PostgresErrorCodes.UniqueViolation };
}
