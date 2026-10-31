using System.Globalization;
using System.Text;
using System.Text.Json;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Same answer (<see cref="GameType.CoupleQuiz"/>, docs/games.md): ten prompts about the two of you, both answer at
/// once, no clock. A prompt is answered in your own words, or, for a "Who…" prompt, by picking one of you two. Once
/// both are in, both answers show to both; matching answers (<see cref="Same"/>: any case, accents, punctuation or a
/// leading "the", and a one-letter slip in a longer word) give each partner a heart, and so does a pair either partner
/// counts as the same by hand (<see cref="CountsAction"/>). Cooperative: the score is how many you matched. Until both
/// are in, each partner sees only their own answer.
/// </summary>
/// <remarks>
/// The state is the one the engine's built-in quiz kept (plus the region, the kept answers and the rounds so far), so
/// games started before this class existed carry on as they were, trivia prompts and all.
/// </remarks>
public sealed class SameAnswerRules : IGameRules
{
    public const int Rounds = 10;

    /// <summary>The longest answer kept: a few words is what matches, and the app stops at this many characters.</summary>
    public const int MaxAnswerLength = 200;

    private const string WhoKind = "who";
    private const string TextKind = "text";

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.CoupleQuiz;

    /// <summary>Both answer the same prompt at once; nobody waits for the other to go first.</summary>
    public bool AnyoneMayAct => true;

    /// <summary>The setup for a new game: its region and its ten prompts, fixed once the game is invited.</summary>
    public static string CreateSetup(string region, IEnumerable<string>? recentSetups = null)
        => JsonSerializer.Serialize(new { region, prompts = SameAnswerBank.Pick(region, Rounds, FreshPick.Seen(recentSetups, "prompts")) }, SetupJson);

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

    /// <summary>The move that counts a revealed round that didn't match as a match ("That counts!").</summary>
    public const string CountsAction = "counts";

    /// <summary>A round may still be counted once the game is over, while the finished game is on screen.</summary>
    public bool PlayableAfterEnd(string action) => string.Equals(action?.Trim(), CountsAction, StringComparison.OrdinalIgnoreCase);

