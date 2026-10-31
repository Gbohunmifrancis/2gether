using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class SpotTheDifferenceRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime start = new(2026, 10, 31, 18, 0, 0, DateTimeKind.Utc);

    private GameEngineResult Start(int seed = 7) => engine.Start(GameType.SpotTheDifference, [maya, theo], start, SpotTheDifferenceRules.CreateSetup(GameRegion.Global, seed));

    private GameEngineResult Spot(GameEngineResult from, Guid actor, int? index, DateTime at)
        => engine.Apply(GameType.SpotTheDifference, from.StateJson, actor, "spot", null, index, at);

    private GameEngineResult Timeout(GameEngineResult from, DateTime at)
        => engine.Apply(GameType.SpotTheDifference, from.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, at);

    private JsonElement View(GameEngineResult result, Guid? viewer = null)
    {
        using var document = JsonDocument.Parse(engine.ToPublicState(GameType.SpotTheDifference, result.StateJson, viewer));
        return document.RootElement.Clone();
    }

    /// <summary>Finds every difference of the picture in play, alternating players a second apart; [mayaTakes] are Maya's.</summary>
    private GameEngineResult FindAll(GameEngineResult from, DateTime at, params int[] mayaTakes)
    {
        var state = from;
        for (var index = 0; index < SpotTheDifferenceRules.Differences; index++)
            state = Spot(state, mayaTakes.Contains(index) ? maya : theo, index, at.AddSeconds(index + 1));
        return state;
    }

    [Fact]
    public void Starts_on_the_first_of_three_pictures_both_may_spot_and_the_clock_runs()
    {
        var started = Start();
        var view = View(started, maya);

        Assert.True(GameEngine.RulesFor(GameType.SpotTheDifference) is SpotTheDifferenceRules);
        Assert.True(engine.AnyoneMayAct(GameType.SpotTheDifference));
        Assert.True(engine.IsTimed(GameType.SpotTheDifference));
        Assert.Equal(start.AddSeconds(SpotTheDifferenceRules.SceneSeconds), started.DeadlineUtc);
        Assert.False(started.Completed);
        Assert.NotNull(started.CurrentTurnUserId);
        Assert.Equal("spotting", view.GetProperty("phase").GetString());
        Assert.Equal(0, view.GetProperty("scene").GetInt32());
        Assert.Equal(3, view.GetProperty("totalScenes").GetInt32());
        Assert.Equal(7, view.GetProperty("differences").GetInt32());
        Assert.Equal(90, view.GetProperty("sceneSeconds").GetInt32());
        Assert.Equal(SpotTheDifferenceRules.Plan(7)[0].Kind, view.GetProperty("kind").GetString());
        Assert.Equal(SpotTheDifferenceRules.Plan(7)[0].Seed, view.GetProperty("seed").GetInt32());
        Assert.Empty(view.GetProperty("found").EnumerateArray());
        // Only the picture in play is listed, not the ones to come.
        Assert.Single(view.GetProperty("scenes").EnumerateArray());
        Assert.Equal(0, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("winner").ValueKind);
        Assert.False(view.GetProperty("tie").GetBoolean());
    }

    [Fact]
    public void The_setup_seed_fixes_three_different_pictures_and_both_partners_see_the_same()
    {
        Assert.Equal(SpotTheDifferenceRules.CreateSetup("global", 42), SpotTheDifferenceRules.CreateSetup("global", 42));
        for (var seed = 0; seed < 60; seed++)
        {
            var plan = SpotTheDifferenceRules.Plan(seed);
            Assert.Equal(3, plan.Count);
            Assert.Equal(3, plan.Select(scene => scene.Kind).Distinct().Count());
            Assert.All(plan, scene => Assert.Contains(scene.Kind, SpotTheDifferenceRules.Kinds));
        }
        Assert.Equal(SpotTheDifferenceRules.Kinds.Count, Enumerable.Range(0, 60).SelectMany(SpotTheDifferenceRules.Plan).Select(scene => scene.Kind).Distinct().Count());

        var started = Start(42);
        Assert.Equal(View(started, maya).GetProperty("seed").GetInt32(), View(started, theo).GetProperty("seed").GetInt32());
        Assert.Equal(View(started, maya).GetProperty("kind").GetString(), View(started, theo).GetProperty("kind").GetString());
    }

    [Fact]
    public async Task The_setup_factory_draws_the_pictures_and_keeps_only_the_region_public()
    {
        var setup = await new GameSetupFactory(new NoQuestions()).CreateAsync(GameType.SpotTheDifference, GameRegion.Nigeria, null, CancellationToken.None);
        Assert.Equal(GameRegion.Nigeria, GameRegion.Of(setup));
        Assert.Equal("""{"region":"ng"}""", GameRegion.PublicSetup(setup));
        using var document = JsonDocument.Parse(setup);
        var scenes = document.RootElement.GetProperty("scenes").EnumerateArray().ToList();
        Assert.Equal(3, scenes.Count);
        var seed = document.RootElement.GetProperty("seed").GetInt32();
        Assert.Equal(SpotTheDifferenceRules.Plan(seed).Select(scene => scene.Kind), scenes.Select(scene => scene.GetProperty("kind").GetString()));

        var started = engine.Start(GameType.SpotTheDifference, [maya, theo], start, setup);
        Assert.Equal(scenes[0].GetProperty("seed").GetInt32(), View(started).GetProperty("seed").GetInt32());
    }

    [Fact]
    public void A_setup_that_cannot_be_read_still_starts_a_game()
    {
        var started = engine.Start(GameType.SpotTheDifference, [maya, theo], start, "not json");
        Assert.Equal(3, View(started).GetProperty("totalScenes").GetInt32());
        Assert.Contains(View(started).GetProperty("kind").GetString(), SpotTheDifferenceRules.Kinds);
    }

    [Fact]
    public void The_first_to_spot_a_difference_gets_the_point_and_a_later_claim_fails_cleanly()
    {
        var started = Start();
        var spotted = Spot(started, maya, 3, start.AddSeconds(5));
        var view = View(spotted, theo);
        Assert.Equal(1, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(0, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
        var find = Assert.Single(view.GetProperty("found").EnumerateArray());
        Assert.Equal(3, find.GetProperty("index").GetInt32());
        Assert.Equal(maya, find.GetProperty("by").GetGuid());
        // Spotting doesn't move the clock.
        Assert.Equal(started.DeadlineUtc, spotted.DeadlineUtc);

        var late = Assert.Throws<InvalidOperationException>(() => Spot(spotted, theo, 3, start.AddSeconds(6)));
        Assert.Equal("Your partner spotted that one first.", late.Message);
        var again = Assert.Throws<InvalidOperationException>(() => Spot(spotted, maya, 3, start.AddSeconds(6)));
        Assert.Equal("You already spotted that one.", again.Message);

        var theirs = Spot(spotted, theo, 4, start.AddSeconds(6));
        Assert.Equal(1, View(theirs).GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(2, View(theirs).GetProperty("found").GetArrayLength());
    }

    [Fact]
    public void Spots_cross_in_flight_and_are_judged_on_arrival_but_never_on_the_next_picture()
    {
        // Both race for the same differences: a spot made on an older version still counts (no 409 to retry).
        Assert.True(engine.MovesMayCross(GameType.SpotTheDifference));
        var started = Start(11);
        var onFirst = engine.Apply(GameType.SpotTheDifference, started.StateJson, maya, "spot", """{"scene":0}""", 2, start.AddSeconds(2));
        Assert.Equal(1, View(onFirst).GetProperty("scores").GetProperty(maya.ToString()).GetInt32());

        // A spot made on the first picture that arrives once the second is up is refused, not claimed there.
        var done = FindAll(started, start, 0, 1, 2, 3);
        var second = Timeout(done, start.AddSeconds(SpotTheDifferenceRules.Differences + SpotTheDifferenceRules.BeatSeconds));
        var stale = Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.SpotTheDifference, second.StateJson, theo, "spot", """{"scene":0}""", 5, start.AddSeconds(20)));
        Assert.Equal("That picture is over.", stale.Message);
        var fresh = engine.Apply(GameType.SpotTheDifference, second.StateJson, theo, "spot", """{"scene":1}""", 5, start.AddSeconds(20));
        Assert.Equal(1, View(fresh).GetProperty("found").GetArrayLength());
        // A phone that doesn't name the picture still plays; anything else in the value is refused.
        Assert.Equal(1, View(Spot(second, theo, 5, start.AddSeconds(20))).GetProperty("found").GetArrayLength());
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.SpotTheDifference, second.StateJson, theo, "spot", "not json", 5, start.AddSeconds(20)));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.SpotTheDifference, second.StateJson, theo, "spot", """{"scene":-1}""", 5, start.AddSeconds(20)));
    }

    [Fact]
    public void Spots_too_close_together_are_refused_by_server_time_per_player()
    {
        var started = Start();
        var first = Spot(started, maya, 0, start.AddSeconds(2));
        var tooSoon = Assert.Throws<InvalidOperationException>(() => Spot(first, maya, 1, start.AddSeconds(2).AddMilliseconds(120)));
        Assert.Equal("Not so fast: one spot at a time.", tooSoon.Message);
        // The partner has their own pace.
        var partner = Spot(first, theo, 1, start.AddSeconds(2).AddMilliseconds(50));
        var later = Spot(partner, maya, 2, start.AddSeconds(2).AddMilliseconds(300));
        Assert.Equal(2, View(later).GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
    }

    [Theory]
    [InlineData(null)]
    [InlineData(-1)]
    [InlineData(7)]
    public void A_spot_must_name_one_of_the_seven_differences(int? index)
    {
        var started = Start();
        var error = Assert.Throws<InvalidOperationException>(() => Spot(started, maya, index, start.AddSeconds(1)));
        Assert.Equal("That isn't one of the differences.", error.Message);
    }

    [Fact]
    public void Only_spots_and_only_players()
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.SpotTheDifference, started.StateJson, maya, "pick", null, 0, start));
        Assert.Throws<InvalidOperationException>(() => Spot(started, Guid.NewGuid(), 0, start.AddSeconds(1)));
    }

    [Fact]
    public void A_picture_all_found_pauses_for_a_beat_then_the_next_starts_with_a_fresh_clock()
    {
        var started = Start(11);
        var done = FindAll(started, start, 0, 1, 2, 3);
        var between = View(done);
        Assert.Equal("between", between.GetProperty("phase").GetString());
        Assert.False(done.Completed);
        var lastFind = start.AddSeconds(SpotTheDifferenceRules.Differences);
        Assert.Equal(lastFind.AddSeconds(SpotTheDifferenceRules.BeatSeconds), done.DeadlineUtc);
        // The finished picture stays up, every difference circled, until the beat is over.
        Assert.Equal(0, between.GetProperty("scene").GetInt32());
        Assert.Equal(7, between.GetProperty("found").GetArrayLength());
        Assert.Equal("The next picture is on its way.", Assert.Throws<InvalidOperationException>(() => Spot(done, maya, 0, lastFind.AddSeconds(1))).Message);

        var next = Timeout(done, lastFind.AddSeconds(3));
        var view = View(next);
        Assert.Equal("spotting", view.GetProperty("phase").GetString());
        Assert.Equal(1, view.GetProperty("scene").GetInt32());
        Assert.Equal(SpotTheDifferenceRules.Plan(11)[1].Kind, view.GetProperty("kind").GetString());
        Assert.Equal(SpotTheDifferenceRules.Plan(11)[1].Seed, view.GetProperty("seed").GetInt32());
        Assert.Empty(view.GetProperty("found").EnumerateArray());
        Assert.Equal(2, view.GetProperty("scenes").GetArrayLength());
        Assert.Equal(7, view.GetProperty("scenes")[0].GetProperty("found").GetArrayLength());
        Assert.Equal(lastFind.AddSeconds(3 + SpotTheDifferenceRules.SceneSeconds), next.DeadlineUtc);
        Assert.Equal(4, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(3, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
    }

    [Fact]
    public void A_picture_that_runs_out_reveals_the_rest_and_moves_on()
    {
        var started = Start();
        var one = Spot(started, theo, 5, start.AddSeconds(10));
        var ranOut = Timeout(one, start.AddSeconds(90));
        var view = View(ranOut);
        Assert.Equal("between", view.GetProperty("phase").GetString());
        Assert.True(view.GetProperty("scenes")[0].GetProperty("timedOut").GetBoolean());
        Assert.Equal(start.AddSeconds(90 + SpotTheDifferenceRules.BeatSeconds), ranOut.DeadlineUtc);

        var next = Timeout(ranOut, start.AddSeconds(93));
        Assert.Equal(1, View(next).GetProperty("scene").GetInt32());
        Assert.False(View(next).GetProperty("scenes")[1].GetProperty("timedOut").GetBoolean());
    }

    [Fact]
    public void After_the_last_picture_more_spots_wins()
    {
        var state = Start();
        var at = start;
        for (var scene = 0; scene < 3; scene++)
        {
            state = FindAll(state, at, 0, 1, 2, 3);
            at = at.AddSeconds(20);
            if (scene < 2) state = Timeout(state, at);
        }
        Assert.True(state.Completed);
        Assert.Null(state.DeadlineUtc);
        Assert.Null(state.CurrentTurnUserId);
        var view = View(state, theo);
        Assert.Equal("completed", view.GetProperty("phase").GetString());
        Assert.Equal(12, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(9, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(maya, view.GetProperty("winner").GetGuid());
        Assert.False(view.GetProperty("tie").GetBoolean());
        Assert.Equal(3, view.GetProperty("scenes").GetArrayLength());
        Assert.Equal("This game is over.", Assert.Throws<InvalidOperationException>(() => Spot(state, theo, 0, at.AddMinutes(1))).Message);

        var summary = engine.Summarise(GameType.SpotTheDifference, state.StateJson);
        Assert.Equal(12, summary.Scores[maya]);
        Assert.Equal(9, summary.Scores[theo]);
        Assert.Equal(3, summary.RoundsPlayed);
        Assert.Equal(maya, summary.DeclaredWinnerUserId);
    }

    [Fact]
    public void Equal_spots_is_a_tie_even_when_the_clock_ends_the_last_picture()
    {
        var state = Start();
        state = Timeout(state, start.AddSeconds(90));
        state = Timeout(state, start.AddSeconds(93));
        state = Timeout(state, start.AddSeconds(183));
        state = Timeout(state, start.AddSeconds(186));
        state = Spot(state, maya, 0, start.AddSeconds(200));
        state = Spot(state, theo, 1, start.AddSeconds(201));
        state = Timeout(state, start.AddSeconds(276));
        Assert.True(state.Completed);
        var view = View(state);
        Assert.True(view.GetProperty("tie").GetBoolean());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("winner").ValueKind);
        Assert.True(view.GetProperty("scenes")[2].GetProperty("timedOut").GetBoolean());
        var summary = engine.Summarise(GameType.SpotTheDifference, state.StateJson);
        Assert.Null(summary.DeclaredWinnerUserId);
        Assert.Equal(1, summary.Scores[maya]);
        Assert.Equal(1, summary.Scores[theo]);
    }

    [Fact]
    public void A_game_in_progress_summarises_the_spots_so_far_with_no_winner()
    {
        var state = Spot(Start(), maya, 2, start.AddSeconds(4));
        var summary = engine.Summarise(GameType.SpotTheDifference, state.StateJson);
        Assert.Equal(1, summary.Scores[maya]);
        Assert.Equal(0, summary.RoundsPlayed);
        Assert.Null(summary.DeclaredWinnerUserId);
        Assert.Equal(1, engine.Summarise(GameType.SpotTheDifference, FindAll(Start(), start, 0).StateJson).RoundsPlayed);
    }

    private sealed class NoQuestions : IGameQuestionProvider
    {
        public Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<string>>([]);
        public Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<TriviaQuestion>>([]);
    }
}
