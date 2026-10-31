using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Tic-tac-toe, played at once (docs/games.md): no turns, both partners mark squares as fast as they like, and a
/// square goes to whoever's move reaches the server first. After each of your own marks your pen needs
/// <see cref="CooldownMs"/> to refill (the ink drying), timed by the server. Three in a row wins a game, a full board
/// is a draw; <see cref="NextGameDelayMs"/> after a game ends the next starts on a fresh board (the session's
/// deadline, so the timeout worker starts it even if nobody moves). A match is up to five games, first to three wins.
/// </summary>
/// <remarks>
/// The marks are hand drawn: <c>place</c> may carry the strokes of the mark (<c>value</c>
/// <c>{"game":n,"strokes":[[[x,y],...],...]}</c>, coordinates as fractions of the square), and <c>ink</c> adds a
/// stroke or two to your own newest mark while its ink is wet (the second line of an X). <c>game</c> names the game the
/// move was made on, so a move that crossed the end of a game is never played on the next board. Moves may cross in
/// flight (<see cref="MovesMayCross"/>): each is judged against the board as it is when it arrives.
/// The state is a superset of the turn-by-turn grid state (<see cref="GridMatchState"/>) the game had before, so
/// finished turn-by-turn games still read, and one still open carries on under these rules.
/// </remarks>
public sealed class TicTacToeRules : IGameRules
{
    public const string PlayingPhase = GridMatchRules.PlayingPhase;
    public const string GameOverPhase = GridMatchRules.GameOverPhase;
    public const string MatchOverPhase = GridMatchRules.MatchOverPhase;

    /// <summary>How long a pen takes to refill after its own mark.</summary>
    public const int CooldownMs = 650;
    /// <summary>
    /// Network jitter forgiven on the cooldown: the phone waits the full <see cref="CooldownMs"/> from when it sent the
    /// last mark, but two moves can arrive closer together than they were sent.
    /// </summary>
    public const int CooldownJitterMs = 100;
    /// <summary>The beat between a finished game and the next one's fresh board.</summary>
    public const int NextGameDelayMs = 1500;
    /// <summary>A first move for the next game may arrive this much before the beat is over (phone clocks differ a little).</summary>
    public const int EarlyStartMs = 250;
    /// <summary>How long after a mark its owner may still add strokes to it with <c>ink</c>.</summary>
    public const int WetInkMs = 2000;
    public const int MaxStrokesPerMark = 3;
    public const int MaxPointsPerStroke = 64;

    public const string PlaceAction = "place";
    public const string InkAction = "ink";

    private const int Size = 3;
    private const int GamesInMatch = 5;
    private const int WinsNeeded = 3;

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly int[][] Lines =
    [
        [0, 1, 2], [3, 4, 5], [6, 7, 8],
        [0, 3, 6], [1, 4, 7], [2, 5, 8],
        [0, 4, 8], [2, 4, 6],
    ];

