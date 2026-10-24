using System.Security.Cryptography;
using System.Text.Json;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Truth or dare (docs/games.md): in turn, no clock. Ten turns, alternating. The chooser picks truth or dare and
/// gets a prompt from the region's bank (never the same one twice in a game); the partner then marks it done (a point
/// to the chooser) or skipped, and becomes the next chooser.
/// </summary>
public sealed class TruthOrDareRules : IGameRules
{
    public const int Turns = 10;
    public const string Truth = "truth";
    public const string Dare = "dare";
    public const string Done = "done";
    public const string Skipped = "skipped";
    private const string ChoosingPhase = "choosing";
    private const string JudgingPhase = "judging";
    private const string CompletedPhase = "completed";

    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.TruthOrDare;

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var state = new State
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Region = GameRegion.Of(setupJson),
        };
        return Result(state);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        var value = move.Value?.Trim().ToLowerInvariant();
        if (string.Equals(move.Action, "choose", StringComparison.OrdinalIgnoreCase))
        {
            if (state.Phase != ChoosingPhase) throw new InvalidOperationException(Loc.T("Your partner is still on their prompt."));
            if (move.Actor != Chooser(state)) throw new InvalidOperationException(Loc.T("It's your partner's turn to choose."));
            if (value is not (Truth or Dare)) throw new InvalidOperationException(Loc.T("Choose truth or dare."));
            var bank = value == Truth ? TruthOrDarePrompts.Truths(state.Region) : TruthOrDarePrompts.Dares(state.Region);
            var unused = bank.Where(prompt => !state.Used.Contains(prompt)).ToList();
            if (unused.Count == 0) throw new InvalidOperationException(Loc.T("There are no new prompts of that kind left. Pick the other one."));
            var prompt = unused[RandomNumberGenerator.GetInt32(unused.Count)];
            state.Used.Add(prompt);
            state.Choice = value;
            state.Prompt = prompt;
            state.Phase = JudgingPhase;
            return Result(state);
        }
        if (string.Equals(move.Action, "judge", StringComparison.OrdinalIgnoreCase))
        {
            if (state.Phase != JudgingPhase) throw new InvalidOperationException(Loc.T("There is nothing to judge yet."));
            if (move.Actor != Judge(state)) throw new InvalidOperationException(Loc.T("Your partner judges your prompt."));
            if (value is not (Done or Skipped)) throw new InvalidOperationException(Loc.T("Mark it done or skipped."));
            var chooser = Chooser(state);
            if (value == Done) state.Scores[chooser]++;
            state.Last = new Outcome { Chooser = chooser, Choice = state.Choice!, Prompt = state.Prompt!, Result = value };
            state.Choice = null;
            state.Prompt = null;
            state.Turn++;
            state.Phase = state.Turn >= Turns ? CompletedPhase : ChoosingPhase;
            return Result(state);
        }
        throw new InvalidOperationException(Loc.T("That move isn't part of Truth or dare."));
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        // Nothing here is secret: both partners see the prompt as soon as it is drawn.
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            turn = state.Turn,
            totalTurns = Turns,
            phase = state.Phase,
            chooser = state.Phase == CompletedPhase ? (Guid?)null : Chooser(state),
            judge = state.Phase == CompletedPhase ? (Guid?)null : Judge(state),
            choice = state.Choice,
            prompt = Show(state.Prompt),
            last = state.Last is null ? null : new { state.Last.Chooser, state.Last.Choice, prompt = Show(state.Last.Prompt), state.Last.Result },
        }, PublicJson);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Turn, null);
    }

    private static string? Show(string? prompt) => prompt is not null && TruthOrDarePrompts.IsTranslated(prompt) ? Loc.T(prompt) : prompt;

    private static Guid Chooser(State state) => state.Players[state.Turn % state.Players.Count];

    private static Guid Judge(State state) => state.Players[(state.Turn + 1) % state.Players.Count];

    private static GameEngineResult Result(State state)
    {
        var completed = state.Phase == CompletedPhase;
        Guid? next = completed ? null : state.Phase == JudgingPhase ? Judge(state) : Chooser(state);
        return new GameEngineResult(JsonSerializer.Serialize(state), next, null, completed);
    }

    private static State Read(string json)
        => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException("Game state is invalid.");

    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public string Region { get; set; } = GameRegion.Global;
        /// <summary>Turns finished so far; turn n is chosen by <c>Players[n % 2]</c> and judged by the other.</summary>
        public int Turn { get; set; }
        public string Phase { get; set; } = ChoosingPhase;
        public string? Choice { get; set; }
        /// <summary>The prompt in its English (or Naija) form; translated as it is shown.</summary>
        public string? Prompt { get; set; }
        public List<string> Used { get; set; } = [];
        public Outcome? Last { get; set; }
    }

    private sealed class Outcome
    {
        public Guid Chooser { get; set; }
        public string Choice { get; set; } = Truth;
        public string Prompt { get; set; } = string.Empty;
        public string Result { get; set; } = Done;
    }
}
