using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Heart to heart (docs/games.md): five skill rounds, the same for both (the same kind and seed, so the same shape or
/// target), each played on the partner's own time, no clock. Each round is scored 0 to 100 on the phone and sent as
/// <c>score</c>. A partner's score stays hidden from you until you've played that round too. A round goes to the
/// higher score, a tie is a heart each; the duel goes to whoever has more hearts. Level on hearts, the higher total of
/// points breaks the tie; level on both, it's a tie.
/// </summary>
/// <remarks>
/// The phone says when a round starts (<c>open</c>). Opening a round again without having scored it (backing out to
/// try again) deals that player a different seed for it (<see cref="Reseed"/>): the same kind, still at level 1, but
/// another shape or target, so a round can't be scouted and replayed. The partner's round keeps its own seed. A score
/// may name the seed it was played on (<c>{"score": n, "seed": s}</c>); it must be the player's current one.
/// </remarks>
public sealed class SkillDuelRules : IGameRules
{
    public const int Rounds = 5;
    public const int MaxScore = 100;
    private const string PlayingPhase = "playing";
    private const string CompletedPhase = "completed";

    /// <summary>The skill games, by the app's <c>SkillKind</c> names (ui/play/Skills.kt): they travel as is.</summary>
    public static readonly IReadOnlyList<string> Kinds =
        ["FairShare", "FillTheHeart", "OtherHalf", "Heartbeat", "KissTheCenter", "PromiseRing", "CupidsAim", "SlowKiss", "DrawAHeart"];

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.SkillDuel;

    public bool AnyoneMayAct => true;

    /// <summary>
    /// Each score is one partner's own round, judged on arrival (a round scored twice is refused), so two scores sent
    /// at the same moment never need a reload and a resend.
    /// </summary>
    public bool MovesMayCross => true;

    /// <summary>The setup for a new duel: its region, a seed, and the five rounds that seed draws.</summary>
    public static string CreateSetup(string region, int? seed = null)
    {
        var duelSeed = seed ?? Random.Shared.Next();
        return JsonSerializer.Serialize(new { region, seed = duelSeed, rounds = Plan(duelSeed) }, SetupJson);
    }

    /// <summary>
    /// Five rounds from a seed: five of the kinds in a shuffled order (so none comes twice, let alone twice in a
    /// row), each with its own seed for the shape or target.
    /// </summary>
    public static List<Round> Plan(int seed)
    {
        var random = new Random(seed);
        return Kinds.OrderBy(_ => random.Next()).Take(Rounds).Select(kind => new Round { Kind = kind, Seed = random.Next() }).ToList();
    }

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var state = new State
        {
            Players = players.ToList(),
            Rounds = SetupRounds(setupJson) ?? Plan(Random.Shared.Next()),
        };
        foreach (var player in players) state.Scores[player] = Enumerable.Repeat<int?>(null, state.Rounds.Count).ToList();
        return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[0], null, false);
    }

    public const string OpenAction = "open";
    public const string ScoreAction = "score";

    public GameEngineResult Apply(GameMove move)
    {
        var open = string.Equals(move.Action, OpenAction, StringComparison.OrdinalIgnoreCase);
        if (!open && !string.Equals(move.Action, ScoreAction, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("Heart to heart expects a score."));
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        if (!state.Players.Contains(move.Actor) || !state.Scores.TryGetValue(move.Actor, out var mine))
            throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (move.Index is not { } round || round < 0 || round >= state.Rounds.Count)
            throw new InvalidOperationException(Loc.T("Pick one of the five rounds."));
        if (mine[round] is not null) throw new InvalidOperationException(Loc.T("You already played this round."));

        if (open)
        {
            // Backing out of a round and opening it again deals another round of the same kind.
            var opens = Slots(state.Opens, move.Actor, state.Rounds.Count, 0);
            if (opens[round] > 0) Slots(state.Seeds, move.Actor, state.Rounds.Count, (int?)null)[round] = Reseed(SeedOf(state, move.Actor, round), opens[round]);
            opens[round]++;
            return Result(state, move.Actor, completed: false);
        }

        var (score, seed) = ParseScore(move.Value);
        if (score is null) throw new InvalidOperationException(Loc.T("A score is a whole number from 0 to 100."));
        // A score played on a round this player has since been dealt again is for a round that's gone.
        if (seed is { } played && played != SeedOf(state, move.Actor, round))
            throw new InvalidOperationException(Loc.T("That round was dealt again. Play it once more."));
        mine[round] = score;

        var completed = state.Players.All(player => Finished(state, player));
        if (completed) state.Phase = CompletedPhase;
        return Result(state, move.Actor, completed);
    }

    /// <summary>Both may play at any time; the turn names whoever still has rounds to play, the partner first.</summary>
    private static GameEngineResult Result(State state, Guid actor, bool completed)
    {
        var partner = state.Players.FirstOrDefault(player => player != actor);
        Guid? next = completed ? null : partner != Guid.Empty && !Finished(state, partner) ? partner : actor;
        return new GameEngineResult(JsonSerializer.Serialize(state), next, null, completed);
    }

    /// <summary>The seed <paramref name="player"/> plays <paramref name="round"/> on: their own once they've been dealt it again, else the round's.</summary>
    public static int SeedOf(string stateJson, Guid player, int round) => SeedOf(Read(stateJson), player, round);

    private static int SeedOf(State state, Guid player, int round)
        => state.Seeds.TryGetValue(player, out var seeds) && round < seeds.Count && seeds[round] is { } own ? own : state.Rounds[round].Seed;

    /// <summary>
    /// The seed for a round dealt again: drawn from the one it replaces and how many times it has been opened, so it's
    /// the same on every server, and never the one it replaces.
    /// </summary>
    public static int Reseed(int seed, int opens)
    {
        var random = new Random(unchecked(seed ^ (opens * 0x5BD1E995)));
        var next = random.Next();
        while (next == seed) next = random.Next();
        return next;
    }

    /// <summary>
    /// The score just sent finished <paramref name="actor"/>'s five rounds while their partner still has some to play
    /// (the moment the partner is reminded, docs/games.md). It happens once per duel: the second to finish ends it.
    /// </summary>
    public static bool FinishedFirst(string before, string after, Guid actor)
    {
        var then = Read(before);
        var now = Read(after);
        var partner = now.Players.FirstOrDefault(player => player != actor);
        return !Finished(then, actor) && Finished(now, actor) && partner != Guid.Empty && !Finished(now, partner);
    }

    /// <summary>A player's list in <paramref name="lists"/>, one slot per round, made (or filled out) when missing.</summary>
    private static List<T> Slots<T>(Dictionary<Guid, List<T>> lists, Guid player, int rounds, T empty)
    {
        if (!lists.TryGetValue(player, out var list)) lists[player] = list = [];
        while (list.Count < rounds) list.Add(empty);
        return list;
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var me = viewer is { } v && state.Scores.ContainsKey(v) ? v : (Guid?)null;
        var partner = me is { } m ? state.Players.FirstOrDefault(player => player != m) : (Guid?)null;
        var hearts = Hearts(state);
        var finished = state.Players.All(player => Finished(state, player));
        var totals = finished ? state.Players.ToDictionary(player => player, player => Total(state, player)) : null;
        var outcome = finished ? Decide(state) : null;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Phase,
            totalRounds = state.Rounds.Count,
            rounds = state.Rounds.Select((round, index) =>
            {
                var myScore = me is { } m ? ScoreOf(state, m, index) : null;
                var theirs = partner is { } p ? ScoreOf(state, p, index) : null;
                var both = Both(state, index);
                return new
                {
                    index,
                    round.Kind,
                    // The viewer's own seed: a round they backed out of and opened again has been dealt anew.
                    seed = me is { } own ? SeedOf(state, own, index) : round.Seed,
                    // How many times the viewer has opened this round (on any phone): opening it again without a score deals it anew.
                    opens = me is { } opener && state.Opens.TryGetValue(opener, out var counts) && index < counts.Count ? counts[index] : 0,
                    mine = myScore,
                    // The partner's score is theirs alone until you've played the round too; until then, only that they have.
                    partner = myScore is null ? null : theirs,
                    played = theirs is not null,
                    winner = both ? RoundWinner(state, index) : null,
                    tie = both && RoundWinner(state, index) is null,
                };
            }),
            hearts,
            totals,
            winner = outcome?.Winner,
            tie = outcome is { Winner: null },
            // What settled it: "hearts", or "points" when the hearts were level; null for a tie and until the end.
            decidedBy = outcome?.DecidedBy,
            // The gap in hearts, and in points, at the end.
            margin = outcome?.HeartMargin,
            pointMargin = outcome?.PointMargin,
        }, PublicJson);
    }

    /// <summary>
    /// The hearts (rounds won, a tie a heart each) are the scores kept for the history. A finished duel declares its
    /// winner, so when the hearts are level and the points broke the tie the history still names the duel's winner;
    /// a tie on both declares none, and the level hearts make it a draw. An unfinished duel declares no winner.
    /// </summary>
    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        var played = Enumerable.Range(0, state.Rounds.Count).Count(index => Both(state, index));
        var finished = state.Players.All(player => Finished(state, player));
        return new GameScoreSummary(Hearts(state), played, finished ? Decide(state).Winner : null);
    }

    /// <summary>
    /// For the admin insights: every score played (its round's kind and the points), the gap in hearts over the rounds
    /// both have played (null before any), and whether a finished duel was a tie. Numbers only.
    /// </summary>
    public static SkillDuelTally Tally(string stateJson)
    {
        var state = Read(stateJson);
        var scores = new List<(string Kind, int Score)>();
        foreach (var player in state.Players)
            for (var round = 0; round < state.Rounds.Count; round++)
                if (ScoreOf(state, player, round) is { } score) scores.Add((state.Rounds[round].Kind, score));
        var hearts = Hearts(state).Values.ToList();
        var anyPlayed = Enumerable.Range(0, state.Rounds.Count).Any(round => Both(state, round));
        var finished = state.Players.Count > 0 && state.Players.All(player => Finished(state, player));
        return new SkillDuelTally(scores, anyPlayed && hearts.Count == 2 ? Math.Abs(hearts[0] - hearts[1]) : null, finished && Decide(state).Winner is null);
    }

    /// <summary>
    /// The score in <c>{"score": n}</c>: a whole number from 0 to 100; and the seed it was played on (<c>"seed"</c>, a
    /// whole number), when the phone says.
    /// </summary>
    private static (int? Score, int? Seed) ParseScore(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return (null, null);
        try
        {
            using var document = JsonDocument.Parse(value);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return (null, null);
            if (!root.TryGetProperty("score", out var score) || score.ValueKind != JsonValueKind.Number || !score.TryGetInt32(out var n)) return (null, null);
            int? seed = root.TryGetProperty("seed", out var played) && played.ValueKind == JsonValueKind.Number && played.TryGetInt32(out var s) ? s : null;
            return (n is >= 0 and <= MaxScore ? n : null, seed);
        }
        catch (JsonException)
        {
            return (null, null);
        }
    }

    private static int? ScoreOf(State state, Guid player, int round)
        => state.Scores.TryGetValue(player, out var scores) && round < scores.Count ? scores[round] : null;

    private static bool Finished(State state, Guid player)
        => state.Scores.TryGetValue(player, out var scores) && scores.Count == state.Rounds.Count && scores.All(score => score is not null);

    private static bool Both(State state, int round) => state.Players.Count > 0 && state.Players.All(player => ScoreOf(state, player, round) is not null);

    private static int Total(State state, Guid player) => state.Scores.TryGetValue(player, out var scores) ? scores.Sum(score => score ?? 0) : 0;

    /// <summary>The higher score of a round both have played; null for a tie.</summary>
    private static Guid? RoundWinner(State state, int round)
    {
        var scored = state.Players.Select(player => (player, score: ScoreOf(state, player, round) ?? 0)).ToList();
        var best = scored.Max(item => item.score);
        var leaders = scored.Where(item => item.score == best).ToList();
        return leaders.Count == 1 ? leaders[0].player : null;
    }

    /// <summary>
    /// The duel's result over the rounds both have played: more hearts wins; level on hearts, the higher total of
    /// points; level on both, a tie (no winner, <c>DecidedBy</c> null).
    /// </summary>
    private static DuelOutcome Decide(State state)
    {
        var hearts = Hearts(state);
        var points = PlayedTotals(state);
        var heartMargin = hearts.Count == 0 ? 0 : hearts.Values.Max() - hearts.Values.Min();
        var pointMargin = points.Count == 0 ? 0 : points.Values.Max() - points.Values.Min();
        if (Leader(hearts) is { } byHearts) return new DuelOutcome(byHearts, "hearts", heartMargin, pointMargin);
        if (Leader(points) is { } byPoints) return new DuelOutcome(byPoints, "points", heartMargin, pointMargin);
        return new DuelOutcome(null, null, heartMargin, pointMargin);
    }

    /// <summary>The one player with the most; null when it is shared (or nobody played).</summary>
    private static Guid? Leader(Dictionary<Guid, int> counts)
    {
        if (counts.Count == 0) return null;
        var best = counts.Values.Max();
        var leaders = counts.Where(item => item.Value == best).Select(item => item.Key).ToList();
        return leaders.Count == 1 ? leaders[0] : null;
    }

    private sealed record DuelOutcome(Guid? Winner, string? DecidedBy, int HeartMargin, int PointMargin);

    private static Dictionary<Guid, int> PlayedTotals(State state) => state.Players.ToDictionary(player => player,
        player => Enumerable.Range(0, state.Rounds.Count).Where(round => Both(state, round)).Sum(round => ScoreOf(state, player, round) ?? 0));

    /// <summary>Hearts over the rounds both have played: the higher score wins one, a tie gives one each.</summary>
    private static Dictionary<Guid, int> Hearts(State state)
    {
        var hearts = state.Players.ToDictionary(player => player, _ => 0);
        for (var round = 0; round < state.Rounds.Count; round++)
        {
            if (!Both(state, round)) continue;
            if (RoundWinner(state, round) is { } winner) hearts[winner]++;
            else foreach (var player in state.Players) hearts[player]++;
        }
        return hearts;
    }

    private static List<Round>? SetupRounds(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            var rounds = JsonSerializer.Deserialize<Setup>(setupJson, SetupJson)?.Rounds?.Where(round => Kinds.Contains(round.Kind)).ToList();
            return rounds is { Count: Rounds } ? rounds : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static State Read(string json) => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));

    private sealed class Setup
    {
        public List<Round>? Rounds { get; set; }
    }

    /// <summary>One round: which skill game, and the seed for its shape or target.</summary>
    public sealed class Round
    {
        public string Kind { get; set; } = string.Empty;
        public int Seed { get; set; }
    }

    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public List<Round> Rounds { get; set; } = [];
        /// <summary>Each player's score per round, null until played.</summary>
        public Dictionary<Guid, List<int?>> Scores { get; set; } = [];
        public string Phase { get; set; } = PlayingPhase;
        /// <summary>Each player's own seed per round once they've been dealt it again (null: the round's). Empty in a duel from before.</summary>
        public Dictionary<Guid, List<int?>> Seeds { get; set; } = [];
        /// <summary>How many times each player has opened each round (any phone). Empty in a duel from before.</summary>
        public Dictionary<Guid, List<int>> Opens { get; set; } = [];
    }
}

/// <summary>
/// A duel's scores as the admin insights read them: (kind, points) per round played, the gap in hearts, and whether a
/// finished duel was a tie (level on hearts and on points).
/// </summary>
public sealed record SkillDuelTally(IReadOnlyList<(string Kind, int Score)> Scores, int? Margin, bool Tied = false);
