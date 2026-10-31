using System.Net;
using System.Text;
using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Games;
using Twogether.Shared.Localization;

namespace Twogether.UnitTests;

/// <summary>
/// Same answer, Guess my answer, Would you rather and Trivia: the end-of-game recap, fresh content from game to game,
/// and Trivia in the couple's own language.
/// </summary>
public sealed class CoupleGamesRecapTests
{
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime now = DateTime.UtcNow;

    private JsonElement View(GameType type, string stateJson, Guid? viewer)
        => JsonDocument.Parse(engine.ToPublicState(type, stateJson, viewer)).RootElement.Clone();

    [Fact]
    public void Would_you_rather_keeps_every_round_for_the_recap()
    {
        var state = engine.Start(GameType.WouldYouRather, [maya, theo], now, WouldYouRatherRules.CreateSetup(GameRegion.Global));
        var asked = new List<string>();
        for (var round = 0; round < WouldYouRatherRules.Rounds; round++)
        {
            asked.Add(View(GameType.WouldYouRather, state.StateJson, maya).GetProperty("a").GetString()!);
            state = engine.Apply(GameType.WouldYouRather, state.StateJson, maya, "pick", null, 0, now);
            state = engine.Apply(GameType.WouldYouRather, state.StateJson, theo, "pick", null, round % 3 == 0 ? 1 : 0, now);
            Assert.Equal(round + 1, View(GameType.WouldYouRather, state.StateJson, theo).GetProperty("history").GetArrayLength());
        }

        Assert.True(state.Completed);
        var history = View(GameType.WouldYouRather, state.StateJson, theo).GetProperty("history").EnumerateArray().ToList();
        Assert.Equal(asked, history.Select(played => played.GetProperty("a").GetString()));
        Assert.Equal(Enumerable.Range(0, 10), history.Select(played => played.GetProperty("round").GetInt32()));
        Assert.Equal(1, history[0].GetProperty("picks").GetProperty(theo.ToString()).GetInt32());
        Assert.Equal(6, history.Count(played => played.GetProperty("match").GetBoolean()));
        Assert.Equal(6, engine.Summarise(GameType.WouldYouRather, state.StateJson).Scores[maya]);
    }

    [Fact]
    public void Guess_my_answer_keeps_every_round_and_never_shows_an_answer_before_its_guess()
    {
        var state = engine.Start(GameType.GuessMyAnswer, [maya, theo], now, GuessMyAnswerRules.CreateSetup(GameRegion.Global));
        for (var round = 0; round < GuessMyAnswerRules.Rounds; round++)
        {
            var subject = round % 2 == 0 ? maya : theo;
            var guesser = subject == maya ? theo : maya;
            state = engine.Apply(GameType.GuessMyAnswer, state.StateJson, subject, "answer", null, 2, now);
            // The answer is in, the guess isn't: the history holds only the rounds before, for both.
            Assert.Equal(round, View(GameType.GuessMyAnswer, state.StateJson, guesser).GetProperty("history").GetArrayLength());
            state = engine.Apply(GameType.GuessMyAnswer, state.StateJson, guesser, "guess", null, round < 5 ? 2 : 1, now);
        }

        Assert.True(state.Completed);
        var history = View(GameType.GuessMyAnswer, state.StateJson, maya).GetProperty("history").EnumerateArray().ToList();
        Assert.Equal(8, history.Count);
        Assert.Equal(5, history.Count(played => played.GetProperty("right").GetBoolean()));
        Assert.All(history, played => Assert.Equal(4, played.GetProperty("choices").GetArrayLength()));
        Assert.Equal(theo, history[1].GetProperty("subject").GetGuid());
    }

    [Fact]
    public void Trivia_keeps_every_question_for_the_recap_with_the_right_answer()
    {
        var setup = JsonSerializer.Serialize(new { region = "global", trivia = GlobalTrivia.Draw(TriviaRules.QuestionCount) });
        var state = engine.Start(GameType.Trivia, [maya, theo], now, setup);
        for (var round = 0; round < TriviaRules.QuestionCount; round++)
        {
            state = engine.Apply(GameType.Trivia, state.StateJson, theo, "answer", null, 0, now);
            // Theo is in, Maya isn't: nothing of this question is in anyone's history yet.
            Assert.Equal(round, View(GameType.Trivia, state.StateJson, maya).GetProperty("history").GetArrayLength());
            state = engine.Apply(GameType.Trivia, state.StateJson, maya, "answer", null, 1, now);
        }

        var history = View(GameType.Trivia, state.StateJson, maya).GetProperty("history").EnumerateArray().ToList();
        Assert.Equal(10, history.Count);
        var summary = engine.Summarise(GameType.Trivia, state.StateJson);
        Assert.Equal(summary.Scores[maya], history.Count(played => played.GetProperty("answer").GetInt32() == 1));
        Assert.All(history, played => Assert.Equal(4, played.GetProperty("question").GetProperty("options").GetArrayLength()));
    }

