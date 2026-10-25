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
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Couple;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;
using Twogether.Application.Features.Notifications;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class CoupleController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    ITokenService tokenService,
    IHubContext<CoupleHub> hub,
    AttemptLimiter limiter,
    InviteSettings inviteSettings,
    ILogger<CoupleController> logger) : ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<CoupleDto>> Get(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue) return NotFound();
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value, cancellationToken);
        if (couple is null) return NotFound();
        var users = await db.Users.AsNoTracking().Where(user => user.CoupleId == couple.Id).Select(user => new CoupleMemberDto(user.Id, user.DisplayName, user.AvatarUrl, user.Id == couple.UserAId ? CoupleSide.Left : CoupleSide.Right))
            .ToListAsync(cancellationToken);
        users = users.OrderBy(member => member.Side).ToList();
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
            return Conflict(new ApiError("couple.already_linked", Loc.T("This couple is already linked.")));

        var existing = await db.PartnerInvites.Where(invite => invite.CoupleId == couple.Id && invite.Status == PartnerInviteStatus.Pending).ToListAsync(cancellationToken);
        foreach (var invite in existing) invite.Revoke(clock.UtcNow);

        var code = InviteCodes.Generate(inviteSettings.CodeDigits);
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
        var partnerInvite = PartnerInvite.Create(couple.Id, actor.Id, Hash(code), Hash(token), clock.UtcNow.Add(inviteSettings.Lifetime), clock.UtcNow);
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
        var userKey = currentUser.UserId.Value.ToString();
        // Any request carrying a code is a code attempt, even with a token beside it; otherwise a
        // junk token would let guesses through uncounted.
        var byCode = !string.IsNullOrWhiteSpace(request.Code);
        // Codes are short enough to guess, so a wrong one costs from three budgets. Links carry
        // 192 random bits and are never limited, so they keep working while codes are paused.
        if (byCode && Blocked(limiter,
                (AttemptRules.InviteCodePerUser, userKey),
                (AttemptRules.InviteCodePerIp, ClientIp),
                (AttemptRules.InviteCodeGlobal, "all")) is { } wait)
            return TooManyAttempts(wait, "Too many wrong codes. Wait a bit, or open the invite link your partner sent instead.");

        var code = InviteCodes.Normalize(request.Code);
        var codeHash = code is null ? null : Hash(code);
        var tokenHash = string.IsNullOrWhiteSpace(request.Token) ? null : Hash(request.Token.Trim());
        var invite = codeHash is null && tokenHash is null
            ? null
            : await db.PartnerInvites.SingleOrDefaultAsync(item => (codeHash != null && item.CodeHash == codeHash) || (tokenHash != null && item.LinkTokenHash == tokenHash), cancellationToken);
        if (invite is null || !invite.IsUsable(clock.UtcNow))
        {
            if (byCode)
            {
                limiter.RecordFailure(AttemptRules.InviteCodePerUser, userKey);
                limiter.RecordFailure(AttemptRules.InviteCodePerIp, ClientIp);
                limiter.RecordFailure(AttemptRules.InviteCodeGlobal, "all");
                if (limiter.RetryAfter(AttemptRules.InviteCodeGlobal, "all") is not null)
                    logger.LogError("Wrong invite codes reached the global budget of {Max} per hour; codes are paused. Possible guessing attack.", AttemptRules.InviteCodeGlobal.MaxFailures);
            }
            return NotFound(new ApiError("invite.invalid", Loc.T("This invitation is invalid or expired.")));
        }
        if (invite.InviterUserId == currentUser.UserId.Value) return Conflict(new ApiError("invite.self", Loc.T("You cannot accept your own invitation.")));

        var couple = await db.Couples.SingleAsync(item => item.Id == invite.CoupleId, cancellationToken);
        if (!couple.CanAcceptPartner)
        {
            // A leftover invite from a couple that ended or is already complete must never bring
            // it back with a stranger in it. Retire it so it fails the same way from now on.
            invite.Revoke(clock.UtcNow);
            await db.SaveChangesAsync(cancellationToken);
            return NotFound(new ApiError("invite.invalid", Loc.T("This invitation is invalid or expired.")));
        }
        var user = await db.Users.SingleAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (user.CoupleId.HasValue && user.CoupleId != couple.Id)
        {
            var previous = await db.Couples.SingleOrDefaultAsync(item => item.Id == user.CoupleId.Value, cancellationToken);
            if (previous?.Status != CoupleStatus.PendingInvite) return Conflict(new ApiError("couple.already_linked", Loc.T("You are already linked to a partner.")));
            previous.Dissolve(clock.UtcNow);
            await RevokePendingInvitesAsync(previous.Id, cancellationToken);
        }

        couple.LinkPartner(user.Id, clock.UtcNow);
        user.SetCouple(couple.Id, clock.UtcNow);
        invite.Accept(clock.UtcNow);
        Notification connected;
        using (Loc.Use(await db.LanguageOfAsync(invite.InviterUserId, cancellationToken)))
            connected = Notification.Create(invite.InviterUserId, couple.Id, NotificationType.CoupleConnected, Loc.T("You are connected"),
                Loc.T("{name} joined your private space.", ("name", user.DisplayName)), null, clock.UtcNow);
        db.Notifications.Add(connected);
        await db.SaveChangesAsync(cancellationToken);
        await hub.Clients.Group(CoupleHub.UserGroupName(invite.InviterUserId)).SendAsync("CoupleConnected", new { userId = user.Id, displayName = user.DisplayName }, cancellationToken);
        await hub.Clients.Group(CoupleHub.UserGroupName(invite.InviterUserId)).SendAsync("NotificationCreated", NotificationController.ToDto(connected), cancellationToken);
        var accessToken = tokenService.CreateAccessToken(user.Id, couple.Id, user.Email, user.DisplayName, clock.UtcNow);
        return Ok(new AuthResponse(accessToken, new AuthUserDto(user.Id, user.Email, user.DisplayName, couple.Id, user.TimeZoneId, user.AvatarUrl, user.MapColor, user.CycleOwner, user.Gender,
            user.CountryCode, Twogether.Core.Users.ContentRegions.For(user.CountryCode))));
    }

    /// <summary>
    /// The alarm worker can open or move a dare in the same moment, which fails the save. Nothing
    /// was written or announced then, so this starts once more from the fresh state; a second clash
    /// answers 409 and the person simply tries again.
    /// </summary>
    [HttpPost("breakup")]
    public async Task<IActionResult> BreakUp(CancellationToken cancellationToken)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                return await BreakUpOnceAsync(cancellationToken);
            }
            catch (DbUpdateConcurrencyException exception)
            {
                ((DbContext)db).ChangeTracker.Clear();
                logger.LogInformation(exception, "Breakup for user {UserId} raced another write (attempt {Attempt}).", currentUser.UserId, attempt + 1);
                if (attempt > 0) return Conflict(new ApiError("couple.breakup_conflict", Loc.T("Something changed in your couple space just now. Try again.")));
            }
        }
    }

    private async Task<IActionResult> BreakUpOnceAsync(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return NotFound();
        var couple = await db.Couples.SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        if (couple is null) return NotFound();
        var actor = await db.Users.SingleAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        var partnerId = couple.UserAId == actor.Id ? couple.UserBId : couple.UserAId;
        var cancelledAlarms = await StopExerciseGoalsAsync(couple.Id, cancellationToken);
        var endedGames = await db.GameSessions
            .Where(session => session.CoupleId == couple.Id
                && (session.Status == GameSessionStatus.WaitingForPartner || session.Status == GameSessionStatus.InProgress || session.Status == GameSessionStatus.Paused))
            .ToListAsync(cancellationToken);
        foreach (var session in endedGames) session.EndBy(actor.Id, clock.UtcNow);
        couple.Dissolve(clock.UtcNow);
        await RevokePendingInvitesAsync(couple.Id, cancellationToken);
        actor.SetCouple(null, clock.UtcNow);
        if (partnerId is { } linkedPartnerId)
        {
            var partner = await db.Users.SingleAsync(item => item.Id == linkedPartnerId, cancellationToken);
            partner.SetCouple(null, clock.UtcNow);
            using var _ = Loc.Use(partner.Language);
            var notification = Notification.Create(linkedPartnerId, couple.Id, NotificationType.Breakup, Loc.T("Your couple space was ended"),
                Loc.T("{name} ended the connection.", ("name", actor.DisplayName)), null, clock.UtcNow);
            db.Notifications.Add(notification);
            await db.SaveChangesAsync(cancellationToken);
            await hub.Clients.Group(CoupleHub.UserGroupName(linkedPartnerId)).SendAsync("CoupleEnded", new CoupleEndedDto(actor.Id, actor.DisplayName, clock.UtcNow), cancellationToken);
            await hub.Clients.Group(CoupleHub.UserGroupName(linkedPartnerId)).SendAsync("NotificationCreated", NotificationController.ToDto(notification), cancellationToken);
        }
        else await db.SaveChangesAsync(cancellationToken);
        foreach (var session in endedGames)
            await hub.Clients.Group(CoupleHub.GroupName(couple.Id)).SendAsync("GameEnded", new GameEndedDto(session.Id, actor.Id, actor.DisplayName, clock.UtcNow), cancellationToken);
        // The actor's other devices: they reconnect and lose the old couple's groups.
        await hub.Clients.Group(CoupleHub.UserGroupName(actor.Id)).SendAsync("CoupleEnded", new CoupleEndedDto(actor.Id, actor.DisplayName, clock.UtcNow), cancellationToken);
        // Each dare under its owner's name: the partner's are theirs, not the actor's.
        var partnerName = partnerId is { } owner ? db.Users.Local.SingleOrDefault(user => user.Id == owner)?.DisplayName : null;
        foreach (var occurrence in cancelledAlarms)
            await ExerciseEvents.OccurrenceAsync(hub, ExerciseEvents.Cancelled,
                ExerciseMapper.ToDto(occurrence, occurrence.UserId == actor.Id ? actor.DisplayName : partnerName ?? actor.DisplayName), cancellationToken);
        return NoContent();
    }

    /// <summary>
    /// A dissolved couple must not keep ringing anyone: deactivate every goal and cancel
    /// whatever is ringing now. The worker also ignores inactive couples, so a stray goal
    /// could never fire again even if this step were skipped.
    /// </summary>
    private async Task<List<ExerciseOccurrence>> StopExerciseGoalsAsync(Guid coupleId, CancellationToken cancellationToken)
    {
        var goals = await db.ExerciseGoals.Where(goal => goal.CoupleId == coupleId && goal.IsActive).ToListAsync(cancellationToken);
        foreach (var goal in goals) goal.Deactivate(clock.UtcNow);
        var ringing = await db.ExerciseOccurrences
            .Where(item => item.CoupleId == coupleId && (item.Status == ExerciseOccurrenceStatus.Alarming || item.Status == ExerciseOccurrenceStatus.InProgress))
            .ToListAsync(cancellationToken);
        foreach (var occurrence in ringing) occurrence.Cancel(clock.UtcNow);
        return ringing;
    }

    private async Task RevokePendingInvitesAsync(Guid coupleId, CancellationToken cancellationToken)
    {
        var pending = await db.PartnerInvites.Where(invite => invite.CoupleId == coupleId && invite.Status == PartnerInviteStatus.Pending).ToListAsync(cancellationToken);
        foreach (var invite in pending) invite.Revoke(clock.UtcNow);
    }

    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
}

public sealed record AcceptPartnerInviteRequest(string? Code, string? Token);
public sealed record PartnerInviteDto(Guid Id, string Code, string Token, DateTime ExpiresAtUtc, string? AccessToken = null);
public sealed record CoupleDto(Guid Id, CoupleStatus Status, DateTime? LinkedAtUtc, IReadOnlyList<CoupleMemberDto> Members);
/// <summary>
/// Side fixes each partner's half of the heart on every phone: the person who started the couple
/// is the left (indigo) half, the partner who joined is the right (periwinkle) half.
/// </summary>
public sealed record CoupleMemberDto(Guid UserId, string DisplayName, string? AvatarUrl, CoupleSide Side);

public enum CoupleSide { Left, Right }