    public GameType Type => GameType.TicTacToe;
    public bool AnyoneMayAct => true;
    /// <summary>The beat between games is a deadline: the server starts the next game when it passes.</summary>
    public bool Timed => true;
    public bool MovesMayCross => true;

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var state = new TicTacToeState
        {
            Players = players.ToList(),
            Columns = Size,
            Rows = Size,
            Cells = EmptyBoard(),
            Marks = EmptyMarks(),
            TotalGames = GamesInMatch,
            WinsNeeded = WinsNeeded,
            Scores = players.ToDictionary(player => player, _ => 0),
        };
        return Result(state);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Phase == MatchOverPhase) throw new InvalidOperationException(Loc.T("This match is over."));
        var action = move.Action.Trim().ToLowerInvariant();
        if (action == GameEngine.TimeoutAction) return Timeout(state, move);
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You're not playing this game."));
        switch (action)
        {
            case PlaceAction:
                var claim = ParseMark(move.Value, allowEmpty: true);
                if (state.Phase == GameOverPhase) StartNextGameFor(state, claim.Game, move.UtcNow);
                Place(state, move.Actor, move.Index, claim, move.UtcNow);
                return Result(state);
            case InkAction:
                AddInk(state, move.Actor, move.Index, ParseMark(move.Value, allowEmpty: false), move.UtcNow);
                return Result(state);
            default:
                throw new InvalidOperationException(Loc.T("That move isn't part of this game."));
        }
    }

    /// <summary>Nothing in tic-tac-toe is secret: both partners see the whole board.</summary>
    public string ToPublicState(string stateJson, Guid? viewer) => Write(Read(stateJson));

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Results.Count, state.WinnerUserId);
    }

    /// <summary>
    /// The beat after a game is over: start the next one. A player's move that arrived after the beat comes here too
    /// (the server plays the timeout for a late move, keeping the move's square and strokes); it is played on the
    /// fresh board when it was made for that board, and dropped otherwise.
    /// </summary>
    private static GameEngineResult Timeout(TicTacToeState state, GameMove move)
    {
        if (state.Phase != GameOverPhase || state.NextGameAtUtc is { } at && move.UtcNow < at.AddMilliseconds(-EarlyStartMs)) return Result(state);
        NextGame(state);
        if (state.Players.Contains(move.Actor) && move.Index.HasValue)
        {
            try
            {
                var claim = ParseMark(move.Value, allowEmpty: true);
                if (claim.Game == state.Game) Place(state, move.Actor, move.Index, claim, move.UtcNow);
            }
            catch (InvalidOperationException)
            {
                // The late move doesn't fit the fresh board (an ink stroke for the last game, say); the board still moves on.
            }
        }
        return Result(state);
    }

    /// <summary>A move while a finished board is still up: the first move of the next game, once the beat is (nearly) over.</summary>
    private static void StartNextGameFor(TicTacToeState state, int? game, DateTime utcNow)
    {
        if (game is { } made && made != state.Game + 1) throw new InvalidOperationException(Loc.T("That game is over."));
        if (state.NextGameAtUtc is { } at && utcNow < at.AddMilliseconds(-EarlyStartMs))
            throw new InvalidOperationException(Loc.T("Get ready, the next game is about to start."));
        NextGame(state);
    }

    private static void Place(TicTacToeState state, Guid actor, int? index, Mark claim, DateTime utcNow)
    {
        if (claim.Game is { } game && game != state.Game) throw new InvalidOperationException(Loc.T("That game is over."));
        if (index is not { } cell || cell < 0 || cell >= state.Cells.Count) throw new InvalidOperationException(Loc.T("Pick a square on the board."));
        if (state.Pens.TryGetValue(actor, out var pen) && utcNow < pen.LastAtUtc.AddMilliseconds(CooldownMs - CooldownJitterMs))
            throw new InvalidOperationException(Loc.T("Your ink is still drying."));
        if (state.Cells[cell] is not null) throw new InvalidOperationException(Loc.T("That square is taken."));

        state.Cells[cell] = actor;
        EnsureMarks(state);
        state.Marks[cell] = new TicTacToeMark { Strokes = claim.Strokes };
        state.LastMove = cell;
        state.Pens[actor] = new TicTacToePen { Cell = cell, LastAtUtc = utcNow };

        // Moves are played one at a time, so the first line made is the one that counts: the game ends with it.
        var line = Lines.Where(run => run.All(square => state.Cells[square] == actor)).SelectMany(run => run).Distinct().Order().ToList();
        if (line.Count > 0)
        {
            state.Line = line;
            state.Scores[actor] = state.Scores.GetValueOrDefault(actor) + 1;
            state.Results.Add(actor);
            EndGame(state, utcNow);
        }
        else if (state.Cells.All(owner => owner is not null))
        {
            state.Results.Add(null);
            state.Draws++;
            EndGame(state, utcNow);
        }
    }

    /// <summary>More strokes for your newest mark while its ink is wet: the second line of an X.</summary>
    private static void AddInk(TicTacToeState state, Guid actor, int? index, Mark ink, DateTime utcNow)
    {
        if (ink.Game is { } game && game != state.Game) throw new InvalidOperationException(Loc.T("That game is over."));
        EnsureMarks(state);
        if (index is not { } cell || cell < 0 || cell >= state.Cells.Count || state.Cells[cell] != actor
            || !state.Pens.TryGetValue(actor, out var pen) || pen.Cell != cell || utcNow > pen.LastAtUtc.AddMilliseconds(WetInkMs))
            throw new InvalidOperationException(Loc.T("That mark's ink is already dry."));
        var mark = state.Marks[cell] ??= new TicTacToeMark();
        if (mark.Strokes.Count + ink.Strokes.Count > MaxStrokesPerMark) throw new InvalidOperationException(Loc.T("That mark's ink is already dry."));
        mark.Strokes.AddRange(ink.Strokes);
    }

    private static void EndGame(TicTacToeState state, DateTime utcNow)
    {
        var finished = state.Results.Count >= state.TotalGames || state.Scores.Values.Any(wins => wins >= state.WinsNeeded);
        if (!finished)
        {
            state.Phase = GameOverPhase;
            state.NextGameAtUtc = utcNow.AddMilliseconds(NextGameDelayMs);
            return;
        }
        state.Phase = MatchOverPhase;
        state.NextGameAtUtc = null;
        var best = state.Scores.Values.Max();
        var leaders = state.Scores.Where(item => item.Value == best).Select(item => item.Key).ToList();
        state.WinnerUserId = leaders.Count == 1 ? leaders[0] : null;
    }

    private static void NextGame(TicTacToeState state)
    {
        state.Game++;
        state.Cells = EmptyBoard();
        state.Marks = EmptyMarks();
        state.Line = null;
        state.LastMove = null;
        state.NextGameAtUtc = null;
        state.Pens.Clear();
        state.Phase = PlayingPhase;
    }

    /// <summary>
    /// The turn is only bookkeeping here (anyone may move): the session wants a player while the game is open. The
    /// deadline is the next game's start while a finished board is up.
    /// </summary>
    private static GameEngineResult Result(TicTacToeState state)
        => state.Phase == MatchOverPhase
            ? new GameEngineResult(Write(state), null, null, true)
            : new GameEngineResult(Write(state), state.Players[0], state.Phase == GameOverPhase ? state.NextGameAtUtc : null, false);

    private static List<Guid?> EmptyBoard() => Enumerable.Repeat<Guid?>(null, Size * Size).ToList();
    private static List<TicTacToeMark?> EmptyMarks() => Enumerable.Repeat<TicTacToeMark?>(null, Size * Size).ToList();

    /// <summary>A board from before marks were drawn has none: give it one empty slot per square.</summary>
    private static void EnsureMarks(TicTacToeState state)
    {
        while (state.Marks.Count < state.Cells.Count) state.Marks.Add(null);
    }

    /// <summary>What a move says: the game it was made on (null from a phone that doesn't say) and its strokes.</summary>
    private sealed record Mark(int? Game, List<TicTacToeStroke> Strokes);

    /// <summary>
    /// <c>{"game":n,"strokes":[[[x,y],...],...]}</c> and nothing else: up to <see cref="MaxStrokesPerMark"/> strokes of 1
    /// to <see cref="MaxPointsPerStroke"/> points, coordinates fractions of the square (0 to 1). A tap may send no value.
    /// </summary>
    private static Mark ParseMark(string? value, bool allowEmpty)
    {
        if (string.IsNullOrWhiteSpace(value))
            return allowEmpty ? new Mark(null, []) : throw Invalid();
        JsonDocument document;
        try { document = JsonDocument.Parse(value, new JsonDocumentOptions { MaxDepth = 5 }); }
        catch (JsonException) { throw Invalid(); }
        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) throw Invalid();
            int? game = null;
            List<TicTacToeStroke>? strokes = null;
            foreach (var property in root.EnumerateObject())
            {
                switch (property.Name)
                {
                    case "game" when game is null:
                        if (property.Value.ValueKind != JsonValueKind.Number || !property.Value.TryGetInt32(out var number) || number < 0) throw Invalid();
                        game = number;
                        break;
                    case "strokes" when strokes is null:
                        strokes = Strokes(property.Value);
                        break;
                    default:
                        throw Invalid();
                }
            }
            strokes ??= [];
            if (!allowEmpty && strokes.Count == 0) throw Invalid();
            return new Mark(game, strokes);
        }
    }

    private static List<TicTacToeStroke> Strokes(JsonElement list)
    {
        if (list.ValueKind != JsonValueKind.Array || list.GetArrayLength() > MaxStrokesPerMark) throw Invalid();
        var strokes = new List<TicTacToeStroke>();
        foreach (var stroke in list.EnumerateArray())
        {
            if (stroke.ValueKind != JsonValueKind.Array || stroke.GetArrayLength() is < 1 or > MaxPointsPerStroke) throw Invalid();
            var points = new List<double[]>();
            foreach (var point in stroke.EnumerateArray())
            {
                if (point.ValueKind != JsonValueKind.Array || point.GetArrayLength() != 2) throw Invalid();
                points.Add([Coordinate(point[0]), Coordinate(point[1])]);
            }
            strokes.Add(new TicTacToeStroke { Points = points });
        }
        return strokes;
    }

    private static double Coordinate(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Number || !element.TryGetDouble(out var value) || !double.IsFinite(value) || value < 0 || value > 1) throw Invalid();
        return Math.Round(value, 3);
    }

    private static InvalidOperationException Invalid() => new(Loc.T("That mark couldn't be drawn. Try again."));

    private static TicTacToeState Read(string json)
    {
        var state = JsonSerializer.Deserialize<TicTacToeState>(json, JsonOptions) ?? throw new InvalidOperationException("Game state is invalid.");
        EnsureMarks(state);
        return state;
    }

    private static string Write(TicTacToeState state) => JsonSerializer.Serialize(state, JsonOptions);
}

