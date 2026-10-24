using System.Globalization;
using System.Text;
using System.Text.Json;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Draw and guess (<see cref="GameType.Handwriting"/> on the wire; docs/games.md). Six rounds; the drawer alternates,
/// starting with the first player. The drawer sees a secret word and sends strokes; the partner has three guesses.
/// A right guess gives both a point. Three misses, or the drawer passing, end the round with none. Both may move at
/// once (strokes and guesses cross), so the rules check roles themselves; there is no clock.
/// </summary>
public sealed class DrawAndGuessRules : IGameRules
{
    public const int Rounds = 6;
    public const int MaxGuesses = 3;
    public const int MaxPointsPerStroke = 200;
    public const int MaxStrokesPerRound = 250;
    public const int MaxGuessLength = 60;
    /// <summary>Pen width as a fraction of the canvas width.</summary>
    public const double MinWidth = 0.001, MaxWidth = 0.1;

    public const string Guessed = "guessed", Missed = "missed", Passed = "passed";
    private const string DrawingPhase = "drawing", CompletedPhase = "completed";

    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    /// <summary>Words a guess may start with that don't count: English, plus the app's other Latin-script languages.</summary>
    private static readonly HashSet<string> Articles = ["a", "an", "the", "le", "la", "les", "l", "un", "une", "el", "los", "las", "una", "o", "os", "as", "um", "uma"];

    public GameType Type => GameType.Handwriting;

