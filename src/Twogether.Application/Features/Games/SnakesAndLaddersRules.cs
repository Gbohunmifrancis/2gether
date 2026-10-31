using System.Security.Cryptography;
using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Snakes and ladders (docs/games.md): a 10 by 10 board (1 to 100, row by row, turning at each end), a token each,
/// both starting off the board at 0. A coin flip (the server's) picks who rolls first; then it's turn by turn with no
/// clock. The server rolls the die (<c>roll</c>), moves the token and plays everything the landing square does, so the
/// phone never decides anything. A 6 rolls again; a third 6 in a row sends you back to where the turn started and
/// passes the turn. You must land on 100 exactly: an overshoot bounces back by the excess. First to 100 wins.
/// Each game gets its own board from the setup seed (<see cref="Layout"/>): ladders and snakes of mixed lengths that
/// never chain, and a few heart squares: Kiss &amp; swap (swap places with your partner when they're ahead: the one
/// catch-up in the game), Love boost (+3), Hug shield (your next snake is cancelled) and Heartbreak (back 5).
/// Every move is told in full in <c>last</c>, so both phones replay the same animation.
/// </summary>
public sealed class SnakesAndLaddersRules(Func<int, int>? random = null) : IGameRules
{
    public const int Goal = 100;
    public const int Side = 10;
    public const int BoostSteps = 3;
    public const int HeartbreakSteps = 5;
    /// <summary>Three sixes in a row: back to where the turn started.</summary>
    public const int SixesLimit = 3;

    /// <summary>How long a board plays on average (rolls for one player to reach 100 alone, simulated): a board outside this is redrawn.</summary>
    public const double MinAverageRolls = 26;
    public const double MaxAverageRolls = 42;
    /// <summary>How many games are simulated to judge a board.</summary>
    public const int FairnessGames = 400;

    public const string Swap = "swap";
    public const string Boost = "boost";
    public const string Shield = "shield";
    public const string Heartbreak = "heartbreak";

