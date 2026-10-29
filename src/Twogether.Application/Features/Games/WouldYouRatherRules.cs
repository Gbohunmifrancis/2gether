using System.Text.Json;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Would you rather (docs/games.md): ten rounds, both pick at once, no clock. Each partner picks A or B; a pick stays
/// secret until both are in, then both are revealed. Matching picks give each partner a point: it's cooperative.
/// </summary>
public sealed class WouldYouRatherRules : IGameRules
{
    public const int Rounds = 10;
    private const string PickingPhase = "picking";
    private const string CompletedPhase = "completed";

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.WouldYouRather;

    public bool AnyoneMayAct => true;

    /// <summary>The setup for a new game: its region and its ten questions, fixed once the game is invited.</summary>
    public static string CreateSetup(string region)
        => JsonSerializer.Serialize(new { region, pairs = WouldYouRatherBank.Pick(region, Rounds) }, SetupJson);

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var region = GameRegion.Of(setupJson);
        var state = new State
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Region = region,
            Deck = SetupPairs(setupJson) ?? WouldYouRatherBank.Pick(region, Rounds).Select(pair => new Pair { A = pair.A, B = pair.B }).ToList(),
        };
        return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[0], null, false);
    }

    public GameEngineResult Apply(GameMove move)
    {
        if (!string.Equals(move.Action, "pick", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("Would you rather expects a pick."));
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase || state.Round >= state.Deck.Count) throw new InvalidOperationException(Loc.T("This game is over."));
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (move.Index is not (0 or 1)) throw new InvalidOperationException(Loc.T("Pick one of the two."));
        if (state.Picks.ContainsKey(move.Actor)) throw new InvalidOperationException(Loc.T("You already picked. Wait for your partner."));
        state.Picks[move.Actor] = move.Index.Value;

        if (state.Picks.Count < state.Players.Count)
            return new GameEngineResult(JsonSerializer.Serialize(state), state.Players.First(player => !state.Picks.ContainsKey(player)), null, false);

        var pair = state.Deck[state.Round];
        var match = state.Picks.Values.Distinct().Count() == 1;
        if (match) foreach (var player in state.Players) state.Scores[player] = state.Scores.GetValueOrDefault(player) + 1;
        state.Last = new Reveal { Round = state.Round, A = pair.A, B = pair.B, Picks = new Dictionary<Guid, int>(state.Picks), Match = match };
        state.Picks.Clear();
        state.Round++;
        var completed = state.Round >= state.Deck.Count;
        if (completed) state.Phase = CompletedPhase;
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[0], null, completed);
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var pair = state.Round < state.Deck.Count && state.Phase != CompletedPhase ? state.Deck[state.Round] : null;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            state.Round,
            totalRounds = state.Deck.Count,
            state.Phase,
            a = pair is null ? null : Show(state, pair.A),
            b = pair is null ? null : Show(state, pair.B),
            // Who has picked is public; what they picked is theirs alone until both are in.
            picked = state.Picks.Keys,
            myPick = viewer is { } who && state.Picks.TryGetValue(who, out var mine) ? mine : (int?)null,
            last = state.Last is { } last ? new { last.Round, a = Show(state, last.A), b = Show(state, last.B), last.Picks, last.Match } : null
        }, PublicJson);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Round, null);
    }

    /// <summary>Global content is written in English and read in each partner's language; Naija content isn't translated.</summary>
    private static string Show(State state, string text) => state.Region == GameRegion.Nigeria ? text : Loc.T(text);

    private static List<Pair>? SetupPairs(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            var pairs = JsonSerializer.Deserialize<Setup>(setupJson, SetupJson)?.Pairs?.Where(pair => !string.IsNullOrWhiteSpace(pair.A) && !string.IsNullOrWhiteSpace(pair.B)).ToList();
            return pairs is { Count: > 0 } ? pairs : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static State Read(string json) => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));

    private sealed class Setup
    {
        public List<Pair>? Pairs { get; set; }
    }

    /// <summary>
    /// The stored state. The questions are kept as <c>Deck</c>: before this game had rules it played as the quiz, whose
    /// state keeps a list of strings under <c>Questions</c>. Such an old state still reads (as a finished game).
    /// </summary>
    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public string Region { get; set; } = GameRegion.Global;
        public List<Pair> Deck { get; set; } = [];
        public int Round { get; set; }
        public string Phase { get; set; } = PickingPhase;
        /// <summary>This round's picks: 0 for A, 1 for B. Secret until both are in.</summary>
        public Dictionary<Guid, int> Picks { get; set; } = [];
        public Reveal? Last { get; set; }
    }

    private sealed class Pair
    {
        public string A { get; set; } = string.Empty;
        public string B { get; set; } = string.Empty;
    }

    private sealed class Reveal
    {
        public int Round { get; set; }
        public string A { get; set; } = string.Empty;
        public string B { get; set; } = string.Empty;
        public Dictionary<Guid, int> Picks { get; set; } = [];
        public bool Match { get; set; }
    }
}
