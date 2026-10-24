using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class SameAnswerRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime now = DateTime.UtcNow;

    private GameEngineResult Start(string region = GameRegion.Global)
        => engine.Start(GameType.CoupleQuiz, [maya, theo], now, SameAnswerRules.CreateSetup(region));

    private GameEngineResult Answer(GameEngineResult from, Guid actor, string answer)
        => engine.Apply(GameType.CoupleQuiz, from.StateJson, actor, "answer", answer, null, now);

    private JsonElement View(GameEngineResult result, Guid? viewer)
        => JsonDocument.Parse(engine.ToPublicState(GameType.CoupleQuiz, result.StateJson, viewer)).RootElement;

    [Fact]
    public void Starts_with_ten_couple_prompts_not_trivia()
    {
        var started = Start();
        var view = View(started, maya);

        Assert.True(GameEngine.RulesFor(GameType.CoupleQuiz) is SameAnswerRules);
        Assert.Equal(maya, started.CurrentTurnUserId);
        Assert.Null(started.DeadlineUtc);
        Assert.Equal(10, view.GetProperty("totalRounds").GetInt32());
        Assert.Contains(view.GetProperty("prompt").GetString(), SameAnswerBank.Global);
        Assert.Equal(0, view.GetProperty("answerCount").GetInt32());
    }

    [Fact]
    public void Turns_alternate_and_answers_stay_hidden_from_everyone()
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => Answer(started, theo, "Lagos"));
        Assert.Throws<InvalidOperationException>(() => Answer(started, maya, "  "));
        var one = Answer(started, maya, "Paris");
        Assert.Equal(theo, one.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Answer(one, maya, "Rome"));

        foreach (Guid? viewer in new Guid?[] { maya, theo, null })
        {
            var json = engine.ToPublicState(GameType.CoupleQuiz, one.StateJson, viewer);
            Assert.DoesNotContain("Paris", json, StringComparison.Ordinal);
            Assert.Equal(1, JsonDocument.Parse(json).RootElement.GetProperty("answerCount").GetInt32());
        }
    }

    [Fact]
    public void Matching_answers_give_each_a_heart_and_a_full_game_reports_scores()
    {
        var state = Start();
        for (var round = 0; round < 10; round++)
        {
            Assert.False(state.Completed);
            var first = round % 2 == 0 ? maya : theo;
            var second = first == maya ? theo : maya;
            Assert.Equal(first, state.CurrentTurnUserId);
            state = Answer(state, first, "Paris");
            state = Answer(state, second, round < 6 ? " paris " : "Rome");
            if (!state.Completed) Assert.Equal(round < 6 ? "match" : "different", View(state, maya).GetProperty("lastResult").GetString());
        }

        Assert.True(state.Completed);
        Assert.Null(state.CurrentTurnUserId);
        var summary = engine.Summarise(GameType.CoupleQuiz, state.StateJson);
        Assert.Equal(10, summary.RoundsPlayed);
        Assert.Equal(6, summary.Scores[maya]);
        Assert.Equal(6, summary.Scores[theo]);
        Assert.Throws<InvalidOperationException>(() => Answer(state, maya, "Paris"));
    }

    [Fact]
    public void Nigerian_couples_get_naija_prompts_and_others_get_global_ones()
    {
        Assert.True(SameAnswerBank.Global.Count >= 40);
        Assert.True(SameAnswerBank.Nigerian.Count >= 30);
        Assert.Contains("What is our ideal date night?", SameAnswerBank.Global);
        for (var i = 0; i < 5; i++)
        {
            Assert.Contains(View(Start(GameRegion.Nigeria), maya).GetProperty("prompt").GetString(), SameAnswerBank.Nigerian);
            Assert.Contains(View(Start(GameRegion.Global), maya).GetProperty("prompt").GetString(), SameAnswerBank.Global);
        }
    }

    [Fact]
    public void A_game_in_progress_from_before_keeps_its_trivia_prompts()
    {
        // The engine's old quiz state, as stored by games started before Same answer had its own rules.
        var old = JsonSerializer.Serialize(new
        {
            Players = new[] { maya, theo },
            Scores = new Dictionary<Guid, int> { [maya] = 2, [theo] = 2 },
            Round = 2,
            TurnIndex = 1,
            Prompt = "Which planet is closest to the sun?",
            Answers = new Dictionary<Guid, string> { [maya] = "Mercury" },
            LastResult = "match",
            Questions = new[] { "Q1", "Q2", "Which planet is closest to the sun?", "Q4" }
        });

        var view = JsonDocument.Parse(engine.ToPublicState(GameType.CoupleQuiz, old, maya)).RootElement;
        Assert.Equal("Which planet is closest to the sun?", view.GetProperty("prompt").GetString());
        Assert.Equal(1, view.GetProperty("answerCount").GetInt32());
        Assert.Equal(4, view.GetProperty("totalRounds").GetInt32());

        var next = engine.Apply(GameType.CoupleQuiz, old, theo, "answer", "mercury", null, now);
        var after = JsonDocument.Parse(engine.ToPublicState(GameType.CoupleQuiz, next.StateJson, maya)).RootElement;
        Assert.Equal("Q4", after.GetProperty("prompt").GetString());
        Assert.Equal(3, after.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
    }

    [Fact]
    public void An_old_invite_with_trivia_questions_starts_with_couple_prompts()
    {
        var started = engine.Start(GameType.CoupleQuiz, [maya, theo], now, """{"region":"ng","questions":["Which planet is closest to the sun?"]}""");
        Assert.Contains(View(started, maya).GetProperty("prompt").GetString(), SameAnswerBank.Nigerian);
        Assert.Contains(View(engine.Start(GameType.CoupleQuiz, [maya, theo], now), maya).GetProperty("prompt").GetString(), SameAnswerBank.Global);
    }
}