    private const string PlayingPhase = "playing";
    private const string CompletedPhase = "completed";

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);
    private static readonly string[] HeartKinds = [Swap, Swap, Boost, Boost, Shield, Shield, Heartbreak];

    /// <summary>A whole number from 0 to n - 1: the server's secure RNG, or a script in tests.</summary>
    private readonly Func<int, int> random = random ?? RandomNumberGenerator.GetInt32;

    public GameType Type => GameType.SnakesAndLadders;

    /// <summary>The setup for a new game: its region, a seed, and the board that seed draws.</summary>
    public static string CreateSetup(string region, int? seed = null)
    {
        var gameSeed = seed ?? Random.Shared.Next();
        return JsonSerializer.Serialize(new { region, seed = gameSeed, board = Layout(gameSeed) }, SetupJson);
    }

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var first = players[players.Count > 1 ? random(players.Count) : 0];
        var state = new State
        {
            Players = players.ToList(),
            First = first,
            Turn = first,
            Board = SetupBoard(setupJson) ?? Layout(Random.Shared.Next()),
            Positions = players.ToDictionary(player => player, _ => 0),
            Shields = players.ToDictionary(player => player, _ => false),
            Stats = players.ToDictionary(player => player, _ => new Stats()),
        };
        return Result(state);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        if (!string.Equals(move.Action, "roll", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("Snakes and ladders expects a roll."));
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (state.Turn != move.Actor) throw new InvalidOperationException(Loc.T("It's your partner's turn."));

        var me = move.Actor;
        var partner = state.Players.FirstOrDefault(player => player != me);
        var roll = random(6) + 1;
        var stats = StatsOf(state, me);
        stats.Rolls++;
        if (state.Sixes == 0) state.TurnStart = state.Positions.GetValueOrDefault(me);
        if (roll == 6)
        {
            state.Sixes++;
            stats.Sixes++;
        }
        state.Moves++;
        var from = state.Positions.GetValueOrDefault(me);
        var last = new Last { Move = state.Moves, Player = me, Roll = roll, From = from, Sixes = state.Sixes };

        if (roll == 6 && state.Sixes >= SixesLimit)
        {
            // The classic rule: a third six in a row undoes the whole turn.
            last.Events.Add(new Event { Kind = "threeSixes", From = from, To = state.TurnStart });
            state.Positions[me] = state.TurnStart;
            last.To = state.TurnStart;
            PassTurn(state, partner);
        }
        else
        {
            var outcome = Play(state.Board, from, partner == Guid.Empty ? 0 : state.Positions.GetValueOrDefault(partner), state.Shields.GetValueOrDefault(me), roll);
            last.Steps = outcome.Steps;
            last.Events = outcome.Events;
            last.To = outcome.Position;
            state.Positions[me] = outcome.Position;
            state.Shields[me] = outcome.Shield;
            if (partner != Guid.Empty && outcome.Partner != state.Positions.GetValueOrDefault(partner)) state.Positions[partner] = outcome.Partner;
            foreach (var happened in outcome.Events)
            {
                switch (happened.Kind)
                {
                    case "ladder": stats.Ladders++; break;
                    case "snake": stats.Snakes++; break;
                    case "shield": stats.Saves++; break;
                    case Swap: stats.Swaps++; break;
                }
            }
            if (outcome.Position == Goal)
            {
                state.Phase = CompletedPhase;
                state.Winner = me;
                state.Sixes = 0;
            }
            else if (roll == 6)
            {
                last.ExtraTurn = true;
            }
            else
            {
                PassTurn(state, partner);
            }
        }
        state.Last = last;
        return Result(state);
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var completed = state.Phase == CompletedPhase;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Phase,
            goal = Goal,
            state.First,
            turn = completed ? (Guid?)null : state.Turn,
            board = new
            {
                ladders = state.Board.Ladders.Select(link => new { link.From, link.To }),
                snakes = state.Board.Snakes.Select(link => new { link.From, link.To }),
                hearts = state.Board.Hearts.Select(heart => new { heart.Square, heart.Kind }),
            },
            state.Positions,
            state.Shields,
            stats = state.Stats.ToDictionary(item => item.Key, item => new { item.Value.Rolls, item.Value.Ladders, item.Value.Snakes, item.Value.Sixes, item.Value.Swaps, item.Value.Saves }),
            state.Sixes,
            state.Moves,
            last = state.Last is { } last ? new
            {
                move = last.Move,
                player = last.Player,
                roll = last.Roll,
                from = last.From,
                steps = last.Steps,
                events = last.Events.Select(item => new { item.Kind, item.From, item.To }),
                to = last.To,
                extraTurn = last.ExtraTurn,
                sixes = last.Sixes,
            } : null,
            winner = completed ? state.Winner : null,
        }, PublicJson);
    }

    /// <summary>Each player's square (100 for the winner), the rolls taken in all, and the winner of a finished game.</summary>
    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        var completed = state.Phase == CompletedPhase;
        return new GameScoreSummary(state.Players.ToDictionary(player => player, player => state.Positions.GetValueOrDefault(player)), state.Moves, completed ? state.Winner : null);
    }

    /// <summary>What one roll does: the squares hopped (bouncing back off 100), then whatever the landing square does.</summary>
    public static MoveOutcome Play(Board board, int from, int partner, bool shield, int roll)
    {
        var steps = new List<int>();
        var events = new List<Event>();
        var position = from;
        var forward = true;
        for (var step = 0; step < roll; step++)
        {
            if (position == Goal) forward = false;
            position += forward ? 1 : -1;
            steps.Add(position);
        }
        if (!forward) events.Add(new Event { Kind = "bounce", From = Goal, To = position });
        if (position == Goal)
        {
            events.Add(new Event { Kind = "win", From = Goal, To = Goal });
            return new MoveOutcome(position, partner, shield, steps, events);
        }
        // Ladder and snake ends, and the squares a boost or a heartbreak sends you to, are plain (Valid): nothing chains.
        if (board.Ladders.FirstOrDefault(link => link.From == position) is { } ladder)
        {
            events.Add(new Event { Kind = "ladder", From = position, To = ladder.To });
            position = ladder.To;
        }
        else if (board.Snakes.FirstOrDefault(link => link.From == position) is { } snake)
        {
            if (shield)
            {
                events.Add(new Event { Kind = "shield", From = position, To = position });
                shield = false;
            }
            else
            {
                events.Add(new Event { Kind = "snake", From = position, To = snake.To });
                position = snake.To;
            }
        }
        else if (board.Hearts.FirstOrDefault(heart => heart.Square == position) is { } heart)
        {
            switch (heart.Kind)
            {
                case Boost:
                    events.Add(new Event { Kind = Boost, From = position, To = position + BoostSteps });
                    position += BoostSteps;
                    break;
                case Heartbreak:
                    events.Add(new Event { Kind = Heartbreak, From = position, To = position - HeartbreakSteps });
                    position -= HeartbreakSteps;
                    break;
                case Shield:
                    events.Add(new Event { Kind = "hug", From = position, To = position });
                    shield = true;
                    break;
                case Swap when partner > position:
                    // A swap only ever helps the one behind: you trade places with a partner who's ahead.
                    events.Add(new Event { Kind = Swap, From = position, To = partner });
                    (position, partner) = (partner, position);
                    break;
                case Swap:
                    events.Add(new Event { Kind = "kiss", From = position, To = position });
                    break;
            }
        }
        return new MoveOutcome(position, partner, shield, steps, events);
    }

    /// <summary>
    /// The board a seed draws: 7 to 9 ladders and 7 to 9 snakes of mixed lengths and seven heart squares, redrawn until
    /// it is <see cref="Valid"/> and plays neither too short nor too long on average (<see cref="AverageRolls"/>).
    /// </summary>
    public static Board Layout(int seed)
    {
        for (var attempt = 0; attempt < 400; attempt++)
        {
            var random = new Random(unchecked(seed * 31 + attempt * 7_919));
            var board = Draw(random);
            if (board is null || !Valid(board)) continue;
            var average = AverageRolls(board, unchecked(seed + attempt));
            if (average is >= MinAverageRolls and <= MaxAverageRolls) return board;
        }
        return Classic();
    }

    /// <summary>
    /// The rules every board keeps: ladders go up and snakes down, at least a row each; nothing starts on 1 or 100 or
    /// ends there; every square is the end of one thing at most (so nothing chains into a loop); a boost or a
    /// heartbreak lands on a plain square.
    /// </summary>
    public static bool Valid(Board board)
    {
        if (board.Ladders.Count is < 1 or > 12 || board.Snakes.Count is < 1 or > 12 || board.Hearts.Count > 12) return false;
        var used = new HashSet<int>();
        bool Take(int square) => square is > 1 and < Goal && used.Add(square);
        foreach (var ladder in board.Ladders)
            if (ladder.To <= ladder.From || Row(ladder.To) <= Row(ladder.From) || !Take(ladder.From) || !Take(ladder.To)) return false;
        foreach (var snake in board.Snakes)
            if (snake.To >= snake.From || Row(snake.To) >= Row(snake.From) || !Take(snake.From) || !Take(snake.To)) return false;
        foreach (var heart in board.Hearts)
            if (heart.Kind is not (Swap or Boost or Shield or Heartbreak) || !Take(heart.Square)) return false;
        foreach (var heart in board.Hearts)
        {
            var target = heart.Kind switch { Boost => heart.Square + BoostSteps, Heartbreak => heart.Square - HeartbreakSteps, _ => heart.Square };
            if (target != heart.Square && (target is <= 1 or >= Goal || used.Contains(target))) return false;
        }
        return true;
    }

    /// <summary>
    /// How many rolls one player takes on average to reach 100 alone on this board, over <see cref="FairnessGames"/>
    /// simulated games (a swap square is then just a kiss). A plain board takes about 33.
    /// </summary>
    public static double AverageRolls(Board board, int seed, int games = FairnessGames)
    {
        var random = new Random(seed);
        long total = 0;
        for (var game = 0; game < games; game++)
        {
            var position = 0;
            var shield = false;
            var rolls = 0;
            var sixes = 0;
            var turnStart = 0;
            while (position != Goal && rolls < 2_000)
            {
                var roll = random.Next(1, 7);
                rolls++;
                if (sixes == 0) turnStart = position;
                sixes = roll == 6 ? sixes + 1 : 0;
                if (sixes >= SixesLimit)
                {
                    position = turnStart;
                    sixes = 0;
                    continue;
                }
                var outcome = Play(board, position, 0, shield, roll);
                position = outcome.Position;
                shield = outcome.Shield;
            }
            total += rolls;
        }
        return total / (double)games;
    }

    /// <summary>The square's row from the bottom (0 to 9) and column from the left (0 to 9): odd rows run right to left.</summary>
    public static int Row(int square) => (square - 1) / Side;

    public static int Column(int square)
    {
        var inRow = (square - 1) % Side;
        return Row(square) % 2 == 0 ? inRow : Side - 1 - inRow;
    }

    public static int Square(int row, int column) => row * Side + (row % 2 == 0 ? column : Side - 1 - column) + 1;

    /// <summary>One try at a board: ladders, then snakes, then hearts, each on a free square; null if it got stuck.</summary>
    private static Board? Draw(Random random)
    {
        var board = new Board();
        var used = new HashSet<int> { 1, Goal };
        var starts = new Dictionary<int, int>();

        // Mixed lengths: some short hops, some long climbs and slides, rows apart (1 to 6) and a few columns aside.
        bool Place(List<Link> into, bool up, int minRows, int maxRows)
        {
            for (var tries = 0; tries < 60; tries++)
            {
                var rows = random.Next(minRows, maxRows + 1);
                var fromRow = up ? random.Next(0, Side - rows) : random.Next(rows, Side);
                var toRow = up ? fromRow + rows : fromRow - rows;
                var fromColumn = random.Next(0, Side);
                var toColumn = Math.Clamp(fromColumn + random.Next(-3, 4), 0, Side - 1);
                var from = Square(fromRow, fromColumn);
                var to = Square(toRow, toColumn);
                if (up && to > Goal - 2) continue;
                // At most two starts of a kind in a row, so they spread over the board.
                var key = fromRow * 2 + (up ? 0 : 1);
                if (starts.GetValueOrDefault(key) >= 2 || used.Contains(from) || used.Contains(to)) continue;
                // Room to read the board: ladders never cross or crowd each other, nor snakes each other, and no
                // snake's head sits on a ladder (a snake's body may cross one, as on every board).
                var (a, b) = (Point(from), Point(to));
                if (into.Any(link => SegmentDistance(a, b, Point(link.From), Point(link.To)) < (up ? 0.75 : 0.9))) continue;
                if (!up && board.Ladders.Any(link => PointDistance(a, Point(link.From), Point(link.To)) < 0.45)) continue;
                used.Add(from);
                used.Add(to);
                starts[key] = starts.GetValueOrDefault(key) + 1;
                into.Add(new Link { From = from, To = to });
                return true;
            }
            return false;
        }

        var ladders = random.Next(7, 10);
        var snakes = random.Next(7, 10);
        // Two short, one long of each, then any length.
        foreach (var (min, max) in new[] { (1, 1), (1, 2), (3, 5) }.Concat(Enumerable.Repeat((1, 4), ladders - 3)))
            if (!Place(board.Ladders, true, min, max)) return null;
        foreach (var (min, max) in new[] { (1, 1), (1, 2), (3, 6) }.Concat(Enumerable.Repeat((1, 4), snakes - 3)))
            if (!Place(board.Snakes, false, min, max)) return null;
        // The last row always has a snake, so the finish stays tense; the first two rows a ladder, so it starts well.
        if (board.Snakes.All(snake => Row(snake.From) < Side - 1) || board.Ladders.All(ladder => Row(ladder.From) > 1)) return null;

        foreach (var kind in HeartKinds)
        {
            var placed = false;
            for (var tries = 0; tries < 80 && !placed; tries++)
            {
                var square = random.Next(6, Goal - 5);
                var target = kind switch { Boost => square + BoostSteps, Heartbreak => square - HeartbreakSteps, _ => square };
                if (used.Contains(square) || used.Contains(target) || board.Hearts.Any(heart => Targets(heart) == square)) continue;
                used.Add(square);
                board.Hearts.Add(new Heart { Square = square, Kind = kind });
                placed = true;
            }
            if (!placed) return null;
        }
        // Boost and heartbreak targets must stay plain: nothing placed on them later.
        return board.Hearts.Any(heart => Targets(heart) != heart.Square && used.Contains(Targets(heart))) ? null : board;
    }

    /// <summary>A square's middle in board units (column, row).</summary>
    private static (double X, double Y) Point(int square) => (Column(square) + 0.5, Row(square) + 0.5);

    private static double PointDistance((double X, double Y) p, (double X, double Y) a, (double X, double Y) b)
    {
        var (dx, dy) = (b.X - a.X, b.Y - a.Y);
        var length = dx * dx + dy * dy;
        var t = length == 0 ? 0 : Math.Clamp(((p.X - a.X) * dx + (p.Y - a.Y) * dy) / length, 0, 1);
        var (x, y) = (a.X + t * dx - p.X, a.Y + t * dy - p.Y);
        return Math.Sqrt(x * x + y * y);
    }

    /// <summary>How close two segments come: 0 when they cross.</summary>
    private static double SegmentDistance((double X, double Y) a, (double X, double Y) b, (double X, double Y) c, (double X, double Y) d)
    {
        static double Cross((double X, double Y) o, (double X, double Y) p, (double X, double Y) q) => (p.X - o.X) * (q.Y - o.Y) - (p.Y - o.Y) * (q.X - o.X);
        if (Cross(a, b, c) * Cross(a, b, d) < 0 && Cross(c, d, a) * Cross(c, d, b) < 0) return 0;
        return new[] { PointDistance(a, c, d), PointDistance(b, c, d), PointDistance(c, a, b), PointDistance(d, a, b) }.Min();
    }

    private static int Targets(Heart heart) => heart.Kind switch { Boost => heart.Square + BoostSteps, Heartbreak => heart.Square - HeartbreakSteps, _ => heart.Square };

    /// <summary>A fixed, fair board for the (never seen) case where no seed draws one.</summary>
    public static Board Classic() => new()
    {
        Ladders = [new() { From = 4, To = 14 }, new() { From = 9, To = 31 }, new() { From = 21, To = 42 }, new() { From = 28, To = 84 }, new() { From = 51, To = 67 }, new() { From = 72, To = 91 }, new() { From = 80, To = 98 }],
        Snakes = [new() { From = 17, To = 7 }, new() { From = 54, To = 34 }, new() { From = 62, To = 19 }, new() { From = 64, To = 60 }, new() { From = 87, To = 24 }, new() { From = 93, To = 73 }, new() { From = 95, To = 75 }],
        Hearts = [new() { Square = 12, Kind = Swap }, new() { Square = 47, Kind = Swap }, new() { Square = 26, Kind = Boost }, new() { Square = 68, Kind = Boost }, new() { Square = 38, Kind = Shield }, new() { Square = 82, Kind = Shield }, new() { Square = 58, Kind = Heartbreak }],
    };

    private static void PassTurn(State state, Guid partner)
    {
        state.Sixes = 0;
        if (partner != Guid.Empty) state.Turn = partner;
    }

    private static Stats StatsOf(State state, Guid player)
    {
        if (!state.Stats.TryGetValue(player, out var stats)) state.Stats[player] = stats = new Stats();
        return stats;
    }

    private static GameEngineResult Result(State state)
    {
        var completed = state.Phase == CompletedPhase;
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Turn, null, completed);
    }

    private static Board? SetupBoard(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            var setup = JsonSerializer.Deserialize<Setup>(setupJson, SetupJson);
            if (setup?.Board is { } board && Valid(board)) return board;
            return setup?.Seed is { } seed ? Layout(seed) : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static State Read(string json)
    {
        var state = JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));
        if (state.Players.Count == 0) throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));
        // A state missing parts (older, or hand-made) plays on with what it has.
        if (state.Turn == Guid.Empty) state.Turn = state.First != Guid.Empty ? state.First : state.Players[0];
        if (state.Board.Ladders.Count == 0 && state.Board.Snakes.Count == 0) state.Board = Classic();
        foreach (var player in state.Players)
        {
            state.Positions.TryAdd(player, 0);
            state.Shields.TryAdd(player, false);
            state.Stats.TryAdd(player, new Stats());
        }
        return state;
    }

    public sealed record MoveOutcome(int Position, int Partner, bool Shield, List<int> Steps, List<Event> Events);

    private sealed class Setup
    {
        public int? Seed { get; set; }
        public Board? Board { get; set; }
    }

    public sealed class Board
    {
        public List<Link> Ladders { get; set; } = [];
        public List<Link> Snakes { get; set; } = [];
        public List<Heart> Hearts { get; set; } = [];
    }

    /// <summary>A ladder (from the bottom up) or a snake (from the head down).</summary>
    public sealed class Link
    {
        public int From { get; set; }
        public int To { get; set; }
    }

    /// <summary>A heart square: <c>swap</c>, <c>boost</c>, <c>shield</c> or <c>heartbreak</c>.</summary>
    public sealed class Heart
    {
        public int Square { get; set; }
        public string Kind { get; set; } = string.Empty;
    }

    /// <summary>One thing a move did, in order: where the token (or, for a swap, both) went.</summary>
    public sealed class Event
    {
        public string Kind { get; set; } = string.Empty;
        public int From { get; set; }
        public int To { get; set; }
    }

    public sealed class Stats
    {
        public int Rolls { get; set; }
        public int Ladders { get; set; }
        public int Snakes { get; set; }
        public int Sixes { get; set; }
        public int Swaps { get; set; }
        /// <summary>Snakes a hug shield cancelled.</summary>
        public int Saves { get; set; }
    }

    private sealed class Last
    {
        public int Move { get; set; }
        public Guid Player { get; set; }
        public int Roll { get; set; }
        public int From { get; set; }
        public List<int> Steps { get; set; } = [];
        public List<Event> Events { get; set; } = [];
        public int To { get; set; }
        public bool ExtraTurn { get; set; }
        public int Sixes { get; set; }
    }

    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Guid First { get; set; }
        /// <summary>Whose roll it is.</summary>
        public Guid Turn { get; set; }
        public string Phase { get; set; } = PlayingPhase;
        public Board Board { get; set; } = new();
        public Dictionary<Guid, int> Positions { get; set; } = [];
        public Dictionary<Guid, bool> Shields { get; set; } = [];
        public Dictionary<Guid, Stats> Stats { get; set; } = [];
        /// <summary>Sixes in a row this turn.</summary>
        public int Sixes { get; set; }
        /// <summary>Where the roller's token was when this turn began (three sixes go back there).</summary>
        public int TurnStart { get; set; }
        /// <summary>Rolls so far, both players.</summary>
        public int Moves { get; set; }
        public Last? Last { get; set; }
        public Guid? Winner { get; set; }
    }
}
