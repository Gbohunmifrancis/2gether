using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class CoupleController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    ITokenService tokenService,
    IHubContext<CoupleHub> hub) : ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<CoupleDto>> Get(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue) return NotFound();
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value, cancellationToken);
        if (couple is null) return NotFound();
        var users = await db.Users.AsNoTracking().Where(user => user.CoupleId == couple.Id).Select(user => new CoupleMemberDto(user.Id, user.DisplayName, user.AvatarUrl)).ToListAsync(cancellationToken);
        return Ok(new CoupleDto(couple.Id, couple.Status, couple.LinkedAtUtc, users));
    }

    [HttpPost("invites")]
    public async Task<ActionResult<PartnerInviteDto>> CreateInvite(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var actor = await db.Users.SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (actor is null) return NotFound();
        var couple = actor.CoupleId.HasValue
            ? await db.Couples.SingleOrDefaultAsync(item => item.Id == actor.CoupleId.Value, cancellationToken)
            : null;

        // Repair legacy accounts whose couple row was lost or whose token has no couple claim.
        if (couple is null || couple.Status == CoupleStatus.Dissolved)
        {
            couple = Couple.Create(actor.Id, clock.UtcNow);
            db.Couples.Add(couple);
            actor.SetCouple(couple.Id, clock.UtcNow);
        }
        if (couple.Status != CoupleStatus.PendingInvite)
            return Conflict(new ApiError("couple.already_linked", "This couple is already linked."));

        var existing = await db.PartnerInvites.Where(invite => invite.CoupleId == couple.Id && invite.Status == PartnerInviteStatus.Pending).ToListAsync(cancellationToken);
        foreach (var invite in existing) invite.Revoke(clock.UtcNow);

        var code = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
        var partnerInvite = PartnerInvite.Create(couple.Id, actor.Id, Hash(code), Hash(token), clock.UtcNow.AddDays(7), clock.UtcNow);
        db.PartnerInvites.Add(partnerInvite);
        await db.SaveChangesAsync(cancellationToken);
        // Refresh the access token when repairing a legacy account so the new couple
        // id is present for subsequent API and SignalR calls.
        var accessToken = tokenService.CreateAccessToken(actor.Id, couple.Id, actor.Email, actor.DisplayName, clock.UtcNow);
        return Ok(new PartnerInviteDto(partnerInvite.Id, code, token, partnerInvite.ExpiresAtUtc, accessToken));
    }

    [HttpPost("invites/accept")]
    public async Task<ActionResult<AuthResponse>> AcceptInvite(AcceptPartnerInviteRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var codeHash = string.IsNullOrWhiteSpace(request.Code) ? null : Hash(request.Code.Trim());
        var tokenHash = string.IsNullOrWhiteSpace(request.Token) ? null : Hash(request.Token.Trim());
        var invite = await db.PartnerInvites.SingleOrDefaultAsync(item => (codeHash != null && item.CodeHash == codeHash) || (tokenHash != null && item.LinkTokenHash == tokenHash), cancellationToken);
        if (invite is null || !invite.IsUsable(clock.UtcNow)) return NotFound(new ApiError("invite.invalid", "This invitation is invalid or expired."));
        if (invite.InviterUserId == currentUser.UserId.Value) return Conflict(new ApiError("invite.self", "You cannot accept your own invitation."));

        var couple = await db.Couples.SingleAsync(item => item.Id == invite.CoupleId, cancellationToken);
        var user = await db.Users.SingleAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (user.CoupleId.HasValue && user.CoupleId != couple.Id)
        {
            var previous = await db.Couples.SingleOrDefaultAsync(item => item.Id == user.CoupleId.Value, cancellationToken);
            if (previous?.Status != CoupleStatus.PendingInvite) return Conflict(new ApiError("couple.already_linked", "You are already linked to a partner."));
            previous.Dissolve(clock.UtcNow);
        }

        couple.LinkPartner(user.Id, clock.UtcNow);
        user.SetCouple(couple.Id, clock.UtcNow);
        invite.Accept(clock.UtcNow);
        var connected = Notification.Create(invite.InviterUserId, couple.Id, NotificationType.CoupleConnected, "You are connected", $"{user.DisplayName} joined your private space.", null, clock.UtcNow);
        db.Notifications.Add(connected);
        await db.SaveChangesAsync(cancellationToken);
        await hub.Clients.Group(CoupleHub.UserGroupName(invite.InviterUserId)).SendAsync("CoupleConnected", new { userId = user.Id, displayName = user.DisplayName }, cancellationToken);
        await hub.Clients.Group(CoupleHub.UserGroupName(invite.InviterUserId)).SendAsync("NotificationCreated", NotificationController.ToDto(connected), cancellationToken);
        var accessToken = tokenService.CreateAccessToken(user.Id, couple.Id, user.Email, user.DisplayName, clock.UtcNow);
        return Ok(new AuthResponse(accessToken, new AuthUserDto(user.Id, user.Email, user.DisplayName, couple.Id, user.TimeZoneId, user.AvatarUrl, user.MapColor, user.CycleOwner, user.Gender)));
    }

    [HttpPost("breakup")]
    public async Task<IActionResult> BreakUp(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return NotFound();
        var couple = await db.Couples.SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        if (couple is null) return NotFound();
        var actor = await db.Users.SingleAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        var partnerId = couple.UserAId == actor.Id ? couple.UserBId : couple.UserAId;
        couple.Dissolve(clock.UtcNow);
        actor.SetCouple(null, clock.UtcNow);
        if (partnerId is { } linkedPartnerId)
        {
            var partner = await db.Users.SingleAsync(item => item.Id == linkedPartnerId, cancellationToken);
            partner.SetCouple(null, clock.UtcNow);
            var notification = Notification.Create(linkedPartnerId, couple.Id, NotificationType.Breakup, "Your couple space was ended", $"{actor.DisplayName} ended the connection.", null, clock.UtcNow);
            db.Notifications.Add(notification);
            await db.SaveChangesAsync(cancellationToken);
            await hub.Clients.Group(CoupleHub.UserGroupName(linkedPartnerId)).SendAsync("CoupleEnded", new CoupleEndedDto(actor.Id, actor.DisplayName, clock.UtcNow), cancellationToken);
            await hub.Clients.Group(CoupleHub.UserGroupName(linkedPartnerId)).SendAsync("NotificationCreated", NotificationController.ToDto(notification), cancellationToken);
        }
        else await db.SaveChangesAsync(cancellationToken);
        return NoContent();
    }

    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
}

public sealed record AcceptPartnerInviteRequest(string? Code, string? Token);
public sealed record PartnerInviteDto(Guid Id, string Code, string Token, DateTime ExpiresAtUtc, string? AccessToken = null);
public sealed record CoupleDto(Guid Id, CoupleStatus Status, DateTime? LinkedAtUtc, IReadOnlyList<CoupleMemberDto> Members);
public sealed record CoupleMemberDto(Guid UserId, string DisplayName, string? AvatarUrl);
