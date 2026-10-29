using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// A short match of turn-by-turn games on a grid, shared by tic-tac-toe and connect four (docs/games.md). Partners
/// alternate who starts each game (game 0: player 0); a game won scores a point, a draw scores none. The match ends
/// when someone reaches <see cref="WinsNeeded"/> or after <see cref="GamesInMatch"/> games, most wins taking it.
/// </summary>
/// <remarks>
/// After a game ends the finished board stays in the state (phase <c>gameOver</c>, with its winning line) so both
/// partners see how it went. The next game starts with the next starter's first move: there is no separate "next"
/// action, so nobody has to press anything for the match to carry on.
/// </remarks>
public abstract class GridMatchRules : IGameRules
{
    public const string PlayingPhase = "playing";
    public const string GameOverPhase = "gameOver";
    public const string MatchOverPhase = "matchOver";

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly (int Columns, int Rows)[] Directions = [(1, 0), (0, 1), (1, 1), (1, -1)];

    public abstract GameType Type { get; }
    protected abstract int Columns { get; }
    protected abstract int Rows { get; }
    /// <summary>How many in a row win a game.</summary>
    protected abstract int InARow { get; }
    protected abstract int GamesInMatch { get; }
    protected abstract int WinsNeeded { get; }
    /// <summary>The one move this game takes: <c>place</c> or <c>drop</c>.</summary>
    protected abstract string MoveAction { get; }

    /// <summary>The cell a move at <paramref name="index"/> fills, or <see cref="InvalidOperationException"/> when it can't.</summary>
    protected abstract int Land(GridMatchState state, int? index);

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var state = new GridMatchState
        {
            Players = players.ToList(),
            Columns = Columns,
            Rows = Rows,
            Cells = Enumerable.Repeat<Guid?>(null, Columns * Rows).ToList(),
            TotalGames = GamesInMatch,
            WinsNeeded = WinsNeeded,
            Scores = players.ToDictionary(player => player, _ => 0),
        };
        return new GameEngineResult(Write(state), state.Players[0], null, false);
    }

    public GameEngineResult Apply(GameMove move)
    {
        if (!string.Equals(move.Action, MoveAction, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("That move isn't part of this game."));
        var state = Read(move.StateJson);
        if (state.Phase == MatchOverPhase) throw new InvalidOperationException(Loc.T("This match is over."));
        if (state.Players[state.TurnIndex] != move.Actor) throw new InvalidOperationException(Loc.T("It's not your turn yet."));

        if (state.Phase == GameOverPhase)
        {
            // The next starter's first move clears the finished board.
            state.Game++;
            state.Cells = Enumerable.Repeat<Guid?>(null, state.Columns * state.Rows).ToList();
            state.Line = null;
            state.LastMove = null;
            state.Phase = PlayingPhase;
        }

        var cell = Land(state, move.Index);
        state.Cells[cell] = move.Actor;
        state.LastMove = cell;

        var line = LineThrough(state, cell);
        if (line is not null)
        {
            state.Line = line;
            state.Scores[move.Actor] = state.Scores.GetValueOrDefault(move.Actor) + 1;
            state.Results.Add(move.Actor);
            return EndGame(state);
        }
        if (state.Cells.All(owner => owner is not null))
        {
            state.Results.Add(null);
            state.Draws++;
            return EndGame(state);
        }
        state.TurnIndex = (state.TurnIndex + 1) % state.Players.Count;
        return new GameEngineResult(Write(state), state.Players[state.TurnIndex], null, false);
    }

    /// <summary>Nothing in a grid game is secret: both partners see the whole board.</summary>
    public string ToPublicState(string stateJson, Guid? viewer) => Write(Read(stateJson));

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Results.Count, state.WinnerUserId);
    }

    private static GameEngineResult EndGame(GridMatchState state)
    {
        var finished = state.Results.Count >= state.TotalGames || state.Scores.Values.Any(wins => wins >= state.WinsNeeded);
        if (!finished)
        {
            state.Phase = GameOverPhase;
            state.TurnIndex = (state.Game + 1) % state.Players.Count;
            return new GameEngineResult(Write(state), state.Players[state.TurnIndex], null, false);
        }
        state.Phase = MatchOverPhase;
        var best = state.Scores.Values.Max();
        var leaders = state.Scores.Where(item => item.Value == best).Select(item => item.Key).ToList();
        state.WinnerUserId = leaders.Count == 1 ? leaders[0] : null;
        return new GameEngineResult(Write(state), null, null, true);
    }

    /// <summary>Every cell of a run of <see cref="InARow"/> or more through <paramref name="cell"/>, in board order; null if none.</summary>
    private List<int>? LineThrough(GridMatchState state, int cell)
    {
        var owner = state.Cells[cell];
        var column = cell % state.Columns;
        var row = cell / state.Columns;
        var line = new SortedSet<int>();
        foreach (var (dc, dr) in Directions)
        {
            var run = new List<int> { cell };
            foreach (var sign in new[] { 1, -1 })
            {
                for (var step = 1; ; step++)
                {
                    var c = column + sign * dc * step;
                    var r = row + sign * dr * step;
                    if (c < 0 || c >= state.Columns || r < 0 || r >= state.Rows || state.Cells[r * state.Columns + c] != owner) break;
                    run.Add(r * state.Columns + c);
                }
            }
            if (run.Count >= InARow) line.UnionWith(run);
        }
        return line.Count > 0 ? line.ToList() : null;
    }

    private static GridMatchState Read(string json)
        => JsonSerializer.Deserialize<GridMatchState>(json, JsonOptions) ?? throw new InvalidOperationException("Game state is invalid.");

    private static string Write(GridMatchState state) => JsonSerializer.Serialize(state, JsonOptions);
}

/// <summary>The whole state of a grid match; it is also what both partners see.</summary>
public sealed class GridMatchState
{
    public List<Guid> Players { get; set; } = [];
    public int Columns { get; set; }
    public int Rows { get; set; }
    /// <summary>Row by row from the top: who holds each cell, or null.</summary>
    public List<Guid?> Cells { get; set; } = [];
    /// <summary>The game being played, or just finished, from 0.</summary>
    public int Game { get; set; }
    public int TotalGames { get; set; }
    public int WinsNeeded { get; set; }
    public string Phase { get; set; } = GridMatchRules.PlayingPhase;
    public int TurnIndex { get; set; }
    public int? LastMove { get; set; }
    /// <summary>The cells of the winning line once a game is won, for the app to highlight.</summary>
    public List<int>? Line { get; set; }
    /// <summary>Games won per player.</summary>
    public Dictionary<Guid, int> Scores { get; set; } = [];
    public int Draws { get; set; }
    /// <summary>Each finished game's winner, or null for a draw.</summary>
    public List<Guid?> Results { get; set; } = [];
    public Guid? WinnerUserId { get; set; }
}
