using System.Text.Json;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Guess my answer (docs/games.md): eight rounds, in turn, no clock. Each round one partner (the subject) answers a
/// question about themselves from four choices, then the other guesses. A right guess is a point for the guesser.
/// The subject alternates, starting with the first player. The answer stays the subject's secret until the guess is in.
/// </summary>
public sealed class GuessMyAnswerRules : IGameRules
{
    public const int Rounds = 8;
    private const string AnsweringPhase = "answering";
    private const string GuessingPhase = "guessing";
    private const string CompletedPhase = "completed";

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.GuessMyAnswer;

    /// <summary>The setup for a new game: its region and its eight questions, fixed once the game is invited.</summary>
    public static string CreateSetup(string region, IEnumerable<string>? recentSetups = null)
        => JsonSerializer.Serialize(new { region, questions = GuessMyAnswerBank.Pick(region, Rounds, FreshPick.Seen(recentSetups, "questions", "text")) }, SetupJson);

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var region = GameRegion.Of(setupJson);
        var state = new State
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Region = region,
            Deck = SetupQuestions(setupJson) ?? GuessMyAnswerBank.Pick(region, Rounds).Select(ToRound).ToList(),
        };
        return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[0], null, false);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase || state.Round >= state.Deck.Count) throw new InvalidOperationException(Loc.T("This game is over."));
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        var question = state.Deck[state.Round];
        var choice = move.Index is { } index && index >= 0 && index < question.Choices.Count ? index : (int?)null;

        if (string.Equals(move.Action, "answer", StringComparison.OrdinalIgnoreCase))
        {
            if (state.Phase != AnsweringPhase) throw new InvalidOperationException(Loc.T("The answer is in. Now it's time to guess."));
            if (move.Actor != Subject(state)) throw new InvalidOperationException(Loc.T("This round is about your partner. Wait to guess their answer."));
            state.Answer = choice ?? throw new InvalidOperationException(Loc.T("Pick one of the four answers."));
            state.Phase = GuessingPhase;
            return new GameEngineResult(JsonSerializer.Serialize(state), Guesser(state), null, false);
        }

        if (string.Equals(move.Action, "guess", StringComparison.OrdinalIgnoreCase))
        {
            if (state.Phase != GuessingPhase) throw new InvalidOperationException(Loc.T("Wait for your partner to answer first."));
            if (move.Actor != Guesser(state)) throw new InvalidOperationException(Loc.T("This round is about you. Your partner is guessing."));
            var guess = choice ?? throw new InvalidOperationException(Loc.T("Pick one of the four answers."));
            var answer = state.Answer ?? 0;
            var right = guess == answer;
            if (right) state.Scores[move.Actor] = state.Scores.GetValueOrDefault(move.Actor) + 1;
            state.Last = new Reveal { Round = state.Round, Subject = Subject(state), Guesser = move.Actor, Text = question.Text, Choices = question.Choices, Answer = answer, Guess = guess, Right = right };
            state.History.Add(state.Last);
            state.Round++;
            state.Answer = null;
            var completed = state.Round >= state.Deck.Count;
            state.Phase = completed ? CompletedPhase : AnsweringPhase;
            return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : Subject(state), null, completed);
        }

        throw new InvalidOperationException(Loc.T("Guess my answer expects an answer or a guess."));
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var question = state.Round < state.Deck.Count && state.Phase != CompletedPhase ? state.Deck[state.Round] : null;
        var subject = question is null ? (Guid?)null : Subject(state);
        // The subject sees their own answer while their partner guesses; the guesser sees it once their guess is in.
        var answer = viewer is { } who && who == subject && state.Phase == GuessingPhase ? state.Answer : null;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            state.Round,
            totalRounds = state.Deck.Count,
            state.Phase,
            subject,
            guesser = question is null ? (Guid?)null : Guesser(state),
            question = question is null ? null : Show(state, question.Text),
            choices = question?.Choices.Select(choice => Show(state, choice)) ?? [],
            answered = state.Phase == GuessingPhase,
            answer,
            last = state.Last is { } last ? View(state, last) : null,
            // Every round played so far, both sides shown: the game's recap once it's over.
            history = state.History.Select(played => View(state, played)),
        }, PublicJson);
    }

    private static object View(State state, Reveal played) => new
    {
        played.Round,
        played.Subject,
        played.Guesser,
        question = Show(state, played.Text),
        choices = played.Choices.Select(choice => Show(state, choice)),
        played.Answer,
        played.Guess,
        played.Right
    };

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Round, null);
    }

    private static Guid Subject(State state) => state.Players[state.Round % state.Players.Count];

    private static Guid Guesser(State state) => state.Players[(state.Round + 1) % state.Players.Count];

    /// <summary>Global content is written in English and read in each partner's language; Naija content isn't translated.</summary>
    private static string Show(State state, string text) => state.Region == GameRegion.Nigeria ? text : Loc.T(text);

    private static RoundQuestion ToRound(ChoiceQuestion question) => new() { Text = question.Text, Choices = question.Choices.ToList() };

    private static List<RoundQuestion>? SetupQuestions(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            var setup = JsonSerializer.Deserialize<Setup>(setupJson, SetupJson);
            var questions = setup?.Questions?.Where(question => !string.IsNullOrWhiteSpace(question.Text) && question.Choices.Count == 4).ToList();
            return questions is { Count: > 0 } ? questions : null;
        }
        catch (JsonException)
        {
            // An invite made before Guess my answer had its own questions carried trivia strings: pick fresh ones.
            return null;
        }
    }

    private static State Read(string json) => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));

    private sealed class Setup
    {
        public List<RoundQuestion>? Questions { get; set; }
    }

    /// <summary>
    /// The stored state. The questions are kept as <c>Deck</c>, not <c>Questions</c>: before this game had rules it played
    /// as the quiz, whose state keeps a list of strings there. Such an old state still reads (as a finished game).
    /// </summary>
    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public string Region { get; set; } = GameRegion.Global;
        public List<RoundQuestion> Deck { get; set; } = [];
        public int Round { get; set; }
        public string Phase { get; set; } = AnsweringPhase;
        /// <summary>The subject's answer this round: secret from the guesser until the guess is in.</summary>
        public int? Answer { get; set; }
        public Reveal? Last { get; set; }
        /// <summary>Every round played, answer and guess (empty in games from before it was kept).</summary>
        public List<Reveal> History { get; set; } = [];
    }

    private sealed class RoundQuestion
    {
        public string Text { get; set; } = string.Empty;
        public List<string> Choices { get; set; } = [];
    }

    private sealed class Reveal
    {
        public int Round { get; set; }
        public Guid Subject { get; set; }
        public Guid Guesser { get; set; }
        public string Text { get; set; } = string.Empty;
        public List<string> Choices { get; set; } = [];
        public int Answer { get; set; }
        public int Guess { get; set; }
        public bool Right { get; set; }
    }
}
