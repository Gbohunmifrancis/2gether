using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Admin;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Presence;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Admin;
using Twogether.Application.Features.Auth;
using Twogether.Application.Features.Billing;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Api.Controllers.Admin;

/// <summary>Charts and totals. Aggregates only.</summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin/analytics")]
public sealed class AdminAnalyticsController(AdminAnalytics analytics) : ApiControllerBase
{
    [HttpGet("overview")]
    public async Task<ActionResult<OverviewDto>> Overview([FromQuery] int days = 30, CancellationToken ct = default) => Ok(await analytics.OverviewAsync(days, ct));

    [HttpGet("engagement")]
    public async Task<ActionResult<EngagementDto>> Engagement([FromQuery] int days = 30, CancellationToken ct = default) => Ok(await analytics.EngagementAsync(days, ct));

    [HttpGet("revenue")]
    public async Task<ActionResult<RevenueDto>> Revenue([FromQuery] int days = 90, CancellationToken ct = default) => Ok(await analytics.RevenueAsync(days, ct));
}

/// <summary>
/// Accounts: search, look up, and act on them. What is shown is account metadata and counts; the
/// couple's own content (answers, photos, cycle, pregnancy, location, food) never leaves their phones
/// through this API.
/// </summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin/users")]
public sealed class AdminUsersController(
    IApplicationDbContext db,
    IDateTimeProvider clock,
    AdminAudit audit,
    HubConnectionRegistry connections,
    IAccountEraser eraser,
    IHubContext<CoupleHub> hub,
    IEmailSender email,
    IEntitlementService entitlements) : ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<AdminPage<AdminUserRow>>> List([FromQuery] string? query, [FromQuery] string? status, [FromQuery] int page = 1, [FromQuery] int pageSize = 25, CancellationToken ct = default)
    {
        pageSize = Math.Clamp(pageSize, 5, 100);
        page = Math.Max(1, page);
        var users = db.Users.AsNoTracking();
        if (!string.IsNullOrWhiteSpace(query))
        {
            var q = query.Trim().ToLowerInvariant();
            users = Guid.TryParse(q, out var id)
                ? users.Where(user => user.Id == id || user.CoupleId == id)
                : users.Where(user => user.Email.Contains(q) || user.DisplayName.ToLower().Contains(q));
        }
        users = status switch
        {
            "disabled" => users.Where(user => user.DisabledAtUtc != null),
            "active" => users.Where(user => user.DisabledAtUtc == null),
            "unlinked" => users.Where(user => user.CoupleId == null || !db.Couples.Any(couple => couple.Id == user.CoupleId && couple.Status == CoupleStatus.Active)),
            _ => users,
        };
        var total = await users.CountAsync(ct);
        var now = clock.UtcNow;
        var rows = await users.OrderByDescending(user => user.CreatedAtUtc).Skip((page - 1) * pageSize).Take(pageSize)
            .Select(user => new AdminUserRow(
                user.Id, user.Email, user.DisplayName, user.CreatedAtUtc, user.LastActiveAtUtc, user.DisabledAtUtc,
                user.GoogleId != null, user.PasswordHash != null,
                db.Couples.Where(couple => couple.Id == user.CoupleId).Select(couple => (CoupleStatus?)couple.Status).FirstOrDefault(),
                db.StoreSubscriptions.Any(item => item.UserId == user.Id && item.ExpiresAtUtc > now
                    && (item.Status == StoreSubscriptionStatus.Active || item.Status == StoreSubscriptionStatus.Canceled || item.Status == StoreSubscriptionStatus.InGracePeriod))))
            .ToListAsync(ct);
        return Ok(new AdminPage<AdminUserRow>(rows, total, page, pageSize));
    }

    [HttpGet("{userId:guid}")]
    public async Task<ActionResult<AdminUserDetail>> Get(Guid userId, CancellationToken ct)
    {
        var user = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == userId, ct);
        if (user is null) return NotFound();
        var couple = user.CoupleId is { } coupleId ? await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == coupleId, ct) : null;
        var partnerId = couple is null ? null : couple.UserAId == userId ? couple.UserBId : couple.UserAId;
        var partner = partnerId is { } pid ? await db.Users.AsNoTracking().Where(item => item.Id == pid).Select(item => new AdminPersonRef(item.Id, item.Email, item.DisplayName)).SingleOrDefaultAsync(ct) : null;
        var devices = await db.DeviceRegistrations.AsNoTracking().Where(item => item.UserId == userId).OrderByDescending(item => item.LastSeenAtUtc)
            .Select(item => new AdminDevice(item.Platform.ToString(), item.AppVersion, item.LastSeenAtUtc, item.DisabledAtUtc == null)).ToListAsync(ct);
        var subscriptions = await db.StoreSubscriptions.AsNoTracking().Where(item => item.UserId == userId).OrderByDescending(item => item.ExpiresAtUtc)
            .Select(item => AdminSubscriptionsController.Row(item, user.Email)).ToListAsync(ct);
        var grants = couple is null ? [] : await db.PlusGrants.AsNoTracking().Where(item => item.CoupleId == couple.Id).OrderByDescending(item => item.CreatedAtUtc)
            .Select(item => new AdminGrant(item.Id, item.CoupleId, item.ExpiresAtUtc, item.Reason, item.RevokedAtUtc, item.CreatedAtUtc)).ToListAsync(ct);
        var counts = new AdminActivityCounts(
            await db.ExerciseOccurrences.CountAsync(item => item.UserId == userId && item.Status == ExerciseOccurrenceStatus.Completed, ct),
            await db.ExerciseOccurrences.CountAsync(item => item.UserId == userId && item.Status == ExerciseOccurrenceStatus.Missed, ct),
            await db.FoodEntries.CountAsync(item => item.UserId == userId, ct),
            await db.FoodScans.CountAsync(item => item.UserId == userId, ct),
            await db.Memories.CountAsync(item => item.UploadedByUserId == userId && item.Status == MemoryStatus.Ready, ct),
            await db.Memories.Where(item => item.UploadedByUserId == userId && item.Status == MemoryStatus.Ready).SumAsync(item => (long?)item.SizeBytes, ct) ?? 0,
            await db.DailyAnswers.CountAsync(item => item.UserId == userId, ct),
            await db.UserActivityDays.CountAsync(item => item.UserId == userId, ct));
        var entitlement = await entitlements.ForUserAsync(userId, ct);
        var history = await db.AdminAuditEvents.AsNoTracking().Where(item => item.TargetType == "user" && item.TargetId == userId.ToString())
            .OrderByDescending(item => item.CreatedAtUtc).Take(20).Select(item => AdminAuditController.Row(item)).ToListAsync(ct);
        audit.Record(User, "user.viewed", "user", userId.ToString());
        await db.SaveChangesAsync(ct);
        return Ok(new AdminUserDetail(
            user.Id, user.Email, user.DisplayName, user.CreatedAtUtc, user.LastActiveAtUtc, user.DisabledAtUtc, user.DisabledReason,
            user.EmailVerified, user.GoogleId != null, user.PasswordHash != null, user.TimeZoneId,
            couple is null ? null : new AdminCouple(couple.Id, couple.Status, couple.LinkedAtUtc, couple.DissolvedAtUtc, partner),
            entitlement.Plan, entitlement.Complimentary, devices, subscriptions, grants, counts, history));
    }

    [HttpPost("{userId:guid}/disable")]
    public async Task<IActionResult> Disable(Guid userId, AdminReasonRequest request, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(request.Reason)) return UnprocessableEntity(new ApiError("admin.reason_required", "Say why, for the record."));
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == userId, ct);
        if (user is null) return NotFound();
        if (IsSelf(userId)) return Conflict(new ApiError("admin.self", "You can't do that to your own account here."));
        user.Disable(request.Reason.Trim(), clock.UtcNow);
        audit.Record(User, "user.disabled", "user", userId.ToString(), request.Reason.Trim());
        await db.SaveChangesAsync(ct);
        connections.AbortAll(userId);
        return NoContent();
    }

    [HttpPost("{userId:guid}/enable")]
    public async Task<IActionResult> Enable(Guid userId, CancellationToken ct)
    {
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == userId, ct);
        if (user is null) return NotFound();
        user.Enable(clock.UtcNow);
        audit.Record(User, "user.enabled", "user", userId.ToString());
        await db.SaveChangesAsync(ct);
        return NoContent();
    }

    /// <summary>Ends every session on every device; the person signs in again.</summary>
    [HttpPost("{userId:guid}/sign-out")]
    public async Task<IActionResult> SignOut(Guid userId, CancellationToken ct)
    {
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == userId, ct);
        if (user is null) return NotFound();
        user.EndSessions(clock.UtcNow);
        audit.Record(User, "user.signed_out", "user", userId.ToString());
        await db.SaveChangesAsync(ct);
        connections.AbortAll(userId);
        return NoContent();
    }

    /// <summary>Emails the person a reset code, exactly as "Forgot password?" would.</summary>
    [HttpPost("{userId:guid}/password-reset")]
    public async Task<IActionResult> PasswordReset(Guid userId, CancellationToken ct)
    {
        if (!email.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError("auth.email_unavailable", "Email isn't set up on this server."));
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == userId, ct);
        if (user is null) return NotFound();
        var now = clock.UtcNow;
        foreach (var old in await db.PasswordResetCodes.Where(item => item.UserId == user.Id && item.UsedAtUtc == null).ToListAsync(ct)) old.Retire(now);
        var code = PasswordResetRules.NewCode();
        db.PasswordResetCodes.Add(PasswordResetCode.Issue(user.Id, PasswordResetRules.Hash(user.Id, code), now));
        audit.Record(User, "user.reset_sent", "user", userId.ToString());
        await db.SaveChangesAsync(ct);
        await email.SendAsync(PasswordResetRules.Email(user.Email, code), ct);
        return NoContent();
    }

    /// <summary>Deletes the account and everything of theirs, as if they had done it. The admin types the email to confirm.</summary>
    [HttpPost("{userId:guid}/delete")]
    public async Task<IActionResult> Delete(Guid userId, AdminDeleteRequest request, CancellationToken ct)
    {
        var user = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == userId, ct);
        if (user is null) return NotFound();
        if (IsSelf(userId)) return Conflict(new ApiError("admin.self", "You can't do that to your own account here."));
        if (!string.Equals(request.ConfirmEmail?.Trim(), user.Email, StringComparison.OrdinalIgnoreCase))
            return UnprocessableEntity(new ApiError("admin.confirm_email", "Type the account's email exactly to confirm."));
        if (string.IsNullOrWhiteSpace(request.Reason)) return UnprocessableEntity(new ApiError("admin.reason_required", "Say why, for the record."));
        audit.Record(User, "user.deleted", "user", userId.ToString(), $"{user.Email}: {request.Reason.Trim()}");
        await db.SaveChangesAsync(ct);
        var erased = await eraser.EraseAsync(userId, ct);
        if (erased is null) return NotFound();
        connections.AbortAll(userId);
        await AccountController.AnnounceErasureAsync(hub, erased, ct);
        return NoContent();
    }

    private bool IsSelf(Guid userId) => User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)?.Value == userId.ToString();
}

