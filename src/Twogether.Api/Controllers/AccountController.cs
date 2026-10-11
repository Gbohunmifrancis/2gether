using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Exercise;

namespace Twogether.Api.Controllers;

/// <summary>Self-service export and deletion, required by Google Play and by GDPR.</summary>
[Authorize]
public sealed class AccountController(
    IApplicationDbContext db,
    IPasswordHasher passwordHasher,
    ICurrentUser currentUser,
    IAccountEraser eraser,
    AccountExportService exporter,
    IHubContext<CoupleHub> hub,
    AttemptLimiter limiter) : ApiControllerBase
{
    [HttpGet("export")]
    public async Task<IActionResult> Export(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var export = await exporter.ExportAsync(currentUser.UserId.Value, cancellationToken);
        if (export is null) return NotFound();
        Response.Headers.ContentDisposition = $"attachment; filename=\"heartpact-export-{export.ExportedAtUtc:yyyyMMdd}.json\"";
        return Ok(export);
    }

    [HttpPost("delete")]
    public async Task<IActionResult> Delete(DeleteAccountRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var user = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == userId, cancellationToken);
        if (user is null) return NotFound();
        if (Blocked(limiter, (AttemptRules.AccountDeletePerUser, userId.ToString())) is { } wait)
            return TooManyAttempts(wait, "Too many wrong passwords. Wait a few minutes and try again.");
        var confirmed = AccountDeletionRules.Confirm(user, request, passwordHasher);
        if (confirmed.IsFailure)
        {
            // A stolen session must not become a free password oracle.
            limiter.RecordFailure(AttemptRules.AccountDeletePerUser, userId.ToString());
            return HandleNoContent(confirmed);
        }

        var subscriptions = await db.StoreSubscriptions.AsNoTracking().Where(item => item.UserId == userId).ToListAsync(cancellationToken);
        if (!request.AcknowledgeActiveSubscription && subscriptions.Any(AccountDeletionRules.WillRenew))
            return Conflict(new ApiError("account.subscription_renews",
                "Your Heartpact Plus subscription keeps renewing after you delete your account. Cancel it in the Play Store, or confirm that you understand."));

        var erased = await eraser.EraseAsync(userId, cancellationToken);
        if (erased is null) return NotFound();

        await AnnounceErasureAsync(hub, erased, cancellationToken);
        return NoContent();
    }

    /// <summary>After an erasure (by the person or by an admin): other devices sign out, the partner is told, alarms stop.</summary>
    public static async Task AnnounceErasureAsync(IHubContext<CoupleHub> hub, AccountErasure erased, CancellationToken cancellationToken)
    {
        // Other devices signed in to this account: tell them to sign out. Their tokens stop working anyway.
        await hub.Clients.Group(CoupleHub.UserGroupName(erased.UserId)).SendAsync("AccountDeleted", cancellationToken);
        if (erased.FormerPartnerId is { } partnerId)
        {
            var partner = hub.Clients.Group(CoupleHub.UserGroupName(partnerId));
            await partner.SendAsync("CoupleEnded", new CoupleEndedDto(erased.UserId, erased.DisplayName, erased.PartnerNotice!.CreatedAtUtc), cancellationToken);
            await partner.SendAsync("NotificationCreated", NotificationController.ToDto(erased.PartnerNotice), cancellationToken);
        }
        foreach (var occurrence in erased.CancelledAlarms)
            await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Cancelled, ExerciseMapper.ToDto(occurrence, erased.DisplayName), cancellationToken);
    }
}
