namespace Twogether.Api.Auth;

public sealed class JwtSettings
{
    public string Issuer { get; set; } = "twogether-api";
    public string Audience { get; set; } = "twogether-web";
    public string SigningKey { get; set; } = "twogether-development-key-change-before-production-2026";
    public int AccessTokenMinutes { get; set; } = 10080;
}
