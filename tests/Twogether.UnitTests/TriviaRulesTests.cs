using System.Net;
using System.Text;
using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Games;

namespace Twogether.UnitTests;

public sealed class TriviaRulesTests
{
    private static readonly DateTime Now = new(2026, 10, 24, 18, 0, 0, DateTimeKind.Utc);
    private readonly GameEngine engine = new();
    private readonly Guid ada = Guid.NewGuid();
    private readonly Guid tunde = Guid.NewGuid();

    private Guid[] Players => [ada, tunde];

    private static string Setup(string region, params TriviaQuestion[] questions)
        => JsonSerializer.Serialize(new { region, trivia = questions });

    private static TriviaQuestion Question(int n, int answer = 0)
        => new($"Question {n}?", [$"Option {n}A", $"Option {n}B", $"Option {n}C", $"Option {n}D"], answer);

    private static TriviaQuestion[] Ten() => Enumerable.Range(1, 10).Select(n => Question(n, n % 4)).ToArray();

    private JsonElement View(string stateJson, Guid? viewer)
    {
        using var document = JsonDocument.Parse(engine.ToPublicState(GameType.Trivia, stateJson, viewer));
        return document.RootElement.Clone();
    }

    private GameEngineResult Answer(string stateJson, Guid player, int index)
        => engine.Apply(GameType.Trivia, stateJson, player, "answer", null, index, Now);

    [Fact]
    public void Trivia_is_answered_by_both_at_once_with_no_clock()
    {
        Assert.True(engine.AnyoneMayAct(GameType.Trivia));
        Assert.False(engine.IsTimed(GameType.Trivia));
        var started = engine.Start(GameType.Trivia, Players, Now, Setup("global", Ten()));
        Assert.Null(started.DeadlineUtc);
        Assert.False(started.Completed);
        Assert.Equal(ada, started.CurrentTurnUserId);
    }

    [Fact]
    public void A_game_starts_on_the_first_of_ten_questions_without_its_answer()
    {
        var started = engine.Start(GameType.Trivia, Players, Now, Setup("global", Ten()));
        var view = View(started.StateJson, ada);
        Assert.Equal(0, view.GetProperty("round").GetInt32());
        Assert.Equal(10, view.GetProperty("totalRounds").GetInt32());
        Assert.Equal("Question 1?", view.GetProperty("question").GetProperty("text").GetString());
        Assert.Equal(4, view.GetProperty("question").GetProperty("options").GetArrayLength());
        Assert.False(view.GetProperty("question").TryGetProperty("answer", out _));
        Assert.Equal(JsonValueKind.Null, view.GetProperty("reveal").ValueKind);
        Assert.Equal(0, view.GetProperty("scores").GetProperty(ada.ToString()).GetInt32());
        Assert.False(view.GetProperty("completed").GetBoolean());
    }

    [Fact]
    public void A_pick_stays_secret_from_the_partner_until_both_have_answered()
    {
        var started = engine.Start(GameType.Trivia, Players, Now, Setup("global", Ten()));
        var first = Answer(started.StateJson, ada, 2);
        Assert.False(first.Completed);
        Assert.Equal(tunde, first.CurrentTurnUserId);

        var mine = View(first.StateJson, ada);
        Assert.Equal(2, mine.GetProperty("myAnswer").GetInt32());
        var theirs = View(first.StateJson, tunde);
        Assert.Equal(JsonValueKind.Null, theirs.GetProperty("myAnswer").ValueKind);
        Assert.Equal([ada.ToString()], theirs.GetProperty("answered").EnumerateArray().Select(item => item.GetString()));
        Assert.Equal(JsonValueKind.Null, theirs.GetProperty("reveal").ValueKind);
        Assert.Equal(JsonValueKind.Null, View(first.StateJson, null).GetProperty("myAnswer").ValueKind);
        // Neither the right answer nor Ada's pick is anywhere in Tunde's view.
        var raw = engine.ToPublicState(GameType.Trivia, first.StateJson, tunde);
        Assert.DoesNotContain("picks", raw, StringComparison.Ordinal);
        Assert.DoesNotContain("\"answer\"", raw, StringComparison.Ordinal);
    }

