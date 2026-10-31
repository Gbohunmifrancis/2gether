using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http.Headers;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Net.Http.Headers;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Account;
using Twogether.Core.Users;

namespace Twogether.Api.Controllers;

/// <summary>
/// Profile pictures. Only ever the caller's own (upload, remove) or the picture of the caller or their current partner
/// (read): which couple someone is in is read from the database, never taken from the request.
/// </summary>
[Authorize]
[Route("api")]
public sealed class AvatarController(AvatarService avatars, ICurrentUser currentUser) : ApiControllerBase
{
    private const string CacheControl = "private, max-age=86400";

    /// <summary>The body is the picture itself (image/jpeg, image/png or image/webp, up to 400 KB). Answers <c>{ "avatarVersion": n }</c>.</summary>
    [HttpPut("account/avatar")]
    public async Task<IActionResult> Put(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        if (Request.ContentLength > AvatarImage.MaxBytes) return FromError(AvatarRules.TooLarge);

        // Never more than one byte past the limit is read, whatever the client says or streams.
        var buffer = new byte[AvatarImage.MaxBytes + 1];
        var length = 0;
        while (length < buffer.Length)
        {
            var read = await Request.Body.ReadAsync(buffer.AsMemory(length), cancellationToken);
            if (read == 0) break;
            length += read;
        }
        if (length > AvatarImage.MaxBytes) return FromError(AvatarRules.TooLarge);

        return HandleResult(await avatars.SetAsync(currentUser.UserId.Value, Request.ContentType, buffer[..length], cancellationToken));
    }

    [HttpDelete("account/avatar")]
    public async Task<IActionResult> Delete(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        return await avatars.ClearAsync(currentUser.UserId.Value, cancellationToken) ? NoContent() : NotFound();
    }

    /// <summary>The picture of the caller or of the person they are linked with now; 404 for anyone else or when there is none. Cached by version.</summary>
    [HttpGet("users/{userId:guid}/avatar")]
    public async Task<IActionResult> Get(Guid userId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var viewerId = currentUser.UserId.Value;

        var version = await avatars.VisibleVersionAsync(viewerId, userId, cancellationToken);
        if (version is null) return NotFound();
        if (Matches(Request.GetTypedHeaders(), version.Value))
        {
            Response.Headers.CacheControl = CacheControl;
            Response.Headers.ETag = ETag(version.Value).ToString();
            return StatusCode(StatusCodes.Status304NotModified);
        }

        var picture = await avatars.LoadAsync(viewerId, userId, cancellationToken);
        if (picture is null) return NotFound();
        Response.Headers.CacheControl = CacheControl;
        Response.Headers.ETag = ETag(picture.Version).ToString();
        // The bytes are served as the type they really are and are never to be sniffed as anything else.
        Response.Headers.XContentTypeOptions = "nosniff";
        return File(picture.Bytes, picture.ContentType);
    }

    private static EntityTagHeaderValue ETag(long version) => new($"\"{version}\"");

    private static bool Matches(RequestHeaders headers, long version)
        => headers.IfNoneMatch.Any(tag => tag == EntityTagHeaderValue.Any || tag.Compare(ETag(version), useStrongComparison: false));
}
