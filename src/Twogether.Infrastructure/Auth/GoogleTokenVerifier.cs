using Google.Apis.Auth;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Auth;

namespace Twogether.Infrastructure.Auth;

/// <summary>Checks a Google ID token's signature, expiry, issuer and audience against Google's published keys.</summary>
public sealed class GoogleTokenVerifier(GoogleAuthSettings settings, ILogger<GoogleTokenVerifier> logger) : IGoogleTokenVerifier
{
    public bool IsConfigured => settings.ClientIds.Count > 0;

    public async Task<GoogleIdentity?> VerifyAsync(string idToken, CancellationToken cancellationToken)
    {
        try
        {
            var payload = await GoogleJsonWebSignature.ValidateAsync(idToken, new GoogleJsonWebSignature.ValidationSettings { Audience = settings.ClientIds });
            return string.IsNullOrWhiteSpace(payload.Email) ? null : new GoogleIdentity(payload.Subject, payload.Email, payload.EmailVerified, payload.Name);
        }
        catch (InvalidJwtException exception)
        {
            logger.LogInformation("Rejected a Google ID token: {Reason}", exception.Message);
            return null;
        }
    }
}
