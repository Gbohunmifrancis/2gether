using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Notifications;
using Twogether.Api.Common;
using Twogether.Core.Entities;

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

    [HttpGet("preferences")]
    public async Task<ActionResult<NotificationPreferencesDto>> Preferences(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var timeZoneId = await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => user.TimeZoneId).SingleOrDefaultAsync(cancellationToken);
        if (timeZoneId is null) return NotFound();
        var preference = await db.NotificationPreferences.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == userId, cancellationToken);
        return Ok(NotificationPreferenceRules.ToDto(preference, timeZoneId, clock.UtcNow));
    }

    [HttpPut("preferences")]
    public async Task<IActionResult> UpdatePreferences(UpdateNotificationPreferencesRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var valid = NotificationPreferenceRules.Validate(request);
        if (valid.IsFailure) return HandleResult(valid);
        var userId = currentUser.UserId.Value;
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == userId, cancellationToken);
        if (user is null) return NotFound();

        var now = clock.UtcNow;
        if (valid.Value.TimeZoneId is { } zone && zone != user.TimeZoneId) user.SetTimeZone(zone, now);
        for (var attempt = 0; ; attempt++)
        {
            var preference = await db.NotificationPreferences.SingleOrDefaultAsync(item => item.UserId == userId, cancellationToken);
            if (preference is null)
            {
                preference = NotificationPreference.Create(userId, now);
                db.NotificationPreferences.Add(preference);
            }
            preference.Update(request.Messages, request.Games, request.Exercise, request.Cycle, request.Pregnancy, request.DailyQuestions,
                request.Album ?? preference.AlbumEnabled, request.QuietHoursEnabled, valid.Value.QuietStart, valid.Value.QuietEnd, now);
            try
            {
                await db.SaveChangesAsync(cancellationToken);
                return Ok(NotificationPreferenceRules.ToDto(preference, user.TimeZoneId, now));
            }
            catch (DbUpdateException exception) when (attempt == 0 && exception.InnerException is PostgresException { SqlState: "23505" })
            {
                // Two devices saved for the first time at once: the other inserted the row, so this save becomes an update.
                foreach (var entry in db.NotificationPreferences.Local.ToList()) ((DbContext)db).Entry(entry).State = EntityState.Detached;
            }
        }
    }

    public static NotificationDto ToDto(Twogether.Core.Entities.Notification item) => new(item.Id, item.UserId, item.Type, item.Title, item.Body, item.DataJson, item.CreatedAtUtc, item.ReadAtUtc);
}