    public bool AnyoneMayAct => true;

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        if (players.Count < 2) throw new InvalidOperationException("Draw and guess needs two players.");
        var region = GameRegion.Of(setupJson);
        var state = new DrawState
        {
            Players = players.Take(2).ToList(),
            Scores = players.Take(2).ToDictionary(player => player, _ => 0),
            Region = region,
            Words = SetupWords(setupJson) ?? DrawAndGuessWords.Pick(region, Rounds).ToList(),
        };
        return Result(state);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        var drawer = Drawer(state);
        var action = move.Action.Trim().ToLowerInvariant();
        switch (action)
        {
            case "stroke" or "undo" or "clear" or "pass" when move.Actor != drawer:
                throw new InvalidOperationException(Loc.T("Your partner is drawing this round. Guess what it is."));
            case "guess" when move.Actor == drawer:
                throw new InvalidOperationException(Loc.T("You're drawing this round. Your partner does the guessing."));
            case "stroke":
                if (state.Strokes.Count >= MaxStrokesPerRound)
                    throw new InvalidOperationException(Loc.T("The drawing is full. Undo or clear to keep going."));
                state.Strokes.Add(ParseStroke(move.Value));
                return Result(state);
            case "undo":
                if (state.Strokes.Count == 0) throw new InvalidOperationException(Loc.T("There's nothing to undo."));
                state.Strokes.RemoveAt(state.Strokes.Count - 1);
                return Result(state);
            case "clear":
                if (state.Strokes.Count == 0) throw new InvalidOperationException(Loc.T("The drawing is already empty."));
                state.Strokes.Clear();
                return Result(state);
            case "pass":
                return EndRound(state, Passed, null);
            case "guess":
                return ApplyGuess(state, move.Value);
            default:
                throw new InvalidOperationException(Loc.T("That move isn't part of Draw and guess."));
        }
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var playing = state.Phase != CompletedPhase;
        Guid? drawer = playing ? Drawer(state) : null;
        Guid? guesser = playing ? Guesser(state) : null;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            state.Round,
            totalRounds = Rounds,
            state.Phase,
            drawer,
            guesser,
            // The word is the drawer's secret: nobody else, not even a null viewer, sees it while the round is on.
            word = playing && viewer is { } who && who == drawer ? Show(state, state.Words[state.Round]) : null,
            strokes = playing ? state.Strokes : [],
            wrongGuesses = playing ? state.WrongGuesses : [],
            guessesLeft = playing ? MaxGuesses - state.WrongGuesses.Count : 0,
            maxGuesses = MaxGuesses,
            lastRound = state.LastRound is { } last ? new
            {
                last.Round,
                last.Drawer,
                word = Show(state, last.Word),
                last.Outcome,
                last.Guess,
                last.Strokes,
                last.WrongGuesses,
            } : null,
        }, PublicJson);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        return new GameScoreSummary(state.Scores, state.Round, null);
    }

    private GameEngineResult ApplyGuess(DrawState state, string? value)
    {
        var guess = value?.Trim() ?? string.Empty;
        if (guess.Length == 0) throw new InvalidOperationException(Loc.T("Type a guess first."));
        if (guess.Length > MaxGuessLength) throw new InvalidOperationException(Loc.Plural(MaxGuessLength, "Keep your guess under {count} character.", "Keep your guess under {count} characters."));
        var word = state.Words[state.Round];
        // The guesser may answer in English or in their own language (this request's).
        var targets = new[] { word, Show(state, word) }.Select(Normalize).Where(text => text.Length > 0).ToHashSet();
        var normalized = Normalize(guess);
        if (targets.Contains(normalized)) return EndRound(state, Guessed, guess);
        if (state.WrongGuesses.Any(previous => Normalize(previous) == normalized))
            throw new InvalidOperationException(Loc.T("You already tried that one."));
        state.WrongGuesses.Add(guess);
        return state.WrongGuesses.Count >= MaxGuesses ? EndRound(state, Missed, null) : Result(state);
    }

    private static GameEngineResult EndRound(DrawState state, string outcome, string? guess)
    {
        if (outcome == Guessed)
            foreach (var player in state.Players) state.Scores[player]++;
        state.LastRound = new RoundResult
        {
            Round = state.Round,
            Drawer = Drawer(state),
            Word = state.Words[state.Round],
            Outcome = outcome,
            Guess = guess,
            Strokes = state.Strokes,
            WrongGuesses = state.WrongGuesses,
        };
        state.Strokes = [];
        state.WrongGuesses = [];
        state.Round++;
        if (state.Round >= Rounds) state.Phase = CompletedPhase;
        return Result(state);
    }

    private static GameEngineResult Result(DrawState state)
    {
        var completed = state.Phase == CompletedPhase;
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : Drawer(state), null, completed);
    }

    private static Guid Drawer(DrawState state) => state.Players[state.Round % 2];
    private static Guid Guesser(DrawState state) => state.Players[(state.Round + 1) % 2];

    /// <summary>The word as the current reader sees it: global words in their language, Naija words as written.</summary>
    private static string Show(DrawState state, string word) => state.Region == GameRegion.Nigeria ? word : Loc.T(word);

    /// <summary>
    /// What a guess comes down to: no case, accents, punctuation or spaces, and no leading article, so "The Umbrella",
    /// "umbrella " and "Umbréllà" all match, and "ice-cream" matches "ice cream".
    /// </summary>
    public static string Normalize(string text)
    {
        var decomposed = text.Trim().ToLowerInvariant().Normalize(NormalizationForm.FormD);
        var plain = new StringBuilder(decomposed.Length);
        foreach (var c in decomposed)
        {
            var category = CharUnicodeInfo.GetUnicodeCategory(c);
            if (category == UnicodeCategory.NonSpacingMark) continue;
            plain.Append(char.IsLetterOrDigit(c) ? c : ' ');
        }
        var words = plain.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
        if (words.Count > 1 && Articles.Contains(words[0])) words.RemoveAt(0);
        return string.Concat(words).Normalize(NormalizationForm.FormC);
    }

    /// <summary>
    /// One stroke, strictly: <c>{"points":[[x,y],...],"width":w}</c> and nothing else, with 1 to 200 points whose
    /// coordinates are fractions of the canvas (0 to 1) and a width between <see cref="MinWidth"/> and <see cref="MaxWidth"/>.
    /// </summary>
    public static Stroke ParseStroke(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) throw Invalid();
        JsonDocument document;
        try { document = JsonDocument.Parse(value, new JsonDocumentOptions { MaxDepth = 4 }); }
        catch (JsonException) { throw Invalid(); }
        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) throw Invalid();
            JsonElement? points = null, width = null;
            foreach (var property in root.EnumerateObject())
            {
                switch (property.Name)
                {
                    case "points" when points is null: points = property.Value; break;
                    case "width" when width is null: width = property.Value; break;
                    default: throw Invalid();
                }
            }
            if (points is not { ValueKind: JsonValueKind.Array } list || width is not { ValueKind: JsonValueKind.Number } size) throw Invalid();
            var count = list.GetArrayLength();
            if (count is < 1 or > MaxPointsPerStroke) throw Invalid();
            if (!size.TryGetDouble(out var w) || !double.IsFinite(w) || w < MinWidth || w > MaxWidth) throw Invalid();
            var parsed = new List<double[]>(count);
            foreach (var point in list.EnumerateArray())
            {
                if (point.ValueKind != JsonValueKind.Array || point.GetArrayLength() != 2) throw Invalid();
                var x = Coordinate(point[0]);
                var y = Coordinate(point[1]);
                parsed.Add([x, y]);
            }
            return new Stroke { Points = parsed, Width = Math.Round(w, 4) };
        }
    }

    private static double Coordinate(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Number || !element.TryGetDouble(out var value) || !double.IsFinite(value) || value < 0 || value > 1) throw Invalid();
        return Math.Round(value, 3);
    }

    private static InvalidOperationException Invalid() => new(Loc.T("That stroke couldn't be drawn. Try again."));

    private static List<string>? SetupWords(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            using var setup = JsonDocument.Parse(setupJson);
            if (!setup.RootElement.TryGetProperty("words", out var words) || words.ValueKind != JsonValueKind.Array) return null;
            var list = words.EnumerateArray().Where(word => word.ValueKind == JsonValueKind.String).Select(word => word.GetString()!).Where(word => word.Length > 0).ToList();
            return list.Count >= Rounds ? list : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static DrawState Read(string json) => JsonSerializer.Deserialize<DrawState>(json) ?? throw new InvalidOperationException("Game state is invalid.");

    private sealed class DrawState
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public string Region { get; set; } = GameRegion.Global;
        /// <summary>One word per round, the drawer's secret. Server only.</summary>
        public List<string> Words { get; set; } = [];
        public int Round { get; set; }
        public string Phase { get; set; } = DrawingPhase;
        public List<Stroke> Strokes { get; set; } = [];
        public List<string> WrongGuesses { get; set; } = [];
        public RoundResult? LastRound { get; set; }
    }

    private sealed class RoundResult
    {
        public int Round { get; set; }
        public Guid Drawer { get; set; }
        public string Word { get; set; } = string.Empty;
        public string Outcome { get; set; } = string.Empty;
        public string? Guess { get; set; }
        public List<Stroke> Strokes { get; set; } = [];
        public List<string> WrongGuesses { get; set; } = [];
    }

    public sealed class Stroke
    {
        public List<double[]> Points { get; set; } = [];
        public double Width { get; set; }
    }
}