/// <summary>Google Play subscriptions and complimentary Plus.</summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin")]
public sealed class AdminSubscriptionsController(
    IApplicationDbContext db,
    IDateTimeProvider clock,
    AdminAudit audit,
    IGooglePlayBilling billing,
    GooglePlaySubscriptionSync sync) : ApiControllerBase
{
    internal static AdminSubscriptionRow Row(StoreSubscription item, string? buyerEmail) => new(
        item.Id, item.Store, item.ProductId, item.UserId, buyerEmail, item.Status, item.ExpiresAtUtc, item.AutoRenewing, item.Acknowledged,
        item.CreatedAtUtc, item.LastVerifiedAtUtc, item.PurchaseToken.Length > 12 ? "…" + item.PurchaseToken[^12..] : item.PurchaseToken);

    [HttpGet("subscriptions")]
    public async Task<ActionResult<AdminPage<AdminSubscriptionRow>>> List([FromQuery] string? status, [FromQuery] string? query, [FromQuery] int page = 1, [FromQuery] int pageSize = 25, CancellationToken ct = default)
    {
        pageSize = Math.Clamp(pageSize, 5, 100);
        page = Math.Max(1, page);
        var rows = db.StoreSubscriptions.AsNoTracking().Join(db.Users.AsNoTracking(), item => item.UserId, user => user.Id, (item, user) => new { item, user.Email });
        if (Enum.TryParse<StoreSubscriptionStatus>(status, ignoreCase: true, out var wanted)) rows = rows.Where(row => row.item.Status == wanted);
        if (!string.IsNullOrWhiteSpace(query)) { var q = query.Trim().ToLowerInvariant(); rows = rows.Where(row => row.Email.Contains(q) || row.item.ProductId.Contains(q)); }
        var total = await rows.CountAsync(ct);
        var items = await rows.OrderByDescending(row => row.item.UpdatedAtUtc).Skip((page - 1) * pageSize).Take(pageSize).ToListAsync(ct);
        return Ok(new AdminPage<AdminSubscriptionRow>(items.Select(row => Row(row.item, row.Email)).ToList(), total, page, pageSize));
    }

    /// <summary>Re-read from Google Play (after a change in the Play Console, or to check a complaint).</summary>
    [HttpPost("subscriptions/{id:guid}/sync")]
    public Task<IActionResult> Sync(Guid id, CancellationToken ct) => Manage(id, "subscription.synced", null, ct);

    /// <summary>Stops renewal. Access lasts until the paid period ends.</summary>
    [HttpPost("subscriptions/{id:guid}/cancel")]
    public Task<IActionResult> Cancel(Guid id, AdminReasonRequest request, CancellationToken ct) => Manage(id, "subscription.canceled", request.Reason, ct, billing.CancelAsync);

    /// <summary>Refunds the latest payment and keeps the subscription.</summary>
    [HttpPost("subscriptions/{id:guid}/refund")]
    public Task<IActionResult> Refund(Guid id, AdminReasonRequest request, CancellationToken ct) => Manage(id, "subscription.refunded", request.Reason, ct, billing.RefundAsync);

    /// <summary>Refunds and ends access now.</summary>
    [HttpPost("subscriptions/{id:guid}/revoke")]
    public Task<IActionResult> Revoke(Guid id, AdminReasonRequest request, CancellationToken ct) => Manage(id, "subscription.revoked", request.Reason, ct, billing.RevokeAsync);

    private async Task<IActionResult> Manage(Guid id, string action, string? reason, CancellationToken ct, Func<string, string, CancellationToken, Task<Result>>? call = null)
    {
        var subscription = await db.StoreSubscriptions.AsNoTracking().SingleOrDefaultAsync(item => item.Id == id, ct);
        if (subscription is null) return NotFound();
        if (call is not null && string.IsNullOrWhiteSpace(reason)) return UnprocessableEntity(new ApiError("admin.reason_required", "Say why, for the record."));
        if (call is not null)
        {
            var result = await call(subscription.ProductId, subscription.PurchaseToken, ct);
            audit.Record(User, action, "subscription", id.ToString(), reason?.Trim(), result.IsSuccess);
            await db.SaveChangesAsync(ct);
            if (result.IsFailure) return FromError(result.Error);
        }
        var synced = await sync.SyncAsync(subscription.PurchaseToken, null, ct);
        if (call is null)
        {
            audit.Record(User, action, "subscription", id.ToString(), succeeded: synced.IsSuccess);
            await db.SaveChangesAsync(ct);
        }
        if (synced.IsFailure) return FromError(synced.Error);
        var fresh = await db.StoreSubscriptions.AsNoTracking().SingleAsync(item => item.Id == id, ct);
        var buyer = await db.Users.AsNoTracking().Where(user => user.Id == fresh.UserId).Select(user => user.Email).SingleOrDefaultAsync(ct);
        return Ok(Row(fresh, buyer));
    }

    [HttpGet("grants")]
    public async Task<ActionResult<IReadOnlyList<AdminGrant>>> Grants([FromQuery] bool activeOnly = true, CancellationToken ct = default)
    {
        var now = clock.UtcNow;
        var grants = db.PlusGrants.AsNoTracking();
        if (activeOnly) grants = grants.Where(item => item.RevokedAtUtc == null && item.ExpiresAtUtc > now);
        return Ok(await grants.OrderByDescending(item => item.CreatedAtUtc).Take(200)
            .Select(item => new AdminGrant(item.Id, item.CoupleId, item.ExpiresAtUtc, item.Reason, item.RevokedAtUtc, item.CreatedAtUtc)).ToListAsync(ct));
    }

    /// <summary>Complimentary Plus for a couple, for 1 to 366 days.</summary>
    [HttpPost("couples/{coupleId:guid}/grants")]
    public async Task<ActionResult<AdminGrant>> Grant(Guid coupleId, AdminGrantRequest request, CancellationToken ct)
    {
        if (request.Days is < 1 or > 366) return UnprocessableEntity(new ApiError("admin.invalid_days", "Grant between 1 and 366 days."));
        if (string.IsNullOrWhiteSpace(request.Reason)) return UnprocessableEntity(new ApiError("admin.reason_required", "Say why, for the record."));
        if (!await db.Couples.AnyAsync(item => item.Id == coupleId, ct)) return NotFound();
        var now = clock.UtcNow;
        var adminId = Guid.Parse(User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)!.Value);
        var grant = PlusGrant.Create(coupleId, now.AddDays(request.Days), request.Reason.Trim(), adminId, now);
        db.PlusGrants.Add(grant);
        audit.Record(User, "plus.granted", "couple", coupleId.ToString(), $"{request.Days} days: {request.Reason.Trim()}");
        await db.SaveChangesAsync(ct);
        return Ok(new AdminGrant(grant.Id, grant.CoupleId, grant.ExpiresAtUtc, grant.Reason, grant.RevokedAtUtc, grant.CreatedAtUtc));
    }

    [HttpPost("grants/{grantId:guid}/revoke")]
    public async Task<IActionResult> RevokeGrant(Guid grantId, CancellationToken ct)
    {
        var grant = await db.PlusGrants.SingleOrDefaultAsync(item => item.Id == grantId, ct);
        if (grant is null) return NotFound();
        grant.Revoke(clock.UtcNow);
        audit.Record(User, "plus.revoked", "couple", grant.CoupleId.ToString(), grantId.ToString());
        await db.SaveChangesAsync(ct);
        return NoContent();
    }
}

