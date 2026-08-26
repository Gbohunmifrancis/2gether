using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Hubs;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class MessageController(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, IHubContext<CoupleHub> hub) : ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<MessageDto>>> List([FromQuery] int limit = 50, CancellationToken cancellationToken = default)
    {
        if (!currentUser.CoupleId.HasValue) return Ok(Array.Empty<MessageDto>());
        limit = Math.Clamp(limit, 1, 100);
        var messages = await db.Messages.AsNoTracking().Where(message => message.CoupleId == currentUser.CoupleId.Value && message.DeletedAtUtc == null).OrderByDescending(message => message.CreatedAtUtc).Take(limit).ToListAsync(cancellationToken);
        return Ok(messages.OrderBy(message => message.CreatedAtUtc).Select(ToDto).ToList());
    }

    [HttpPost]
    public async Task<ActionResult<MessageDto>> Send(SendMessageRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return BadRequest(new ApiError("message.no_couple", "Link a partner before sending messages."));
        var linkedCouple = await db.Couples.AsNoTracking().AnyAsync(couple => couple.Id == currentUser.CoupleId.Value && couple.Status == Twogether.Core.Enums.CoupleStatus.Active, cancellationToken);
        if (!linkedCouple) return BadRequest(new ApiError("message.no_partner", "Link a partner before sending messages."));
        if (string.IsNullOrWhiteSpace(request.Body) || request.Body.Length > 4000) return UnprocessableEntity(new ApiError("message.invalid_body", "Message must be between 1 and 4000 characters."));
        var message = Message.Create(currentUser.CoupleId.Value, currentUser.UserId.Value, request.Body.Trim(), request.IsPrivate, clock.UtcNow);
        db.Messages.Add(message);
        var senderName = await db.Users.AsNoTracking().Where(item => item.Id == currentUser.UserId.Value).Select(item => item.DisplayName).SingleAsync(cancellationToken);
        var partnerId = await db.Users.AsNoTracking().Where(item => item.CoupleId == currentUser.CoupleId.Value && item.Id != currentUser.UserId.Value).Select(item => (Guid?)item.Id).SingleOrDefaultAsync(cancellationToken);
        Notification? notification = null;
        if (partnerId.HasValue)
        {
            notification = Notification.Create(partnerId.Value, message.CoupleId, NotificationType.Message, request.IsPrivate ? "A private message" : $"New message from {senderName}", request.IsPrivate ? $"{senderName} sent something just for you." : message.Body[..Math.Min(message.Body.Length, 140)], null, clock.UtcNow);
            db.Notifications.Add(notification);
        }
        await db.SaveChangesAsync(cancellationToken);
        var result = ToDto(message);
        await hub.Clients.Group(CoupleHub.GroupName(message.CoupleId)).SendAsync("MessageCreated", result, cancellationToken);
        if (notification is not null) await hub.Clients.Group(CoupleHub.UserGroupName(notification.UserId)).SendAsync("NotificationCreated", NotificationController.ToDto(notification), cancellationToken);
        return Ok(result);
    }

    [HttpPut("{messageId:guid}")]
    public async Task<ActionResult<MessageDto>> Edit(Guid messageId, SendMessageRequest request, CancellationToken cancellationToken)
    {
        var message = await OwnedMessage(messageId, cancellationToken);
        if (message is null) return NotFound();
        if (message.SenderUserId != currentUser.UserId) return Forbid();
        if (string.IsNullOrWhiteSpace(request.Body) || request.Body.Length > 4000) return UnprocessableEntity(new ApiError("message.invalid_body", "Message must be between 1 and 4000 characters."));
        message.Edit(request.Body.Trim(), clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        var result = ToDto(message);
        await hub.Clients.Group(CoupleHub.GroupName(message.CoupleId)).SendAsync("MessageUpdated", result, cancellationToken);
        return Ok(result);
    }

    [HttpDelete("{messageId:guid}")]
    public async Task<IActionResult> Delete(Guid messageId, CancellationToken cancellationToken)
    {
        var message = await OwnedMessage(messageId, cancellationToken);
        if (message is null) return NotFound();
        if (message.SenderUserId != currentUser.UserId) return Forbid();
        message.Delete(clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        await hub.Clients.Group(CoupleHub.GroupName(message.CoupleId)).SendAsync("MessageDeleted", message.Id, cancellationToken);
        return NoContent();
    }

    private Task<Message?> OwnedMessage(Guid messageId, CancellationToken cancellationToken)
        => currentUser.CoupleId.HasValue
            ? db.Messages.SingleOrDefaultAsync(message => message.Id == messageId && message.CoupleId == currentUser.CoupleId.Value && message.DeletedAtUtc == null, cancellationToken)
            : Task.FromResult<Message?>(null);

    private static MessageDto ToDto(Message message) => new(message.Id, message.CoupleId, message.SenderUserId, message.Body, message.IsPrivate, message.CreatedAtUtc, message.EditedAtUtc);
}

public sealed record SendMessageRequest(string Body, bool IsPrivate = false);
public sealed record MessageDto(Guid Id, Guid CoupleId, Guid SenderUserId, string Body, bool IsPrivate, DateTime CreatedAtUtc, DateTime? EditedAtUtc);
