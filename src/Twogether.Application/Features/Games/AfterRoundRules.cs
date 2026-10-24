using System.Security.Cryptography;
using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// After Round (docs/games.md), the finger-count game from After Round 1 (afterround1.tech), one on one, for Nigerian
/// couples. Each round both call a number from 0 to 10 (each number once a round, first come), then both lay 0 to 5
/// fingers at once, never the value they laid last round. The fingers are counted, and whoever called the total takes
/// the round. First to five wins, and the winner then slaps the loser in a slap duel: both stop a sweeping needle as
/// close to the green as they can, and the better press decides whether the slap lands, glances or is dodged. No coins.
/// </summary>
public sealed class AfterRoundRules : IGameRules
{
    public const int WinningScore = 5;
    public const int MaxRounds = 25;
    public const int MaxFingers = 5;
    public const int CallSeconds = 15;
    public const int LaySeconds = 10;
    /// <summary>The duel: a three-second count-in on the phone, the four-second gauge, and room for a slow network.</summary>
    public const int DuelSeconds = 12;
    public const int DuelPeriodMs = 1100;
    public const int DuelWindowMs = 4000;
    /// <summary>Rounds in a row with nobody moving before the game ends on its own.</summary>
    public const int IdleRoundsToEnd = 2;

    private const double PerfectBand = 0.03;
    private const double GreatBand = 0.08;
    private const double GoodBand = 0.16;
    private const double TieEpsilon = 0.01;
    /// <summary>A slapper who never presses still slaps: a weak one, never a freeze.</summary>
    private const double AutoSlapAccuracy = 0.2;

    private const string CallingPhase = "calling";
    private const string LayingPhase = "laying";
    private const string DuelPhase = "duel";
    private const string CompletedPhase = "completed";

    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.AfterRound;

    public bool AnyoneMayAct => true;

