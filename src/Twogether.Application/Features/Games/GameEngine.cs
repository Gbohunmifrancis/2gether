using System.Security.Cryptography;
using System.Text.Json;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Games;

public sealed class GameEngine : IGameEngine
{
    private const int ICallOnRounds = 3;
    private const int AnsweringSeconds = 15;
    private const int VettingSeconds = 20;
    private const string AnsweringPhase = "answering";
    private const string VettingPhase = "vetting";
    private const string CompletedPhase = "completed";

    private static readonly JsonSerializerOptions PublicJsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly string[] Questions =
    [
        "What is our ideal date night?",
        "Which trip should we take next?",
        "What always makes us laugh?",
        "What is our best shared memory?",
        "Which meal would we happily repeat?",
        "What small thing makes you feel loved?",
        "What song belongs on our soundtrack?",
        "What would our perfect slow Sunday look like?",
        "Which new skill should we learn together?",
        "What place feels like home to us?",
        "What is one dream we should make time for?",
        "What is your favorite way for us to reconnect?"
    ];

    public GameEngineResult Start(GameType gameType, IReadOnlyList<Guid> playerIds, DateTime utcNow, string? setupJson = null)
    {
        if (playerIds.Count == 0) throw new InvalidOperationException("At least one player is required.");
        var stateJson = gameType == GameType.MemoryMatch
            ? JsonSerializer.Serialize(CreateMemoryState(playerIds))
            : gameType == GameType.ICallOn
                ? JsonSerializer.Serialize(CreateICallOnState(playerIds))
                : JsonSerializer.Serialize(CreateQuizState(playerIds, setupJson));
        return new GameEngineResult(stateJson, playerIds[0], gameType == GameType.ICallOn ? utcNow.AddSeconds(AnsweringSeconds) : utcNow.AddMinutes(2), false);
    }

    public GameEngineResult Apply(GameType gameType, string stateJson, Guid actorUserId, string action, string? value, int? index, DateTime utcNow, IReadOnlyDictionary<string, bool?>? validations = null, IReadOnlyDictionary<Guid, CoSubmission>? coSubmissions = null)
        => gameType == GameType.MemoryMatch
            ? ApplyMemory(stateJson, actorUserId, action, index, utcNow)
            : gameType == GameType.ICallOn
                ? ApplyICallOn(stateJson, actorUserId, action, value, utcNow, validations, coSubmissions)
                : ApplyQuiz(stateJson, actorUserId, action, value, utcNow);

    public string ToPublicState(GameType gameType, string stateJson)
    {
        if (gameType != GameType.MemoryMatch && gameType != GameType.ICallOn)
        {
            var quiz = Deserialize<QuizState>(stateJson);
            return JsonSerializer.Serialize(new
            {
                quiz.Players,
                quiz.Scores,
                quiz.Round,
                totalRounds = quiz.Questions.Count,
                quiz.TurnIndex,
                quiz.Prompt,
                answerCount = quiz.Answers.Count,
                quiz.LastResult
            }, PublicJsonOptions);
        }
        if (gameType == GameType.ICallOn)
        {
            var iCallOn = Deserialize<ICallOnState>(stateJson);
            return JsonSerializer.Serialize(new
            {
                iCallOn.Players,
                iCallOn.Scores,
                iCallOn.Round,
                totalRounds = ICallOnRounds,
                iCallOn.Letter,
                iCallOn.Categories,
                iCallOn.Phase,
                submitted = iCallOn.Submissions.Keys,
                // Every pending item is either the viewer's own answer or one the viewer
                // has to judge, so with two players nothing leaks by sending them both.
                // The round result itself stays hidden until every verdict is in.
                pendingVetting = iCallOn.PendingVetting.Select(item => new { item.Player, item.Judge, item.Category, item.Answer, item.Allowed }),
                iCallOn.Reveal,
                iCallOn.RoundWinnerUserId,
                iCallOn.WinnerUserId,
                iCallOn.LastResult
            }, PublicJsonOptions);
        }
        var state = Deserialize<MemoryState>(stateJson);
        var publicState = new
        {
            state.Players,
            state.Scores,
            cards = state.Cards.Select((card, index) => new { index, value = card.Matched || state.Revealed.Contains(index) ? (int?)card.Value : null, card.Matched }),
            state.Revealed,
            state.LastResult
        };
        return JsonSerializer.Serialize(publicState, PublicJsonOptions);
    }

