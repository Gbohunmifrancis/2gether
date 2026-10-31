using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class TruthOrDareRulesTests
{
    private static readonly DateTime Now = new(2026, 10, 24, 18, 0, 0, DateTimeKind.Utc);
    private readonly TruthOrDareRules rules = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();

    private GameEngineResult Start(string region = GameRegion.Global)
        => rules.Start([maya, theo], Now, JsonSerializer.Serialize(new { region }));

    private GameEngineResult Move(GameEngineResult from, Guid actor, string action, string? value)
        => rules.Apply(new GameMove(from.StateJson, actor, action, value, null, Now));

    private JsonElement View(GameEngineResult result) => JsonDocument.Parse(rules.ToPublicState(result.StateJson, maya)).RootElement;

    [Fact]
    public void Is_registered_turn_by_turn_with_no_clock()
    {
        var engine = new GameEngine();
        Assert.Same(typeof(TruthOrDareRules), GameEngine.RulesFor(GameType.TruthOrDare)?.GetType());
        Assert.False(engine.IsTimed(GameType.TruthOrDare));
        Assert.True(engine.AnyoneMayAct(GameType.TruthOrDare));
        var started = engine.Start(GameType.TruthOrDare, [maya, theo], Now, """{"region":"global"}""");
        Assert.Null(started.DeadlineUtc);
    }

    [Fact]
    public void Starts_with_the_first_player_choosing()
    {
        var started = Start();
        Assert.Equal(maya, started.CurrentTurnUserId);
        Assert.False(started.Completed);
        var view = View(started);
        Assert.Equal("choosing", view.GetProperty("phase").GetString());
        Assert.Equal(0, view.GetProperty("turn").GetInt32());
        Assert.Equal(10, view.GetProperty("totalTurns").GetInt32());
        Assert.Equal(maya, view.GetProperty("chooser").GetGuid());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("prompt").ValueKind);
    }

    [Fact]
    public void Choosing_draws_a_prompt_and_hands_the_turn_to_the_judge()
    {
        var chosen = Move(Start(), maya, "choose", "dare");
        Assert.Equal(theo, chosen.CurrentTurnUserId);
        Assert.Null(chosen.DeadlineUtc);
        var view = View(chosen);
        Assert.Equal("judging", view.GetProperty("phase").GetString());
        Assert.Equal("dare", view.GetProperty("choice").GetString());
        Assert.Contains(view.GetProperty("prompt").GetString(), TruthOrDarePrompts.Dares(GameRegion.Global));
    }

    [Fact]
    public void Done_scores_the_chooser_and_skipped_does_not()
    {
        var done = Move(Move(Start(), maya, "choose", "truth"), theo, "judge", "done");
        Assert.Equal(theo, done.CurrentTurnUserId);
        var view = View(done);
        Assert.Equal(1, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal("done", view.GetProperty("last").GetProperty("result").GetString());
        Assert.Equal(maya, view.GetProperty("last").GetProperty("chooser").GetGuid());
        Assert.Equal(theo, view.GetProperty("chooser").GetGuid());

        var skipped = Move(Move(done, theo, "choose", "dare"), maya, "judge", "skipped");
        var after = View(skipped);
        Assert.Equal(0, after.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(1, after.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(maya, skipped.CurrentTurnUserId);
    }

    [Fact]
    public void Only_the_chooser_chooses_and_only_the_partner_judges()
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => Move(started, theo, "choose", "truth"));
        Assert.Throws<InvalidOperationException>(() => Move(started, maya, "judge", "done"));
        var chosen = Move(started, maya, "choose", "truth");
        Assert.Throws<InvalidOperationException>(() => Move(chosen, maya, "judge", "done"));
        Assert.Throws<InvalidOperationException>(() => Move(chosen, maya, "choose", "dare"));
        Assert.Throws<InvalidOperationException>(() => Move(chosen, theo, "choose", "dare"));
    }

    [Fact]
    public void Refuses_unknown_moves_and_values()
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => Move(started, maya, "choose", "both"));
        Assert.Throws<InvalidOperationException>(() => Move(started, maya, "choose", null));
        Assert.Throws<InvalidOperationException>(() => Move(started, maya, "flip", "truth"));
        var chosen = Move(started, maya, "choose", "TRUTH");
        Assert.Throws<InvalidOperationException>(() => Move(chosen, theo, "judge", "maybe"));
    }

    [Fact]
    public void A_full_game_is_ten_alternating_turns_with_no_prompt_twice()
    {
        var state = Start();
        var prompts = new List<string>();
        for (var turn = 0; turn < TruthOrDareRules.Turns; turn++)
        {
            var chooser = turn % 2 == 0 ? maya : theo;
            var judge = chooser == maya ? theo : maya;
            Assert.Equal(chooser, state.CurrentTurnUserId);
            state = Move(state, chooser, "choose", turn < 5 ? "truth" : "dare");
            prompts.Add(View(state).GetProperty("prompt").GetString()!);
            state = Move(state, judge, "judge", turn % 3 == 0 ? "skipped" : "done");
        }
        Assert.True(state.Completed);
        Assert.Null(state.CurrentTurnUserId);
        Assert.Equal(prompts.Count, prompts.Distinct().Count());
        var view = View(state);
        Assert.Equal("completed", view.GetProperty("phase").GetString());
        Assert.Throws<InvalidOperationException>(() => Move(state, maya, "choose", "truth"));

        // Skipped on turns 0, 3, 6 and 9: maya chose 0, 2, 4, 6, 8 (done 2, 4, 8), theo 1, 3, 5, 7, 9 (done 1, 5, 7).
        var summary = rules.Summarise(state.StateJson);
        Assert.Equal(3, summary.Scores[maya]);
        Assert.Equal(3, summary.Scores[theo]);
        Assert.Equal(10, summary.RoundsPlayed);
    }

    [Fact]
    public void Prompts_never_repeat_even_when_the_same_kind_is_always_chosen()
    {
        for (var game = 0; game < 20; game++)
        {
            var state = Start(game % 2 == 0 ? GameRegion.Global : GameRegion.Nigeria);
            var prompts = new HashSet<string>();
            for (var turn = 0; turn < TruthOrDareRules.Turns; turn++)
            {
                var chooser = turn % 2 == 0 ? maya : theo;
                state = Move(state, chooser, "choose", "dare");
                Assert.True(prompts.Add(View(state).GetProperty("prompt").GetString()!));
                state = Move(state, chooser == maya ? theo : maya, "judge", "done");
            }
        }
    }

    [Fact]
    public void Banks_are_big_enough_and_have_no_duplicates()
    {
        foreach (var bank in new[]
                 {
                     TruthOrDarePrompts.Truths(GameRegion.Global), TruthOrDarePrompts.Dares(GameRegion.Global),
                     TruthOrDarePrompts.Truths(GameRegion.Nigeria), TruthOrDarePrompts.Dares(GameRegion.Nigeria),
                 })
        {
            Assert.Equal(bank.Count, bank.Distinct().Count());
            Assert.All(bank, prompt => Assert.False(string.IsNullOrWhiteSpace(prompt)));
            Assert.All(bank, prompt => Assert.DoesNotContain("{", prompt));
        }
        Assert.True(TruthOrDarePrompts.Truths(GameRegion.Global).Count >= 40);
        Assert.True(TruthOrDarePrompts.Dares(GameRegion.Global).Count >= 40);
        Assert.True(TruthOrDarePrompts.Truths(GameRegion.Nigeria).Count >= 30);
        Assert.True(TruthOrDarePrompts.Dares(GameRegion.Nigeria).Count >= 30);
    }

    [Fact]
    public void Nigerian_couples_draw_from_the_naija_banks()
    {
        var naija = Move(Start(GameRegion.Nigeria), maya, "choose", "truth");
        Assert.Contains(View(naija).GetProperty("prompt").GetString(), TruthOrDarePrompts.Truths(GameRegion.Nigeria));

        var global = Move(Start(), maya, "choose", "truth");
        Assert.Contains(View(global).GetProperty("prompt").GetString(), TruthOrDarePrompts.Truths(GameRegion.Global));
        Assert.Empty(TruthOrDarePrompts.Truths(GameRegion.Nigeria).Intersect(TruthOrDarePrompts.Truths(GameRegion.Global)));
        Assert.Empty(TruthOrDarePrompts.Dares(GameRegion.Nigeria).Intersect(TruthOrDarePrompts.Dares(GameRegion.Global)));
    }

    [Fact]
    public void A_game_without_a_region_plays_the_global_banks()
    {
        var chosen = rules.Apply(new GameMove(rules.Start([maya, theo], Now, null).StateJson, maya, "choose", "dare", null, Now));
        Assert.Contains(View(chosen).GetProperty("prompt").GetString(), TruthOrDarePrompts.Dares(GameRegion.Global));
    }
}
