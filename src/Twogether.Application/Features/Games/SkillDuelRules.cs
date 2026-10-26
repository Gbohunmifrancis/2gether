using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Heart to heart (docs/games.md): five skill rounds, the same for both (the same kind and seed, so the same shape or
/// target), each played on the partner's own time, no clock. Each round is scored 0 to 100 on the phone and sent as
/// <c>score</c>. A partner's score stays hidden from you until you've played that round too. A round goes to the
/// higher score, a tie is a heart each; the duel goes to the higher total.
/// </summary>
public sealed class SkillDuelRules : IGameRules
{
    public const int Rounds = 5;
    public const int MaxScore = 100;
    private const string PlayingPhase = "playing";
    private const string CompletedPhase = "completed";

    /// <summary>The skill games, by the app's <c>SkillKind</c> names (ui/play/Skills.kt): they travel as is.</summary>
    public static readonly IReadOnlyList<string> Kinds = ["FairShare", "FillTheHeart", "OtherHalf", "Heartbeat", "KissTheCenter", "PromiseRing"];

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.SkillDuel;

    public bool AnyoneMayAct => true;

    /// <summary>The setup for a new duel: its region, a seed, and the five rounds that seed draws.</summary>
    public static string CreateSetup(string region, int? seed = null)
    {
        var duelSeed = seed ?? Random.Shared.Next();
        return JsonSerializer.Serialize(new { region, seed = duelSeed, rounds = Plan(duelSeed) }, SetupJson);
    }

    /// <summary>
    /// Five rounds from a seed: five of the six kinds in a shuffled order (so none comes twice, let alone twice in a
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

    public GameEngineResult Apply(GameMove move)
    {
        if (!string.Equals(move.Action, "score", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("Heart to heart expects a score."));
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        if (!state.Players.Contains(move.Actor) || !state.Scores.TryGetValue(move.Actor, out var mine))
            throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (move.Index is not { } round || round < 0 || round >= state.Rounds.Count)
            throw new InvalidOperationException(Loc.T("Pick one of the five rounds."));
        var score = ParseScore(move.Value) ?? throw new InvalidOperationException(Loc.T("A score is a whole number from 0 to 100."));
        if (mine[round] is not null) throw new InvalidOperationException(Loc.T("You already played this round."));
        mine[round] = score;

        var completed = state.Players.All(player => Finished(state, player));
        if (completed) state.Phase = CompletedPhase;
        // Both may play at any time; the turn names whoever still has rounds to play, the partner first.
        var partner = state.Players.FirstOrDefault(player => player != move.Actor);
        Guid? next = completed ? null : partner != Guid.Empty && !Finished(state, partner) ? partner : move.Actor;
        return new GameEngineResult(JsonSerializer.Serialize(state), next, null, completed);
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var me = viewer is { } v && state.Scores.ContainsKey(v) ? v : (Guid?)null;
        var partner = me is { } m ? state.Players.FirstOrDefault(player => player != m) : (Guid?)null;
        var hearts = Hearts(state);
        var finished = state.Players.All(player => Finished(state, player));
        var totals = finished ? state.Players.ToDictionary(player => player, player => Total(state, player)) : null;
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
                    round.Seed,
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
            winner = finished ? DuelWinner(state) : null,
            tie = finished && DuelWinner(state) is null,
            margin = totals is null ? (int?)null : Math.Abs(totals.Values.Max() - totals.Values.Min()),
        }, PublicJson);
    }

    /// <summary>
    /// Hearts (rounds won, a tie a heart each) are the scores; the duel's winner is the higher total. A perfect tie on
    /// totals has no declared winner, so the history falls back to the hearts.
    /// </summary>
    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        var played = Enumerable.Range(0, state.Rounds.Count).Count(index => Both(state, index));
        var finished = state.Players.All(player => Finished(state, player));
        return new GameScoreSummary(Hearts(state), played, finished ? DuelWinner(state) : null);
    }

    /// <summary>The score in <c>{"score": n}</c>: a whole number from 0 to 100, nothing else.</summary>
    private static int? ParseScore(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        try
        {
            using var document = JsonDocument.Parse(value);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            if (!root.TryGetProperty("score", out var score) || score.ValueKind != JsonValueKind.Number || !score.TryGetInt32(out var n)) return null;
            return n is >= 0 and <= MaxScore ? n : null;
        }
        catch (JsonException)
        {
            return null;
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

    private static Guid? DuelWinner(State state)
    {
        var totals = state.Players.Select(player => (player, total: Total(state, player))).ToList();
        if (totals.Count == 0) return null;
        var best = totals.Max(item => item.total);
        var leaders = totals.Where(item => item.total == best).ToList();
        return leaders.Count == 1 ? leaders[0].player : null;
    }

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
    }
}