    [Fact]
    public void The_recap_reads_in_each_partners_language()
    {
        var setup = JsonSerializer.Serialize(new { region = "global", pairs = new[] { new { a = "Have a dog", b = "Have a cat" } } });
        var state = engine.Start(GameType.WouldYouRather, [maya, theo], now, setup);
        state = engine.Apply(GameType.WouldYouRather, state.StateJson, maya, "pick", null, 0, now);
        state = engine.Apply(GameType.WouldYouRather, state.StateJson, theo, "pick", null, 0, now);
        using (Loc.Use("fr"))
        {
            var played = View(GameType.WouldYouRather, state.StateJson, maya).GetProperty("history")[0];
            Assert.Equal(Loc.T("Have a dog"), played.GetProperty("a").GetString());
            Assert.NotEqual("Have a dog", played.GetProperty("a").GetString());
        }
    }

    [Fact]
    public void Old_states_without_a_history_still_read()
    {
        var old = JsonSerializer.Serialize(new
        {
            Players = new[] { maya, theo },
            Scores = new Dictionary<Guid, int> { [maya] = 1, [theo] = 1 },
            Region = "global",
            Deck = new[] { new { A = "Have a dog", B = "Have a cat" }, new { A = "Ice cream", B = "Cake" } },
            Round = 1,
            Phase = "picking",
            Picks = new Dictionary<Guid, int>(),
            Last = new { Round = 0, A = "Have a dog", B = "Have a cat", Picks = new Dictionary<Guid, int> { [maya] = 0, [theo] = 0 }, Match = true },
        });
        Assert.Equal(0, View(GameType.WouldYouRather, old, maya).GetProperty("history").GetArrayLength());
        var next = engine.Apply(GameType.WouldYouRather, old, maya, "pick", null, 1, now);
        next = engine.Apply(GameType.WouldYouRather, next.StateJson, theo, "pick", null, 1, now);
        Assert.True(next.Completed);
        Assert.Equal(1, View(GameType.WouldYouRather, next.StateJson, maya).GetProperty("history").GetArrayLength());
    }

    [Fact]
    public void A_new_game_avoids_what_the_couple_just_played()
    {
        var first = WouldYouRatherRules.CreateSetup(GameRegion.Nigeria);
        var recent = new[] { WouldYouRatherRules.CreateSetup(GameRegion.Nigeria, [first]), first };
        var seen = FreshPick.Seen(recent, "pairs", "a");
        Assert.Equal(20, seen.Count);
        var fresh = JsonDocument.Parse(WouldYouRatherRules.CreateSetup(GameRegion.Nigeria, recent)).RootElement.GetProperty("pairs").EnumerateArray().Select(pair => pair.GetProperty("a").GetString()!).ToList();
        Assert.DoesNotContain(fresh, seen.Contains);

        var earlier = GuessMyAnswerRules.CreateSetup(GameRegion.Global);
        var guess = new[] { GuessMyAnswerRules.CreateSetup(GameRegion.Global, [earlier]), earlier };
        var guessed = FreshPick.Seen(guess, "questions", "text");
        Assert.Equal(16, guessed.Count);
        var next = JsonDocument.Parse(GuessMyAnswerRules.CreateSetup(GameRegion.Global, guess)).RootElement.GetProperty("questions").EnumerateArray().Select(question => question.GetProperty("text").GetString()!).ToList();
        Assert.Equal(8, next.Distinct().Count());
        Assert.DoesNotContain(next, guessed.Contains);

        // Junk in an old setup is skipped, not fatal.
        Assert.Empty(FreshPick.Seen(["not json", "{}", """{"pairs":"no"}""", ""], "pairs", "a"));
    }

