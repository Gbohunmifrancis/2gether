using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

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

    /// <summary>
    /// Games with their own rules class. Same answer, memory match and I call on are still built in below; a type with
    /// no rules yet plays as Same answer.
    /// </summary>
    private static readonly Dictionary<GameType, IGameRules> Registered = new IGameRules[]
    {
        new TruthOrDareRules(),
        new WordDashRules(),
        new SameAnswerRules(),
        new GuessMyAnswerRules(),
        new WouldYouRatherRules(),
        new TicTacToeRules(),
        new ConnectFourRules(),
        new DrawAndGuessRules(),
        new TriviaRules(),
        new SkillDuelRules(),
        new SpotTheDifferenceRules(),
        new SnakesAndLaddersRules(),
    }.ToDictionary(rules => rules.Type);

    public static IGameRules? RulesFor(GameType gameType) => Registered.GetValueOrDefault(gameType);

    public bool AnyoneMayAct(GameType gameType) => RulesFor(gameType)?.AnyoneMayAct ?? gameType == GameType.ICallOn;

    public bool IsTimed(GameType gameType) => RulesFor(gameType)?.Timed ?? gameType == GameType.ICallOn;

    public bool MovesMayCross(GameType gameType) => RulesFor(gameType)?.MovesMayCross ?? false;

    public string ToPublicState(GameType gameType, string stateJson, Guid? viewer)
    {
        // An invite that hasn't started has no state yet; the rules all assume a game in play.
        if (NotStarted(stateJson)) return "{}";
        return RulesFor(gameType) is { } rules ? rules.ToPublicState(stateJson, viewer) : ToPublicState(gameType, stateJson);
    }

    private static bool NotStarted(string? stateJson) => string.IsNullOrWhiteSpace(stateJson) || stateJson.Trim() == "{}";

    public GameEngineResult Start(GameType gameType, IReadOnlyList<Guid> playerIds, DateTime utcNow, string? setupJson = null)
    {
        if (RulesFor(gameType) is { } rules)
        {
            if (playerIds.Count == 0) throw new InvalidOperationException("At least one player is required.");
            return rules.Start(playerIds, utcNow, setupJson);
        }
        if (playerIds.Count == 0) throw new InvalidOperationException("At least one player is required.");
        var stateJson = gameType == GameType.MemoryMatch
            ? JsonSerializer.Serialize(CreateMemoryState(playerIds, setupJson))
            : gameType == GameType.ICallOn
                ? JsonSerializer.Serialize(CreateICallOnState(playerIds, setupJson))
                : JsonSerializer.Serialize(CreateQuizState(playerIds, setupJson));
        // Only I call on is played against the clock. Quiz and memory match are turn by turn: a couple
        // may take their turns hours apart, so those turns have no deadline.
        return new GameEngineResult(stateJson, playerIds[0], gameType == GameType.ICallOn ? utcNow.AddSeconds(AnsweringSeconds) : null, false);
    }

    public GameEngineResult Apply(GameType gameType, string stateJson, Guid actorUserId, string action, string? value, int? index, DateTime utcNow, IReadOnlyDictionary<string, bool?>? validations = null, IReadOnlyDictionary<Guid, CoSubmission>? coSubmissions = null)
        => RulesFor(gameType) is { } rules
            ? rules.Apply(new GameMove(stateJson, actorUserId, action, value, index, utcNow, validations, coSubmissions))
            : gameType == GameType.MemoryMatch
            ? ApplyMemory(stateJson, actorUserId, action, index, utcNow)
            : gameType == GameType.ICallOn
                ? ApplyICallOn(stateJson, actorUserId, action, value, utcNow, validations, coSubmissions)
                : ApplyQuiz(stateJson, actorUserId, action, value, utcNow);

    public string ToPublicState(GameType gameType, string stateJson)
    {
        if (NotStarted(stateJson)) return "{}";
        if (RulesFor(gameType) is { } rules) return rules.ToPublicState(stateJson, null);
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
            state.LastResult,
            state.LastPair,
            state.Theme
        };
        return JsonSerializer.Serialize(publicState, PublicJsonOptions);
    }

    public GameScoreSummary Summarise(GameType gameType, string stateJson)
    {
        if (NotStarted(stateJson)) return new GameScoreSummary(new Dictionary<Guid, int>(), 0, null);
        try
        {
            if (RulesFor(gameType) is { } rules) return rules.Summarise(stateJson);
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
            return new GameEngineResult(JsonSerializer.Serialize(state), state.Players[state.TurnIndex], null, false);
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
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[state.TurnIndex], null, completed);
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
        state.LastPair = null;

        if (state.Revealed.Count == 2)
        {
            var first = state.Cards[state.Revealed[0]];
            var second = state.Cards[state.Revealed[1]];
            // Both players saw both cards turn over: the pair names them until the next flip, so both phones know which
            // two to celebrate (a match) or to keep face up (a miss).
            state.LastPair = [new MemoryFlip(state.Revealed[0], first.Value), new MemoryFlip(state.Revealed[1], second.Value)];
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
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[state.TurnIndex], null, completed);
    }

    /// <summary>
    /// Ends a round when its time is up. Only the server sends it: the timeout worker, or a
    /// player's action that arrives after the deadline. A client choosing it early could close
    /// the round on a partner still answering, or score answers nobody has judged.
    /// </summary>
    public const string TimeoutAction = "timeout";

    /// <summary>Longest action payload: a full set of answers or a verdict fits easily.</summary>
    public const int MaxActionValueLength = 4000;

    /// <summary>A requested starting letter: one of A to Z, or none to let the game pick.</summary>
    public static bool IsValidLetter(string? letter) => letter is null || letter.Length == 1 && char.IsAsciiLetter(letter[0]);

    public static bool IsServerOnlyAction(string? action) => string.Equals(action?.Trim(), TimeoutAction, StringComparison.OrdinalIgnoreCase);

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
            if (state.Phase != VettingPhase) throw new InvalidOperationException(Loc.T("There is nothing waiting for a verdict."));
            if (string.IsNullOrWhiteSpace(value)) throw new InvalidOperationException(Loc.T("That verdict couldn't be read. Try again."));
            VetRequest? verdict;
            try { verdict = JsonSerializer.Deserialize<VetRequest>(value, PublicJsonOptions); }
            catch (JsonException) { verdict = null; }
            if (verdict is null) throw new InvalidOperationException(Loc.T("That verdict couldn't be read. Try again."));
            var pending = state.PendingVetting.FirstOrDefault(item => item.Player == verdict.Player && string.Equals(item.Category, verdict.Category, StringComparison.OrdinalIgnoreCase))
                ?? throw new InvalidOperationException(Loc.T("That answer is not waiting for a verdict."));
            if (pending.Judge != actorUserId) throw new InvalidOperationException(Loc.T("Only your partner's answers are yours to judge."));
            pending.Allowed = verdict.Allow;
            return state.PendingVetting.All(item => item.Allowed.HasValue)
                ? ScoreRound(state, utcNow)
                : new GameEngineResult(JsonSerializer.Serialize(state), state.Players[0], utcNow.AddSeconds(VettingSeconds), false);
        }
        if (!string.Equals(action, "submit", StringComparison.OrdinalIgnoreCase) || string.IsNullOrWhiteSpace(value))
            throw new InvalidOperationException(Loc.T("Fill in every category first."));
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        if (state.Phase == VettingPhase) throw new InvalidOperationException(Loc.T("This round is being judged. Wait for the verdicts."));
        if (!state.Players.Contains(actorUserId)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (state.Submissions.ContainsKey(actorUserId)) throw new InvalidOperationException(Loc.T("You already sent your answers. Wait for your partner."));
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
            catch (Exception exception) when (exception is JsonException or InvalidOperationException) { }
        }
    }

    private static void Record(ICallOnState state, Guid player, string answersJson, IReadOnlyDictionary<string, bool?>? validations, bool requireComplete)
    {
        Dictionary<string, string>? answers;
        try { answers = JsonSerializer.Deserialize<Dictionary<string, string>>(answersJson); }
        catch (JsonException) when (requireComplete) { answers = null; }
        if (answers is null) throw new InvalidOperationException(Loc.T("Those answers couldn't be read. Try again."));
        // One line each, and no longer than the app lets anyone type: an answer is a word or two.
        var normalized = state.Categories.ToDictionary(category => category, category => Clean(answers.FirstOrDefault(item => string.Equals(item.Key, category, StringComparison.OrdinalIgnoreCase)).Value));
        if (requireComplete && state.Categories.Any(category => string.IsNullOrWhiteSpace(normalized[category])))
            throw new InvalidOperationException(Loc.T("Fill in every category first."));
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
                if (Rule(category, answer, state.Letter, state.Validations.GetValueOrDefault(player), state.Region) != AnswerRuling.NeedsVetting) continue;
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
            // "Zürich" and "zurich " are the same answer: shared, a point each.
            foreach (var group in counted.GroupBy(item => Fold(item.Answer)))
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
            state.UsedLetters.Add(state.Letter);
            state.Letter = RandomLetter(state.Region, state.UsedLetters);
            state.Phase = AnsweringPhase;
        }
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[0], completed ? null : utcNow.AddSeconds(AnsweringSeconds), completed);
    }

    private static bool Counts(ICallOnState state, Guid player, string category, string answer, IReadOnlyDictionary<(Guid, string), bool> verdicts)
        => Rule(category, answer, state.Letter, state.Validations.GetValueOrDefault(player), state.Region) switch
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

    /// <summary>Nigerian couples get Naija pictures on the cards (the app draws the theme); the pairs work the same.</summary>
    private static MemoryState CreateMemoryState(IReadOnlyList<Guid> players, string? setupJson)
    {
        var cards = Enumerable.Range(1, 6).SelectMany(value => new[] { new MemoryCard { Value = value }, new MemoryCard { Value = value } }).ToList();
        for (var i = cards.Count - 1; i > 0; i--)
        {
            var swap = RandomNumberGenerator.GetInt32(i + 1);
            (cards[i], cards[swap]) = (cards[swap], cards[i]);
        }
        return new MemoryState { Players = players.ToList(), Scores = players.ToDictionary(player => player, _ => 0), Cards = cards, Theme = GameRegion.Of(setupJson) == GameRegion.Nigeria ? NaijaTheme : null };
    }

    /// <summary>
    /// The first round uses the letter the inviter chose, if any; later rounds draw at random. Nigerian couples play
    /// Name, Place, Animal and Food with Nigerian words in the lists.
    /// </summary>
    private static ICallOnState CreateICallOnState(IReadOnlyList<Guid> players, string? setupJson)
    {
        var region = GameRegion.Of(setupJson);
        return new()
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Region = region,
            Letter = ChosenLetter(setupJson) ?? RandomLetter(region),
            Categories = region == GameRegion.Nigeria ? ["Name", "Place", "Animal", "Food"] : ["Name", "Place", "Thing", "Food"],
        };
    }

    private static string? ChosenLetter(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            using var document = JsonDocument.Parse(setupJson);
            return document.RootElement.TryGetProperty("letter", out var letter) && letter.ValueKind == JsonValueKind.String && IsValidLetter(letter.GetString())
                ? letter.GetString()!.ToUpperInvariant()
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>A letter the round may use, not one already played this game while others are left.</summary>
    private static string RandomLetter(string? region, IReadOnlyCollection<string>? played = null)
    {
        var playable = PlayableLetters(region);
        var fresh = played is null ? playable : playable.Where(letter => !played.Contains(letter, StringComparer.OrdinalIgnoreCase)).ToList();
        if (fresh.Count > 0) playable = fresh;
        return playable[RandomNumberGenerator.GetInt32(playable.Count)];
    }

    /// <summary>
    /// The I call on round whose answers are being typed, or null when nobody is typing (judging, or over): the
    /// round a draft must name to be counted when the clock runs out.
    /// </summary>
    public static int? ICallOnAnsweringRound(string stateJson)
    {
        try
        {
            var state = Deserialize<ICallOnState>(stateJson);
            return state.Phase == AnsweringPhase ? state.Round : null;
        }
        catch (Exception exception) when (exception is JsonException or InvalidOperationException)
        {
            return null;
        }
    }

    /// <summary>
    /// An answer as it is compared: no accents, no case, single spaces. "Éric" starts with E and "Zürich" is "zurich",
    /// so a French or Portuguese keyboard plays the same game as an English one.
    /// </summary>
    public static string Fold(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return string.Empty;
        var decomposed = text.Trim().ToLowerInvariant().Normalize(NormalizationForm.FormD);
        var plain = new StringBuilder(decomposed.Length);
        foreach (var c in decomposed)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.NonSpacingMark) continue;
            plain.Append(char.IsWhiteSpace(c) ? ' ' : c);
        }
        return string.Join(' ', plain.ToString().Normalize(NormalizationForm.FormC).Split(' ', StringSplitOptions.RemoveEmptyEntries));
    }

    /// <summary>An answer as it is kept: one line, single spaces, at most <see cref="MaxAnswerLength"/> characters.</summary>
    private static string Clean(string? answer)
    {
        if (string.IsNullOrWhiteSpace(answer)) return string.Empty;
        var oneLine = string.Join(' ', answer.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        return oneLine.Length > MaxAnswerLength ? oneLine[..MaxAnswerLength].TrimEnd() : oneLine;
    }

    /// <summary>The longest answer kept; the app lets 40 characters be typed.</summary>
    public const int MaxAnswerLength = 40;

    /// <summary>The letters a round may draw: those with a word in every category of the region's lists.</summary>
    public static IReadOnlyList<string> PlayableLetters(string? region) => Enumerable.Range('A', 26)
        .Select(value => ((char)value).ToString())
        .Where(letter => WordListsFor(region).Values.All(words => words.Any(word => word.StartsWith(letter, StringComparison.OrdinalIgnoreCase))))
        .ToList();

    /// <summary>Games set up before regions existed have no region and keep the global lists.</summary>
    private static IReadOnlyDictionary<string, HashSet<string>> WordListsFor(string? region) => region == GameRegion.Nigeria ? NaijaWordLists : WordLists;

    private static AnswerRuling Rule(string category, string answer, string letter, IReadOnlyDictionary<string, bool?>? validations, string? region)
    {
        var normalized = Fold(answer);
        if (normalized.Length == 0 || !normalized.StartsWith(Fold(letter), StringComparison.Ordinal)) return AnswerRuling.Invalid;
        if (validations?.TryGetValue(category, out var external) == true && external == true) return AnswerRuling.Valid;
        if (WordListsFor(region).TryGetValue(category, out var words) && words.Contains(normalized)) return AnswerRuling.Valid;
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

    /// <summary>Nigerian couples' lists (NaijaICallOnWords): the global words plus Nigerian names, places, animals and foods.</summary>
    private static readonly IReadOnlyDictionary<string, HashSet<string>> NaijaWordLists = new Dictionary<string, HashSet<string>>(StringComparer.OrdinalIgnoreCase)
    {
        ["Name"] = [.. WordLists["Name"], .. NaijaICallOnWords.Names],
        ["Place"] = [.. WordLists["Place"], .. NaijaICallOnWords.Places],
        ["Animal"] = [.. NaijaICallOnWords.Animals],
        ["Food"] = [.. WordLists["Food"], .. NaijaICallOnWords.Foods],
    };

    /// <summary>The memory theme with Naija pictures; the app draws them.</summary>
    public const string NaijaTheme = "naija";

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
        /// <summary>The two cards of the last miss, face up for both players until the next flip.</summary>
        public List<MemoryFlip>? LastPair { get; set; }
        /// <summary>The pictures on the cards: null for the classic six, "naija" for Nigerian couples.</summary>
        public string? Theme { get; set; }
    }

    private sealed record MemoryFlip(int Index, int Value);

    private sealed class MemoryCard { public int Value { get; set; } public bool Matched { get; set; } }

    private sealed class ICallOnState
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public int TurnIndex { get; set; }
        public int Round { get; set; }
        public string Letter { get; set; } = "A";
        /// <summary>Letters of the rounds already played, so a later round draws a new one.</summary>
        public List<string> UsedLetters { get; set; } = [];
        public List<string> Categories { get; set; } = [];
        /// <summary>Whose word lists score the game; null for games set up before regions, which play the global lists.</summary>
        public string? Region { get; set; }
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
