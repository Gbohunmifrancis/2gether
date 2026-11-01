using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Admin;

namespace Twogether.Api.Admin;

/// <summary>
/// Destructive admin actions need a fresh authenticator code in <c>X-Admin-Code</c>, checked like a sign-in:
/// the code is used up (the account's last used step moves past it) before the action runs, so a stolen
/// session token alone can't disable, delete, refund or cancel anything. One code covers
/// <see cref="StepUpGrace.Window"/> of such actions from the same session, so a few in a row ask once.
/// </summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class RequireStepUpAttribute() : TypeFilterAttribute(typeof(StepUpFilter));

/// <summary>Which admin sessions gave a code lately, in memory: a restart only means one more code.</summary>
public sealed class StepUpGrace
{
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(5);
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, DateTime> confirmed = new();

    public bool IsOpen(string key, DateTime utcNow) => confirmed.TryGetValue(key, out var at) && utcNow - at < Window;

    public void Open(string key, DateTime utcNow)
    {
        confirmed[key] = utcNow;
        // Forget the stale ones as we go; there are only ever a handful of admins.
        foreach (var (k, at) in confirmed)
            if (utcNow - at >= Window) confirmed.TryRemove(k, out _);
    }
}

public sealed class StepUpFilter(
    IApplicationDbContext db,
    IDateTimeProvider clock,
    AdminSecretProtector secrets,
    AttemptLimiter limiter,
    AdminAudit audit,
    StepUpGrace? grace = null) : IAsyncActionFilter
{
    public const string Header = "X-Admin-Code";
    public const string RequiredCode = "admin.step_up_required";
    public const string InvalidCode = "admin.step_up_invalid";

    public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
    {
        var ct = context.HttpContext.RequestAborted;
        var admin = context.HttpContext.User;
        var code = context.HttpContext.Request.Headers[Header].ToString().Trim();
        // The same admin in the same session (the bearer token, hashed) gave a code in the last few minutes.
        var session = SessionKey(admin, context.HttpContext.Request.Headers.Authorization.ToString());
        if (grace is not null && session is not null && grace.IsOpen(session, clock.UtcNow))
        {
            await next();
            return;
        }
        if (code.Length == 0)
        {
            context.Result = Forbidden(RequiredCode, "Enter a fresh code from your authenticator app to do this.");
            return;
        }
        if (!Guid.TryParse(admin.FindFirst(ClaimTypes.NameIdentifier)?.Value, out var adminId))
        {
            context.Result = new UnauthorizedResult();
            return;
        }
        var key = adminId.ToString();
        if (limiter.RetryAfter(AttemptRules.AdminStepUpPerAdmin, key) is { } wait)
        {
            context.HttpContext.Response.Headers.RetryAfter = ((int)Math.Ceiling(wait.TotalSeconds)).ToString(System.Globalization.CultureInfo.InvariantCulture);
            context.Result = new ObjectResult(new ApiError("rate_limited", "Too many wrong codes. Wait a few minutes and try again.")) { StatusCode = StatusCodes.Status429TooManyRequests };
            return;
        }

        var account = await db.AdminAccounts.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == adminId, ct);
        var secret = account is { Enrolled: true } ? secrets.Unprotect(account.TotpSecret).Secret : null;
        var now = clock.UtcNow;
        var step = secret is null ? null : Totp.Verify(secret, code, now, account!.LastUsedStep);
        // Single use even when two requests carry the same code at once: only one moves the step.
        var claimed = step is { } accepted
            && await db.AdminAccounts.Where(item => item.UserId == adminId && item.LastUsedStep < accepted)
                .ExecuteUpdateAsync(set => set.SetProperty(item => item.LastUsedStep, accepted).SetProperty(item => item.UpdatedAtUtc, now), ct) == 1;
        if (!claimed)
        {
            limiter.RecordFailure(AttemptRules.AdminStepUpPerAdmin, key);
            audit.Record(admin, "admin.step_up_failed", details: context.HttpContext.Request.Path.Value, succeeded: false);
            await db.SaveChangesAsync(ct);
            context.Result = Forbidden(InvalidCode, "That code didn't work, or was already used. Codes change every 30 seconds.");
            return;
        }
        limiter.Reset(AttemptRules.AdminStepUpPerAdmin, key);
        if (session is not null) grace?.Open(session, now);
        await next();
    }

    private static string? SessionKey(ClaimsPrincipal admin, string authorization)
    {
        var id = admin.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        if (id is null) return null;
        var token = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(authorization)));
        return $"{id}:{token}";
    }

    private static ObjectResult Forbidden(string code, string message) => new(new ApiError(code, message)) { StatusCode = StatusCodes.Status403Forbidden };
}