/// <summary>
/// The whole state of a tic-tac-toe match, which is also what both partners see. The fields up to
/// <see cref="WinnerUserId"/> are the grid match's, so a turn-by-turn game from before reads the same.
/// </summary>
public sealed class TicTacToeState
{
    public List<Guid> Players { get; set; } = [];
    public int Columns { get; set; } = 3;
    public int Rows { get; set; } = 3;
    /// <summary>Row by row from the top: who holds each square, or null.</summary>
    public List<Guid?> Cells { get; set; } = [];
    /// <summary>The game being played, or just finished, from 0.</summary>
    public int Game { get; set; }
    public int TotalGames { get; set; }
    public int WinsNeeded { get; set; }
    public string Phase { get; set; } = GridMatchRules.PlayingPhase;
    /// <summary>Whose turn it was in a turn-by-turn game from before; unused now.</summary>
    public int TurnIndex { get; set; }
    public int? LastMove { get; set; }
    /// <summary>The cells of the winning line once a game is won, for the app to draw through.</summary>
    public List<int>? Line { get; set; }
    public Dictionary<Guid, int> Scores { get; set; } = [];
    public int Draws { get; set; }
    /// <summary>Each finished game's winner, or null for a draw.</summary>
    public List<Guid?> Results { get; set; } = [];
    public Guid? WinnerUserId { get; set; }

    /// <summary>Square by square, the hand-drawn mark on it (null when empty, or when it was placed without strokes).</summary>
    public List<TicTacToeMark?> Marks { get; set; } = [];
    /// <summary>Each player's newest mark in this game and when it landed (server time): the cooldown and wet ink run from it.</summary>
    public Dictionary<Guid, TicTacToePen> Pens { get; set; } = [];
    /// <summary>While a finished board is up: when the next game starts.</summary>
    public DateTime? NextGameAtUtc { get; set; }
    /// <summary>The pen's refill time after each mark, for the app to show.</summary>
    public int CooldownMs => TicTacToeRules.CooldownMs;
}

public sealed class TicTacToeMark
{
    public List<TicTacToeStroke> Strokes { get; set; } = [];
}

public sealed class TicTacToeStroke
{
    public List<double[]> Points { get; set; } = [];
}

public sealed class TicTacToePen
{
    public int Cell { get; set; }
    public DateTime LastAtUtc { get; set; }
}
