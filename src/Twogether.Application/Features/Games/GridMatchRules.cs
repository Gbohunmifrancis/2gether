using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// A short match of turn-by-turn games on a grid: connect four (docs/games.md). Tic-tac-toe played this way too until it
/// went live (<see cref="TicTacToeRules"/>), and its state still reads as this one's. Partners
/// alternate who starts each game (game 0: player 0, who is the couple's first player for this match,
/// <see cref="GameFirstPlayer"/>; game 1: the other; and so on); a game won scores a point, a draw scores none. The
/// match ends when someone reaches <see cref="WinsNeeded"/> or after <see cref="GamesInMatch"/> games, most wins taking it.
/// </summary>
/// <remarks>
/// After a game ends the finished board stays in the state (phase <c>gameOver</c>, with its winning line) for a beat,
/// <see cref="NextGameDelayMs"/>, so both partners see how it went; the beat is the session's deadline
/// (<c>nextGameAtUtc</c> in the state), and when it passes the server starts the next game on a fresh board (the timeout
/// worker), with nobody pressing anything. The next starter may make the first move up to <see cref="EarlyStartMs"/>
/// early (phone clocks differ a little), and a move that arrives after the beat is played on the fresh board. A board
/// left over from before the beat existed (no <c>nextGameAtUtc</c>) is cleared by the next starter's first move, as then.
/// </remarks>
public abstract class GridMatchRules : IGameRules
{
    public const string PlayingPhase = "playing";
    public const string GameOverPhase = "gameOver";
    public const string MatchOverPhase = "matchOver";
    /// <summary>The beat between a finished game and the next one's fresh board.</summary>
    public const int NextGameDelayMs = 1500;
    /// <summary>A first move for the next game may arrive this much before the beat is over.</summary>
    public const int EarlyStartMs = 250;

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

    /// <summary>The beat between games is a deadline: the server starts the next game when it passes.</summary>
    public bool Timed => true;

    /// <summary>The turns themselves have no clock: a couple may take them hours apart (and get a "your turn" push).</summary>
    public bool TurnClock => false;

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
        return Result(state);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (string.Equals(move.Action, GameEngine.TimeoutAction, StringComparison.OrdinalIgnoreCase)) return Timeout(state, move);
        if (!string.Equals(move.Action, MoveAction, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("That move isn't part of this game."));
        if (state.Phase == MatchOverPhase) throw new InvalidOperationException(Loc.T("This match is over."));
        if (state.Players[state.TurnIndex] != move.Actor) throw new InvalidOperationException(Loc.T("It's not your turn yet."));

        if (state.Phase == GameOverPhase)
        {
            // The next starter's first move, once the beat is (nearly) over, clears the finished board.
            if (state.NextGameAtUtc is { } at && move.UtcNow < at.AddMilliseconds(-EarlyStartMs))
                throw new InvalidOperationException(Loc.T("Get ready, the next game is about to start."));
            NextGame(state);
        }
        return Play(state, move);
    }

    /// <summary>
    /// The beat after a game is over: the next game starts on a fresh board. A move that arrived after the beat comes
    /// here too (the server plays the timeout for a late move, keeping its column): the next starter's is played on the
    /// fresh board, anyone else's dropped.
    /// </summary>
    private GameEngineResult Timeout(GridMatchState state, GameMove move)
    {
        if (state.Phase != GameOverPhase || state.NextGameAtUtc is { } at && move.UtcNow < at.AddMilliseconds(-EarlyStartMs)) return Result(state);
        NextGame(state);
        if (move.Index.HasValue && state.Players[state.TurnIndex] == move.Actor)
        {
            try { return Play(state, move); }
            catch (InvalidOperationException) { /* The late move doesn't fit the fresh board: the board still moves on. */ }
        }
        return Result(state);
    }

    private static void NextGame(GridMatchState state)
    {
        state.Game++;
        state.Cells = Enumerable.Repeat<Guid?>(null, state.Columns * state.Rows).ToList();
        state.Line = null;
        state.LastMove = null;
        state.NextGameAtUtc = null;
        state.Phase = PlayingPhase;
    }

    private GameEngineResult Play(GridMatchState state, GameMove move)
    {
        var cell = Land(state, move.Index);
        state.Cells[cell] = move.Actor;
        state.LastMove = cell;

        var line = LineThrough(state, cell);
        if (line is not null)
        {
            state.Line = line;
            state.Scores[move.Actor] = state.Scores.GetValueOrDefault(move.Actor) + 1;
            state.Results.Add(move.Actor);
            return EndGame(state, move.UtcNow);
        }
        if (state.Cells.All(owner => owner is not null))
        {
            state.Results.Add(null);
            state.Draws++;
            return EndGame(state, move.UtcNow);
        }
        state.TurnIndex = (state.TurnIndex + 1) % state.Players.Count;
        return Result(state);
    }

    /// <summary>Nothing in a grid game is secret: both partners see the whole board.</summary>
    public string ToPublicState(string stateJson, Guid? viewer) => Write(Read(stateJson));

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Results.Count, state.WinnerUserId);
    }

    private static GameEngineResult EndGame(GridMatchState state, DateTime utcNow)
    {
        var finished = state.Results.Count >= state.TotalGames || state.Scores.Values.Any(wins => wins >= state.WinsNeeded);
        if (!finished)
        {
            state.Phase = GameOverPhase;
            // The next game's starter: the partners take turns starting, game by game.
            state.TurnIndex = (state.Game + 1) % state.Players.Count;
            state.NextGameAtUtc = utcNow.AddMilliseconds(NextGameDelayMs);
            return Result(state);
        }
        state.Phase = MatchOverPhase;
        state.NextGameAtUtc = null;
        var best = state.Scores.Values.Max();
        var leaders = state.Scores.Where(item => item.Value == best).Select(item => item.Key).ToList();
        state.WinnerUserId = leaders.Count == 1 ? leaders[0] : null;
        return new GameEngineResult(Write(state), null, null, true);
    }

    /// <summary>Whose turn it is (during the beat: the next game's starter); the deadline is the next game's start while a finished board is up.</summary>
    private static GameEngineResult Result(GridMatchState state)
        => state.Phase == MatchOverPhase
            ? new GameEngineResult(Write(state), null, null, true)
            : new GameEngineResult(Write(state), state.Players[state.TurnIndex], state.Phase == GameOverPhase ? state.NextGameAtUtc : null, false);

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
    /// <summary>While a finished board is up: when the next game starts (server time). Null for a board from before the beat.</summary>
    public DateTime? NextGameAtUtc { get; set; }
}