    public GameScoreSummary Summarise(GameType gameType, string stateJson)
    {
        try
        {
            if (gameType == GameType.MemoryMatch)
            {
                var memory = Deserialize<MemoryState>(stateJson);
                return new GameScoreSummary(memory.Scores, memory.Cards.Count(card => card.Matched) / 2, null);
            }
            if (gameType == GameType.ICallOn)
            {
                var iCallOn = Deserialize<ICallOnState>(stateJson);
                return new GameScoreSummary(iCallOn.Scores, iCallOn.Round, iCallOn.WinnerUserId);
            }
            var quiz = Deserialize<QuizState>(stateJson);
            return new GameScoreSummary(quiz.Scores, quiz.Round, null);
        }
        catch (Exception exception) when (exception is JsonException or InvalidOperationException)
        {
            // A corrupted state must not stop the game from being closed out.
            return new GameScoreSummary(new Dictionary<Guid, int>(), 0, null);
        }
    }

    private static GameEngineResult ApplyQuiz(string stateJson, Guid actorUserId, string action, string? value, DateTime utcNow)
    {
        if (!string.Equals(action, "answer", StringComparison.OrdinalIgnoreCase) || string.IsNullOrWhiteSpace(value))
            throw new InvalidOperationException("This game expects a non-empty answer action.");
        var state = Deserialize<QuizState>(stateJson);
        var current = state.Players[state.TurnIndex];
        if (current != actorUserId) throw new InvalidOperationException("It is not this player's turn.");
        state.Answers[actorUserId] = value.Trim();

        if (state.Answers.Count < state.Players.Count)
        {
            state.TurnIndex = (state.TurnIndex + 1) % state.Players.Count;
            return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[state.TurnIndex], utcNow.AddMinutes(2), false);
        }

