using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class DrawAndGuessRulesTests
{
    private static readonly string[] Words = ["umbrella", "ice cream", "cat", "guitar", "pizza", "crown"];
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime now = new(2026, 10, 24, 18, 0, 0, DateTimeKind.Utc);

    private const string Line = """{"points":[[0.1,0.1],[0.5,0.5],[0.9,0.2]],"width":0.01}""";

    private string Start(string region = "global", string[]? words = null)
        => engine.Start(GameType.Handwriting, [maya, theo], now, JsonSerializer.Serialize(new { region, words = words ?? Words })).StateJson;

    private GameEngineResult Move(string state, Guid actor, string action, string? value = null)
        => engine.Apply(GameType.Handwriting, state, actor, action, value, null, now);

    private JsonElement View(string state, Guid? viewer)
    {
        using var document = JsonDocument.Parse(engine.ToPublicState(GameType.Handwriting, state, viewer));
        return document.RootElement.Clone();
    }

    [Fact]
    public void Start_puts_the_first_player_at_the_pen_with_both_free_to_move_and_no_clock()
    {
        var started = engine.Start(GameType.Handwriting, [maya, theo], now, JsonSerializer.Serialize(new { region = "global", words = Words }));
        Assert.True(engine.AnyoneMayAct(GameType.Handwriting));
        Assert.False(engine.IsTimed(GameType.Handwriting));
        Assert.Equal(maya, started.CurrentTurnUserId);
        Assert.Null(started.DeadlineUtc);
        Assert.False(started.Completed);

        var view = View(started.StateJson, maya);
        Assert.Equal(0, view.GetProperty("round").GetInt32());
        Assert.Equal(6, view.GetProperty("totalRounds").GetInt32());
        Assert.Equal(maya, view.GetProperty("drawer").GetGuid());
        Assert.Equal(theo, view.GetProperty("guesser").GetGuid());
        Assert.Equal(3, view.GetProperty("guessesLeft").GetInt32());
        Assert.Equal(0, view.GetProperty("strokes").GetArrayLength());
    }

    [Fact]
    public void The_word_is_the_drawers_secret()
    {
        var state = Start();
        Assert.Equal("umbrella", View(state, maya).GetProperty("word").GetString());
        Assert.Equal(JsonValueKind.Null, View(state, theo).GetProperty("word").ValueKind);
        Assert.Equal(JsonValueKind.Null, View(state, null).GetProperty("word").ValueKind);
        foreach (var view in new[] { engine.ToPublicState(GameType.Handwriting, state, theo), engine.ToPublicState(GameType.Handwriting, state, null), engine.ToPublicState(GameType.Handwriting, state) })
            foreach (var word in Words) Assert.DoesNotContain(word, view, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task The_setup_the_app_sees_holds_no_words()
    {
        var setup = await new GameSetupFactory(new NoQuestions()).CreateAsync(GameType.Handwriting, "global", null, CancellationToken.None);
        Assert.Equal(6, JsonDocument.Parse(setup).RootElement.GetProperty("words").GetArrayLength());
        var shown = GameRegion.PublicSetup(setup);
        Assert.DoesNotContain("words", shown, StringComparison.Ordinal);
        Assert.Equal("""{"region":"global"}""", shown);
    }

    [Theory]
    [InlineData("umbrella")]
    [InlineData("  UMBRELLA ")]
    [InlineData("an umbrella")]
    [InlineData("The Umbrella")]
    [InlineData("úmbrellà")]
    public void A_right_guess_ignores_case_accents_spaces_and_articles(string guess)
    {
        var result = Move(Start(), theo, "guess", guess);
        var view = View(result.StateJson, theo);
        Assert.Equal(1, view.GetProperty("round").GetInt32());
        var last = view.GetProperty("lastRound");
        Assert.Equal("guessed", last.GetProperty("outcome").GetString());
        Assert.Equal("umbrella", last.GetProperty("word").GetString());
        Assert.Equal(1, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(1, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
    }

    [Fact]
    public void Spaces_and_hyphens_inside_a_word_dont_matter()
    {
        var state = Move(Start(), theo, "guess", "umbrella").StateJson;
        state = Move(state, maya, "guess", "Ice-Cream").StateJson;
        Assert.Equal("guessed", View(state, maya).GetProperty("lastRound").GetProperty("outcome").GetString());
    }

    [Fact]
    public void A_guess_in_the_guessers_language_counts_too()
    {
        // "cat" is "chat" in French; the guesser's request language is the current culture.
        var state = Start(words: ["cat", "dog", "fish", "bird", "sun", "moon"]);
        var previous = System.Globalization.CultureInfo.CurrentUICulture;
        try
        {
            using (Twogether.Shared.Localization.Loc.Use("fr"))
            {
                var french = Twogether.Shared.Localization.Loc.T("cat");
                var result = Move(state, theo, "guess", french);
                Assert.Equal("guessed", View(result.StateJson, theo).GetProperty("lastRound").GetProperty("outcome").GetString());
            }
        }
        finally { System.Globalization.CultureInfo.CurrentUICulture = previous; }
    }

    [Fact]
    public void Three_wrong_guesses_end_the_round_with_no_points_and_show_the_word()
    {
        var state = Start();
        state = Move(state, theo, "guess", "parasol").StateJson;
        var view = View(state, theo);
        Assert.Equal(2, view.GetProperty("guessesLeft").GetInt32());
        Assert.Equal("parasol", view.GetProperty("wrongGuesses")[0].GetString());
        Assert.Throws<InvalidOperationException>(() => Move(state, theo, "guess", "Parasol "));
        state = Move(state, theo, "guess", "mushroom").StateJson;
        var last = Move(state, theo, "guess", "tent");

        view = View(last.StateJson, null);
        Assert.Equal(1, view.GetProperty("round").GetInt32());
        Assert.Equal("missed", view.GetProperty("lastRound").GetProperty("outcome").GetString());
        Assert.Equal("umbrella", view.GetProperty("lastRound").GetProperty("word").GetString());
        Assert.Equal(3, view.GetProperty("lastRound").GetProperty("wrongGuesses").GetArrayLength());
        Assert.Equal(0, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(theo, last.CurrentTurnUserId);
    }

    [Fact]
    public void The_drawer_can_pass_and_only_the_drawer()
    {
        var state = Start();
        Assert.Throws<InvalidOperationException>(() => Move(state, theo, "pass"));
        var passed = Move(state, maya, "pass");
        var view = View(passed.StateJson, theo);
        Assert.Equal("passed", view.GetProperty("lastRound").GetProperty("outcome").GetString());
        Assert.Equal("umbrella", view.GetProperty("lastRound").GetProperty("word").GetString());
        Assert.Equal(0, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
    }

    [Fact]
    public void Roles_are_checked_and_swap_each_round()
    {
        var state = Start();
        Assert.Throws<InvalidOperationException>(() => Move(state, theo, "stroke", Line));
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "guess", "umbrella"));
        Assert.Throws<InvalidOperationException>(() => Move(state, Guid.NewGuid(), "guess", "umbrella"));
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "dance"));

        state = Move(state, maya, "stroke", Line).StateJson;
        state = Move(state, theo, "guess", "umbrella").StateJson;
        var view = View(state, theo);
        Assert.Equal(theo, view.GetProperty("drawer").GetGuid());
        Assert.Equal("ice cream", view.GetProperty("word").GetString());
        Assert.Equal(JsonValueKind.Null, View(state, maya).GetProperty("word").ValueKind);
        // A fresh canvas for the new round; the last drawing stays in the reveal.
        Assert.Equal(0, view.GetProperty("strokes").GetArrayLength());
        Assert.Equal(1, view.GetProperty("lastRound").GetProperty("strokes").GetArrayLength());
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "stroke", Line));
        Move(state, theo, "stroke", Line);
    }

    [Fact]
    public void Undo_and_clear_take_strokes_off()
    {
        var state = Start();
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "undo"));
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "clear"));
        state = Move(state, maya, "stroke", Line).StateJson;
        state = Move(state, maya, "stroke", """{"points":[[0.2,0.8]],"width":0.02}""").StateJson;
        Assert.Equal(2, View(state, theo).GetProperty("strokes").GetArrayLength());
        state = Move(state, maya, "undo").StateJson;
        var strokes = View(state, theo).GetProperty("strokes");
        Assert.Equal(1, strokes.GetArrayLength());
        Assert.Equal(3, strokes[0].GetProperty("points").GetArrayLength());
        Assert.Equal(0.9, strokes[0].GetProperty("points")[2][0].GetDouble());
        Assert.Throws<InvalidOperationException>(() => Move(state, theo, "undo"));
        state = Move(state, maya, "clear").StateJson;
        Assert.Equal(0, View(state, theo).GetProperty("strokes").GetArrayLength());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("not json")]
    [InlineData("[]")]
    [InlineData("""{"points":[],"width":0.01}""")]
    [InlineData("""{"points":[[0.1,0.1]]}""")]
    [InlineData("""{"width":0.01}""")]
    [InlineData("""{"points":[[0.1,0.1]],"width":0.01,"color":"red"}""")]
    [InlineData("""{"points":[[0.1,0.1]],"width":0.01,"width":0.02}""")]
    [InlineData("""{"points":[[0.1,1.5]],"width":0.01}""")]
    [InlineData("""{"points":[[-0.1,0.5]],"width":0.01}""")]
    [InlineData("""{"points":[[0.1]],"width":0.01}""")]
    [InlineData("""{"points":[[0.1,0.2,0.3]],"width":0.01}""")]
    [InlineData("""{"points":[["0.1",0.2]],"width":0.01}""")]
    [InlineData("""{"points":[[0.1,0.2]],"width":0}""")]
    [InlineData("""{"points":[[0.1,0.2]],"width":0.5}""")]
    [InlineData("""{"points":[[0.1,0.2]],"width":"thin"}""")]
    [InlineData("""{"points":[{"x":0.1,"y":0.2}],"width":0.01}""")]
    public void Strokes_are_validated_strictly(string? value)
        => Assert.Throws<InvalidOperationException>(() => Move(Start(), maya, "stroke", value));

    [Fact]
    public void Strokes_have_at_most_200_points_and_a_round_at_most_250_strokes()
    {
        string Points(int count) => """{"points":[""" + string.Join(",", Enumerable.Range(0, count).Select(i => FormattableString.Invariant($"[{i % 10 / 10.0:0.0},0.5]"))) + """],"width":0.01}""";
        var state = Start();
        var full = Points(200);
        Assert.True(full.Length < GameEngine.MaxActionValueLength);
        state = Move(state, maya, "stroke", full).StateJson;
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "stroke", Points(201)));

        for (var i = 1; i < DrawAndGuessRules.MaxStrokesPerRound; i++) state = Move(state, maya, "stroke", Line).StateJson;
        Assert.Equal(250, View(state, theo).GetProperty("strokes").GetArrayLength());
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "stroke", Line));
        state = Move(state, maya, "undo").StateJson;
        Move(state, maya, "stroke", Line);
    }

    [Fact]
    public void Six_rounds_then_the_game_is_over_with_the_scores()
    {
        var state = Start();
        string[] guesses = ["umbrella", "nope", "cat", "guitar", "nope", "crown"];
        GameEngineResult result = null!;
        for (var round = 0; round < 6; round++)
        {
            var guesser = round % 2 == 0 ? theo : maya;
            var drawer = round % 2 == 0 ? maya : theo;
            result = guesses[round] == "nope" ? Move(state, drawer, "pass") : Move(state, guesser, "guess", guesses[round]);
            state = result.StateJson;
            Assert.Equal(round == 5, result.Completed);
        }
        Assert.Null(result.CurrentTurnUserId);
        var summary = engine.Summarise(GameType.Handwriting, state);
        Assert.Equal(4, summary.Scores[maya]);
        Assert.Equal(4, summary.Scores[theo]);
        Assert.Equal(6, summary.RoundsPlayed);
        Assert.Null(summary.DeclaredWinnerUserId);

        var view = View(state, maya);
        Assert.Equal("completed", view.GetProperty("phase").GetString());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("word").ValueKind);
        Assert.Equal("crown", view.GetProperty("lastRound").GetProperty("word").GetString());
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "stroke", Line));
    }

    [Fact]
    public async Task Nigerian_couples_draw_naija_words_and_everyone_else_global_ones()
    {
        Assert.True(DrawAndGuessWords.Global.Count >= 80);
        Assert.True(DrawAndGuessWords.Nigeria.Count >= 50);
        Assert.Equal(DrawAndGuessWords.Global.Count, DrawAndGuessWords.Global.Distinct().Count());
        Assert.Equal(DrawAndGuessWords.Nigeria.Count, DrawAndGuessWords.Nigeria.Distinct().Count());

        var factory = new GameSetupFactory(new NoQuestions());
        for (var i = 0; i < 5; i++)
        {
            var ng = JsonDocument.Parse(await factory.CreateAsync(GameType.Handwriting, GameRegion.Nigeria, null, CancellationToken.None)).RootElement;
            var ngWords = ng.GetProperty("words").EnumerateArray().Select(word => word.GetString()!).ToList();
            Assert.Equal(6, ngWords.Distinct().Count());
            Assert.All(ngWords, word => Assert.Contains(word, DrawAndGuessWords.Nigeria));

            var global = JsonDocument.Parse(await factory.CreateAsync(GameType.Handwriting, GameRegion.Global, null, CancellationToken.None)).RootElement;
            Assert.All(global.GetProperty("words").EnumerateArray(), word => Assert.Contains(word.GetString()!, DrawAndGuessWords.Global));
        }

        // A Naija word is shown as written and guessed as written, Pidgin and all.
        var state = Start("ng", ["danfo", "Third Mainland Bridge", "suya", "gele", "okada", "keke"]);
        Assert.Equal("danfo", View(state, maya).GetProperty("word").GetString());
        state = Move(state, theo, "guess", "Danfo").StateJson;
        state = Move(state, maya, "guess", "the third mainland bridge").StateJson;
        Assert.Equal(2, View(state, maya).GetProperty("scores").GetProperty(maya.ToString()).GetInt32());

        // A setup without words (an old invite) still draws from the region's bank.
        var fallback = engine.Start(GameType.Handwriting, [maya, theo], now, """{"region":"ng"}""").StateJson;
        Assert.Contains(View(fallback, maya).GetProperty("word").GetString()!, DrawAndGuessWords.Nigeria);
    }

    private sealed class NoQuestions : IGameQuestionProvider
    {
        public Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken) => Task.FromResult<IReadOnlyList<string>>([]);
        public Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<TriviaQuestion>>([]);
    }
}
