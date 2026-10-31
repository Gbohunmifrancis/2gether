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
    public void Both_answer_at_once_and_an_answer_is_only_its_writers_until_both_are_in()
    {
        var started = Start();
        Assert.True(engine.AnyoneMayAct(GameType.CoupleQuiz));
        Assert.Throws<InvalidOperationException>(() => Answer(started, maya, "  "));
        Assert.Throws<InvalidOperationException>(() => Answer(started, Guid.NewGuid(), "Paris"));
        Assert.Throws<InvalidOperationException>(() => Answer(started, maya, new string('x', SameAnswerRules.MaxAnswerLength + 1)));
        // Theo goes first: nobody has to wait their turn.
        var one = Answer(started, theo, "Paris");
        Assert.Equal(maya, one.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Answer(one, theo, "Rome"));

        Assert.Equal("Paris", View(one, theo).GetProperty("mine").GetString());
        foreach (Guid? viewer in new Guid?[] { maya, null })
        {
            var json = engine.ToPublicState(GameType.CoupleQuiz, one.StateJson, viewer);
            Assert.DoesNotContain("Paris", json, StringComparison.Ordinal);
            var view = JsonDocument.Parse(json).RootElement;
            Assert.Equal(1, view.GetProperty("answerCount").GetInt32());
            Assert.Equal(theo, view.GetProperty("answered")[0].GetGuid());
            Assert.Equal(JsonValueKind.Null, view.GetProperty("mine").ValueKind);
        }
    }

    [Fact]
    public void Once_both_are_in_both_answers_show_to_both()
    {
        var started = Start();
        var prompt = View(started, maya).GetProperty("prompt").GetString();
        var both = Answer(Answer(started, maya, "Paris"), theo, "Rome");
        foreach (var viewer in new[] { maya, theo })
        {
            var last = View(both, viewer).GetProperty("last");
            Assert.Equal(0, last.GetProperty("round").GetInt32());
            Assert.Equal(prompt, last.GetProperty("prompt").GetString());
            Assert.Equal("Paris", last.GetProperty("answers").GetProperty(maya.ToString()).GetString());
            Assert.Equal("Rome", last.GetProperty("answers").GetProperty(theo.ToString()).GetString());
            Assert.False(last.GetProperty("match").GetBoolean());
            Assert.Equal(JsonValueKind.Null, View(both, viewer).GetProperty("mine").ValueKind);
        }
    }

    [Theory]
    [InlineData("Paris", " paris ", true)]
    [InlineData("The beach", "beach!", true)]
    [InlineData("Café", "cafe", true)]
    [InlineData("Rock & roll", "rock and roll", true)]
    [InlineData("Ice cream", "icecream", true)]
    [InlineData("chocolate", "chocolat", true)]
    [InlineData("Our first flat", "first flat", true)]
    [InlineData("la playa", "Playa", true)]
    [InlineData("❤️", "❤️", true)]
    [InlineData("Paris", "Rome", false)]
    [InlineData("cat", "car", false)]
    [InlineData("pizza", "pasta", false)]
    [InlineData("The", "the", true)]
    [InlineData("❤️", "😂", false)]
    [InlineData("दिल", "दल", false)]
    public void Answers_that_say_the_same_thing_match(string first, string second, bool same)
        => Assert.Equal(same, SameAnswerRules.Same(first, second));

    [Fact]
    public void A_who_prompt_is_answered_by_picking_one_of_you()
    {
        var setup = JsonSerializer.Serialize(new { region = "global", prompts = new[] { "Who wakes up first?", "Who is the better cook?", "What's our song?" } });
        var started = engine.Start(GameType.CoupleQuiz, [maya, theo], now, setup);
        Assert.Equal("who", View(started, maya).GetProperty("kind").GetString());
        Assert.Throws<InvalidOperationException>(() => Answer(started, maya, Guid.NewGuid().ToString()));

        // Both say Maya wakes up first: Maya picks herself, Theo picks Maya. That's a match.
        var first = Answer(Answer(started, maya, maya.ToString()), theo, maya.ToString().ToUpperInvariant());
        var last = View(first, theo).GetProperty("last");
        Assert.True(last.GetProperty("match").GetBoolean());
        Assert.Equal("who", last.GetProperty("kind").GetString());
        Assert.Equal(maya.ToString(), last.GetProperty("answers").GetProperty(theo.ToString()).GetString());

        // Each says "me": that's not the same answer.
        var second = Answer(Answer(first, maya, maya.ToString()), theo, theo.ToString());
        Assert.False(View(second, maya).GetProperty("last").GetProperty("match").GetBoolean());
        Assert.Equal("text", View(second, maya).GetProperty("kind").GetString());
        Assert.Equal(1, View(second, maya).GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
    }

    [Fact]
    public void Every_who_prompt_in_the_banks_is_about_the_two_of_you_and_none_asks_about_you_alone()
    {
        foreach (var prompt in SameAnswerBank.Global.Concat(SameAnswerBank.Nigerian))
        {
            Assert.Equal(prompt.StartsWith("Who ", StringComparison.Ordinal), SameAnswerBank.IsWho(prompt));
            // "you" alone makes each partner answer about themselves: never the same answer.
            Assert.DoesNotMatch(@"\b[Yy]ou(r)?\b", prompt.Replace("I love you", "", StringComparison.Ordinal));
        }
        Assert.False(SameAnswerBank.IsWho("Who painted the Mona Lisa?"));
        Assert.Equal(SameAnswerBank.Global.Count, SameAnswerBank.Global.Distinct().Count());
        Assert.Equal(SameAnswerBank.Nigerian.Count, SameAnswerBank.Nigerian.Distinct().Count());
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
            state = Answer(state, first, "Paris");
            state = Answer(state, second, round < 6 ? " paris " : "Rome");
            if (!state.Completed) Assert.Equal(round < 6 ? "match" : "different", View(state, maya).GetProperty("lastResult").GetString());
        }
        var history = View(state, maya).GetProperty("history");
        Assert.Equal(10, history.GetArrayLength());
        Assert.Equal(6, history.EnumerateArray().Count(played => played.GetProperty("match").GetBoolean()));
        Assert.Equal(JsonValueKind.Null, View(state, maya).GetProperty("prompt").ValueKind);

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

        Assert.Equal(0, view.GetProperty("history").GetArrayLength());
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.CoupleQuiz, old, maya, "answer", "Venus", null, now));

        var next = engine.Apply(GameType.CoupleQuiz, old, theo, "answer", "mercury", null, now);
        var after = JsonDocument.Parse(engine.ToPublicState(GameType.CoupleQuiz, next.StateJson, maya)).RootElement;
        Assert.Equal("Q4", after.GetProperty("prompt").GetString());
        Assert.Equal(3, after.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal("Mercury", after.GetProperty("last").GetProperty("answers").GetProperty(maya.ToString()).GetString());
        // An old trivia question that starts with "Who" is still answered in words.
        Assert.Equal("text", after.GetProperty("last").GetProperty("kind").GetString());
    }

    [Fact]
    public void A_new_game_draws_prompts_the_couple_did_not_just_play()
    {
        var recent = Enumerable.Range(0, 3).Select(_ => SameAnswerRules.CreateSetup(GameRegion.Global)).ToList();
        var seen = recent.SelectMany(setup => JsonDocument.Parse(setup).RootElement.GetProperty("prompts").EnumerateArray().Select(prompt => prompt.GetString()!)).ToHashSet();
        var fresh = JsonDocument.Parse(SameAnswerRules.CreateSetup(GameRegion.Global, recent)).RootElement.GetProperty("prompts").EnumerateArray().Select(prompt => prompt.GetString()!).ToList();
        Assert.Equal(10, fresh.Distinct().Count());
        // The bank has more than 30 prompts beyond the 30 just seen... minus overlaps, so at least min(10, unseen) are new.
        var unseen = SameAnswerBank.Global.Count - seen.Count;
        Assert.Equal(Math.Min(10, unseen), fresh.Count(prompt => !seen.Contains(prompt)));
    }

    [Fact]
    public void An_old_invite_with_trivia_questions_starts_with_couple_prompts()
    {
        var started = engine.Start(GameType.CoupleQuiz, [maya, theo], now, """{"region":"ng","questions":["Which planet is closest to the sun?"]}""");
        Assert.Contains(View(started, maya).GetProperty("prompt").GetString(), SameAnswerBank.Nigerian);
        Assert.Contains(View(engine.Start(GameType.CoupleQuiz, [maya, theo], now), maya).GetProperty("prompt").GetString(), SameAnswerBank.Global);
    }
}