        var match = state.Answers.Values.Distinct(StringComparer.OrdinalIgnoreCase).Count() == 1;
        if (match) foreach (var player in state.Players) state.Scores[player]++;
        state.LastResult = match ? "match" : "different";
        state.Round++;
        state.Answers.Clear();
        var completed = state.Round >= state.Questions.Count;
        if (!completed)
        {
            state.Prompt = state.Questions[state.Round];
            state.TurnIndex = state.Round % state.Players.Count;
        }
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[state.TurnIndex], completed ? null : utcNow.AddMinutes(2), completed);
    }

    private static GameEngineResult ApplyMemory(string stateJson, Guid actorUserId, string action, int? index, DateTime utcNow)
    {
        if (!string.Equals(action, "flip", StringComparison.OrdinalIgnoreCase) || index is null)
            throw new InvalidOperationException("Memory match expects a card index.");
        var state = Deserialize<MemoryState>(stateJson);
        if (state.Players[state.TurnIndex] != actorUserId) throw new InvalidOperationException("It is not this player's turn.");
        if (index < 0 || index >= state.Cards.Count || state.Cards[index.Value].Matched || state.Revealed.Contains(index.Value))
            throw new InvalidOperationException("This card cannot be flipped.");
        state.Revealed.Add(index.Value);
        state.LastResult = null;

        if (state.Revealed.Count == 2)
        {
            var first = state.Cards[state.Revealed[0]];
            var second = state.Cards[state.Revealed[1]];
            if (first.Value == second.Value)
            {
                first.Matched = true;
                second.Matched = true;
                state.Scores[actorUserId]++;
                state.LastResult = "match";
            }
            else
            {
                state.LastResult = "different";
                state.TurnIndex = (state.TurnIndex + 1) % state.Players.Count;
            }
            state.Revealed.Clear();
        }

        var completed = state.Cards.All(card => card.Matched);
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[state.TurnIndex], completed ? null : utcNow.AddMinutes(2), completed);
    }

    private static GameEngineResult ApplyICallOn(string stateJson, Guid actorUserId, string action, string? value, DateTime utcNow, IReadOnlyDictionary<string, bool?>? validations, IReadOnlyDictionary<Guid, CoSubmission>? coSubmissions)
    {
        var state = Deserialize<ICallOnState>(stateJson);
        if (string.Equals(action, "timeout", StringComparison.OrdinalIgnoreCase))
        {
            // A silent judge must not wedge the round. Unknown words default to allowed:
            // being slow to answer should not cost your partner their points.
            if (state.Phase == VettingPhase) return ScoreRound(state, utcNow);
            AbsorbCoSubmissions(state, coSubmissions);
            return CloseAnswering(state, utcNow);
        }
        if (string.Equals(action, "vet", StringComparison.OrdinalIgnoreCase))
        {
            if (state.Phase != VettingPhase) throw new InvalidOperationException("There is nothing waiting for a verdict.");
            if (string.IsNullOrWhiteSpace(value)) throw new InvalidOperationException("A verdict needs a player, a category and a decision.");
            var verdict = JsonSerializer.Deserialize<VetRequest>(value, PublicJsonOptions) ?? throw new InvalidOperationException("That verdict is invalid.");
            var pending = state.PendingVetting.FirstOrDefault(item => item.Player == verdict.Player && string.Equals(item.Category, verdict.Category, StringComparison.OrdinalIgnoreCase))
                ?? throw new InvalidOperationException("That answer is not waiting for a verdict.");
            if (pending.Judge != actorUserId) throw new InvalidOperationException("Only your partner's answers are yours to vet.");
            pending.Allowed = verdict.Allow;
            return state.PendingVetting.All(item => item.Allowed.HasValue)
                ? ScoreRound(state, utcNow)
                : new GameEngineResult(JsonSerializer.Serialize(state), state.Players[0], utcNow.AddSeconds(VettingSeconds), false);
        }
        if (!string.Equals(action, "submit", StringComparison.OrdinalIgnoreCase) || string.IsNullOrWhiteSpace(value))
            throw new InvalidOperationException("I call on expects answers for every category.");
        if (state.Phase == VettingPhase) throw new InvalidOperationException("This round is being vetted. Wait for the verdicts.");
        if (!state.Players.Contains(actorUserId)) throw new InvalidOperationException("You are not a player in this game.");
        if (state.Submissions.ContainsKey(actorUserId)) throw new InvalidOperationException("You already submitted this round. Wait for your partner.");
        Record(state, actorUserId, value, validations, requireComplete: true);
        // Whoever submits first ends the round for both: the partner's in-progress
        // answers ride along so a fast finisher does not have to wait out the clock.
        AbsorbCoSubmissions(state, coSubmissions);

        if (state.Submissions.Count < state.Players.Count)
            return new GameEngineResult(JsonSerializer.Serialize(state), state.Players.First(player => !state.Submissions.ContainsKey(player)), utcNow.AddSeconds(AnsweringSeconds), false);

        return CloseAnswering(state, utcNow);
    }

    private static void AbsorbCoSubmissions(ICallOnState state, IReadOnlyDictionary<Guid, CoSubmission>? coSubmissions)
    {
        if (coSubmissions is null) return;
        foreach (var (player, submission) in coSubmissions)
        {
            if (!state.Players.Contains(player) || state.Submissions.ContainsKey(player)) continue;
            if (string.IsNullOrWhiteSpace(submission.AnswersJson)) continue;
            // A draft is whatever the partner had typed so far, so partial answers count.
            try { Record(state, player, submission.AnswersJson, submission.Validations, requireComplete: false); }
            catch (JsonException) { }
        }
    }

    private static void Record(ICallOnState state, Guid player, string answersJson, IReadOnlyDictionary<string, bool?>? validations, bool requireComplete)
    {
        var answers = JsonSerializer.Deserialize<Dictionary<string, string>>(answersJson) ?? throw new InvalidOperationException("Answers are invalid.");
        var normalized = state.Categories.ToDictionary(category => category, category => answers.FirstOrDefault(item => string.Equals(item.Key, category, StringComparison.OrdinalIgnoreCase)).Value?.Trim() ?? string.Empty);
        if (requireComplete)
        {
            var missing = state.Categories.Where(category => string.IsNullOrWhiteSpace(normalized[category])).ToList();
            if (missing.Count > 0) throw new InvalidOperationException($"Complete: {string.Join(", ", missing)}.");
        }
        state.Submissions[player] = normalized;
        state.Validations[player] = validations?.ToDictionary(item => item.Key, item => item.Value, StringComparer.OrdinalIgnoreCase) ?? [];
    }

    private static GameEngineResult CloseAnswering(ICallOnState state, DateTime utcNow)
    {
        foreach (var player in state.Players)
        {
            if (!state.Submissions.ContainsKey(player))
                state.Submissions[player] = state.Categories.ToDictionary(category => category, _ => string.Empty);
            if (!state.Validations.ContainsKey(player))
                state.Validations[player] = [];
        }

        state.PendingVetting.Clear();
        foreach (var player in state.Players)
        {
            var judge = state.Players.FirstOrDefault(other => other != player);
            if (judge == Guid.Empty) continue;
            foreach (var category in state.Categories)
            {
                var answer = state.Submissions[player][category];
                if (Rule(category, answer, state.Letter, state.Validations.GetValueOrDefault(player)) != AnswerRuling.NeedsVetting) continue;
                state.PendingVetting.Add(new VettingItem { Player = player, Judge = judge, Category = category, Answer = answer });
            }
        }

        if (state.PendingVetting.Count == 0) return ScoreRound(state, utcNow);
        state.Phase = VettingPhase;
        state.LastResult = VettingPhase;
        return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[0], utcNow.AddSeconds(VettingSeconds), false);
    }

    private static GameEngineResult ScoreRound(ICallOnState state, DateTime utcNow)
    {
        var verdicts = state.PendingVetting.ToDictionary(item => (item.Player, item.Category), item => item.Allowed ?? true);
        var roundScores = state.Players.ToDictionary(player => player, _ => 0);
        var entries = new List<RevealEntry>();
        foreach (var category in state.Categories)
        {
            var counted = state.Players
                .Select(player => (Player: player, Answer: state.Submissions[player][category]))
                .Where(item => Counts(state, item.Player, category, item.Answer, verdicts))
                .ToList();
            var awarded = new Dictionary<Guid, int>();
            foreach (var group in counted.GroupBy(item => item.Answer, StringComparer.OrdinalIgnoreCase))
            {
                var points = group.Count() == 1 ? 2 : 1;
                foreach (var item in group)
                {
                    roundScores[item.Player] += points;
                    awarded[item.Player] = points;
                }
            }
            foreach (var player in state.Players)
                entries.Add(new RevealEntry
                {
                    Player = player,
                    Category = category,
                    Answer = state.Submissions[player][category],
                    Counted = awarded.ContainsKey(player),
                    Points = awarded.GetValueOrDefault(player)
                });
        }

        foreach (var player in state.Players) state.Scores[player] += roundScores[player];
        var bestRoundScore = roundScores.Values.Max();
        var roundWinners = roundScores.Where(item => item.Value == bestRoundScore && item.Value > 0).Select(item => item.Key).ToList();
        state.RoundWinnerUserId = roundWinners.Count == 1 ? roundWinners[0] : null;
        state.Reveal = new RoundReveal { Round = state.Round, Letter = state.Letter, Entries = entries, RoundScores = roundScores };
        state.LastResult = JsonSerializer.Serialize(roundScores);
        state.PendingVetting.Clear();
        state.Round++;
        var completed = state.Round >= ICallOnRounds;
        if (completed)
        {
            var bestScore = state.Scores.Values.Max();
            var winners = state.Scores.Where(item => item.Value == bestScore).Select(item => item.Key).ToList();
            state.WinnerUserId = winners.Count == 1 ? winners[0] : null;
            state.LastResult = "gameCompleted";
            state.Phase = CompletedPhase;
        }
        else
        {
            state.Submissions.Clear();
            state.Validations.Clear();
            state.Letter = RandomLetter();
            state.Phase = AnsweringPhase;
        }
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[0], completed ? null : utcNow.AddSeconds(AnsweringSeconds), completed);
    }

    private static bool Counts(ICallOnState state, Guid player, string category, string answer, IReadOnlyDictionary<(Guid, string), bool> verdicts)
        => Rule(category, answer, state.Letter, state.Validations.GetValueOrDefault(player)) switch
        {
            AnswerRuling.Valid => true,
            AnswerRuling.NeedsVetting => verdicts.GetValueOrDefault((player, category), true),
            _ => false
        };

    private static QuizState CreateQuizState(IReadOnlyList<Guid> players, string? setupJson)
    {
        var questions = Questions.ToList();
        if (!string.IsNullOrWhiteSpace(setupJson))
        {
            try
            {
                using var setup = JsonDocument.Parse(setupJson);
                if (setup.RootElement.TryGetProperty("questions", out var external))
                {
                    var loaded = external.EnumerateArray().Select(item => item.GetString()).Where(item => !string.IsNullOrWhiteSpace(item)).Cast<string>().ToList();
                    if (loaded.Count > 0) questions = loaded;
                }
            }
            catch (JsonException) { }
        }
        return new QuizState { Players = players.ToList(), Scores = players.ToDictionary(player => player, _ => 0), Prompt = questions[0], Questions = questions };
    }

    private static MemoryState CreateMemoryState(IReadOnlyList<Guid> players)
    {
        var cards = Enumerable.Range(1, 6).SelectMany(value => new[] { new MemoryCard { Value = value }, new MemoryCard { Value = value } }).ToList();
        for (var i = cards.Count - 1; i > 0; i--)
        {
            var swap = RandomNumberGenerator.GetInt32(i + 1);
            (cards[i], cards[swap]) = (cards[swap], cards[i]);
        }
        return new MemoryState { Players = players.ToList(), Scores = players.ToDictionary(player => player, _ => 0), Cards = cards };
    }

    private static ICallOnState CreateICallOnState(IReadOnlyList<Guid> players)
        => new() { Players = players.ToList(), Scores = players.ToDictionary(player => player, _ => 0), Letter = RandomLetter(), Categories = ["Name", "Place", "Thing", "Food"] };

    private static string RandomLetter()
    {
        var playable = Enumerable.Range('A', 26)
            .Select(value => ((char)value).ToString())
            .Where(letter => WordLists.Values.All(words => words.Any(word => word.StartsWith(letter, StringComparison.OrdinalIgnoreCase))))
            .ToList();
        return playable[RandomNumberGenerator.GetInt32(playable.Count)];
    }

    private static AnswerRuling Rule(string category, string answer, string letter, IReadOnlyDictionary<string, bool?>? validations)
    {
        if (string.IsNullOrWhiteSpace(answer) || !answer.StartsWith(letter, StringComparison.OrdinalIgnoreCase)) return AnswerRuling.Invalid;
        if (validations?.TryGetValue(category, out var external) == true && external == true) return AnswerRuling.Valid;
        var normalized = answer.Trim().ToLowerInvariant();
        if (WordLists.TryGetValue(category, out var words) && words.Contains(normalized)) return AnswerRuling.Valid;
        // The dictionary either said no or could not say. Nigerian names, local places
        // and everyday slang legitimately miss an English word list, so the partner
        // rules on it instead of the engine silently dropping the answer.
        return AnswerRuling.NeedsVetting;
    }

    // A bundled fallback word bank keeps scoring available offline. It is deliberately
    // broad and can be replaced/extended by a dictionary provider without changing game state.
    private static readonly IReadOnlyDictionary<string, HashSet<string>> WordLists = new Dictionary<string, HashSet<string>>(StringComparer.OrdinalIgnoreCase)
    {
        ["Name"] = ["aaron", "alice", "anna", "ben", "bella", "charles", "david", "daniel", "emma", "ella", "faith", "grace", "henry", "isabel", "james", "jane", "john", "karen", "kelly", "lara", "leo", "maria", "mark", "nina", "oliver", "paul", "quinn", "rachel", "rose", "sam", "sara", "tina", "uma", "victor", "wendy", "xavier", "yara", "zara"],
        ["Place"] = ["abuja", "accra", "amsterdam", "athens", "boston", "cairo", "chicago", "dallas", "dubai", "edinburgh", "ibadan", "johannesburg", "kampala", "lagos", "london", "madrid", "miami", "nairobi", "oslo", "paris", "quito", "rome", "seattle", "tokyo", "uyo", "venice", "washington", "yokohama", "zurich"],
        ["Thing"] = ["anchor", "bag", "book", "chair", "drum", "envelope", "fork", "guitar", "hat", "iron", "jacket", "key", "lamp", "mirror", "notebook", "orange", "pencil", "quilt", "ring", "shoe", "table", "umbrella", "vase", "wallet", "xylophone", "yarn", "zipper"],
        ["Food"] = ["apple", "avocado", "banana", "bread", "cake", "dates", "eggs", "fish", "grape", "hamburger", "icecream", "jollof", "kebab", "lasagna", "mango", "noodles", "okra", "pasta", "quiche", "rice", "salad", "taco", "ugali", "vanilla", "waffle", "yam", "zucchini"]
    };

    private static T Deserialize<T>(string json) where T : class
        => JsonSerializer.Deserialize<T>(json) ?? throw new InvalidOperationException("Game state is invalid.");

    private enum AnswerRuling { Invalid, Valid, NeedsVetting }

    private sealed class QuizState
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public int Round { get; set; }
        public int TurnIndex { get; set; }
        public string Prompt { get; set; } = string.Empty;
        public Dictionary<Guid, string> Answers { get; set; } = [];
        public string? LastResult { get; set; }
        public List<string> Questions { get; set; } = [];
    }

    private sealed class MemoryState
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public int TurnIndex { get; set; }
        public List<MemoryCard> Cards { get; set; } = [];
        public List<int> Revealed { get; set; } = [];
        public string? LastResult { get; set; }
    }

    private sealed class MemoryCard { public int Value { get; set; } public bool Matched { get; set; } }

    private sealed class ICallOnState
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public int TurnIndex { get; set; }
        public int Round { get; set; }
        public string Letter { get; set; } = "A";
        public List<string> Categories { get; set; } = [];
        public string Phase { get; set; } = AnsweringPhase;
        public Guid? RoundWinnerUserId { get; set; }
        public Guid? WinnerUserId { get; set; }
        public string? LastResult { get; set; }
        public Dictionary<Guid, Dictionary<string, string>> Submissions { get; set; } = [];
        public Dictionary<Guid, Dictionary<string, bool?>> Validations { get; set; } = [];
        public List<VettingItem> PendingVetting { get; set; } = [];
        public RoundReveal? Reveal { get; set; }
    }

    private sealed class VettingItem
    {
        public Guid Player { get; set; }
        public Guid Judge { get; set; }
        public string Category { get; set; } = string.Empty;
        public string Answer { get; set; } = string.Empty;
        public bool? Allowed { get; set; }
    }

    private sealed class RoundReveal
    {
        public int Round { get; set; }
        public string Letter { get; set; } = string.Empty;
        public List<RevealEntry> Entries { get; set; } = [];
        public Dictionary<Guid, int> RoundScores { get; set; } = [];
    }

    private sealed class RevealEntry
    {
        public Guid Player { get; set; }
        public string Category { get; set; } = string.Empty;
        public string Answer { get; set; } = string.Empty;
        public bool Counted { get; set; }
        public int Points { get; set; }
    }

    private sealed record VetRequest(Guid Player, string Category, bool Allow);
}
