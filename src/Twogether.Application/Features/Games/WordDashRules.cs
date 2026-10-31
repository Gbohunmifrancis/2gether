using System.Security.Cryptography;
using System.Text.Json;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Word dash (docs/games.md): a word chain, in turn, 20 seconds a turn. Each word starts with the last letter of the
/// one before (the first with the chain's letter), is letters only, is new in this game, and is a real word. A refused
/// word changes nothing: the same player tries again while their time lasts. When the time runs out, the other
/// partner scores and a fresh chain starts with a new letter, the player who ran out going first. First to five wins;
/// the game stops after fifteen chains.
/// </summary>
public sealed class WordDashRules : IGameRules
{
    public const int TurnSeconds = 20;
    public const int WinningScore = 5;
    public const int MaxChains = 15;
    public const int MaxWordLength = 30;

    /// <summary>The key of the dictionary's verdict in <see cref="GameMove.Validations"/>; a missing or null verdict means it couldn't say.</summary>
    public const string WordKey = "word";

    private const string PlayingPhase = "playing";
    private const string CompletedPhase = "completed";

    /// <summary>Letters a chain may start with: enough English words begin with each.</summary>
    private const string StartLetters = "ABCDEFGHILMNOPRSTW";

    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.WordDash;

    public bool Timed => true;

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var state = new State
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Region = GameRegion.Of(setupJson),
            StartLetter = RandomLetter(),
            ChainNumber = 1,
        };
        return Result(state, utcNow);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        if (string.Equals(move.Action, GameEngine.TimeoutAction, StringComparison.OrdinalIgnoreCase)) return TimeOut(state, move.UtcNow);
        if (!string.Equals(move.Action, "play", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("That move isn't part of Word dash."));
        var player = state.Players[state.TurnIndex];
        if (move.Actor != player) throw new InvalidOperationException(Loc.T("It's your partner's turn."));

        var word = Normalize(move.Value);
        var letter = NextLetter(state);
        if (word.Length < 2 || word.Length > MaxWordLength || !word.All(char.IsAsciiLetter))
            throw new InvalidOperationException(Loc.T("Play one English word of two letters or more, A to Z only."));
        if (!word.StartsWith(letter, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("Your word has to start with {letter}.", ("letter", letter)));
        if (state.Used.Contains(word))
            throw new InvalidOperationException(Loc.T("\"{word}\" has been played already. Try another.", ("word", word)));
        if (!IsAccepted(state, word, move.Validations))
            throw new InvalidOperationException(Loc.T("\"{word}\" isn't in the dictionary. Try another.", ("word", word)));

        state.Used.Add(word);
        state.Chain.Add(new Link { Player = player, Word = word });
        state.LastResult = null;
        state.TurnIndex = (state.TurnIndex + 1) % state.Players.Count;
        return Result(state, move.UtcNow);
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Scores,
            letter = state.Phase == CompletedPhase ? null : NextLetter(state),
            startLetter = state.StartLetter,
            chain = state.Chain.Select(link => new { link.Player, link.Word }),
            chainNumber = state.ChainNumber,
            maxChains = MaxChains,
            target = WinningScore,
            turnSeconds = TurnSeconds,
            phase = state.Phase,
            lastResult = state.LastResult,
            timedOut = state.TimedOut,
            lastChain = state.LastChain,
            winnerUserId = state.WinnerUserId,
        }, PublicJson);
    }

    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        var chainsPlayed = state.Phase == CompletedPhase ? state.ChainNumber : state.ChainNumber - 1;
        return new GameScoreSummary(state.Scores, chainsPlayed, state.WinnerUserId);
    }

    /// <summary>
    /// Real words only, but a dictionary that can't be reached (no verdict) doesn't cost the couple: the word stands.
    /// Nigerian couples also play everyday Naija words an English dictionary doesn't know.
    /// </summary>
    private static bool IsAccepted(State state, string word, IReadOnlyDictionary<string, bool?>? validations)
    {
        if (state.Region == GameRegion.Nigeria && NaijaWords.Contains(word)) return true;
        var verdict = validations?.GetValueOrDefault(WordKey);
        return verdict != false;
    }

    private static GameEngineResult TimeOut(State state, DateTime utcNow)
    {
        var timedOut = state.Players[state.TurnIndex];
        var other = state.Players.FirstOrDefault(player => player != timedOut);
        if (other != Guid.Empty) state.Scores[other]++;
        state.LastResult = "timeout";
        state.TimedOut = timedOut;
        state.LastChain = state.Chain.Select(link => link.Word).ToList();
        state.Chain.Clear();

        var best = state.Scores.Values.Max();
        if (best >= WinningScore || state.ChainNumber >= MaxChains)
        {
            var leaders = state.Scores.Where(item => item.Value == best).Select(item => item.Key).ToList();
            state.WinnerUserId = leaders.Count == 1 ? leaders[0] : null;
            state.Phase = CompletedPhase;
            return Result(state, utcNow);
        }

        // A fresh chain: a new letter, and the player who ran out of time starts it.
        state.ChainNumber++;
        state.StartLetter = RandomLetter(state.StartLetter);
        return Result(state, utcNow);
    }

    /// <summary>The letter the next word must start with: the chain's letter, then the last letter of the last word.</summary>
    private static string NextLetter(State state)
        => state.Chain.Count == 0 ? state.StartLetter : char.ToUpperInvariant(state.Chain[^1].Word[^1]).ToString();

    /// <summary>
    /// A word as it is played: lower case, accents dropped ("café" is "cafe", as an English dictionary spells it) and
    /// hyphens closed up ("puff-puff" is "puffpuff"). What's left must be A to Z.
    /// </summary>
    public static string Normalize(string? value) => GameEngine.Fold(value).Replace("-", string.Empty).Replace("'", string.Empty).Replace("’", string.Empty);

    private static string RandomLetter(string? not = null)
    {
        var letters = StartLetters.Where(letter => letter.ToString() != not).ToList();
        return letters[RandomNumberGenerator.GetInt32(letters.Count)].ToString();
    }

    private static GameEngineResult Result(State state, DateTime utcNow)
    {
        var completed = state.Phase == CompletedPhase;
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[state.TurnIndex],
            completed ? null : utcNow.AddSeconds(TurnSeconds), completed);
    }

    private static State Read(string json)
        => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException("Game state is invalid.");

    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public string Region { get; set; } = GameRegion.Global;
        public string Phase { get; set; } = PlayingPhase;
        public int TurnIndex { get; set; }
        /// <summary>The chain being played, counting from 1.</summary>
        public int ChainNumber { get; set; }
        public string StartLetter { get; set; } = "A";
        public List<Link> Chain { get; set; } = [];
        /// <summary>Every word played this game, lower case: no word twice, even in a later chain.</summary>
        public HashSet<string> Used { get; set; } = [];
        public string? LastResult { get; set; }
        public Guid? TimedOut { get; set; }
        public List<string>? LastChain { get; set; }
        public Guid? WinnerUserId { get; set; }
    }

    private sealed class Link
    {
        public Guid Player { get; set; }
        public string Word { get; set; } = string.Empty;
    }
}