    [Fact]
    public void Both_answers_reveal_the_picks_and_the_right_answer_and_score_a_point_each_right()
    {
        // Question 1's right answer is index 1.
        var started = engine.Start(GameType.Trivia, Players, Now, Setup("global", Ten()));
        var revealed = Answer(Answer(started.StateJson, ada, 1).StateJson, tunde, 3);

        foreach (var viewer in new Guid?[] { ada, tunde })
        {
            var view = View(revealed.StateJson, viewer);
            var reveal = view.GetProperty("reveal");
            Assert.Equal(0, reveal.GetProperty("round").GetInt32());
            Assert.Equal(1, reveal.GetProperty("answer").GetInt32());
            Assert.Equal("Question 1?", reveal.GetProperty("question").GetProperty("text").GetString());
            Assert.Equal(1, reveal.GetProperty("picks").GetProperty(ada.ToString()).GetInt32());
            Assert.Equal(3, reveal.GetProperty("picks").GetProperty(tunde.ToString()).GetInt32());
            Assert.Equal(1, view.GetProperty("scores").GetProperty(ada.ToString()).GetInt32());
            Assert.Equal(0, view.GetProperty("scores").GetProperty(tunde.ToString()).GetInt32());
            // On to question 2, nobody has answered it yet.
            Assert.Equal(1, view.GetProperty("round").GetInt32());
            Assert.Equal("Question 2?", view.GetProperty("question").GetProperty("text").GetString());
            Assert.Empty(view.GetProperty("answered").EnumerateArray());
            Assert.Equal(JsonValueKind.Null, view.GetProperty("myAnswer").ValueKind);
        }
    }

    [Fact]
    public void A_full_game_completes_after_ten_questions_and_summarises_the_scores()
    {
        var questions = Ten();
        var state = engine.Start(GameType.Trivia, Players, Now, Setup("global", questions)).StateJson;
        GameEngineResult result = null!;
        for (var round = 0; round < 10; round++)
        {
            var right = questions[round].Answer;
            // Ada is always right; Tunde is right on even rounds only.
            state = Answer(state, tunde, round % 2 == 0 ? right : (right + 1) % 4).StateJson;
            result = Answer(state, ada, right);
            state = result.StateJson;
            Assert.Equal(round == 9, result.Completed);
        }

        Assert.Null(result.CurrentTurnUserId);
        var view = View(state, ada);
        Assert.True(view.GetProperty("completed").GetBoolean());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("question").ValueKind);
        Assert.Equal(9, view.GetProperty("reveal").GetProperty("round").GetInt32());

