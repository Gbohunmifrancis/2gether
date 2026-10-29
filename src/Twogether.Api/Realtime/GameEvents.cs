using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Api.Hubs;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Shared.Localization;

namespace Twogether.Api.Realtime;

/// <summary>
/// Game updates go to each partner on their own, each with the state as they may see it (in draw and guess the word
/// is the drawer's alone; an answer stays hidden until both are in) and in their own language (docs/games.md).
/// </summary>
public static class GameEvents
{
    public sealed record Player(Guid Id, string Language);

    public static Task<List<Player>> PlayersAsync(IApplicationDbContext db, Guid coupleId, CancellationToken cancellationToken)
        => db.Users.AsNoTracking().Where(user => user.CoupleId == coupleId).Select(user => new Player(user.Id, user.Language)).ToListAsync(cancellationToken);

    public static async Task SendAsync(IHubContext<CoupleHub> hub, IGameEngine engine, GameSession session, IEnumerable<Player> players, string eventName, CancellationToken cancellationToken)
    {
        foreach (var player in players)
        {
            GameSessionDto dto;
            using (Loc.Use(player.Language)) dto = GameController.ToDto(session, engine, player.Id);
            await hub.Clients.Group(CoupleHub.UserGroupName(player.Id)).SendAsync(eventName, dto, cancellationToken);
        }
    }
}