    [Fact]
    public void The_banks_hold_no_question_twice()
    {
        foreach (var bank in new[] { GuessMyAnswerBank.Global, GuessMyAnswerBank.Nigerian })
        {
            Assert.Equal(bank.Count, bank.Select(question => question.Text).Distinct().Count());
            Assert.All(bank, question => Assert.Equal(4, question.Choices.Distinct().Count()));
        }
        foreach (var bank in new[] { WouldYouRatherBank.Global, WouldYouRatherBank.Nigerian })
            Assert.Equal(bank.Count, bank.Select(pair => pair.A).Distinct().Count());
        Assert.True(GlobalTrivia.Questions.Count >= 75);
    }

    [Fact]
    public async Task A_couple_where_either_reads_another_language_gets_trivia_they_can_read()
    {
        var provider = new FakeProvider();
        var setup = await new GameSetupFactory(provider).CreateAsync(GameType.Trivia, GameRegion.Global, null, CancellationToken.None, new GameSetupContext(["en", "fr"], []));
        Assert.Equal(0, provider.Calls);
        var texts = Texts(setup);
        Assert.Equal(10, texts.Distinct().Count());
        Assert.All(texts, text => Assert.Contains(GlobalTrivia.Questions, item => item.Question == text));
        using var document = JsonDocument.Parse(setup);
        Assert.All(document.RootElement.GetProperty("trivia").EnumerateArray(), item => Assert.True(item.GetProperty("Translate").GetBoolean()));

        await new GameSetupFactory(provider).CreateAsync(GameType.Trivia, GameRegion.Global, null, CancellationToken.None, new GameSetupContext(["en", "en"], []));
        Assert.Equal(1, provider.Calls);
    }

    [Fact]
    public async Task Trivia_skips_questions_from_the_couples_last_games()
    {
        var recent = new List<string>();
        for (var i = 0; i < 3; i++)
            recent.Add(await new GameSetupFactory(new FakeProvider()).CreateAsync(GameType.Trivia, GameRegion.Global, null, CancellationToken.None, new GameSetupContext(["hi"], recent.ToList())));
        var seen = recent.SelectMany(Texts).ToHashSet();
        Assert.Equal(30, seen.Count);
        var next = Texts(await new GameSetupFactory(new FakeProvider()).CreateAsync(GameType.Trivia, GameRegion.Global, null, CancellationToken.None, new GameSetupContext(["hi"], recent)));
        Assert.DoesNotContain(next, seen.Contains);

        var naija = Texts(await new GameSetupFactory(new FakeProvider()).CreateAsync(GameType.Trivia, GameRegion.Nigeria, null, CancellationToken.None, new GameSetupContext(["en"], [])));
        var again = Texts(await new GameSetupFactory(new FakeProvider()).CreateAsync(GameType.Trivia, GameRegion.Nigeria, null, CancellationToken.None,
            new GameSetupContext(["en"], [JsonSerializer.Serialize(new { region = "ng", trivia = naija.Select(text => new { Text = text }) })])));
        Assert.DoesNotContain(again, naija.Contains);
    }

    [Fact]
    public async Task Open_trivia_leaves_out_hard_questions_and_asks_for_spares()
    {
        const string payload = """
            {"response_code":0,"results":[
              {"type":"multiple","difficulty":"hard","question":"Obscure?","correct_answer":"A","incorrect_answers":["B","C","D"]},
              {"type":"multiple","difficulty":"easy","question":"Easy?","correct_answer":"A","incorrect_answers":["B","C","D"]},
              {"type":"multiple","difficulty":"medium","question":"Medium?","correct_answer":"A","incorrect_answers":["B","C","D"]}
            ]}
            """;
        var handler = new Respond(payload);
        var questions = await new OpenTriviaQuestionProvider(new HttpClient(handler)).GetTriviaAsync(10);
        Assert.Equal(["Easy?", "Medium?"], questions.Select(question => question.Text));
        Assert.Contains("amount=20", handler.Asked!.Query, StringComparison.Ordinal);
    }

    private static List<string> Texts(string setupJson)
        => JsonDocument.Parse(setupJson).RootElement.GetProperty("trivia").EnumerateArray().Select(item => item.GetProperty("Text").GetString()!).ToList();

    private sealed class FakeProvider : IGameQuestionProvider
    {
        public int Calls { get; private set; }
        public Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<string>>([]);
        public Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult<IReadOnlyList<TriviaQuestion>>([]);
        }
    }

    private sealed class Respond(string body) : HttpMessageHandler
    {
        public Uri? Asked { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Asked = request.RequestUri;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") });
        }
    }
}