        var summary = engine.Summarise(GameType.Trivia, state);
        Assert.Equal(10, summary.Scores[ada]);
        Assert.Equal(5, summary.Scores[tunde]);
        Assert.Equal(10, summary.RoundsPlayed);
        Assert.Throws<InvalidOperationException>(() => Answer(state, ada, 0));
    }

    [Fact]
    public void Moves_that_are_not_allowed_are_refused()
    {
        var state = engine.Start(GameType.Trivia, Players, Now, Setup("global", Ten())).StateJson;
        Assert.Throws<InvalidOperationException>(() => Answer(state, ada, 4));
        Assert.Throws<InvalidOperationException>(() => Answer(state, ada, -1));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.Trivia, state, ada, "answer", "Paris", null, Now));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.Trivia, state, ada, "skip", null, 0, Now));
        Assert.Throws<InvalidOperationException>(() => Answer(state, Guid.NewGuid(), 0));
        var answered = Answer(state, ada, 0).StateJson;
        Assert.Throws<InvalidOperationException>(() => Answer(answered, ada, 1));
    }

    [Fact]
    public async Task Nigerian_couples_get_naija_trivia_without_asking_open_trivia()
    {
        var provider = new FakeProvider(Ten());
        var setup = await new GameSetupFactory(provider).CreateAsync(GameType.Trivia, GameRegion.Nigeria, null, CancellationToken.None);
        Assert.Equal(0, provider.Calls);
        Assert.Equal(GameRegion.Nigeria, GameRegion.Of(setup));
        var texts = Questions(setup);
        Assert.Equal(10, texts.Count);
        Assert.Equal(10, texts.Distinct().Count());
        Assert.All(texts, text => Assert.Contains(NaijaTrivia.Questions, item => item.Question == text));
        // The questions stay on the server: the app sees only the region.
        Assert.Equal("""{"region":"ng"}""", GameRegion.PublicSetup(setup));
    }

    [Fact]
    public async Task Global_couples_get_open_trivia_questions()
    {
        var provider = new FakeProvider(Ten());
        var setup = await new GameSetupFactory(provider).CreateAsync(GameType.Trivia, GameRegion.Global, null, CancellationToken.None);
        Assert.Equal(1, provider.Calls);
        Assert.Equal(Enumerable.Range(1, 10).Select(n => $"Question {n}?"), Questions(setup));

        var started = engine.Start(GameType.Trivia, Players, Now, setup);
        Assert.Equal("Question 1?", View(started.StateJson, ada).GetProperty("question").GetProperty("text").GetString());
    }

    [Fact]
    public async Task When_open_trivia_is_down_the_built_in_bank_fills_in()
    {
        var empty = await new GameSetupFactory(new FakeProvider([])).CreateAsync(GameType.Trivia, GameRegion.Global, null, CancellationToken.None);
        var fallback = Questions(empty);
        Assert.Equal(10, fallback.Count);
        Assert.All(fallback, text => Assert.Contains(GlobalTrivia.Questions, item => item.Question == text));

        var some = await new GameSetupFactory(new FakeProvider([Question(1), Question(2), Question(3)])).CreateAsync(GameType.Trivia, GameRegion.Global, null, CancellationToken.None);
        var topped = Questions(some);
        Assert.Equal(10, topped.Count);
        Assert.Equal(["Question 1?", "Question 2?", "Question 3?"], topped.Take(3));
        Assert.All(topped.Skip(3), text => Assert.Contains(GlobalTrivia.Questions, item => item.Question == text));
    }

    [Fact]
    public void A_game_with_no_questions_in_its_setup_draws_from_its_regions_bank()
    {
        var naija = View(engine.Start(GameType.Trivia, Players, Now, """{"region":"ng"}""").StateJson, ada);
        Assert.Contains(NaijaTrivia.Questions, item => item.Question == naija.GetProperty("question").GetProperty("text").GetString());
        Assert.Equal(10, naija.GetProperty("totalRounds").GetInt32());

        var global = View(engine.Start(GameType.Trivia, Players, Now, null).StateJson, ada);
        Assert.Contains(GlobalTrivia.Questions, item => item.Question == global.GetProperty("question").GetProperty("text").GetString());
    }

    [Fact]
    public void The_banks_are_big_enough_and_every_question_is_playable()
    {
        Assert.True(NaijaTrivia.Questions.Count >= 150, $"Naija trivia has {NaijaTrivia.Questions.Count} questions.");
        Assert.True(GlobalTrivia.Questions.Count >= 40, $"Global trivia has {GlobalTrivia.Questions.Count} questions.");
        foreach (var bank in new[] { NaijaTrivia.Questions, GlobalTrivia.Questions })
        {
            Assert.Equal(bank.Count, bank.Select(item => item.Question).Distinct(StringComparer.OrdinalIgnoreCase).Count());
            Assert.All(bank, item =>
            {
                var question = item.Shuffled(false);
                Assert.True(question.IsPlayable, item.Question);
                Assert.Equal(item.Answer, question.Options[question.Answer]);
            });
        }
    }

    [Fact]
    public async Task Open_trivia_answers_are_decoded_shuffled_and_keep_the_right_index()
    {
        const string payload = """
            {"response_code":0,"results":[
              {"type":"multiple","question":"Which planet is called the &quot;Red Planet&quot;?","correct_answer":"Mars","incorrect_answers":["Venus","Jupiter","Saturn &amp; Co"]},
              {"type":"multiple","question":"Broken","correct_answer":"Only one","incorrect_answers":["Two"]}
            ]}
            """;
        var questions = await new OpenTriviaQuestionProvider(new HttpClient(new Respond(HttpStatusCode.OK, payload))).GetTriviaAsync(10);
        var question = Assert.Single(questions);
        Assert.Equal("Which planet is called the \"Red Planet\"?", question.Text);
        Assert.Equal("Mars", question.Options[question.Answer]);
        Assert.Contains("Saturn & Co", question.Options);
        Assert.False(question.Translate);
    }

    [Theory]
    [InlineData(HttpStatusCode.ServiceUnavailable, "{}")]
    [InlineData(HttpStatusCode.OK, """{"response_code":5,"results":[]}""")]
    [InlineData(HttpStatusCode.OK, "not json")]
    public async Task Open_trivia_failures_give_no_questions(HttpStatusCode status, string body)
        => Assert.Empty(await new OpenTriviaQuestionProvider(new HttpClient(new Respond(status, body))).GetTriviaAsync(10));

    private static List<string> Questions(string setupJson)
    {
        using var setup = JsonDocument.Parse(setupJson);
        return setup.RootElement.GetProperty("trivia").EnumerateArray().Select(item => item.GetProperty("Text").GetString()!).ToList();
    }

    private sealed class FakeProvider(IReadOnlyList<TriviaQuestion> trivia) : IGameQuestionProvider
    {
        public int Calls { get; private set; }
        public Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<string>>([]);
        public Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult(trivia);
        }
    }

    private sealed class Respond(HttpStatusCode status, string body) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") });
    }
}
