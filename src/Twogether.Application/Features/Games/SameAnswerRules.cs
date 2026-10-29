using System.Text.Json;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Same answer (<see cref="GameType.CoupleQuiz"/>, docs/games.md): ten prompts about the two of you, in turn, no clock.
/// Whoever's turn it is answers in their own words; when both have, matching answers (trimmed, any case) give each
/// partner a point. Cooperative: the score is how many you matched. Answers are never shown to the other partner.
/// </summary>
/// <remarks>
/// The state is the one the engine's built-in quiz kept (plus the region), so games started before this class existed
/// carry on as they were, trivia prompts and all.
/// </remarks>
public sealed class SameAnswerRules : IGameRules
{
    public const int Rounds = 10;

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.CoupleQuiz;

    /// <summary>The setup for a new game: its region and its ten prompts, fixed once the game is invited.</summary>
    public static string CreateSetup(string region)
        => JsonSerializer.Serialize(new { region, prompts = SameAnswerBank.Pick(region, Rounds) }, SetupJson);

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var region = GameRegion.Of(setupJson);
        // An invite made before Same answer had prompts of its own carries trivia questions instead: it gets fresh prompts.
        var prompts = SetupPrompts(setupJson) ?? SameAnswerBank.Pick(region, Rounds).ToList();
        var state = new State
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Region = region,
            Questions = prompts,
            Prompt = prompts[0],
        };
        return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[0], null, false);
    }

    public GameEngineResult Apply(GameMove move)
    {
        if (!string.Equals(move.Action, "answer", StringComparison.OrdinalIgnoreCase) || string.IsNullOrWhiteSpace(move.Value))
            throw new InvalidOperationException(Loc.T("Write an answer first."));
        var state = Read(move.StateJson);
        if (state.Round >= state.Questions.Count) throw new InvalidOperationException(Loc.T("This game is over."));
        if (state.Players[state.TurnIndex] != move.Actor) throw new InvalidOperationException(Loc.T("It's your partner's turn."));
        state.Answers[move.Actor] = move.Value.Trim();

        if (state.Answers.Count < state.Players.Count)
        {
            state.TurnIndex = (state.TurnIndex + 1) % state.Players.Count;
            return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[state.TurnIndex], null, false);
        }

        var match = state.Answers.Values.Distinct(StringComparer.OrdinalIgnoreCase).Count() == 1;
        if (match) foreach (var player in state.Players) state.Scores[player] = state.Scores.GetValueOrDefault(player) + 1;
        state.LastResult = match ? "match" : "different";
        state.Round++;
        state.Answers.Clear();
        var completed = state.Round >= state.Questions.Count;
        if (!completed)
        {
            state.Prompt = state.Questions[state.Round];
            state.TurnIndex = state.Round % state.Players.Count;
        }
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[state.TurnIndex], null, completed);
    }

    /// <summary>Nobody sees an answer, not even their own: only how many are in.</summary>
    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            state.Round,
            totalRounds = state.Questions.Count,
            state.TurnIndex,
            prompt = Show(state, state.Prompt),
            answerCount = state.Answers.Count,
            state.LastResult
        }, PublicJson);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Round, null);
    }

    /// <summary>
    /// Global prompts are written in English and read in each partner's language; Naija prompts aren't translated.
    /// Trivia prompts in older games have no translation, so they show as they are.
    /// </summary>
    private static string Show(State state, string text) => state.Region == GameRegion.Nigeria ? text : Loc.T(text);

    private static List<string>? SetupPrompts(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            var prompts = JsonSerializer.Deserialize<Setup>(setupJson, SetupJson)?.Prompts?.Where(prompt => !string.IsNullOrWhiteSpace(prompt)).ToList();
            return prompts is { Count: > 0 } ? prompts : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static State Read(string json) => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));

    private sealed class Setup
    {
        public List<string>? Prompts { get; set; }
    }

    /// <summary>The engine's old quiz state, field for field, plus the region (absent, so global, in older games).</summary>
    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public int Round { get; set; }
        public int TurnIndex { get; set; }
        public string Prompt { get; set; } = string.Empty;
        public Dictionary<Guid, string> Answers { get; set; } = [];
        public string? LastResult { get; set; }
        public List<string> Questions { get; set; } = [];
        public string Region { get; set; } = GameRegion.Global;
    }
}
