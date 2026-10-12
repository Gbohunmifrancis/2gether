using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.IdentityModel.Tokens;
using Twogether.Api.Auth;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin;
using Twogether.Core.Entities;

namespace Twogether.Api.Admin;

/// <summary>
/// Admin sessions are separate from app sessions: their own audience (an app token never opens the
/// dashboard, and an admin token never opens the app API), a short lifetime, and a role claim.
/// </summary>
public static class AdminAuth
{
    public const string Scheme = "Admin";
    public const string Policy = "Admin";
    public const string RoleClaim = "role";
    public const string RoleValue = "admin";

    public static string Audience(JwtSettings jwt) => jwt.Audience + "-admin";

    public static (string Token, DateTime ExpiresAtUtc) CreateToken(JwtSettings jwt, AdminSettings settings, User user, DateTime utcNow)
    {
        var expires = utcNow + settings.TokenLifetime;
        var token = new JwtSecurityToken(
            jwt.Issuer,
            Audience(jwt),
            [
                new Claim(JwtRegisteredClaimNames.Sub, user.Id.ToString()),
                new Claim(ClaimTypes.NameIdentifier, user.Id.ToString()),
                new Claim(JwtRegisteredClaimNames.Email, user.Email),
                new Claim(ClaimTypes.Name, user.DisplayName),
                new Claim(RoleClaim, RoleValue),
                new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
            ],
            notBefore: utcNow,
            expires: expires,
            signingCredentials: new SigningCredentials(new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwt.SigningKey)), SecurityAlgorithms.HmacSha256));
        return (new JwtSecurityTokenHandler().WriteToken(token), expires);
    }
}

/// <summary>Writes the admin audit trail. Every admin action, allowed or refused, goes through here.</summary>
public sealed class AdminAudit(IApplicationDbContext db, IDateTimeProvider clock, IHttpContextAccessor http)
{
    public void Record(Guid? adminId, string adminEmail, string action, string? targetType = null, string? targetId = null, string? details = null, bool succeeded = true)
    {
        var ip = http.HttpContext?.Connection.RemoteIpAddress?.ToString();
        db.AdminAuditEvents.Add(AdminAuditEvent.Record(adminId, adminEmail, action, targetType, targetId, details, ip, succeeded, clock.UtcNow));
    }

    /// <summary>For the signed-in admin of this request.</summary>
    public void Record(ClaimsPrincipal admin, string action, string? targetType = null, string? targetId = null, string? details = null, bool succeeded = true)
    {
        Guid.TryParse(admin.FindFirst(ClaimTypes.NameIdentifier)?.Value, out var id);
        Record(id == Guid.Empty ? null : id, admin.FindFirst(ClaimTypes.Email)?.Value ?? admin.FindFirst(JwtRegisteredClaimNames.Email)?.Value ?? "unknown", action, targetType, targetId, details, succeeded);
    }
}
