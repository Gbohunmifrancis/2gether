using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class SkillDuelRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime now = DateTime.UtcNow;

    private GameEngineResult Start(int seed = 7) => engine.Start(GameType.SkillDuel, [maya, theo], now, SkillDuelRules.CreateSetup(GameRegion.Global, seed));

    private GameEngineResult Score(GameEngineResult from, Guid actor, int? round, string? value)
        => engine.Apply(GameType.SkillDuel, from.StateJson, actor, "score", value, round, now);

    private GameEngineResult Score(GameEngineResult from, Guid actor, int round, int score) => Score(from, actor, round, $$"""{"score":{{score}}}""");

    private JsonElement View(GameEngineResult result, Guid? viewer)
    {
        using var document = JsonDocument.Parse(engine.ToPublicState(GameType.SkillDuel, result.StateJson, viewer));
        return document.RootElement.Clone();
    }

    private GameEngineResult PlayAll(GameEngineResult from, Guid actor, params int[] scores)
    {
        var state = from;
        for (var round = 0; round < scores.Length; round++) state = Score(state, actor, round, scores[round]);
        return state;
    }

    [Fact]
    public void Starts_with_five_varied_rounds_both_may_play_and_there_is_no_clock()
    {
        var started = Start();
        var view = View(started, maya);

        Assert.True(GameEngine.RulesFor(GameType.SkillDuel) is SkillDuelRules);
        Assert.True(engine.AnyoneMayAct(GameType.SkillDuel));
        Assert.False(engine.IsTimed(GameType.SkillDuel));
        Assert.Null(started.DeadlineUtc);
        Assert.False(started.Completed);
        Assert.Equal(5, view.GetProperty("totalRounds").GetInt32());
        var kinds = view.GetProperty("rounds").EnumerateArray().Select(round => round.GetProperty("kind").GetString()!).ToList();
        Assert.Equal(5, kinds.Count);
        Assert.All(kinds, kind => Assert.Contains(kind, SkillDuelRules.Kinds));
        Assert.Equal(5, kinds.Distinct().Count());
        Assert.Equal("playing", view.GetProperty("phase").GetString());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("totals").ValueKind);
    }

    [Fact]
    public void The_setup_seed_fixes_the_rounds_and_both_partners_see_the_same_ones()
    {
        Assert.Equal(SkillDuelRules.CreateSetup("global", 42), SkillDuelRules.CreateSetup("global", 42));
        for (var seed = 0; seed < 50; seed++)
        {
            var plan = SkillDuelRules.Plan(seed);
            Assert.Equal(5, plan.Count);
            for (var i = 1; i < plan.Count; i++) Assert.NotEqual(plan[i - 1].Kind, plan[i].Kind);
        }
        // Over many duels every kind turns up, in different orders.
        Assert.Equal(6, Enumerable.Range(0, 50).SelectMany(seed => SkillDuelRules.Plan(seed)).Select(round => round.Kind).Distinct().Count());
        Assert.True(Enumerable.Range(0, 50).Select(seed => SkillDuelRules.Plan(seed)[0].Kind).Distinct().Count() > 1);

        var started = Start(42);
        var forMaya = View(started, maya).GetProperty("rounds");
        var forTheo = View(started, theo).GetProperty("rounds");
        for (var i = 0; i < 5; i++)
        {
            Assert.Equal(forMaya[i].GetProperty("kind").GetString(), forTheo[i].GetProperty("kind").GetString());
            Assert.Equal(forMaya[i].GetProperty("seed").GetInt32(), forTheo[i].GetProperty("seed").GetInt32());
            Assert.Equal(SkillDuelRules.Plan(42)[i].Seed, forMaya[i].GetProperty("seed").GetInt32());
        }
    }

    [Fact]
    public void A_duel_without_a_setup_still_gets_five_rounds()
    {
        var started = engine.Start(GameType.SkillDuel, [maya, theo], now, """{"region":"global"}""");
        Assert.Equal(5, View(started, maya).GetProperty("rounds").GetArrayLength());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("88")]
    [InlineData("""{"score":101}""")]
    [InlineData("""{"score":-1}""")]
    [InlineData("""{"score":50.5}""")]
    [InlineData("""{"score":"50"}""")]
    [InlineData("""{"points":50}""")]
    [InlineData("""[50]""")]
    [InlineData("not json")]
    public void A_score_must_be_a_whole_number_from_0_to_100(string? value)
        => Assert.Throws<InvalidOperationException>(() => Score(Start(), maya, 0, value));

    [Fact]
    public void Rounds_play_in_any_order_but_each_only_once_by_each_player()
    {
        var started = Start();
        var fourth = Score(started, maya, 3, 70);
        var first = Score(fourth, maya, 0, 0);
        Assert.Throws<InvalidOperationException>(() => Score(first, maya, 3, 90));
        Assert.Throws<InvalidOperationException>(() => Score(started, maya, 5, 50));
        Assert.Throws<InvalidOperationException>(() => Score(started, maya, -1, 50));
        Assert.Throws<InvalidOperationException>(() => Score(started, maya, null, """{"score":50}"""));
        Assert.Throws<InvalidOperationException>(() => Score(started, Guid.NewGuid(), 0, 50));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.SkillDuel, started.StateJson, maya, "answer", """{"score":50}""", 0, now));
        // Theo may still play round 3 after Maya has.
        var theoToo = Score(first, theo, 3, 40);
        Assert.Equal(40, View(theoToo, theo).GetProperty("rounds")[3].GetProperty("mine").GetInt32());
        Assert.Equal(100, View(Score(started, maya, 0, 100), maya).GetProperty("rounds")[0].GetProperty("mine").GetInt32());
        Assert.Equal(0, View(first, maya).GetProperty("rounds")[0].GetProperty("mine").GetInt32());
    }

    [Fact]
    public void The_turn_names_whoever_still_has_rounds_to_play()
    {
        var started = Start();
        Assert.Equal(maya, started.CurrentTurnUserId);
        var one = Score(started, maya, 0, 50);
        Assert.Equal(theo, one.CurrentTurnUserId);
        var theoDone = PlayAll(one, theo, 10, 20, 30, 40, 50);
        Assert.Equal(maya, theoDone.CurrentTurnUserId);
        Assert.False(theoDone.Completed);
    }

    [Fact]
    public void A_partners_score_stays_hidden_until_you_have_played_that_round_too()
    {
        var theoPlayed = Score(Start(), theo, 2, 81);

        var forMaya = View(theoPlayed, maya).GetProperty("rounds")[2];
        Assert.True(forMaya.GetProperty("played").GetBoolean());
        Assert.Equal(JsonValueKind.Null, forMaya.GetProperty("partner").ValueKind);
        Assert.Equal(JsonValueKind.Null, forMaya.GetProperty("mine").ValueKind);
        Assert.Equal(JsonValueKind.Null, forMaya.GetProperty("winner").ValueKind);
        Assert.False(forMaya.GetProperty("tie").GetBoolean());
        // Nobody else sees it either: no score is shown anywhere but in Theo's own view.
        foreach (var viewer in new Guid?[] { maya, null })
            Assert.All(View(theoPlayed, viewer).GetProperty("rounds").EnumerateArray(), round =>
            {
                Assert.Equal(JsonValueKind.Null, round.GetProperty("mine").ValueKind);
                Assert.Equal(JsonValueKind.Null, round.GetProperty("partner").ValueKind);
            });

        var forTheo = View(theoPlayed, theo).GetProperty("rounds")[2];
        Assert.Equal(81, forTheo.GetProperty("mine").GetInt32());
        Assert.False(forTheo.GetProperty("played").GetBoolean());

        var both = Score(theoPlayed, maya, 2, 64);
        var revealed = View(both, maya).GetProperty("rounds")[2];
        Assert.Equal(64, revealed.GetProperty("mine").GetInt32());
        Assert.Equal(81, revealed.GetProperty("partner").GetInt32());
        Assert.Equal(theo, revealed.GetProperty("winner").GetGuid());
        Assert.Equal(1, View(both, maya).GetProperty("hearts").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(0, View(both, maya).GetProperty("hearts").GetProperty(maya.ToString()).GetInt32());
    }

    [Fact]
    public void Hearts_count_only_rounds_both_have_played()
    {
        // Maya has played everything; Theo one round. The hearts can't give away her other scores.
        var state = Score(PlayAll(Start(), maya, 90, 90, 90, 90, 90), theo, 0, 95);
        var hearts = View(state, theo).GetProperty("hearts");
        Assert.Equal(1, hearts.GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(0, hearts.GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(JsonValueKind.Null, View(state, theo).GetProperty("totals").ValueKind);
        Assert.Equal(JsonValueKind.Null, View(state, theo).GetProperty("winner").ValueKind);
        Assert.Equal(1, engine.Summarise(GameType.SkillDuel, state.StateJson).RoundsPlayed);
    }

    [Fact]
    public void A_tied_round_is_a_heart_each()
    {
        var tied = Score(Score(Start(), maya, 1, 77), theo, 1, 77);
        var round = View(tied, theo).GetProperty("rounds")[1];
        Assert.True(round.GetProperty("tie").GetBoolean());
        Assert.Equal(JsonValueKind.Null, round.GetProperty("winner").ValueKind);
        var hearts = View(tied, theo).GetProperty("hearts");
        Assert.Equal(1, hearts.GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(1, hearts.GetProperty(theo.ToString()).GetInt32());
    }

    [Fact]
    public void The_duel_ends_when_both_have_played_all_five_and_goes_to_more_hearts()
    {
        var mayaDone = PlayAll(Start(), maya, 90, 40, 70, 100, 55);
        Assert.False(mayaDone.Completed);
        var done = PlayAll(mayaDone, theo, 80, 60, 70, 20, 50);
        Assert.True(done.Completed);
        Assert.Null(done.CurrentTurnUserId);

        var view = View(done, theo);
        Assert.Equal("completed", view.GetProperty("phase").GetString());
        // Maya won rounds 0, 3 and 4, Theo round 1, round 2 tied.
        Assert.Equal(4, view.GetProperty("hearts").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(2, view.GetProperty("hearts").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(355, view.GetProperty("totals").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(280, view.GetProperty("totals").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(maya, view.GetProperty("winner").GetGuid());
        Assert.Equal("hearts", view.GetProperty("decidedBy").GetString());
        Assert.Equal(2, view.GetProperty("margin").GetInt32());
        Assert.Equal(75, view.GetProperty("pointMargin").GetInt32());
        Assert.False(view.GetProperty("tie").GetBoolean());

        var summary = engine.Summarise(GameType.SkillDuel, done.StateJson);
        // The history keeps the hearts, and names the duel's winner.
        Assert.Equal(4, summary.Scores[maya]);
        Assert.Equal(2, summary.Scores[theo]);
        Assert.Equal(5, summary.RoundsPlayed);
        Assert.Equal(maya, summary.DeclaredWinnerUserId);

        Assert.Throws<InvalidOperationException>(() => Score(done, maya, 0, 50));
    }

    [Fact]
    public void The_hearts_decide_the_duel_even_when_the_points_say_otherwise()
    {
        // Theo wins three rounds by a point; Maya wins two by a lot, and has far more points.
        var done = PlayAll(PlayAll(Start(), maya, 50, 50, 50, 100, 100), theo, 51, 51, 51, 10, 10);
        var view = View(done, maya);
        Assert.Equal(theo, view.GetProperty("winner").GetGuid());
        Assert.Equal("hearts", view.GetProperty("decidedBy").GetString());
        Assert.Equal(1, view.GetProperty("margin").GetInt32());
        Assert.Equal(177, view.GetProperty("pointMargin").GetInt32());
        Assert.Equal(350, view.GetProperty("totals").GetProperty(maya.ToString()).GetInt32());

        var summary = engine.Summarise(GameType.SkillDuel, done.StateJson);
        Assert.Equal(theo, summary.DeclaredWinnerUserId);
        Assert.Equal(3, summary.Scores[theo]);
        Assert.Equal(2, summary.Scores[maya]);
    }

    [Fact]
    public void Level_on_hearts_the_points_break_the_tie()
    {
        // Maya takes round 0, Theo round 1, the other three are a heart each: 4 hearts each, 250 points to 230.
        var done = PlayAll(PlayAll(Start(), maya, 90, 10, 50, 50, 50), theo, 60, 20, 50, 50, 50);
        var view = View(done, theo);
        Assert.Equal(4, view.GetProperty("hearts").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(4, view.GetProperty("hearts").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(maya, view.GetProperty("winner").GetGuid());
        Assert.False(view.GetProperty("tie").GetBoolean());
        Assert.Equal("points", view.GetProperty("decidedBy").GetString());
        Assert.Equal(0, view.GetProperty("margin").GetInt32());
        Assert.Equal(20, view.GetProperty("pointMargin").GetInt32());

        // The history's hearts are level, but it declares the duel's winner, so the record agrees with the board.
        var summary = engine.Summarise(GameType.SkillDuel, done.StateJson);
        Assert.Equal(summary.Scores[maya], summary.Scores[theo]);
        Assert.Equal(maya, summary.DeclaredWinnerUserId);
        var session = GameSession.Create(Guid.NewGuid(), GameType.SkillDuel, maya, now);
        session.Start(maya, null, done.StateJson, now);
        session.Complete(done.StateJson, now);
        Assert.Equal(maya, new GameOutcomeService(engine).Record(session, GameOutcome.Completed, null, now).WinnerUserId);

        var tally = SkillDuelRules.Tally(done.StateJson);
        Assert.Equal(0, tally.Margin);
        Assert.False(tally.Tied);
    }

    [Fact]
    public void Level_on_hearts_and_points_is_a_tie()
    {
        var done = PlayAll(PlayAll(Start(), maya, 60, 40, 80, 20, 50), theo, 40, 60, 20, 80, 50);
        var view = View(done, maya);
        Assert.True(view.GetProperty("tie").GetBoolean());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("winner").ValueKind);
        Assert.Equal(JsonValueKind.Null, view.GetProperty("decidedBy").ValueKind);
        Assert.Equal(0, view.GetProperty("margin").GetInt32());
        Assert.Equal(0, view.GetProperty("pointMargin").GetInt32());
        Assert.Null(engine.Summarise(GameType.SkillDuel, done.StateJson).DeclaredWinnerUserId);
        var session = GameSession.Create(Guid.NewGuid(), GameType.SkillDuel, maya, now);
        session.Start(maya, null, done.StateJson, now);
        Assert.Null(new GameOutcomeService(engine).Record(session, GameOutcome.Completed, null, now).WinnerUserId);
        Assert.True(SkillDuelRules.Tally(done.StateJson).Tied);
    }

    [Fact]
    public void An_unfinished_duel_declares_no_winner()
    {
        var half = PlayAll(Start(), maya, 100, 100, 100);
        Assert.Null(engine.Summarise(GameType.SkillDuel, half.StateJson).DeclaredWinnerUserId);
        Assert.Equal(JsonValueKind.Null, View(half, maya).GetProperty("decidedBy").ValueKind);
        Assert.False(SkillDuelRules.Tally(half.StateJson).Tied);
    }
}
