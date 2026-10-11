using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Admin;
using Twogether.Api.Auth;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Admin;
using Twogether.Core.Entities;

namespace Twogether.Api.Controllers.Admin;

/// <summary>
/// Sign-in to the admin dashboard: an allowlisted account's password plus an authenticator code.
/// The first sign-in hands back a new authenticator secret to scan; the first code that checks out
/// enrolls it. Every attempt is audited.
/// </summary>
[Route("api/admin/auth")]
public sealed class AdminAuthController(
    IApplicationDbContext db,
    IPasswordHasher passwordHasher,
    IDateTimeProvider clock,
    AttemptLimiter limiter,
    AdminSettings settings,
    JwtSettings jwt,
    AdminAudit audit) : ApiControllerBase
{
    [AllowAnonymous]
    [HttpPost("login")]
    public async Task<IActionResult> Login(AdminLoginRequest request, CancellationToken cancellationToken)
    {
        var email = (request.Email ?? string.Empty).Trim().ToLowerInvariant();
        if (Blocked(limiter, (AttemptRules.AdminLoginPerEmail, email), (AttemptRules.AdminLoginPerIp, ClientIp)) is { } wait)
            return TooManyAttempts(wait, "Too many attempts. Wait a few minutes and try again.");

        var user = await db.Users.SingleOrDefaultAsync(candidate => candidate.Email == email, cancellationToken);
        // One answer for every way this can fail, so the endpoint reveals nothing about who is an admin.
        if (user is null || user.PasswordHash is null || user.DisabledAtUtc is not null || !settings.IsAdmin(user.Email)
            || !passwordHasher.Verify(request.Password ?? string.Empty, user.PasswordHash))
            return await Fail(email, user?.Id, "Email or password is incorrect.", cancellationToken);

        var now = clock.UtcNow;
        var account = await db.AdminAccounts.SingleOrDefaultAsync(item => item.UserId == user.Id, cancellationToken);
        if (account is null)
        {
            account = AdminAccount.Pending(user.Id, Totp.NewSecret(), now);
            db.AdminAccounts.Add(account);
            await db.SaveChangesAsync(cancellationToken);
        }
        if (string.IsNullOrWhiteSpace(request.Code))
        {
            // Password fine: ask for the code, or for enrollment first.
            return account.Enrolled
                ? Unauthorized(new ApiError("admin.code_required", "Enter the code from your authenticator app."))
                : Conflict(new AdminEnrollment("admin.enrollment_required", "Scan this with an authenticator app, then enter its code.", account.TotpSecret, Totp.Uri(account.TotpSecret, user.Email)));
        }
        if (Totp.Verify(account.TotpSecret, request.Code, now, account.LastUsedStep) is not { } step)
            return await Fail(email, user.Id, "That code didn't work. Codes change every 30 seconds.", cancellationToken, code: "admin.invalid_code");

        var enrolling = !account.Enrolled;
        account.Accept(step, now);
        limiter.Reset(AttemptRules.AdminLoginPerEmail, email);
        audit.Record(user.Id, user.Email, enrolling ? "admin.enrolled" : "admin.signed_in");
        await db.SaveChangesAsync(cancellationToken);
        var (token, expires) = AdminAuth.CreateToken(jwt, settings, user, now);
        return Ok(new AdminSession(token, expires, new AdminIdentity(user.Id, user.Email, user.DisplayName)));
    }

    [Authorize(Policy = AdminAuth.Policy)]
    [HttpGet("me")]
    public ActionResult<AdminIdentity> Me()
        => Ok(new AdminIdentity(Guid.Parse(User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)!.Value),
            User.FindFirst(System.Security.Claims.ClaimTypes.Email)!.Value, User.FindFirst(System.Security.Claims.ClaimTypes.Name)?.Value ?? ""));

    private async Task<IActionResult> Fail(string email, Guid? userId, string message, CancellationToken cancellationToken, string code = "admin.invalid_credentials")
    {
        limiter.RecordFailure(AttemptRules.AdminLoginPerEmail, email);
        limiter.RecordFailure(AttemptRules.AdminLoginPerIp, ClientIp);
        audit.Record(userId, email, "admin.sign_in_failed", succeeded: false, details: code);
        await db.SaveChangesAsync(cancellationToken);
        return Unauthorized(new ApiError(code, message));
    }
}

public sealed record AdminLoginRequest(string Email, string Password, string? Code = null);
public sealed record AdminIdentity(Guid Id, string Email, string DisplayName);
public sealed record AdminSession(string AccessToken, DateTime ExpiresAtUtc, AdminIdentity Admin);
public sealed record AdminEnrollment(string Code, string Message, string Secret, string OtpauthUri);
