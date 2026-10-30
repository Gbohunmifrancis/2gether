using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Enums;
using Twogether.Core.Users;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Account;

/// <summary>The answer to an upload: the new version, to put in the picture's address.</summary>
public sealed record AvatarDto(long AvatarVersion);

/// <summary>A picture ready to send. <see cref="ContentType"/> comes from the bytes themselves.</summary>
public sealed record AvatarPicture(string ContentType, byte[] Bytes, long Version);

public static class AvatarRules
{
    public static Error TooLarge => Error.Validation("avatar.too_large", Loc.T("Profile pictures must be under {kilobytes} KB.", ("kilobytes", AvatarImage.MaxBytes / 1024)));

    /// <summary>
    /// The type the upload says it is must be one we take, and the bytes must really start like a JPEG, PNG or WebP.
    /// Returns the type the bytes are, which is what the picture is later served as.
    /// </summary>
    public static Result<string> Validate(string? contentType, ReadOnlySpan<byte> bytes)
    {
        var declared = contentType?.Split(';')[0].Trim();
        if (!AvatarImage.IsAllowedContentType(declared))
            return Error.Validation("avatar.unsupported_type", Loc.T("Profile pictures must be JPEG, PNG or WebP."));
        if (bytes.Length == 0)
            return Error.Validation("avatar.empty", Loc.T("The picture is empty."));
        if (bytes.Length > AvatarImage.MaxBytes)
            return TooLarge;
        if (AvatarImage.Detect(bytes) is not { } detected)
            return Error.Validation("avatar.not_an_image", Loc.T("That file is not a JPEG, PNG or WebP picture."));
        return detected;
    }
}

/// <summary>
/// Profile pictures, kept in the user's row. A picture is shown only to its owner and to the person they are
/// linked with right now; nobody else, and nobody after a breakup.
/// </summary>
public sealed class AvatarService(IApplicationDbContext db, IDateTimeProvider clock)
{
    public async Task<Result<AvatarDto>> SetAsync(Guid userId, string? contentType, byte[] bytes, CancellationToken cancellationToken)
    {
        var valid = AvatarRules.Validate(contentType, bytes);
        if (valid.IsFailure) return valid.Error;
        var now = clock.UtcNow;
        // One statement, so two uploads at once each get their own version.
        var changed = await db.Users.Where(user => user.Id == userId)
            .ExecuteUpdateAsync(set => set
                .SetProperty(user => user.AvatarBytes, bytes)
                .SetProperty(user => user.AvatarVersion, user => user.AvatarVersion + 1)
                .SetProperty(user => user.UpdatedAtUtc, now), cancellationToken);
        if (changed == 0) return Error.NotFound("avatar.user_not_found", Loc.T("Account not found."));
        var version = await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => user.AvatarVersion).SingleAsync(cancellationToken);
        return new AvatarDto(version);
    }

    /// <summary>Removes the picture. Fine when there is none. False only when the account is gone.</summary>
    public async Task<bool> ClearAsync(Guid userId, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        await db.Users.Where(user => user.Id == userId && user.AvatarBytes != null)
            .ExecuteUpdateAsync(set => set
                .SetProperty(user => user.AvatarBytes, (byte[]?)null)
                .SetProperty(user => user.AvatarVersion, user => user.AvatarVersion + 1)
                .SetProperty(user => user.UpdatedAtUtc, now), cancellationToken);
        return await db.Users.AnyAsync(user => user.Id == userId, cancellationToken);
    }

    /// <summary>
    /// The version of <paramref name="ownerId"/>'s picture if <paramref name="viewerId"/> may see it, else null. Reads
    /// no picture bytes, so an unchanged picture is answered without them. Who the viewer is linked with is read
    /// from the database now, not from anything the client sent.
    /// </summary>
    public Task<long?> VisibleVersionAsync(Guid viewerId, Guid ownerId, CancellationToken cancellationToken)
        => VisibleTo(viewerId, ownerId).Select(user => (long?)user.AvatarVersion).FirstOrDefaultAsync(cancellationToken);

    public async Task<AvatarPicture?> LoadAsync(Guid viewerId, Guid ownerId, CancellationToken cancellationToken)
    {
        var row = await VisibleTo(viewerId, ownerId).Select(user => new { user.AvatarBytes, user.AvatarVersion }).FirstOrDefaultAsync(cancellationToken);
        if (row?.AvatarBytes is not { } bytes || AvatarImage.Detect(bytes) is not { } contentType) return null;
        return new AvatarPicture(contentType, bytes, row.AvatarVersion);
    }

    private IQueryable<Twogether.Core.Entities.User> VisibleTo(Guid viewerId, Guid ownerId)
        => db.Users.AsNoTracking().Where(owner => owner.Id == ownerId && owner.AvatarBytes != null
            && (owner.Id == viewerId
                || (owner.CoupleId != null
                    && db.Users.Any(viewer => viewer.Id == viewerId && viewer.CoupleId == owner.CoupleId)
                    && db.Couples.Any(couple => couple.Id == owner.CoupleId && couple.Status == CoupleStatus.Active))));
}
