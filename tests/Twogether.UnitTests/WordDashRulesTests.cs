using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class WordDashRulesTests
{
    private static readonly DateTime Now = new(2026, 10, 24, 18, 0, 0, DateTimeKind.Utc);
    private readonly WordDashRules rules = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();

    private static IReadOnlyDictionary<string, bool?> Verdict(bool? valid) => new Dictionary<string, bool?> { [WordDashRules.WordKey] = valid };

    private GameEngineResult Start(string region = GameRegion.Global)
        => rules.Start([maya, theo], Now, JsonSerializer.Serialize(new { region }));

    private GameEngineResult Play(GameEngineResult from, Guid actor, string word, bool? valid = true, DateTime? at = null)
        => rules.Apply(new GameMove(from.StateJson, actor, "play", word, null, at ?? Now, Verdict(valid)));

    private GameEngineResult TimeOut(GameEngineResult from, DateTime? at = null)
        => rules.Apply(new GameMove(from.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, at ?? Now));

    private JsonElement View(GameEngineResult result) => JsonDocument.Parse(rules.ToPublicState(result.StateJson, maya)).RootElement;

    private string Letter(GameEngineResult result) => View(result).GetProperty("letter").GetString()!;

    /// <summary>A made-up word that is letters only, starts with <paramref name="letter"/> and ends with <paramref name="end"/>.</summary>
    private static string Word(string letter, char end = 'e', string middle = "ar") => (letter + middle + end).ToLowerInvariant();

    [Fact]
    public void Is_registered_and_timed_at_twenty_seconds_a_turn()
    {
        var engine = new GameEngine();
        Assert.True(engine.IsTimed(GameType.WordDash));
        Assert.False(engine.AnyoneMayAct(GameType.WordDash));
        var started = engine.Start(GameType.WordDash, [maya, theo], Now, """{"region":"global"}""");
        Assert.Equal(maya, started.CurrentTurnUserId);
        Assert.Equal(Now.AddSeconds(20), started.DeadlineUtc);
        Assert.False(started.Completed);
    }

    [Fact]
    public void Starts_with_a_random_letter_and_an_empty_chain()
    {
        var view = View(Start());
        Assert.Matches("^[A-Z]$", view.GetProperty("letter").GetString());
        Assert.Equal(0, view.GetProperty("chain").GetArrayLength());
        Assert.Equal(1, view.GetProperty("chainNumber").GetInt32());
        Assert.Equal(5, view.GetProperty("target").GetInt32());
        Assert.Equal(15, view.GetProperty("maxChains").GetInt32());
    }

    [Fact]
    public void A_word_extends_the_chain_and_passes_the_turn_with_a_fresh_clock()
    {
        var started = Start();
        var word = Word(Letter(started), 't');
        var later = Now.AddSeconds(7);
        var played = Play(started, maya, word.ToUpperInvariant() + "  ", at: later);
        Assert.Equal(theo, played.CurrentTurnUserId);
        Assert.Equal(later.AddSeconds(20), played.DeadlineUtc);
        var view = View(played);
        Assert.Equal("T", view.GetProperty("letter").GetString());
        var link = view.GetProperty("chain")[0];
        Assert.Equal(word, link.GetProperty("word").GetString());
        Assert.Equal(maya, link.GetProperty("player").GetGuid());
    }

    [Fact]
    public void Only_the_player_whose_turn_it_is_may_play()
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => Play(started, theo, Word(Letter(started))));
        var played = Play(started, maya, Word(Letter(started), 't'));
        Assert.Throws<InvalidOperationException>(() => Play(played, maya, "tea"));
    }

    [Theory]
    [InlineData("a")]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("two words")]
    [InlineData("rock'n'roll")]
    [InlineData("abc1")]
    public void Refuses_words_that_are_not_letters_only_or_too_short(string word)
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => Play(started, maya, word));
    }

    [Fact]
    public void Refuses_a_word_with_the_wrong_first_letter()
    {
        var started = Start();
        var wrong = Letter(started) == "S" ? "table" : "spoon";
        Assert.Throws<InvalidOperationException>(() => Play(started, maya, wrong));
        var played = Play(started, maya, Word(Letter(started), 'n'));
        Assert.Throws<InvalidOperationException>(() => Play(played, theo, "apple"));
        Assert.Equal(maya, Play(played, theo, "nest").CurrentTurnUserId);
    }

    [Fact]
    public void Refuses_a_word_already_played_this_game_even_in_a_later_chain()
    {
        var started = Start();
        // A word that starts and ends with the same letter can follow itself.
        var letter = Letter(started).ToLowerInvariant()[0];
        var loop = Word(letter.ToString(), letter);
        var once = Play(started, maya, loop);
        var error = Assert.Throws<InvalidOperationException>(() => Play(once, theo, loop.ToUpperInvariant()));
        Assert.Contains(loop, error.Message);

        var fresh = TimeOut(once);
        var next = Letter(fresh).ToLowerInvariant();
        var reused = Play(fresh, theo, Word(next, letter));
        Assert.Throws<InvalidOperationException>(() => Play(reused, maya, loop));
    }

    [Fact]
    public void A_refused_word_changes_nothing_and_the_same_player_tries_again()
    {
        var started = Start();
        var word = Word(Letter(started));
        Assert.Throws<InvalidOperationException>(() => Play(started, maya, word, valid: false));
        // The state passed in is untouched, so the same player plays on the same clock.
        var retry = Play(started, maya, Word(Letter(started), 'e', "ol"));
        Assert.Equal(theo, retry.CurrentTurnUserId);
    }

    [Fact]
    public void A_dictionary_that_cannot_be_reached_lets_the_word_stand()
    {
        var started = Start();
        var unknown = Play(started, maya, Word(Letter(started), 'k'), valid: null);
        Assert.Equal(theo, unknown.CurrentTurnUserId);
        var noVerdict = rules.Apply(new GameMove(unknown.StateJson, theo, "play", "kite", null, Now));
        Assert.Equal(maya, noVerdict.CurrentTurnUserId);
    }

    [Fact]
    public void Nigerian_couples_may_play_naija_words_the_dictionary_refuses()
    {
        var naija = rules.Apply(new GameMove(ChainEndingIn(GameRegion.Nigeria, 'j'), maya, "play", "jollof", null, Now, Verdict(false)));
        Assert.Equal(theo, naija.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => rules.Apply(new GameMove(ChainEndingIn(GameRegion.Global, 'j'), maya, "play", "jollof", null, Now, Verdict(false))));
    }

    /// <summary>A state where it is maya's turn and the next word must start with <paramref name="letter"/>.</summary>
    private string ChainEndingIn(string region, char letter)
    {
        var started = Start(region);
        var first = Play(started, maya, Word(Letter(started), letter));
        // Theo plays a word from that letter back to the same letter, so maya plays from it next.
        return Play(first, theo, Word(letter.ToString(), letter, "ii")).StateJson;
    }

    [Fact]
    public void Running_out_of_time_scores_the_partner_and_starts_a_new_chain_with_the_slow_player()
    {
        var started = Start();
        var played = Play(started, maya, Word(Letter(started), 't'));
        var oldLetter = View(started).GetProperty("startLetter").GetString();
        var later = Now.AddSeconds(25);
        var timedOut = TimeOut(played, later);
        Assert.Equal(theo, timedOut.CurrentTurnUserId);
        Assert.Equal(later.AddSeconds(20), timedOut.DeadlineUtc);
        Assert.False(timedOut.Completed);
        var view = View(timedOut);
        Assert.Equal(1, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(0, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal("timeout", view.GetProperty("lastResult").GetString());
        Assert.Equal(theo, view.GetProperty("timedOut").GetGuid());
        Assert.Equal(0, view.GetProperty("chain").GetArrayLength());
        Assert.Equal(1, view.GetProperty("lastChain").GetArrayLength());
        Assert.Equal(2, view.GetProperty("chainNumber").GetInt32());
        Assert.NotEqual(oldLetter, view.GetProperty("letter").GetString());
    }

    [Fact]
    public void First_to_five_wins()
    {
        var state = Start();
        for (var i = 0; i < 4; i++) state = TimeOut(state);
        Assert.False(state.Completed);
        Assert.Equal(maya, state.CurrentTurnUserId);
        state = TimeOut(state);
        Assert.True(state.Completed);
        Assert.Null(state.CurrentTurnUserId);
        Assert.Null(state.DeadlineUtc);
        var summary = rules.Summarise(state.StateJson);
        Assert.Equal(5, summary.Scores[theo]);
        Assert.Equal(0, summary.Scores[maya]);
        Assert.Equal(theo, summary.DeclaredWinnerUserId);
        Assert.Equal(5, summary.RoundsPlayed);
        Assert.Throws<InvalidOperationException>(() => TimeOut(state));
        Assert.Throws<InvalidOperationException>(() => Play(state, maya, "apple"));
    }

    [Fact]
    public void A_full_game_alternates_chains_and_a_level_game_is_decided_in_the_ninth()
    {
        var state = Start();
        var words = 0;
        string Fresh(GameEngineResult at) => Letter(at).ToLowerInvariant() + new string('z', ++words) + "e";

        // Each chain: the starter plays a word, the partner runs out of time. The starter scores and the partner
        // starts the next chain, so the points alternate.
        for (var chain = 1; chain <= 8; chain++)
        {
            Assert.Equal(chain, View(state).GetProperty("chainNumber").GetInt32());
            var starter = state.CurrentTurnUserId!.Value;
            state = Play(state, starter, Fresh(state));
            state = TimeOut(state);
            Assert.False(state.Completed);
            Assert.NotEqual(starter, state.CurrentTurnUserId);
        }
        var level = rules.Summarise(state.StateJson);
        Assert.Equal(4, level.Scores[maya]);
        Assert.Equal(4, level.Scores[theo]);
        Assert.Equal(8, level.RoundsPlayed);
        Assert.Null(level.DeclaredWinnerUserId);

        Assert.Equal(maya, state.CurrentTurnUserId);
        state = Play(state, maya, Fresh(state));
        state = Play(state, theo, Fresh(state));
        state = Play(state, maya, Fresh(state));
        Assert.Equal(3, View(state).GetProperty("chain").GetArrayLength());
        state = TimeOut(state);
        Assert.True(state.Completed);
        var final = rules.Summarise(state.StateJson);
        Assert.Equal(5, final.Scores[maya]);
        Assert.Equal(4, final.Scores[theo]);
        Assert.Equal(maya, final.DeclaredWinnerUserId);
        Assert.Equal(9, final.RoundsPlayed);
        Assert.Equal(maya, View(state).GetProperty("winnerUserId").GetGuid());
    }

    [Fact]
    public void Moves_other_than_play_are_refused()
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => rules.Apply(new GameMove(started.StateJson, maya, "choose", "truth", null, Now)));
    }
}
