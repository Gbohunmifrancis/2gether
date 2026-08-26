using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class NotificationController(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock) : Common.ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<NotificationDto>>> List(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var items = await db.Notifications.AsNoTracking()
            .Where(item => item.UserId == currentUser.UserId.Value)
            .OrderByDescending(item => item.CreatedAtUtc)
            .Take(50)
            .ToListAsync(cancellationToken);
        return Ok(items.Select(ToDto).ToList());
    }

    [HttpPost("{notificationId:guid}/read")]
    public async Task<IActionResult> MarkRead(Guid notificationId, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var item = await db.Notifications.SingleOrDefaultAsync(candidate => candidate.Id == notificationId && candidate.UserId == currentUser.UserId.Value, cancellationToken);
        if (item is null) return NotFound();
        item.MarkRead(clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return NoContent();
    }

    [HttpPost("read-all")]
    public async Task<IActionResult> MarkAllRead(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var items = await db.Notifications.Where(item => item.UserId == currentUser.UserId.Value && item.ReadAtUtc == null).ToListAsync(cancellationToken);
        foreach (var item in items) item.MarkRead(clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return NoContent();
    }

    public static NotificationDto ToDto(Twogether.Core.Entities.Notification item) => new(item.Id, item.UserId, item.Type, item.Title, item.Body, item.DataJson, item.CreatedAtUtc, item.ReadAtUtc);
}
