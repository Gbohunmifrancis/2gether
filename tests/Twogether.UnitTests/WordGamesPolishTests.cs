using System.Text.Json;
using System.Text.Json.Nodes;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.UnitTests;

/// <summary>
/// Word dash, Draw and guess and Truth or dare: what people actually type (accents, hyphens, plurals), and a way out
/// of any prompt that never needs the partner's say-so.
/// </summary>
public sealed class WordGamesPolishTests
{
    private static readonly DateTime Now = new(2026, 10, 24, 18, 0, 0, DateTimeKind.Utc);
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();

    // Word dash

    private static IReadOnlyDictionary<string, bool?> Verdict(bool? valid) => new Dictionary<string, bool?> { [WordDashRules.WordKey] = valid };

    /// <summary>A chain whose next word must start with <paramref name="letter"/>.</summary>
    private string DashStartingWith(char letter, string region = GameRegion.Global)
    {
        var state = JsonNode.Parse(new WordDashRules().Start([maya, theo], Now, JsonSerializer.Serialize(new { region })).StateJson)!.AsObject();
        state["StartLetter"] = letter.ToString();
        return state.ToJsonString();
    }

    private GameEngineResult Play(string state, string word, bool? valid = true)
        => engine.Apply(GameType.WordDash, state, maya, "play", word, null, Now, Verdict(valid));

    private static JsonElement DashView(GameEngineResult result) => JsonDocument.Parse(new WordDashRules().ToPublicState(result.StateJson, null)).RootElement;

    [Theory]
    [InlineData("Café", "cafe")]
    [InlineData("  CAFÉ ", "cafe")]
    [InlineData("crème", "creme")]
    public void Word_dash_drops_accents_the_way_an_english_dictionary_spells_the_word(string typed, string played)
    {
        var next = Play(DashStartingWith('C'), typed);
        Assert.Equal(played, DashView(next).GetProperty("chain")[0].GetProperty("word").GetString());
    }

    [Fact]
    public void Word_dash_closes_up_hyphens_so_naija_words_play_as_written()
    {
        var next = Play(DashStartingWith('P', GameRegion.Nigeria), "puff-puff", valid: false);
        Assert.Equal("puffpuff", DashView(next).GetProperty("chain")[0].GetProperty("word").GetString());
    }

    [Fact]
    public void Word_dash_says_plainly_that_words_are_english_when_another_script_is_typed()
    {
        var error = Assert.Throws<InvalidOperationException>(() => Play(DashStartingWith('S'), "سلام"));
        Assert.Equal("Play one English word of two letters or more, A to Z only.", error.Message);
        using (Loc.Use("hi"))
            Assert.NotEqual("Play one English word of two letters or more, A to Z only.", Assert.Throws<InvalidOperationException>(() => Play(DashStartingWith('S'), "नमस्ते")).Message);
    }

    [Fact]
    public void The_dictionary_is_asked_about_the_word_as_it_will_be_played()
    {
        Assert.Equal("cafe", WordDashRules.Normalize("Café"));
        Assert.Equal("puffpuff", WordDashRules.Normalize("puff-puff"));
        Assert.Equal("dont", WordDashRules.Normalize("don’t"));
    }

    // Draw and guess

    private string Drawing(params string[] words)
        => engine.Start(GameType.Handwriting, [maya, theo], Now, JsonSerializer.Serialize(new { region = "global", words })).StateJson;

    [Theory]
    [InlineData("cat", "cats")]
    [InlineData("scissors", "scissor")]
    [InlineData("glasses", "glass")]
    [InlineData("bus", "buses")]
    [InlineData("ice cream", "ice creams")]
    public void A_plural_or_singular_guess_is_right(string word, string guess)
    {
        var state = Drawing(word, "dog", "sun", "moon", "star", "tree");
        var result = engine.Apply(GameType.Handwriting, state, theo, "guess", guess, null, Now);
        var view = JsonDocument.Parse(engine.ToPublicState(GameType.Handwriting, result.StateJson, theo)).RootElement;
        Assert.Equal("guessed", view.GetProperty("lastRound").GetProperty("outcome").GetString());
    }

