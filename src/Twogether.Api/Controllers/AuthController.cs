using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Auth;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Users;
using Twogether.Shared.Localization;

namespace Twogether.Api.Controllers;

public sealed class AuthController(
    IApplicationDbContext db,
    IPasswordHasher passwordHasher,
    ITokenService tokenService,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    AttemptLimiter limiter,
    IEmailSender email,
    IGoogleTokenVerifier google,
    Twogether.Api.Presence.HubConnectionRegistry hubConnections,
    Twogether.Api.Realtime.LocationSharingStopper locationStopper,
    ILogger<AuthController> logger) : ApiControllerBase
{
    private static ApiError ResetSent => new("auth.reset_sent", Loc.T("If an account uses that email, a code is on its way. It works for 15 minutes."));
    private static ApiError BadResetCode => new("auth.invalid_reset_code", Loc.T("That code is wrong or has expired. Ask for a new one."));
    private static ApiError BadCountry => new("auth.invalid_country", Loc.T("Choose your country from the list."));

    /// <summary>
    /// Emails a six-digit code. The answer is the same whether or not the account exists, so the
    /// endpoint cannot be used to find out who has an account.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("password/forgot")]
    public async Task<IActionResult> ForgotPassword(ForgotPasswordRequest request, CancellationToken cancellationToken)
    {
        if (!email.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError("auth.email_unavailable", Loc.T("Password reset isn't set up on this server yet.")));
        var address = (request.Email ?? string.Empty).Trim().ToLowerInvariant();
        if (address.Length is 0 or > 320 || !address.Contains('@')) return UnprocessableEntity(new ApiError("auth.invalid_email", Loc.T("Enter the email you signed up with.")));
        if (Blocked(limiter, (AttemptRules.ResetRequestPerEmail, address), (AttemptRules.ResetRequestPerIp, ClientIp)) is { } wait)
            return TooManyAttempts(wait, "Too many codes asked for. Wait a little and try again.");
        limiter.RecordFailure(AttemptRules.ResetRequestPerEmail, address);
        limiter.RecordFailure(AttemptRules.ResetRequestPerIp, ClientIp);

        var user = await db.Users.AsNoTracking().SingleOrDefaultAsync(candidate => candidate.Email == address, cancellationToken);
        if (user is null) return Accepted(ResetSent);
        var now = clock.UtcNow;
        foreach (var old in await db.PasswordResetCodes.Where(item => item.UserId == user.Id && item.UsedAtUtc == null).ToListAsync(cancellationToken)) old.Retire(now);
        var code = PasswordResetRules.NewCode();
        db.PasswordResetCodes.Add(PasswordResetCode.Issue(user.Id, PasswordResetRules.Hash(user.Id, code), now));
        await db.SaveChangesAsync(cancellationToken);
        try
        {
            await email.SendAsync(PasswordResetRules.Email(user.Email, code), cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            // The same answer either way: a failure here must not reveal that the account exists.
            logger.LogError(exception, "Sending a password reset code failed.");
        }
        return Accepted(ResetSent);
    }

    /// <summary>The code from the email plus a new password: signs in, and every other session ends.</summary>
    [AllowAnonymous]
    [HttpPost("password/reset")]
    public async Task<ActionResult<AuthResponse>> ResetPassword(ResetPasswordRequest request, CancellationToken cancellationToken)
    {
        var address = (request.Email ?? string.Empty).Trim().ToLowerInvariant();
        if (Blocked(limiter, (AttemptRules.ResetCodePerEmail, address), (AttemptRules.ResetCodePerIp, ClientIp)) is { } wait)
            return TooManyAttempts(wait, "Too many wrong codes. Wait a few minutes and try again.");
        if (string.IsNullOrEmpty(request.NewPassword) || request.NewPassword.Length < PasswordResetRules.MinPasswordLength)
            return UnprocessableEntity(new ApiError("auth.invalid_password", Loc.T("Use at least {min} characters.", ("min", PasswordResetRules.MinPasswordLength))));

        var now = clock.UtcNow;
        var user = await db.Users.SingleOrDefaultAsync(candidate => candidate.Email == address, cancellationToken);
        var reset = user is null ? null : await db.PasswordResetCodes
            .Where(item => item.UserId == user.Id && item.UsedAtUtc == null)
            .OrderByDescending(item => item.CreatedAtUtc)
            .FirstOrDefaultAsync(cancellationToken);
        if (user is null || reset is null || !reset.IsLive(now))
        {
            limiter.RecordFailure(AttemptRules.ResetCodePerEmail, address);
            limiter.RecordFailure(AttemptRules.ResetCodePerIp, ClientIp);
            return BadRequest(BadResetCode);
        }
        reset.RecordAttempt(now);
        if (!PasswordResetRules.Matches(user.Id, request.Code ?? string.Empty, reset.CodeHash))
        {
            await db.SaveChangesAsync(cancellationToken);
            limiter.RecordFailure(AttemptRules.ResetCodePerEmail, address);
            limiter.RecordFailure(AttemptRules.ResetCodePerIp, ClientIp);
            return BadRequest(BadResetCode);
        }
        if (user.DisabledAtUtc is not null) return Disabled();
        reset.Retire(now);
        user.ResetPassword(passwordHasher.Hash(request.NewPassword), now);
        await db.SaveChangesAsync(cancellationToken);
        limiter.Reset(AttemptRules.ResetCodePerEmail, address);
        limiter.Reset(AttemptRules.LoginPerEmail, address);
        var closed = hubConnections.AbortAll(user.Id);
        // The phones that just lost their session may have been sharing: sharing ends with them.
        await locationStopper.StopAsync(user.Id, cancellationToken);
        logger.LogInformation("Password reset for user {UserId}; older sessions end ({Closed} live connections closed).", user.Id, closed);
        return Ok(ToResponse(user, tokenService.CreateAccessToken(user.Id, user.CoupleId, user.Email, user.DisplayName, now)));
    }

    /// <summary>
    /// Sign in (or up) with a Google ID token from the app. An existing account with the same,
    /// Google-verified email is linked. A new account needs a date of birth: the first call then
    /// answers 422 auth.profile_required, and the app asks and calls again with it.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("google")]
    public async Task<ActionResult<AuthResponse>> Google(GoogleSignInRequest request, CancellationToken cancellationToken)
    {
        if (!google.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError("auth.google_unavailable", Loc.T("Google sign-in isn't set up on this server yet.")));
        if (Blocked(limiter, (AttemptRules.GooglePerIp, ClientIp)) is { } wait)
            return TooManyAttempts(wait, "Too many sign-in attempts. Wait a few minutes and try again.");
        var identity = string.IsNullOrWhiteSpace(request.IdToken) ? null : await google.VerifyAsync(request.IdToken, cancellationToken);
        if (identity is null || !identity.EmailVerified)
        {
            limiter.RecordFailure(AttemptRules.GooglePerIp, ClientIp);
            return Unauthorized(new ApiError("auth.invalid_google_token", Loc.T("Google sign-in didn't work. Try again.")));
        }

        var now = clock.UtcNow;
        var address = identity.Email.Trim().ToLowerInvariant();
        var user = await db.Users.SingleOrDefaultAsync(candidate => candidate.GoogleId == identity.Subject, cancellationToken)
            ?? await db.Users.SingleOrDefaultAsync(candidate => candidate.Email == address, cancellationToken);
        if (user is not null)
        {
            if (user.DisabledAtUtc is not null) return Disabled();
            if (user.GoogleId is null) user.LinkGoogle(identity.Subject, now);
            else if (user.GoogleId != identity.Subject) return Conflict(new ApiError("auth.google_mismatch", Loc.T("This email is linked to a different Google account.")));
            await db.SaveChangesAsync(cancellationToken);
            await EnsureFemaleCycleOwnerAsync(user, cancellationToken);
            return Ok(ToResponse(user, tokenService.CreateAccessToken(user.Id, user.CoupleId, user.Email, user.DisplayName, now)));
        }

        if (request.DateOfBirth is null)
            return UnprocessableEntity(new GoogleProfileRequired("auth.profile_required", Loc.T("Tell us your birthday to finish signing up."), identity.Email, identity.Name));
        if (Birthday(request.DateOfBirth.Value, now) is { } refused) return refused;
        if (!Enum.IsDefined(request.Gender))
            return UnprocessableEntity(new ApiError("auth.invalid_gender", Loc.T("Choose the option that best describes you.")));
        if (!TryCountry(request.CountryCode, out var googleCountry)) return UnprocessableEntity(BadCountry);
        var name = (string.IsNullOrWhiteSpace(request.DisplayName) ? identity.Name : request.DisplayName)?.Trim();
        if (string.IsNullOrWhiteSpace(name) || name.Length > 100) name = address.Split('@')[0];
        var created = Twogether.Core.Entities.User.Create(address, name, request.DateOfBirth.Value, now, passwordHash: null, googleId: identity.Subject,
            cycleOwner: request.Gender == Gender.Female, gender: request.Gender);
        created.LinkGoogle(identity.Subject, now);
        if (googleCountry is not null) created.SetCountry(googleCountry, now);
        var couple = Twogether.Core.Entities.Couple.Create(created.Id, now);
        created.SetCouple(couple.Id, now);
        db.Users.Add(created);
        db.Couples.Add(couple);
        db.CycleProfiles.Add(CycleProfile.Create(created.Id, now));
        await db.SaveChangesAsync(cancellationToken);
        return Ok(ToResponse(created, tokenService.CreateAccessToken(created.Id, created.CoupleId, created.Email, created.DisplayName, now)));
    }

    [AllowAnonymous]
    [HttpPost("register")]
    public async Task<ActionResult<AuthResponse>> Register(RegisterRequest request, CancellationToken cancellationToken)
    {
        if (Blocked(limiter, (AttemptRules.RegisterPerIp, ClientIp)) is { } wait)
            return TooManyAttempts(wait, "Too many sign-ups from this network. Try again later.");
        limiter.RecordFailure(AttemptRules.RegisterPerIp, ClientIp);
        if (string.IsNullOrWhiteSpace(request.Email) || string.IsNullOrWhiteSpace(request.Password) || request.Password.Length < 8)
            return UnprocessableEntity(new ApiError("auth.invalid_registration", Loc.T("Email and a password of at least 8 characters are required.")));
        if (await db.Users.AnyAsync(user => user.Email == request.Email.Trim().ToLower(), cancellationToken))
            return Conflict(new ApiError("auth.email_exists", Loc.T("An account with this email already exists.")));

        var now = clock.UtcNow;
        if (Birthday(request.DateOfBirth, now) is { } refused) return refused;
        if (!Enum.IsDefined(request.Gender))
            return UnprocessableEntity(new ApiError("auth.invalid_gender", Loc.T("Choose the option that best describes you.")));
        if (!TryCountry(request.CountryCode, out var country)) return UnprocessableEntity(BadCountry);
        var user = Twogether.Core.Entities.User.Create(
            request.Email,
            request.DisplayName,
            request.DateOfBirth,
            now,
            passwordHasher.Hash(request.Password),
            cycleOwner: request.Gender == Gender.Female,
            gender: request.Gender);
        if (country is not null) user.SetCountry(country, now);
        var couple = Twogether.Core.Entities.Couple.Create(user.Id, now);
        user.SetCouple(couple.Id, now);
        db.Users.Add(user);
        db.Couples.Add(couple);
        db.CycleProfiles.Add(CycleProfile.Create(user.Id, now));
        await db.SaveChangesAsync(cancellationToken);
        return Ok(ToResponse(user, tokenService.CreateAccessToken(user.Id, user.CoupleId, user.Email, user.DisplayName, now)));
    }

    [AllowAnonymous]
    [HttpPost("login")]
    public async Task<ActionResult<AuthResponse>> Login(LoginRequest request, CancellationToken cancellationToken)
    {
        var email = (request.Email ?? string.Empty).Trim().ToLowerInvariant();
        if (Blocked(limiter, (AttemptRules.LoginPerEmail, email), (AttemptRules.LoginPerIp, ClientIp)) is { } wait)
            return TooManyAttempts(wait, "Too many sign-in attempts. Wait a few minutes and try again.");
        var user = await db.Users.SingleOrDefaultAsync(candidate => candidate.Email == email, cancellationToken);
        if (user is null || user.PasswordHash is null || !passwordHasher.Verify(request.Password ?? string.Empty, user.PasswordHash))
        {
            // Counted for unknown emails too, so the limit does not reveal which accounts exist.
            limiter.RecordFailure(AttemptRules.LoginPerEmail, email);
            limiter.RecordFailure(AttemptRules.LoginPerIp, ClientIp);
            return Unauthorized(new ApiError("auth.invalid_credentials", Loc.T("Email or password is incorrect.")));
        }
        limiter.Reset(AttemptRules.LoginPerEmail, email);
        if (user.DisabledAtUtc is not null) return Disabled();
        await EnsureFemaleCycleOwnerAsync(user, cancellationToken);
        var token = tokenService.CreateAccessToken(user.Id, user.CoupleId, user.Email, user.DisplayName, clock.UtcNow);
        return Ok(ToResponse(user, token));
    }

    [Authorize]
    [HttpGet("me")]
    public async Task<ActionResult<AuthUserDto>> Me(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var user = await db.Users.SingleOrDefaultAsync(candidate => candidate.Id == currentUser.UserId.Value, cancellationToken);
        if (user is not null) await EnsureFemaleCycleOwnerAsync(user, cancellationToken);
        return user is null ? NotFound() : Ok(ToUser(user));
    }

    [Authorize]
    [HttpPut("profile")]
    public async Task<ActionResult<AuthResponse>> UpdateProfile(UpdateProfileRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        if (string.IsNullOrWhiteSpace(request.DisplayName) || request.DisplayName.Trim().Length > 100)
            return UnprocessableEntity(new ApiError("profile.invalid_name", Loc.T("Nickname must be between 1 and 100 characters.")));
        // The partner's app loads this image, so only an https address is accepted: no tracking
        // over plain http, no javascript: or data: tricks, nothing the database would cut off.
        var avatar = string.IsNullOrWhiteSpace(request.AvatarUrl) ? null : request.AvatarUrl.Trim();
        var mapColor = string.IsNullOrWhiteSpace(request.MapColor) ? "#f45c91" : request.MapColor.Trim();
        if (!System.Text.RegularExpressions.Regex.IsMatch(mapColor, "^#[0-9a-fA-F]{6}$"))
            return UnprocessableEntity(new ApiError("profile.invalid_color", Loc.T("Map color must be a six-digit hex color.")));
        // Left out by app versions from before the country was asked: then it stays as it is.
        if (!TryCountry(request.CountryCode, out var country)) return UnprocessableEntity(BadCountry);
        if (request.Gender is { } gender && !Enum.IsDefined(gender))
            return UnprocessableEntity(new ApiError("auth.invalid_gender", Loc.T("Choose the option that best describes you.")));
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (user is null) return NotFound();
        // An unchanged avatar is let through: the profile screen sends it back with every edit.
        if (avatar is not null && avatar != user.AvatarUrl && (avatar.Length > 500 || !Uri.TryCreate(avatar, UriKind.Absolute, out var avatarUri) || avatarUri.Scheme != Uri.UriSchemeHttps))
            return UnprocessableEntity(new ApiError("profile.invalid_avatar", Loc.T("The avatar must be an https link of at most 500 characters.")));
        user.UpdateProfile(request.DisplayName.Trim(), avatar, mapColor, clock.UtcNow);
        if (country is not null) user.SetCountry(country, clock.UtcNow);
        if (request.Gender is { } newGender && newGender != user.Gender) user.SetGender(newGender, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        // Becoming a woman turns on cycle tracking, as at sign-up, unless the partner already tracks one. Changing away
        // keeps the cycle and its history: nothing is deleted behind anyone's back.
        await EnsureFemaleCycleOwnerAsync(user, cancellationToken);
        var token = tokenService.CreateAccessToken(user.Id, user.CoupleId, user.Email, user.DisplayName, clock.UtcNow);
        return Ok(new AuthResponse(token, ToUser(user)));
    }

    private ObjectResult Disabled() => StatusCode(StatusCodes.Status403Forbidden, new ApiError("auth.account_disabled", Loc.T("This account has been disabled. Contact support if you think this is a mistake.")));

    private static AuthResponse ToResponse(User user, string token) => new(token, ToUser(user));
    private static AuthUserDto ToUser(User user) => new(user.Id, user.Email, user.DisplayName, user.CoupleId, user.TimeZoneId, user.AvatarUrl, user.MapColor, user.CycleOwner, user.Gender,
        user.CountryCode, ContentRegions.For(user.CountryCode), user.PublicAvatarVersion);

    /// <summary>Optional on the wire (older app versions don't send it), but a code that is sent must be a real country.</summary>
    /// <summary>A new account's date of birth: a real date, and 18 or over (AgeRules). Null when it's fine.</summary>
    private UnprocessableEntityObjectResult? Birthday(DateOnly dateOfBirth, DateTime now)
    {
        if (!AgeRules.IsPlausible(dateOfBirth, now))
            return UnprocessableEntity(new ApiError("auth.invalid_birthday", Loc.T("That date doesn't look right.")));
        if (!AgeRules.IsAdult(dateOfBirth, now))
            return UnprocessableEntity(new ApiError("auth.too_young", Loc.T("Heartpact is for adults. You need to be 18 or over to sign up.")));
        return null;
    }

    private static bool TryCountry(string? requested, out string? country)
    {
        country = ContentRegions.Normalize(requested);
        return country is not null || string.IsNullOrWhiteSpace(requested);
    }

    private async Task EnsureFemaleCycleOwnerAsync(User user, CancellationToken cancellationToken)
    {
        if (user.Gender != Gender.Female || user.CycleOwner) return;
        if (user.CoupleId.HasValue && await db.Users.AsNoTracking().AnyAsync(
                candidate => candidate.CoupleId == user.CoupleId.Value
                    && candidate.Id != user.Id
                    && candidate.CycleOwner,
                cancellationToken))
            return;

        user.ClaimCycleOwnership(clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
    }
}

public sealed record RegisterRequest(string Email, string Password, string DisplayName, DateOnly DateOfBirth, Gender Gender = Gender.PreferNotToSay, string? CountryCode = null);
public sealed record LoginRequest(string Email, string Password);
public sealed record ForgotPasswordRequest(string Email);
public sealed record ResetPasswordRequest(string Email, string Code, string NewPassword);
public sealed record GoogleSignInRequest(string IdToken, DateOnly? DateOfBirth = null, Gender Gender = Gender.PreferNotToSay, string? DisplayName = null, string? CountryCode = null);
/// <summary>An ApiError plus what Google told us, so the app can prefill the last step.</summary>
public sealed record GoogleProfileRequired(string Code, string Message, string Email, string? Name);
public sealed record AuthResponse(string AccessToken, AuthUserDto User);
/// <summary>Country and gender are optional: left out (as older app versions do), they stay as they are.</summary>
public sealed record UpdateProfileRequest(string DisplayName, string? AvatarUrl, string? MapColor, string? CountryCode = null, Gender? Gender = null);
/// <summary>
/// <c>Region</c> is the content region for <c>CountryCode</c> (africa, mena, southasia, eastasia, latam, default).
/// <c>AvatarVersion</c> is 0 without a profile picture, else the version of <c>GET api/users/{id}/avatar</c> to ask for.
/// </summary>
public sealed record AuthUserDto(Guid Id, string Email, string DisplayName, Guid? CoupleId, string TimeZoneId, string? AvatarUrl, string MapColor, bool CycleOwner, Gender Gender,
    string? CountryCode, string Region, long AvatarVersion = 0);
