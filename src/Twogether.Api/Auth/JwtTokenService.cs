using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.IdentityModel.Tokens;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Api.Auth;

public sealed class JwtTokenService(JwtSettings settings) : ITokenService
{
    public string CreateAccessToken(Guid userId, Guid? coupleId, string email, string displayName, DateTime utcNow)
    {
        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, userId.ToString()),
            new(ClaimTypes.NameIdentifier, userId.ToString()),
            new(JwtRegisteredClaimNames.Email, email),
            new(ClaimTypes.Name, displayName),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString())
        };

        if (coupleId.HasValue) claims.Add(new Claim("couple_id", coupleId.Value.ToString()));

        var credentials = new SigningCredentials(
            new SymmetricSecurityKey(Encoding.UTF8.GetBytes(settings.SigningKey)),
            SecurityAlgorithms.HmacSha256);

        var token = new JwtSecurityToken(
            settings.Issuer,
            settings.Audience,
            claims,
            notBefore: utcNow,
            expires: utcNow.AddMinutes(settings.AccessTokenMinutes),
            signingCredentials: credentials);

        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}
