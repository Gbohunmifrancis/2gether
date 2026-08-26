using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

public sealed class AuthController(
    IApplicationDbContext db,
    IPasswordHasher passwordHasher,
    ITokenService tokenService,
    ICurrentUser currentUser,
    IDateTimeProvider clock) : ApiControllerBase
{
    [AllowAnonymous]
    [HttpPost("register")]
    public async Task<ActionResult<AuthResponse>> Register(RegisterRequest request, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.Email) || string.IsNullOrWhiteSpace(request.Password) || request.Password.Length < 8)
            return UnprocessableEntity(new ApiError("auth.invalid_registration", "Email and a password of at least 8 characters are required."));
        if (await db.Users.AnyAsync(user => user.Email == request.Email.Trim().ToLower(), cancellationToken))
            return Conflict(new ApiError("auth.email_exists", "An account with this email already exists."));

        var now = clock.UtcNow;
        if (!Enum.IsDefined(request.Gender))
            return UnprocessableEntity(new ApiError("auth.invalid_gender", "Choose the option that best describes you."));
        var user = Twogether.Core.Entities.User.Create(
            request.Email,
            request.DisplayName,
            request.DateOfBirth,
            now,
            passwordHasher.Hash(request.Password),
            cycleOwner: request.Gender == Gender.Female,
            gender: request.Gender);
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
        var user = await db.Users.SingleOrDefaultAsync(candidate => candidate.Email == request.Email.Trim().ToLower(), cancellationToken);
        if (user is null || user.PasswordHash is null || !passwordHasher.Verify(request.Password, user.PasswordHash))
            return Unauthorized(new ApiError("auth.invalid_credentials", "Email or password is incorrect."));
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
            return UnprocessableEntity(new ApiError("profile.invalid_name", "Nickname must be between 1 and 100 characters."));
        var mapColor = string.IsNullOrWhiteSpace(request.MapColor) ? "#f45c91" : request.MapColor.Trim();
        if (!System.Text.RegularExpressions.Regex.IsMatch(mapColor, "^#[0-9a-fA-F]{6}$"))
            return UnprocessableEntity(new ApiError("profile.invalid_color", "Map color must be a six-digit hex color."));
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (user is null) return NotFound();
        user.UpdateProfile(request.DisplayName.Trim(), request.AvatarUrl, mapColor, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        var token = tokenService.CreateAccessToken(user.Id, user.CoupleId, user.Email, user.DisplayName, clock.UtcNow);
        return Ok(new AuthResponse(token, ToUser(user)));
    }

    private static AuthResponse ToResponse(User user, string token) => new(token, ToUser(user));
    private static AuthUserDto ToUser(User user) => new(user.Id, user.Email, user.DisplayName, user.CoupleId, user.TimeZoneId, user.AvatarUrl, user.MapColor, user.CycleOwner, user.Gender);

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

public sealed record RegisterRequest(string Email, string Password, string DisplayName, DateOnly DateOfBirth, Gender Gender = Gender.PreferNotToSay);
public sealed record LoginRequest(string Email, string Password);
public sealed record AuthResponse(string AccessToken, AuthUserDto User);
public sealed record UpdateProfileRequest(string DisplayName, string? AvatarUrl, string? MapColor);
public sealed record AuthUserDto(Guid Id, string Email, string DisplayName, Guid? CoupleId, string TimeZoneId, string? AvatarUrl, string MapColor, bool CycleOwner, Gender Gender);
