using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Trivia (docs/games.md): ten multiple-choice questions, both partners answer at once, no clock. A partner's pick and
/// the right answer stay hidden until both have answered; then both see them and a right answer scores a point.
/// Global couples get Open Trivia DB (the built-in bank if it's down); Nigerian couples get Naija trivia.
/// </summary>
public sealed class TriviaRules : IGameRules
{
    public const int QuestionCount = 10;
    private const string AnswerAction = "answer";

    private static readonly JsonSerializerOptions PublicJsonOptions = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.Trivia;

    public bool AnyoneMayAct => true;

    /// <summary>The questions a new game is set up with, fixed when it is invited.</summary>
    public static async Task<IReadOnlyList<TriviaQuestion>> QuestionsFor(string region, IGameQuestionProvider provider, CancellationToken cancellationToken)
    {
        if (region == GameRegion.Nigeria) return NaijaTrivia.Draw(QuestionCount);
        var fetched = (await provider.GetTriviaAsync(QuestionCount, cancellationToken)).Where(question => question.IsPlayable).Take(QuestionCount).ToList();
        return fetched.Count == QuestionCount ? fetched : [.. fetched, .. GlobalTrivia.Draw(QuestionCount - fetched.Count)];
    }

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var questions = FromSetup(setupJson);
        if (questions.Count == 0)
            questions = [.. GameRegion.Of(setupJson) == GameRegion.Nigeria ? NaijaTrivia.Draw(QuestionCount) : GlobalTrivia.Draw(QuestionCount)];
        var state = new TriviaState
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Questions = questions,
        };
        return new GameEngineResult(JsonSerializer.Serialize(state), players[0], null, false);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Round >= state.Questions.Count) throw new InvalidOperationException(Loc.T("This game is over."));
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        var question = state.Questions[state.Round];
        if (!string.Equals(move.Action, AnswerAction, StringComparison.OrdinalIgnoreCase) || move.Index is not { } pick || pick < 0 || pick >= question.Options.Count)
            throw new InvalidOperationException(Loc.T("Pick one of the four answers."));
        if (state.Answers.ContainsKey(move.Actor)) throw new InvalidOperationException(Loc.T("You already answered this one. Wait for your partner."));
        state.Answers[move.Actor] = pick;

        if (state.Answers.Count < state.Players.Count)
            return new GameEngineResult(JsonSerializer.Serialize(state), state.Players.First(player => !state.Answers.ContainsKey(player)), null, false);

        // Both are in: show both picks and the right answer, score, and move on to the next question.
        foreach (var (player, answer) in state.Answers)
            if (answer == question.Answer) state.Scores[player]++;
        state.Reveal = new TriviaReveal { Round = state.Round, Picks = new Dictionary<Guid, int>(state.Answers) };
        state.Answers.Clear();
        state.Round++;
        var completed = state.Round >= state.Questions.Count;
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[0], null, completed);
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var current = state.Round < state.Questions.Count ? state.Questions[state.Round] : null;
        var revealed = state.Reveal is { } reveal && reveal.Round < state.Questions.Count ? state.Questions[reveal.Round] : null;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            state.Round,
            totalRounds = state.Questions.Count,
            // The current question without its answer: the right one only shows once both have picked.
            question = current is null ? null : Shown(current),
            answered = state.Players.Where(state.Answers.ContainsKey),
            myAnswer = viewer is { } me && state.Answers.TryGetValue(me, out var mine) ? mine : (int?)null,
            reveal = revealed is null ? null : new
            {
                round = state.Reveal!.Round,
                question = Shown(revealed),
                answer = revealed.Answer,
                picks = state.Reveal.Picks,
            },
            completed = current is null,
        }, PublicJsonOptions);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Round, null);
    }

    /// <summary>The built-in bank is English marked with Loc.N, so each partner reads it in their own language.</summary>
    private static object Shown(TriviaQuestion question) => question.Translate
        ? new { text = Loc.T(question.Text), options = question.Options.Select(option => Loc.T(option)).ToList() }
        : new { text = question.Text, options = question.Options.ToList() };

    private static List<TriviaQuestion> FromSetup(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return [];
        try
        {
            using var setup = JsonDocument.Parse(setupJson);
            if (!setup.RootElement.TryGetProperty("trivia", out var trivia) || trivia.ValueKind != JsonValueKind.Array) return [];
            return (trivia.Deserialize<List<TriviaQuestion>>() ?? []).Where(question => question.IsPlayable).Take(QuestionCount).ToList();
        }
        catch (JsonException)
        {
            return [];
        }
    }

    private static TriviaState Read(string stateJson)
        => JsonSerializer.Deserialize<TriviaState>(stateJson) ?? throw new InvalidOperationException("Game state is invalid.");

    private sealed class TriviaState
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        /// <summary>The question being answered; equals the question count once the game is over.</summary>
        public int Round { get; set; }
        public List<TriviaQuestion> Questions { get; set; } = [];
        /// <summary>This question's picks so far: secret from the partner until both are in.</summary>
        public Dictionary<Guid, int> Answers { get; set; } = [];
        public TriviaReveal? Reveal { get; set; }
    }

    /// <summary>The last question both answered: what each picked. Its right answer is in the question.</summary>
    private sealed class TriviaReveal
    {
        public int Round { get; set; }
        public Dictionary<Guid, int> Picks { get; set; } = [];
    }
}
