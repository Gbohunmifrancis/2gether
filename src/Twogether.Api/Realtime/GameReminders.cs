using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Api.Hubs;
using Twogether.Api.Presence;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Api.Realtime;

/// <summary>Game pushes beyond the "your turn" nudge (docs/games.md).</summary>
public static class GameReminders
{
    /// <summary>
    /// Heart to heart: the score just made finished <paramref name="actorId"/>'s five rounds while their partner still
    /// has some to play. The partner gets one push saying so, unless they're in the app (a couple hub connection). It
    /// is sent at most once per duel: that moment comes once (the second to finish ends the duel), and a reminder
    /// already sent for this duel is never sent again.
    /// </summary>
    public static async Task DuelFinishedAsync(IApplicationDbContext db, IHubContext<CoupleHub> hub, CouplePresenceTracker? presence, IDateTimeProvider clock,
        GameSession session, string stateBefore, Guid actorId, CancellationToken cancellationToken)
    {
        if (session.GameType != GameType.SkillDuel || session.Status != GameSessionStatus.InProgress) return;
        if (!SkillDuelRules.FinishedFirst(stateBefore, session.StateJson, actorId)) return;
        var partnerId = await db.Users.AsNoTracking().Where(user => user.CoupleId == session.CoupleId && user.Id != actorId).Select(user => (Guid?)user.Id).SingleOrDefaultAsync(cancellationToken);
        if (partnerId is not { } partner || presence?.IsOnline(session.CoupleId, partner) == true) return;
        var data = JsonSerializer.Serialize(new { sessionId = session.Id, reminder = "duelFinished" });
        if (await db.Notifications.AnyAsync(item => item.UserId == partner && item.Type == NotificationType.GameTurn && item.DataJson == data, cancellationToken)) return;
        var actor = await db.Users.AsNoTracking().Where(user => user.Id == actorId).Select(user => user.DisplayName).SingleAsync(cancellationToken);
        Notification notification;
        using (Loc.Use(await db.LanguageOfAsync(partner, cancellationToken)))
            notification = Notification.Create(partner, session.CoupleId, NotificationType.GameTurn, Loc.T("Your turn in Heart to heart"),
                Loc.T("{name} has played all five rounds. Play yours to see who wins!", ("name", actor)), data, clock.UtcNow);
        db.Notifications.Add(notification);
        await db.SaveChangesAsync(cancellationToken);
        await hub.Clients.Group(CoupleHub.UserGroupName(partner)).SendAsync("NotificationCreated", NotificationController.ToDto(notification), cancellationToken);
    }
}