    [Theory]
    [InlineData("cat", "cart")]
    [InlineData("cat", "ca")]
    [InlineData("bee", "beer")]
    public void A_near_miss_is_still_a_miss(string word, string guess)
    {
        Assert.False(DrawAndGuessRules.SameWord(DrawAndGuessRules.Normalize(word), DrawAndGuessRules.Normalize(guess)));
        var state = Drawing(word, "dog", "sun", "moon", "star", "tree");
        var result = engine.Apply(GameType.Handwriting, state, theo, "guess", guess, null, Now);
        var view = JsonDocument.Parse(engine.ToPublicState(GameType.Handwriting, result.StateJson, theo)).RootElement;
        Assert.Equal(JsonValueKind.Null, view.GetProperty("lastRound").ValueKind);
    }

    // Truth or dare

    private readonly TruthOrDareRules truthOrDare = new();

    private GameEngineResult Dare(GameEngineResult from, Guid actor, string action, string? value = null)
        => truthOrDare.Apply(new GameMove(from.StateJson, actor, action, value, null, Now));

    private JsonElement DareView(GameEngineResult result) => JsonDocument.Parse(truthOrDare.ToPublicState(result.StateJson, maya)).RootElement;

    private GameEngineResult Chosen() => Dare(truthOrDare.Start([maya, theo], Now, """{"region":"global"}"""), maya, "choose", "dare");

    [Fact]
    public void The_chooser_may_swap_a_prompt_once_a_turn_for_another_of_the_same_kind()
    {
        var chosen = Chosen();
        var first = DareView(chosen).GetProperty("prompt").GetString();
        var swapped = Dare(chosen, maya, "swap");
        var view = DareView(swapped);
        Assert.NotEqual(first, view.GetProperty("prompt").GetString());
        Assert.Equal("dare", view.GetProperty("choice").GetString());
        Assert.True(view.GetProperty("swapped").GetBoolean());
        Assert.Equal(theo, swapped.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Dare(swapped, maya, "swap"));
        // The partner can't swap it for them.
        Assert.Throws<InvalidOperationException>(() => Dare(chosen, theo, "swap"));

        // The swap is used up only for this turn.
        var next = Dare(Dare(swapped, theo, "judge", "done"), theo, "choose", "truth");
        Assert.False(DareView(next).GetProperty("swapped").GetBoolean());
        Assert.NotEqual(JsonValueKind.Null, DareView(Dare(next, theo, "swap")).GetProperty("prompt").ValueKind);
    }

    [Fact]
    public void The_chooser_may_pass_without_asking_and_the_turn_moves_on_with_no_heart()
    {
        var passed = Dare(Chosen(), maya, "pass");
        var view = DareView(passed);
        Assert.Equal("choosing", view.GetProperty("phase").GetString());
        Assert.Equal(theo, view.GetProperty("chooser").GetGuid());
        Assert.Equal(0, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        var last = view.GetProperty("last");
        Assert.Equal("skipped", last.GetProperty("result").GetString());
        Assert.True(last.GetProperty("passed").GetBoolean());
        Assert.Equal(1, truthOrDare.Summarise(passed.StateJson).RoundsPlayed);

        // Only the one with the prompt passes, and only once there is one.
        Assert.Throws<InvalidOperationException>(() => Dare(Chosen(), theo, "pass"));
        Assert.Throws<InvalidOperationException>(() => Dare(passed, theo, "pass"));
    }

    [Fact]
    public void A_skip_marked_by_the_partner_is_not_a_pass()
    {
        var skipped = Dare(Chosen(), theo, "judge", "skipped");
        Assert.False(DareView(skipped).GetProperty("last").GetProperty("passed").GetBoolean());
    }

    [Fact]
    public void A_game_saved_before_swaps_and_passes_carries_on()
    {
        var old = JsonNode.Parse(Chosen().StateJson)!.AsObject();
        old.Remove("Swapped");
        var stateJson = old.ToJsonString();
        var swapped = truthOrDare.Apply(new GameMove(stateJson, maya, "swap", null, null, Now));
        Assert.True(DareView(swapped).GetProperty("swapped").GetBoolean());
    }
}