    public GameEngineResult Apply(GameMove move)
    {
        if (PlayableAfterEnd(move.Action)) return Count(move);
        if (!string.Equals(move.Action, "answer", StringComparison.OrdinalIgnoreCase) || string.IsNullOrWhiteSpace(move.Value))
            throw new InvalidOperationException(Loc.T("Write an answer first."));
        var state = Read(move.StateJson);
        if (state.Round >= state.Questions.Count) throw new InvalidOperationException(Loc.T("This game is over."));
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (state.Answers.ContainsKey(move.Actor)) throw new InvalidOperationException(Loc.T("You already answered this one. Wait for your partner."));
        var answer = move.Value.Trim();
        if (answer.Length > MaxAnswerLength) throw new InvalidOperationException(Loc.T("Keep it short: a few words is plenty."));
        // A "Who" prompt is answered with one of the two of you. An app from before sends words instead; they still count.
        if (SameAnswerBank.IsWho(state.Prompt) && Guid.TryParse(answer, out var picked))
        {
            if (!state.Players.Contains(picked)) throw new InvalidOperationException(Loc.T("Pick one of you two."));
            answer = picked.ToString();
        }
        state.Answers[move.Actor] = answer;

        if (state.Answers.Count < state.Players.Count)
            return new GameEngineResult(JsonSerializer.Serialize(state), state.Players.First(player => !state.Answers.ContainsKey(player)), null, false);

        var given = state.Players.Select(player => state.Answers[player]).ToList();
        var match = given.All(text => Same(text, given[0]));
        if (match) foreach (var player in state.Players) state.Scores[player] = state.Scores.GetValueOrDefault(player) + 1;
        state.LastResult = match ? "match" : "different";
        state.History.Add(new Played { Round = state.Round, Prompt = state.Prompt, Answers = new Dictionary<Guid, string>(state.Answers), Match = match });
        state.Round++;
        state.Answers.Clear();
        var completed = state.Round >= state.Questions.Count;
        if (!completed)
        {
            state.Prompt = state.Questions[state.Round];
            state.TurnIndex = 0;
        }
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[0], null, completed);
    }

    /// <summary>
    /// "That counts!": either partner may count a revealed round whose answers didn't match ("pizza", "pepperoni
    /// pizza") as a match: any round of the game, while it's played or for a while after it ends (the controller's
    /// <c>FinishedGameAmendWindow</c>). Both get the heart a match gives. Once per round and for good: counting a round
    /// already counted changes nothing, so two taps that cross make one heart each, not two.
    /// </summary>
    private GameEngineResult Count(GameMove move)
    {
        var state = Read(move.StateJson);
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        var played = move.Index is { } round ? state.History.FirstOrDefault(item => item.Round == round) : null;
        if (played is null) throw new InvalidOperationException(Loc.T("You can count a question once you've both answered it."));
        if (played.Match) throw new InvalidOperationException(Loc.T("Those answers already match."));
        if (!played.Accepted)
        {
            played.Accepted = true;
            played.AcceptedBy = move.Actor;
            foreach (var player in state.Players) state.Scores[player] = state.Scores.GetValueOrDefault(player) + 1;
        }
        var completed = state.Round >= state.Questions.Count;
        var turn = completed ? (Guid?)null : state.Players.FirstOrDefault(player => !state.Answers.ContainsKey(player), state.Players[0]);
        return new GameEngineResult(JsonSerializer.Serialize(state), turn, null, completed);
    }

    /// <summary>
    /// Until both are in, each partner sees only their own answer (<c>mine</c>) and who has answered; then the round's
    /// answers show to both in <c>last</c>, and every round played so far in <c>history</c>.
    /// </summary>
    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var playing = state.Round < state.Questions.Count;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            state.Round,
            totalRounds = state.Questions.Count,
            // Older apps answer when it's their turn: the first player who hasn't answered yet.
            turnIndex = playing ? Math.Max(0, state.Players.FindIndex(player => !state.Answers.ContainsKey(player))) : state.TurnIndex,
            prompt = playing ? Show(state, state.Prompt) : null,
            kind = playing ? Kind(state.Prompt) : null,
            answerCount = state.Answers.Count,
            answered = state.Players.Where(state.Answers.ContainsKey),
            mine = viewer is { } me && state.Answers.TryGetValue(me, out var own) ? own : null,
            state.LastResult,
            last = state.History.Count > 0 ? View(state, state.History[^1]) : null,
            history = state.History.Select(played => View(state, played)),
        }, PublicJson);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Round, null);
    }

    /// <summary>
    /// Two answers that say the same thing: equal once case, accents, punctuation, spacing and a leading article
    /// ("the", "our", "la"…) are set aside, or one slip apart in words of five letters or more ("chocolate",
    /// "chocolat"). Answers with no letters or digits at all (an emoji) must be the same as typed.
    /// </summary>
    public static bool Same(string first, string second)
    {
        var a = Key(first);
        var b = Key(second);
        if (a.Length == 0 || b.Length == 0) return string.Equals(first.Trim(), second.Trim(), StringComparison.OrdinalIgnoreCase);
        if (a == b) return true;
        return Math.Min(a.Length, b.Length) >= 5 && WithinOneEdit(a, b);
    }

    private static readonly HashSet<string> Articles = ["the", "a", "an", "our", "my", "le", "la", "les", "l", "un", "une", "el", "los", "las", "lo", "o", "os", "as", "um", "uma", "nos", "nossa", "nosso", "notre", "nuestro", "nuestra"];

    /// <summary>The comparable core of an answer: lower case, Latin accents dropped, only letters and digits, no leading article, no spaces.</summary>
    internal static string Key(string text)
    {
        var decomposed = text.Trim().ToLowerInvariant().Replace("&", " and ", StringComparison.Ordinal).Normalize(NormalizationForm.FormD);
        var kept = new StringBuilder(decomposed.Length);
        var previous = ' ';
        foreach (var c in decomposed)
        {
            var category = CharUnicodeInfo.GetUnicodeCategory(c);
            if (category is UnicodeCategory.NonSpacingMark or UnicodeCategory.SpacingCombiningMark)
            {
                // A mark on a Latin letter is an accent (é → e); on another letter (Devanagari, Arabic) it's part of the word.
                if (char.IsLetter(previous) && !char.IsAsciiLetter(previous)) kept.Append(c);
                continue;
            }
            var keep = char.IsLetterOrDigit(c);
            kept.Append(keep ? c : ' ');
            previous = keep ? c : ' ';
        }
        var words = kept.ToString().Normalize(NormalizationForm.FormC).Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
        while (words.Count > 1 && Articles.Contains(words[0])) words.RemoveAt(0);
        return string.Concat(words);
    }

    private static bool WithinOneEdit(string a, string b)
    {
        if (Math.Abs(a.Length - b.Length) > 1) return false;
        if (a.Length > b.Length) (a, b) = (b, a);
        int i = 0, j = 0, edits = 0;
        while (i < a.Length && j < b.Length)
        {
            if (a[i] == b[j]) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (a.Length == b.Length) i++;
            j++;
        }
        return edits + (b.Length - j) + (a.Length - i) <= 1;
    }

    private static string Kind(string prompt) => SameAnswerBank.IsWho(prompt) ? WhoKind : TextKind;

    private static object View(State state, Played played) => new
    {
        played.Round,
        prompt = Show(state, played.Prompt),
        kind = Kind(played.Prompt),
        played.Answers,
        // A round counted by hand reads as a match (older apps show its heart); accepted and acceptedBy say it was
        // counted, and by whom.
        match = played.Match || played.Accepted,
        accepted = played.Accepted,
        acceptedBy = played.AcceptedBy,
    };

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

    /// <summary>The engine's old quiz state, field for field, plus the region (absent, so global, in older games) and the rounds played.</summary>
    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public int Round { get; set; }
        /// <summary>Who answered first in the old turn-by-turn game; kept so older states read as they were.</summary>
        public int TurnIndex { get; set; }
        public string Prompt { get; set; } = string.Empty;
        /// <summary>This round's answers so far: each partner's own until both are in.</summary>
        public Dictionary<Guid, string> Answers { get; set; } = [];
        public string? LastResult { get; set; }
        public List<string> Questions { get; set; } = [];
        public string Region { get; set; } = GameRegion.Global;
        /// <summary>Every round played, both answers in (empty in games from before it was kept).</summary>
        public List<Played> History { get; set; } = [];
    }

    private sealed class Played
    {
        public int Round { get; set; }
        public string Prompt { get; set; } = string.Empty;
        public Dictionary<Guid, string> Answers { get; set; } = [];
        /// <summary>The answers matched on their own (<see cref="Same"/>).</summary>
        public bool Match { get; set; }
        /// <summary>Counted as a match by hand ("That counts!"); false in games from before it existed.</summary>
        public bool Accepted { get; set; }
        public Guid? AcceptedBy { get; set; }
    }
}