[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin/audit")]
public sealed class AdminAuditController(IApplicationDbContext db) : ApiControllerBase
{
    internal static AdminAuditRow Row(AdminAuditEvent item) => new(item.Id, item.AdminEmail, item.Action, item.TargetType, item.TargetId, item.Details, item.IpAddress, item.Succeeded, item.CreatedAtUtc);

    [HttpGet]
    public async Task<ActionResult<AdminPage<AdminAuditRow>>> List([FromQuery] string? query, [FromQuery] string? action, [FromQuery] int page = 1, [FromQuery] int pageSize = 50, CancellationToken ct = default)
    {
        pageSize = Math.Clamp(pageSize, 10, 200);
        page = Math.Max(1, page);
        var events = db.AdminAuditEvents.AsNoTracking();
        if (!string.IsNullOrWhiteSpace(action)) events = events.Where(item => item.Action.StartsWith(action));
        if (!string.IsNullOrWhiteSpace(query)) { var q = query.Trim().ToLowerInvariant(); events = events.Where(item => item.AdminEmail.Contains(q) || (item.TargetId != null && item.TargetId.Contains(q))); }
        var total = await events.CountAsync(ct);
        var items = await events.OrderByDescending(item => item.CreatedAtUtc).Skip((page - 1) * pageSize).Take(pageSize).ToListAsync(ct);
        return Ok(new AdminPage<AdminAuditRow>(items.Select(Row).ToList(), total, page, pageSize));
    }
}

/// <summary>What is switched on, and whether the background work is keeping up.</summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin")]
public sealed class AdminSystemController(
    IApplicationDbContext db,
    IDateTimeProvider clock,
    AdminSettings settings,
    AdminAudit audit,
    IConfiguration configuration,
    IEmailSender email,
    IGoogleTokenVerifier google,
    IGooglePlayBilling billing) : ApiControllerBase
{
    [HttpGet("system")]
    public async Task<ActionResult<AdminSystem>> System(CancellationToken ct)
    {
        var now = clock.UtcNow;
        var dayAgo = now.AddDays(-1);
        var pushPending = await db.Notifications.CountAsync(item => item.PushState == PushState.Pending, ct);
        var pushFailed = await db.Notifications.CountAsync(item => item.PushState == PushState.Failed && item.CreatedAtUtc >= dayAgo, ct);
        var pushSent = await db.Notifications.CountAsync(item => item.PushState == PushState.Sent && item.CreatedAtUtc >= dayAgo, ct);
        var memoriesDeleting = await db.Memories.CountAsync(item => item.Status == MemoryStatus.Deleting, ct);
        var openGames = await db.GameSessions.CountAsync(item => item.Status == GameSessionStatus.InProgress || item.Status == GameSessionStatus.WaitingForPartner, ct);
        var features = new List<AdminFeature>
        {
            new("Push notifications", !string.IsNullOrWhiteSpace(configuration["Push:Provider"]), configuration["Push:Provider"] ?? "off"),
            new("Photo storage", !string.IsNullOrWhiteSpace(configuration["Media:ServiceUrl"]) && !string.IsNullOrWhiteSpace(configuration["Media:Bucket"]), configuration["Media:Bucket"] ?? "off"),
            new("Food photo estimates", !string.IsNullOrWhiteSpace(configuration["Food:AnthropicApiKey"]) || !string.IsNullOrWhiteSpace(configuration["ANTHROPIC_API_KEY"]), configuration["Food:Model"] ?? "default model"),
            new("Email (password reset)", email.IsConfigured, configuration["Email:Provider"] ?? "off"),
            new("Google sign-in", google.IsConfigured, google.IsConfigured ? "on" : "off"),
            new("Google Play billing", billing.IsConfigured, configuration["Billing:Provider"] ?? "off"),
        };
        return Ok(new AdminSystem(now, features, pushPending, pushSent, pushFailed, memoriesDeleting, openGames,
            settings.Emails.Count, settings.TokenLifetime.TotalHours, settings.Currency, settings.MonthlyPrices));
    }

    /// <summary>Who may use the dashboard, and whether each has set up an authenticator.</summary>
    [HttpGet("admins")]
    public async Task<ActionResult<IReadOnlyList<AdminRow>>> Admins(CancellationToken ct)
    {
        var emails = settings.Emails.Select(item => item.ToLowerInvariant()).ToList();
        var users = await db.Users.AsNoTracking().Where(user => emails.Contains(user.Email)).Select(user => new { user.Id, user.Email, user.DisplayName }).ToListAsync(ct);
        var accounts = await db.AdminAccounts.AsNoTracking().ToListAsync(ct);
        return Ok(emails.Select(address =>
        {
            var user = users.FirstOrDefault(item => item.Email == address);
            var account = user is null ? null : accounts.FirstOrDefault(item => item.UserId == user.Id);
            return new AdminRow(address, user?.Id, user?.DisplayName, user is not null, account?.EnrolledAtUtc);
        }).ToList());
    }

    /// <summary>Lost phone: the admin enrolls a new authenticator at the next sign-in. Not for yourself.</summary>
    [HttpPost("admins/{userId:guid}/reset-authenticator")]
    public async Task<IActionResult> ResetAuthenticator(Guid userId, CancellationToken ct)
    {
        if (User.FindFirst(global::System.Security.Claims.ClaimTypes.NameIdentifier)?.Value == userId.ToString())
            return Conflict(new ApiError("admin.self", "Ask another admin to reset your authenticator."));
        var account = await db.AdminAccounts.SingleOrDefaultAsync(item => item.UserId == userId, ct);
        if (account is null) return NotFound();
        account.Reset(Totp.NewSecret(), clock.UtcNow);
        var user = await db.Users.SingleAsync(item => item.Id == userId, ct);
        user.EndSessions(clock.UtcNow);
        audit.Record(User, "admin.authenticator_reset", "user", userId.ToString());
        await db.SaveChangesAsync(ct);
        return NoContent();
    }
}

public sealed record AdminPage<T>(IReadOnlyList<T> Items, int Total, int Page, int PageSize);
public sealed record AdminUserRow(Guid Id, string Email, string DisplayName, DateTime CreatedAtUtc, DateTime? LastActiveAtUtc, DateTime? DisabledAtUtc, bool Google, bool Password, CoupleStatus? CoupleStatus, bool Paying);
public sealed record AdminPersonRef(Guid Id, string Email, string DisplayName);
public sealed record AdminCouple(Guid Id, CoupleStatus Status, DateTime? LinkedAtUtc, DateTime? DissolvedAtUtc, AdminPersonRef? Partner);
public sealed record AdminDevice(string Platform, string? AppVersion, DateTime LastSeenAtUtc, bool Active);
public sealed record AdminActivityCounts(int DaresCompleted, int DaresMissed, int FoodEntries, int FoodScans, int PhotosShared, long PhotoBytes, int QuestionsAnswered, int ActiveDays);
public sealed record AdminUserDetail(
    Guid Id, string Email, string DisplayName, DateTime CreatedAtUtc, DateTime? LastActiveAtUtc, DateTime? DisabledAtUtc, string? DisabledReason,
    bool EmailVerified, bool Google, bool Password, string TimeZoneId,
    AdminCouple? Couple, Plan Plan, bool Complimentary,
    IReadOnlyList<AdminDevice> Devices, IReadOnlyList<AdminSubscriptionRow> Subscriptions, IReadOnlyList<AdminGrant> Grants,
    AdminActivityCounts Activity, IReadOnlyList<AdminAuditRow> History);
public sealed record AdminSubscriptionRow(Guid Id, string Store, string ProductId, Guid UserId, string? BuyerEmail, StoreSubscriptionStatus Status, DateTime ExpiresAtUtc, bool AutoRenewing, bool Acknowledged, DateTime CreatedAtUtc, DateTime? LastVerifiedAtUtc, string TokenSuffix);
public sealed record AdminGrant(Guid Id, Guid CoupleId, DateTime ExpiresAtUtc, string Reason, DateTime? RevokedAtUtc, DateTime CreatedAtUtc);
public sealed record AdminAuditRow(Guid Id, string AdminEmail, string Action, string? TargetType, string? TargetId, string? Details, string? IpAddress, bool Succeeded, DateTime CreatedAtUtc);
public sealed record AdminFeature(string Name, bool On, string Detail);
public sealed record AdminSystem(DateTime NowUtc, IReadOnlyList<AdminFeature> Features, int PushPending, int PushSentToday, int PushFailedToday, int PhotosBeingDeleted, int OpenGames, int AdminCount, double TokenHours, string Currency, IReadOnlyDictionary<string, decimal> MonthlyPrices);
public sealed record AdminRow(string Email, Guid? UserId, string? DisplayName, bool HasAccount, DateTime? AuthenticatorEnrolledAtUtc);
public sealed record AdminReasonRequest(string? Reason);
public sealed record AdminDeleteRequest(string? ConfirmEmail, string? Reason);
public sealed record AdminGrantRequest(int Days, string? Reason);
