using System.Collections.Concurrent;
using Twogether.Core.Enums;

namespace Twogether.Api.Realtime;

/// <summary>
/// Live ink (docs/games.md): the stroke a partner is drawing on a tic-tac-toe square, relayed over the couple hub to
/// the other partner only while it's drawn. Never stored: the mark itself still goes in with the move. Messages are
/// tiny (a square and a few points as fractions of it); the app sends at most <see cref="AppRate"/> a second.
/// </summary>
public sealed record GameInkDto(Guid SessionId, Guid UserId, int Cell, int Stroke, float[] Points);

/// <summary>
/// Who may ink where, remembered for a few seconds so a stroke's twenty messages a second don't each look the game up,
/// and a cap on how fast one person's ink is relayed.
/// </summary>
public sealed class GameInkGate
{
    /// <summary>The games with live ink.</summary>
    public static readonly IReadOnlySet<GameType> Games = new HashSet<GameType> { GameType.TicTacToe };
    /// <summary>How often the app sends ink while a finger moves.</summary>
    public const int AppRate = 20;
    /// <summary>Messages a second relayed for one person; more are dropped.</summary>
    public const int MaxPerSecond = 30;
    /// <summary>Numbers in one message: up to 32 points, each x then y.</summary>
    public const int MaxNumbers = 64;
    /// <summary>The highest square: a 3×3 board. -1 clears the sender's live ink.</summary>
    public const int MaxCell = 8;
    public static readonly TimeSpan Remember = TimeSpan.FromSeconds(3);

    private readonly ConcurrentDictionary<Guid, Window> windows = new();
    private readonly ConcurrentDictionary<(Guid UserId, Guid SessionId), Pass> passes = new();

    /// <summary>A message's shape: a square (or -1), a stroke number, and an even count of coordinates from 0 to 1.</summary>
    public static bool IsValid(int cell, int stroke, float[]? points)
    {
        if (cell < -1 || cell > MaxCell || stroke < 0 || points is null || points.Length > MaxNumbers || points.Length % 2 != 0) return false;
        foreach (var value in points)
            if (!float.IsFinite(value) || value < 0 || value > 1) return false;
        return true;
    }

    /// <summary>Counts a message against the person's rate; false when they're over it.</summary>
    public bool TryTake(Guid userId, DateTime utcNow)
    {
        var second = utcNow.Ticks / TimeSpan.TicksPerSecond;
        var window = windows.AddOrUpdate(userId, _ => new Window(second, 1), (_, current) => current.Second == second ? current with { Count = current.Count + 1 } : new Window(second, 1));
        return window.Count <= MaxPerSecond;
    }

    /// <summary>The partner to relay to, when this person was cleared to ink on this game in the last few seconds.</summary>
    public Guid? Known(Guid userId, Guid sessionId, DateTime utcNow)
        => passes.TryGetValue((userId, sessionId), out var pass) && pass.UntilUtc > utcNow ? pass.PartnerId : null;

    public void Allow(Guid userId, Guid sessionId, Guid partnerId, DateTime utcNow)
    {
        if (passes.Count > 1000)
            foreach (var stale in passes.Where(item => item.Value.UntilUtc <= utcNow).Select(item => item.Key).ToList()) passes.TryRemove(stale, out _);
        if (windows.Count > 1000)
        {
            var second = utcNow.Ticks / TimeSpan.TicksPerSecond;
            foreach (var stale in windows.Where(item => item.Value.Second < second).Select(item => item.Key).ToList()) windows.TryRemove(stale, out _);
        }
        passes[(userId, sessionId)] = new Pass(partnerId, utcNow + Remember);
    }

    private sealed record Window(long Second, int Count);
    private sealed record Pass(Guid PartnerId, DateTime UntilUtc);
}