    public bool Timed => true;

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var state = new State
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Region = GameRegion.Of(setupJson),
        };
        return Result(state, utcNow);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        if (string.Equals(move.Action, GameEngine.TimeoutAction, StringComparison.OrdinalIgnoreCase)) return TimeOut(state, move.UtcNow);
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));

        switch (move.Action.ToLowerInvariant())
        {
            case "call":
                if (state.Phase != CallingPhase) throw new InvalidOperationException(Loc.T("The numbers are called. Lay your fingers."));
                if (state.Calls.ContainsKey(move.Actor)) throw new InvalidOperationException(Loc.T("You already called a number. Wait for your partner."));
                if (move.Index is not { } number || !Reachable(state).Contains(number))
                    throw new InvalidOperationException(Loc.T("No two hands can make that number this round. Call another."));
                if (state.Calls.ContainsValue(number)) throw new InvalidOperationException(Loc.T("Your partner called {number} already. Call another.", ("number", number)));
                state.Calls[move.Actor] = number;
                if (state.Calls.Count == state.Players.Count) state.Phase = LayingPhase;
                return Result(state, move.UtcNow, phaseChanged: state.Phase == LayingPhase);

            case "lay":
                if (state.Phase != LayingPhase) throw new InvalidOperationException(Loc.T("Call a number first."));
                if (state.Fingers.ContainsKey(move.Actor)) throw new InvalidOperationException(Loc.T("Your hand is down. Wait for your partner."));
                if (move.Index is not { } fingers || !LegalFingers(state, move.Actor).Contains(fingers))
                    throw new InvalidOperationException(state.LastFingers.TryGetValue(move.Actor, out var last) && move.Index == last
                        ? Loc.T("You laid {count} last round. Lay a different number.", ("count", last))
                        : Loc.T("Lay 0 to 5 fingers."));
                state.Fingers[move.Actor] = fingers;
                if (state.Fingers.Count < state.Players.Count) return Result(state, move.UtcNow);
                return Resolve(state, move.UtcNow);

            case "press":
                if (state.Phase != DuelPhase || state.Duel is not { } duel) throw new InvalidOperationException(Loc.T("There's no slap duel right now."));
                if (move.Actor != duel.Slapper && move.Actor != duel.Defender) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
                if (duel.Presses.ContainsKey(move.Actor)) throw new InvalidOperationException(Loc.T("You pressed already."));
                // The phone sends how long after its gauge started the press came. No coins ride on it, so it's trusted.
                duel.Presses[move.Actor] = move.Index is { } ms and >= 0 and <= DuelWindowMs ? ms : null;
                if (duel.Presses.Count < 2) return Result(state, move.UtcNow);
                return FinishDuel(state, move.UtcNow);

            default:
                throw new InvalidOperationException(Loc.T("That move isn't part of After Round."));
        }
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var me = viewer is { } who && state.Players.Contains(who) ? who : (Guid?)null;
        var duel = state.Duel;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            state.Round,
            target = WinningScore,
            maxRounds = MaxRounds,
            state.Phase,
            callSeconds = CallSeconds,
            laySeconds = LaySeconds,
            // Calls are public, as at the table; fingers stay hidden until both hands are down.
            calls = state.Calls,
            reachable = state.Phase is CallingPhase or LayingPhase ? Reachable(state) : [],
            lastFingers = state.LastFingers,
            myFingers = me is { } mine && state.Phase == LayingPhase ? LegalFingers(state, mine) : [],
            laid = state.Fingers.Keys,
            myLay = me is { } m && state.Fingers.TryGetValue(m, out var lay) ? lay : (int?)null,
            last = state.Last,
            duel = duel is null ? null : new
            {
                duel.Slapper,
                duel.Defender,
                duel.Zone,
                periodMs = DuelPeriodMs,
                windowMs = DuelWindowMs,
                pressed = duel.Presses.Keys,
                duel.Result,
            },
            state.WinnerUserId,
            state.Ended,
        }, PublicJson);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Round, state.WinnerUserId);
    }

    /// <summary>Totals the two hands can make this round, each player avoiding the value they laid last.</summary>
    public static IReadOnlyList<int> Reachable(IReadOnlyDictionary<Guid, int> lastFingers, IReadOnlyList<Guid> players)
    {
        var totals = new HashSet<int> { 0 };
        foreach (var player in players)
        {
            var legal = Enumerable.Range(0, MaxFingers + 1).Where(value => !lastFingers.TryGetValue(player, out var last) || value != last).ToList();
            totals = totals.SelectMany(sum => legal.Select(value => sum + value)).ToHashSet();
        }
        return totals.Order().ToList();
    }

    /// <summary>How well a press lands: 1 at the centre of the green, falling to 0 at the far end of the gauge.</summary>
    public static double Accuracy(int pressMs, double zone)
    {
        var phase = pressMs / (double)DuelPeriodMs % 2;
        var position = phase < 1 ? phase : 2 - phase;
        return Math.Clamp(1 - Math.Abs(position - zone) / Math.Max(zone, 1 - zone), 0, 1);
    }

    private static IReadOnlyList<int> Reachable(State state) => Reachable(state.LastFingers, state.Players);

    private static List<int> LegalFingers(State state, Guid player)
        => Enumerable.Range(0, MaxFingers + 1).Where(value => !state.LastFingers.TryGetValue(player, out var last) || value != last).ToList();

    /// <summary>The clock ran out: whoever hasn't called or laid gets a legal random one, and a duel press not made is a freeze.</summary>
    private static GameEngineResult TimeOut(State state, DateTime utcNow)
    {
        switch (state.Phase)
        {
            case CallingPhase:
                foreach (var player in state.Players.Where(player => !state.Calls.ContainsKey(player)))
                {
                    var open = Reachable(state).Where(number => !state.Calls.ContainsValue(number)).ToList();
                    state.Calls[player] = open[RandomNumberGenerator.GetInt32(open.Count)];
                    state.AutoCalls.Add(player);
                }
                state.Phase = LayingPhase;
                return Result(state, utcNow, phaseChanged: true);
            case LayingPhase:
                foreach (var player in state.Players.Where(player => !state.Fingers.ContainsKey(player)))
                {
                    var legal = LegalFingers(state, player);
                    state.Fingers[player] = legal[RandomNumberGenerator.GetInt32(legal.Count)];
                    state.AutoLays.Add(player);
                }
                return Resolve(state, utcNow);
            case DuelPhase when state.Duel is { } duel:
                foreach (var player in new[] { duel.Slapper, duel.Defender }) duel.Presses.TryAdd(player, null);
                return FinishDuel(state, utcNow);
            default:
                return Result(state, utcNow);
        }
    }

    /// <summary>Both hands are down: count them, score the round, then the next round, the duel or the end.</summary>
    private static GameEngineResult Resolve(State state, DateTime utcNow)
    {
        var total = state.Fingers.Values.Sum();
        var winner = state.Calls.Where(call => call.Value == total).Select(call => (Guid?)call.Key).FirstOrDefault();
        if (winner is { } w) state.Scores[w]++;
        var auto = state.AutoCalls.Union(state.AutoLays).ToList();
        state.Last = new RoundReveal
        {
            Round = state.Round,
            Calls = new Dictionary<Guid, int>(state.Calls),
            Fingers = new Dictionary<Guid, int>(state.Fingers),
            Total = total,
            Winner = winner,
            Auto = auto,
        };
        var nobodyMoved = state.Players.All(player => state.AutoCalls.Contains(player) && state.AutoLays.Contains(player));
        state.IdleRounds = nobodyMoved ? state.IdleRounds + 1 : 0;
        state.LastFingers = new Dictionary<Guid, int>(state.Fingers);
        state.Calls.Clear();
        state.Fingers.Clear();
        state.AutoCalls.Clear();
        state.AutoLays.Clear();
        state.Round++;

        if (state.IdleRounds >= IdleRoundsToEnd)
        {
            state.Ended = "idle";
            return End(state, utcNow, duel: false);
        }
        if (state.Scores.Values.Max() >= WinningScore || state.Round >= MaxRounds) return End(state, utcNow, duel: true);
        state.Phase = CallingPhase;
        return Result(state, utcNow, phaseChanged: true);
    }

    /// <summary>A single leader wins and slaps; level scores are a draw, with no duel.</summary>
    private static GameEngineResult End(State state, DateTime utcNow, bool duel)
    {
        var best = state.Scores.Values.Max();
        var leaders = state.Scores.Where(score => score.Value == best).Select(score => score.Key).ToList();
        state.WinnerUserId = leaders.Count == 1 ? leaders[0] : null;
        if (duel && state.WinnerUserId is { } winner)
        {
            state.Phase = DuelPhase;
            state.Duel = new Duel
            {
                Slapper = winner,
                Defender = state.Players.First(player => player != winner),
                // Somewhere between 0.30 and 0.70, so nobody learns one fixed spot.
                Zone = Math.Round(0.3 + RandomNumberGenerator.GetInt32(401) / 1000.0, 3),
            };
            return Result(state, utcNow, phaseChanged: true);
        }
        state.Phase = CompletedPhase;
        return Result(state, utcNow);
    }

    private static GameEngineResult FinishDuel(State state, DateTime utcNow)
    {
        var duel = state.Duel!;
        var slapper = Score(duel.Presses.GetValueOrDefault(duel.Slapper), duel.Zone);
        var slapperAccuracy = slapper.Accuracy == 0 ? AutoSlapAccuracy : slapper.Accuracy;
        var defender = Score(duel.Presses.GetValueOrDefault(duel.Defender), duel.Zone);
        var outcome = defender.Accuracy > slapperAccuracy + TieEpsilon ? "dodged"
            : Math.Abs(slapperAccuracy - defender.Accuracy) <= TieEpsilon ? "glancing"
            : "hit";
        duel.Result = new DuelResult
        {
            Outcome = outcome,
            Slapper = new Press { Accuracy = Math.Round(slapperAccuracy, 3), Rating = slapper.Accuracy == 0 ? "weak" : slapper.Rating },
            Defender = new Press { Accuracy = Math.Round(defender.Accuracy, 3), Rating = defender.Rating },
        };
        state.Phase = CompletedPhase;
        return Result(state, utcNow);
    }

    private static Press Score(int? pressMs, double zone)
    {
        if (pressMs is not { } ms) return new Press { Accuracy = 0, Rating = "frozen" };
        var accuracy = Accuracy(ms, zone);
        var distance = (1 - accuracy) * Math.Max(zone, 1 - zone);
        var rating = distance <= PerfectBand ? "perfect" : distance <= GreatBand ? "great" : distance <= GoodBand ? "good" : "weak";
        return new Press { Accuracy = accuracy, Rating = rating };
    }

    /// <summary>
    /// Whose move it is (the first player who still has to move: both may move at once) and the phase's clock. The
    /// deadline is set when a phase starts and holds until it ends.
    /// </summary>
    private static GameEngineResult Result(State state, DateTime utcNow, bool phaseChanged = false)
    {
        if (state.Phase == CompletedPhase) return new GameEngineResult(JsonSerializer.Serialize(state), null, null, true);
        if (phaseChanged || state.DeadlineUtc is null)
            state.DeadlineUtc = utcNow.AddSeconds(state.Phase switch { CallingPhase => CallSeconds, LayingPhase => LaySeconds, _ => DuelSeconds });
        var waitingOn = state.Phase switch
        {
            CallingPhase => state.Players.FirstOrDefault(player => !state.Calls.ContainsKey(player)),
            LayingPhase => state.Players.FirstOrDefault(player => !state.Fingers.ContainsKey(player)),
            _ => new[] { state.Duel!.Slapper, state.Duel.Defender }.FirstOrDefault(player => !state.Duel.Presses.ContainsKey(player)),
        };
        return new GameEngineResult(JsonSerializer.Serialize(state), waitingOn == Guid.Empty ? state.Players[0] : waitingOn, state.DeadlineUtc, false);
    }

    private static State Read(string json)
        => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));

    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public string Region { get; set; } = GameRegion.Nigeria;
        /// <summary>Rounds played, counting from 0.</summary>
        public int Round { get; set; }
        public string Phase { get; set; } = CallingPhase;
        public DateTime? DeadlineUtc { get; set; }
        public Dictionary<Guid, int> Calls { get; set; } = [];
        /// <summary>This round's fingers: secret until both are down.</summary>
        public Dictionary<Guid, int> Fingers { get; set; } = [];
        /// <summary>What each laid last round, which they can't lay again.</summary>
        public Dictionary<Guid, int> LastFingers { get; set; } = [];
        public HashSet<Guid> AutoCalls { get; set; } = [];
        public HashSet<Guid> AutoLays { get; set; } = [];
        public int IdleRounds { get; set; }
        public RoundReveal? Last { get; set; }
        public Duel? Duel { get; set; }
        public Guid? WinnerUserId { get; set; }
        /// <summary>"idle" when the game ended because nobody was moving.</summary>
        public string? Ended { get; set; }
    }

    private sealed class RoundReveal
    {
        public int Round { get; set; }
        public Dictionary<Guid, int> Calls { get; set; } = [];
        public Dictionary<Guid, int> Fingers { get; set; } = [];
        public int Total { get; set; }
        public Guid? Winner { get; set; }
        /// <summary>Who ran out of time and had a number or a hand chosen for them.</summary>
        public List<Guid> Auto { get; set; } = [];
    }

    private sealed class Duel
    {
        public Guid Slapper { get; set; }
        public Guid Defender { get; set; }
        /// <summary>The centre of the green, as a fraction of the gauge.</summary>
        public double Zone { get; set; }
        /// <summary>Milliseconds after the gauge started; null for no press (or one outside the gauge's four seconds).</summary>
        public Dictionary<Guid, int?> Presses { get; set; } = [];
        public DuelResult? Result { get; set; }
    }

    private sealed class DuelResult
    {
        /// <summary>"hit", "glancing" or "dodged".</summary>
        public string Outcome { get; set; } = "hit";
        public Press Slapper { get; set; } = new();
        public Press Defender { get; set; } = new();
    }

    private sealed class Press
    {
        public double Accuracy { get; set; }
        /// <summary>"perfect", "great", "good", "weak" or "frozen".</summary>
        public string Rating { get; set; } = "frozen";
    }
}
