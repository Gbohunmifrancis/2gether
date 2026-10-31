using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Who goes first in a game played turn by turn (docs/games.md): it alternates across the couple's games of that kind,
/// so the same partner doesn't always start. The setup (made when the game is invited) names the first player
/// (<c>first</c>): the other partner from whoever went first in the couple's previous game of that kind that was
/// played (accepted), or a coin flip for their first ever. <see cref="GameEngine.Start"/> puts that player first in
/// the players, which every game's rules already start with; Snakes and ladders reads it in place of its coin.
/// </summary>
public static class GameFirstPlayer
{
    /// <summary>
    /// The games where going first matters: Guess my answer (the first subject), Memory match, Connect four (the first
    /// game of the match; within it the starter alternates game by game), Truth or dare (the first chooser), Word dash,
    /// Draw and guess (the first drawer) and Snakes and ladders. Games played at once have no first player.
    /// </summary>
    public static readonly IReadOnlySet<GameType> Alternating = new HashSet<GameType>
    {
        GameType.GuessMyAnswer, GameType.MemoryMatch, GameType.ConnectFour, GameType.TruthOrDare,
        GameType.WordDash, GameType.Handwriting, GameType.SnakesAndLadders,
    };

    public static bool Alternates(GameType gameType) => Alternating.Contains(gameType);

    /// <summary>
    /// The first player of a new game: not <paramref name="previousFirst"/> when that is one of the two, otherwise
    /// <paramref name="random"/> (0 or 1; the server's coin when null) picks.
    /// </summary>
    public static Guid Choose(IReadOnlyList<Guid> players, Guid? previousFirst, Func<int, int>? random = null)
    {
        if (players.Count == 0) throw new InvalidOperationException("At least one player is required.");
        if (previousFirst is { } previous && players.Contains(previous) && players.Any(player => player != previous))
            return players.First(player => player != previous);
        return players[players.Count > 1 ? (random ?? RandomNumberGenerator.GetInt32)(players.Count) : 0];
    }

    /// <summary>The first player a setup names, or null (a setup from before, or a game where it doesn't matter).</summary>
    public static Guid? Of(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            using var setup = JsonDocument.Parse(setupJson);
            return setup.RootElement.ValueKind == JsonValueKind.Object && setup.RootElement.TryGetProperty("first", out var first)
                && first.ValueKind == JsonValueKind.String && first.TryGetGuid(out var id) ? id : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>The setup with <paramref name="first"/> named as the first player.</summary>
    public static string With(string setupJson, Guid first)
    {
        var setup = (string.IsNullOrWhiteSpace(setupJson) ? null : JsonNode.Parse(setupJson) as JsonObject) ?? [];
        setup["first"] = first.ToString();
        return setup.ToJsonString();
    }

    /// <summary>The players with the setup's first player at the front; as they are when it names none (or a stranger).</summary>
    public static IReadOnlyList<Guid> Order(IReadOnlyList<Guid> players, string? setupJson)
        => Of(setupJson) is { } first && players.Contains(first) ? [first, .. players.Where(player => player != first)] : players;

    /// <summary>
    /// Who went first in a game already played: the one its setup names or, for a game set up before the setup named
    /// one, the one its state started with (Snakes and ladders' coin, <c>first</c>; otherwise the first of its players,
    /// who always started then). Null when neither says.
    /// </summary>
    public static Guid? FirstOf(string? setupJson, string? stateJson)
    {
        if (Of(setupJson) is { } named) return named;
        if (string.IsNullOrWhiteSpace(stateJson)) return null;
        try
        {
            using var state = JsonDocument.Parse(stateJson);
            if (state.RootElement.ValueKind != JsonValueKind.Object) return null;
            Guid? coin = null, front = null;
            foreach (var property in state.RootElement.EnumerateObject())
            {
                if (property.NameEquals("First") || property.NameEquals("first"))
                    coin = property.Value.ValueKind == JsonValueKind.String && property.Value.TryGetGuid(out var id) ? id : null;
                else if ((property.NameEquals("Players") || property.NameEquals("players")) && property.Value.ValueKind == JsonValueKind.Array
                    && property.Value.GetArrayLength() > 0 && property.Value[0].ValueKind == JsonValueKind.String && property.Value[0].TryGetGuid(out var player))
                    front = player;
            }
            return coin ?? front;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>Who went first in the couple's last game of <paramref name="gameType"/> that was played (accepted); null for their first.</summary>
    public static async Task<Guid?> PreviousAsync(IApplicationDbContext db, Guid coupleId, GameType gameType, CancellationToken cancellationToken)
    {
        if (!Alternates(gameType)) return null;
        var previous = await db.GameSessions.AsNoTracking()
            .Where(session => session.CoupleId == coupleId && session.GameType == gameType && session.StartedAtUtc != null)
            .OrderByDescending(session => session.StartedAtUtc)
            .Select(session => new { session.SetupJson, session.StateJson })
            .FirstOrDefaultAsync(cancellationToken);
        return previous is null ? null : FirstOf(previous.SetupJson, previous.StateJson);
    }
}
