using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class GuessMyAnswerRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime now = DateTime.UtcNow;

    private GameEngineResult Start(string region = GameRegion.Global)
        => engine.Start(GameType.GuessMyAnswer, [maya, theo], now, GuessMyAnswerRules.CreateSetup(region));

    private GameEngineResult Act(GameEngineResult from, Guid actor, string action, int index)
        => engine.Apply(GameType.GuessMyAnswer, from.StateJson, actor, action, null, index, now);

    private JsonElement View(GameEngineResult result, Guid? viewer)
        => JsonDocument.Parse(engine.ToPublicState(GameType.GuessMyAnswer, result.StateJson, viewer)).RootElement;

    [Fact]
    public void Starts_with_eight_questions_and_the_first_player_answering_about_themselves()
    {
        var started = Start();
        var view = View(started, maya);

        Assert.True(GameEngine.RulesFor(GameType.GuessMyAnswer) is GuessMyAnswerRules);
        Assert.False(engine.AnyoneMayAct(GameType.GuessMyAnswer));
        Assert.False(engine.IsTimed(GameType.GuessMyAnswer));
        Assert.Equal(maya, started.CurrentTurnUserId);
        Assert.Null(started.DeadlineUtc);
        Assert.Equal(8, view.GetProperty("totalRounds").GetInt32());
        Assert.Equal("answering", view.GetProperty("phase").GetString());
        Assert.Equal(maya, view.GetProperty("subject").GetGuid());
        Assert.Equal(theo, view.GetProperty("guesser").GetGuid());
        Assert.Equal(4, view.GetProperty("choices").GetArrayLength());
        Assert.False(string.IsNullOrWhiteSpace(view.GetProperty("question").GetString()));
    }

    [Fact]
    public void The_setup_fixes_the_questions_so_every_start_from_it_is_the_same()
    {
        var setup = GuessMyAnswerRules.CreateSetup(GameRegion.Global);
        var first = engine.ToPublicState(GameType.GuessMyAnswer, engine.Start(GameType.GuessMyAnswer, [maya, theo], now, setup).StateJson, null);
        var second = engine.ToPublicState(GameType.GuessMyAnswer, engine.Start(GameType.GuessMyAnswer, [maya, theo], now, setup).StateJson, null);
        Assert.Equal(first, second);
        using var parsed = JsonDocument.Parse(setup);
        var questions = parsed.RootElement.GetProperty("questions").EnumerateArray().Select(q => q.GetProperty("text").GetString()).ToList();
        Assert.Equal(8, questions.Distinct().Count());
    }

    [Fact]
    public void Only_the_subject_answers_and_only_the_guesser_guesses()
    {
        var started = Start();
        Assert.Throws<InvalidOperationException>(() => Act(started, theo, "answer", 0));
        Assert.Throws<InvalidOperationException>(() => Act(started, maya, "guess", 0));
        Assert.Throws<InvalidOperationException>(() => Act(started, maya, "answer", 4));
        Assert.Throws<InvalidOperationException>(() => Act(started, maya, "wave", 0));
        Assert.Throws<InvalidOperationException>(() => Act(started, Guid.NewGuid(), "answer", 0));

        var answered = Act(started, maya, "answer", 2);
        Assert.Equal(theo, answered.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Act(answered, maya, "answer", 1));
        Assert.Throws<InvalidOperationException>(() => Act(answered, maya, "guess", 1));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.GuessMyAnswer, answered.StateJson, theo, "guess", null, null, now));
    }

    [Fact]
    public void The_answer_is_the_subjects_secret_until_the_guess_is_in()
    {
        var answered = Act(Start(), maya, "answer", 3);

        Assert.Equal(3, View(answered, maya).GetProperty("answer").GetInt32());
        Assert.Equal(JsonValueKind.Null, View(answered, theo).GetProperty("answer").ValueKind);
        Assert.Equal(JsonValueKind.Null, View(answered, null).GetProperty("answer").ValueKind);
        Assert.True(View(answered, theo).GetProperty("answered").GetBoolean());
        Assert.DoesNotContain("\"answer\":3", engine.ToPublicState(GameType.GuessMyAnswer, answered.StateJson, theo), StringComparison.Ordinal);

        var guessed = Act(answered, theo, "guess", 1);
        var last = View(guessed, theo).GetProperty("last");
        Assert.Equal(3, last.GetProperty("answer").GetInt32());
        Assert.Equal(1, last.GetProperty("guess").GetInt32());
        Assert.False(last.GetProperty("right").GetBoolean());
        Assert.Equal(maya, last.GetProperty("subject").GetGuid());
    }

    [Fact]
    public void A_right_guess_is_a_point_for_the_guesser_and_the_subject_swaps()
    {
        var right = Act(Act(Start(), maya, "answer", 1), theo, "guess", 1);
        var view = View(right, maya);
        Assert.Equal(1, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(0, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.True(view.GetProperty("last").GetProperty("right").GetBoolean());
        Assert.Equal(1, view.GetProperty("round").GetInt32());
        Assert.Equal(theo, view.GetProperty("subject").GetGuid());
        Assert.Equal(theo, right.CurrentTurnUserId);

        var wrong = Act(Act(right, theo, "answer", 0), maya, "guess", 3);
        Assert.Equal(0, View(wrong, maya).GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
    }

    [Fact]
    public void A_full_game_ends_after_eight_rounds_with_the_scores()
    {
        var state = Start();
        for (var round = 0; round < 8; round++)
        {
            Assert.False(state.Completed);
            var subject = round % 2 == 0 ? maya : theo;
            var guesser = round % 2 == 0 ? theo : maya;
            state = Act(state, subject, "answer", round % 4);
            // Maya always guesses right; Theo always guesses the first answer, which is right in rounds 0 and 4.
            state = Act(state, guesser, "guess", guesser == maya ? round % 4 : 0);
        }

        Assert.True(state.Completed);
        Assert.Null(state.CurrentTurnUserId);
        var summary = engine.Summarise(GameType.GuessMyAnswer, state.StateJson);
        Assert.Equal(8, summary.RoundsPlayed);
        Assert.Equal(4, summary.Scores[maya]);
        Assert.Equal(2, summary.Scores[theo]);
        Assert.Equal("completed", View(state, maya).GetProperty("phase").GetString());
        Assert.Throws<InvalidOperationException>(() => Act(state, maya, "answer", 0));
    }

    [Fact]
    public void Nigerian_couples_get_naija_questions_and_others_get_global_ones()
    {
        var naija = GuessMyAnswerBank.Nigerian.Select(q => q.Text).ToHashSet();
        var global = GuessMyAnswerBank.Global.Select(q => q.Text).ToHashSet();
        Assert.True(global.Count >= 40);
        Assert.True(naija.Count >= 30);
        Assert.All(GuessMyAnswerBank.Global.Concat(GuessMyAnswerBank.Nigerian), q => Assert.Equal(4, q.Choices.Count));

        for (var i = 0; i < 5; i++)
        {
            Assert.Contains(View(Start(GameRegion.Nigeria), maya).GetProperty("question").GetString()!, naija);
            Assert.Contains(View(Start(GameRegion.Global), maya).GetProperty("question").GetString()!, global);
        }
    }

    [Fact]
    public void An_old_invite_with_trivia_questions_still_starts_and_an_old_quiz_state_still_reads()
    {
        var old = engine.Start(GameType.GuessMyAnswer, [maya, theo], now, """{"region":"global","questions":["What is the capital of France?"]}""");
        Assert.Equal(8, View(old, maya).GetProperty("totalRounds").GetInt32());

        const string quizState = """{"Players":[],"Scores":{},"Round":3,"TurnIndex":1,"Prompt":"Q","Answers":{},"LastResult":"match","Questions":["Q"]}""";
        Assert.Equal(0, JsonDocument.Parse(engine.ToPublicState(GameType.GuessMyAnswer, quizState, maya)).RootElement.GetProperty("totalRounds").GetInt32());
        Assert.Equal(3, engine.Summarise(GameType.GuessMyAnswer, quizState).RoundsPlayed);
    }
}
