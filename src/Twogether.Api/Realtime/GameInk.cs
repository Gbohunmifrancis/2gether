using System.Collections.Concurrent;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.Api.Realtime;

/// <summary>
/// Live ink (docs/games.md): the stroke a partner is drawing, relayed over the couple hub to the other partner only while
/// it's drawn. In Tic-tac-toe it's a mark on a square; in Draw and guess the drawer's stroke on the canvas, so the
/// guesser watches the drawing appear. Never stored: the mark or stroke itself still goes in with the move. Messages
/// are tiny (a place and a few points as fractions of the square or canvas, never a word); the app sends at most
/// <see cref="AppRate"/> a second.
/// </summary>
public sealed record GameInkDto(Guid SessionId, Guid UserId, int Cell, int Stroke, float[] Points);

/// <summary>
/// Where a game takes live ink: <see cref="MaxCell"/> is the highest place a stroke may be on (a square in Tic-tac-toe,
/// the stroke's place in the drawing in Draw and guess); with <see cref="DrawerOnly"/> only the player whose turn it is
/// (Draw and guess's drawer) may ink, never the other.
/// </summary>
public sealed record GameInkRules(int MaxCell, bool DrawerOnly);

/// <summary>
/// Who may ink where, remembered for a few seconds so a stroke's twenty messages a second don't each look the game up,
/// and a cap on how fast one person's ink is relayed.
/// </summary>
public sealed class GameInkGate
{
    /// <summary>The games with live ink, and their rules.</summary>
    public static readonly IReadOnlyDictionary<GameType, GameInkRules> Games = new Dictionary<GameType, GameInkRules>
    {
        // The nine squares, row by row; both partners draw at once.
        [GameType.TicTacToe] = new(8, DrawerOnly: false),
        // The place the stroke will take in the drawing; only the round's drawer draws.
        [GameType.Handwriting] = new(DrawAndGuessRules.MaxStrokesPerRound - 1, DrawerOnly: true),
    };

    /// <summary>How often the app sends ink while a finger moves.</summary>
    public const int AppRate = 20;
    /// <summary>Messages a second relayed for one person; more are dropped.</summary>
    public const int MaxPerSecond = 30;
    /// <summary>Numbers in one message: up to 32 points, each x then y.</summary>
    public const int MaxNumbers = 64;
    public static readonly TimeSpan Remember = TimeSpan.FromSeconds(3);

    private static readonly int HighestCell = Games.Values.Max(rules => rules.MaxCell);

    private readonly ConcurrentDictionary<Guid, Window> windows = new();
    private readonly ConcurrentDictionary<(Guid UserId, Guid SessionId), Pass> passes = new();

    /// <summary>
    /// A message's shape, whatever the game: a place (or -1 to un-draw), a stroke number, and an even count of
    /// coordinates from 0 to 1. <see cref="Fits"/> then checks the place against the game's own board.
    /// </summary>
    public static bool IsValid(int cell, int stroke, float[]? points)
    {
        if (cell < -1 || cell > HighestCell || stroke < 0 || points is null || points.Length > MaxNumbers || points.Length % 2 != 0) return false;
        foreach (var value in points)
            if (!float.IsFinite(value) || value < 0 || value > 1) return false;
        return true;
    }

    /// <summary>Whether <paramref name="cell"/> is a place on <paramref name="game"/>'s board (or -1).</summary>
    public static bool Fits(GameType game, int cell) => Games.TryGetValue(game, out var rules) && cell >= -1 && cell <= rules.MaxCell;

    /// <summary>Counts a message against the person's rate; false when they're over it.</summary>
    public bool TryTake(Guid userId, DateTime utcNow)
    {
        var second = utcNow.Ticks / TimeSpan.TicksPerSecond;
        var window = windows.AddOrUpdate(userId, _ => new Window(second, 1), (_, current) => current.Second == second ? current with { Count = current.Count + 1 } : new Window(second, 1));
        return window.Count <= MaxPerSecond;
    }

    /// <summary>The partner to relay to and the game, when this person was cleared to ink on it in the last few seconds.</summary>
    public (Guid PartnerId, GameType Game)? Known(Guid userId, Guid sessionId, DateTime utcNow)
        => passes.TryGetValue((userId, sessionId), out var pass) && pass.UntilUtc > utcNow ? (pass.PartnerId, pass.Game) : null;

    public void Allow(Guid userId, Guid sessionId, Guid partnerId, GameType game, DateTime utcNow)
    {
        if (passes.Count > 1000)
            foreach (var stale in passes.Where(item => item.Value.UntilUtc <= utcNow).Select(item => item.Key).ToList()) passes.TryRemove(stale, out _);
        if (windows.Count > 1000)
        {
            var second = utcNow.Ticks / TimeSpan.TicksPerSecond;
            foreach (var stale in windows.Where(item => item.Value.Second < second).Select(item => item.Key).ToList()) windows.TryRemove(stale, out _);
        }
        passes[(userId, sessionId)] = new Pass(partnerId, game, utcNow + Remember);
    }

    /// <summary>
    /// Forgets who may ink on a game, so the next message looks it up again. Called when the turn moves on (a new round
    /// of Draw and guess makes the drawer the guesser, who may no longer ink) or the game ends.
    /// </summary>
    public void Forget(Guid sessionId)
    {
        foreach (var key in passes.Keys.Where(key => key.SessionId == sessionId).ToList()) passes.TryRemove(key, out _);
    }

    private sealed record Window(long Second, int Count);
    private sealed record Pass(Guid PartnerId, GameType Game, DateTime UntilUtc);
}
