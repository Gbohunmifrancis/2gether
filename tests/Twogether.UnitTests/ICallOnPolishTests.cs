using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using Twogether.Api.Controllers;
using Twogether.Api.Presence;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Games;
using Twogether.Shared.Localization;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// I call on, end to end: answers typed but not sent still count when the clock runs out, a submit that arrives just
/// too late isn't thrown away, accents don't cost a point, and every refusal reads in the player's language.
/// </summary>
public sealed class ICallOnPolishTests : IDisposable
{
    private static readonly DateTime Now = new(2026, 10, 24, 18, 0, 0, DateTimeKind.Utc);
    private readonly GameEngine engine = new();
    private readonly Guid ada = Guid.NewGuid();
    private readonly Guid tunde = Guid.NewGuid();
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now.AddMinutes(10));
    private readonly FakeHub hub = new();

    public void Dispose() => database.Dispose();

    private string Start(string letter = "Z") => engine.Start(GameType.ICallOn, [ada, tunde], Now, JsonSerializer.Serialize(new { region = "global", letter })).StateJson;

    private static string Answers(string name, string place, string thing, string food)
        => JsonSerializer.Serialize(new Dictionary<string, string> { ["Name"] = name, ["Place"] = place, ["Thing"] = thing, ["Food"] = food });

    private JsonElement View(string state) => JsonDocument.Parse(engine.ToPublicState(GameType.ICallOn, state, ada)).RootElement;

    private static IEnumerable<JsonElement> Entries(JsonElement view, Guid player, string category)
        => view.GetProperty("reveal").GetProperty("entries").EnumerateArray()
            .Where(entry => entry.GetProperty("player").GetGuid() == player && entry.GetProperty("category").GetString() == category);

    [Fact]
    public void Accents_and_case_neither_lose_the_letter_nor_split_a_shared_answer()
    {
        var state = engine.Apply(GameType.ICallOn, Start(), ada, "submit", Answers("Zara", "Zürich", "Zipper", "Zucchini"), null, Now).StateJson;
        var scored = engine.Apply(GameType.ICallOn, state, tunde, "submit", Answers("Zara", " zurich ", "Zipper", "Zucchini"), null, Now);
        var view = View(scored.StateJson);
        // Zürich is on the list as "zurich": no judging needed, and the two answers are the same one, a point each.
        Assert.Equal(0, view.GetProperty("pendingVetting").GetArrayLength());
        Assert.Equal(1, Assert.Single(Entries(view, ada, "Place")).GetProperty("points").GetInt32());
        Assert.Equal(1, Assert.Single(Entries(view, tunde, "Place")).GetProperty("points").GetInt32());
    }

    [Fact]
    public void An_accented_capital_still_starts_with_the_letter()
    {
        var state = engine.Start(GameType.ICallOn, [ada, tunde], Now, """{"region":"global","letter":"E"}""").StateJson;
        state = engine.Apply(GameType.ICallOn, state, ada, "submit", Answers("Éric", "Édimbourg", "Écharpe", "Éclair"), null, Now).StateJson;
        state = engine.Apply(GameType.ICallOn, state, tunde, "submit", Answers("Emma", "Edinburgh", "Envelope", "Eggs"), null, Now).StateJson;
        // Not refused for the letter: unknown words go to the partner to judge instead.
        var pending = View(state).GetProperty("pendingVetting").EnumerateArray().Where(item => item.GetProperty("player").GetGuid() == ada).ToList();
        Assert.Equal(4, pending.Count);
    }

    [Fact]
    public void Answers_typed_but_not_sent_count_when_the_clock_runs_out()
    {
        var live = new GameLiveState();
        var session = Playing(Start());
        live.RecordDraft(session.Id, ada, 0, """{"Name":"Zara","Place":"","Thing":"Zipper","Food":""}""");
        live.RecordDraft(session.Id, tunde, 1, """{"Name":"Zane","Place":"Zurich","Thing":"Zip","Food":"Zucchini"}"""); // another round's: ignored

        var drafts = GameController.LateAnswers(live, session, Guid.Empty, null, null);
        var next = engine.Apply(GameType.ICallOn, session.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, Now.AddSeconds(16), coSubmissions: drafts);

        var view = View(next.StateJson);
        Assert.Equal(2, Assert.Single(Entries(view, ada, "Name")).GetProperty("points").GetInt32());
        Assert.Equal(2, Assert.Single(Entries(view, ada, "Thing")).GetProperty("points").GetInt32());
        Assert.Equal(0, Assert.Single(Entries(view, tunde, "Name")).GetProperty("points").GetInt32());
    }

    [Fact]
    public void A_broken_draft_never_stops_the_round_from_closing()
    {
        var session = Playing(Start());
        var drafts = new Dictionary<Guid, CoSubmission> { [ada] = new("null"), [tunde] = new("{not json") };
        var next = engine.Apply(GameType.ICallOn, session.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, Now.AddSeconds(16), coSubmissions: drafts);
        Assert.Equal(1, View(next.StateJson).GetProperty("round").GetInt32());
    }

    [Fact]
    public void Drafts_are_only_read_while_answers_are_being_typed()
    {
        var live = new GameLiveState();
        var session = Playing(Start());
        Assert.Null(GameController.LateAnswers(live, session, ada, "vet", "{}"));
        var other = GameSession.Create(Guid.NewGuid(), GameType.WordDash, ada, Now, "{}");
        Assert.Null(GameController.LateAnswers(live, other, ada, "submit", "{}"));
    }

    [Fact]
    public async Task A_submit_that_arrives_just_too_late_still_counts()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var session = GameSession.Create(couple.CoupleId, GameType.ICallOn, couple.MayaId, clock.UtcNow.AddMinutes(-1), """{"region":"global","letter":"Z"}""");
        var countdownEnds = clock.UtcNow.AddSeconds(-20);
        session.AcceptInvite(countdownEnds, clock.UtcNow.AddSeconds(-25));
        var start = engine.Start(GameType.ICallOn, [couple.MayaId, couple.TheoId], countdownEnds, session.SetupJson);
        session.Start(start.CurrentTurnUserId!.Value, start.DeadlineUtc, start.StateJson, clock.UtcNow.AddSeconds(-25));
        db.GameSessions.Add(session);
        await db.SaveChangesAsync();

        var controller = new GameController(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, engine, new NoWords(), null!, hub, new GameLiveState());
        var result = await controller.ApplyAction(session.Id, new ApplyGameActionRequest(session.StateVersion, "submit", Answers("Zara", "Zurich", "Zipper", "Zucchini"), null), default);

        var dto = Assert.IsType<GameSessionDto>(Assert.IsType<OkObjectResult>(result.Result).Value);
        var view = JsonDocument.Parse(dto.StateJson).RootElement;
        Assert.Equal(1, view.GetProperty("round").GetInt32());
        Assert.Equal(8, view.GetProperty("scores").GetProperty(couple.MayaId.ToString()).GetInt32());
    }

    [Fact]
    public void Later_rounds_draw_a_letter_not_played_yet()
    {
        for (var game = 0; game < 20; game++)
        {
            var state = Start("Z");
            var letters = new List<string>();
            for (var round = 0; round < 3; round++)
            {
                letters.Add(View(state).GetProperty("letter").GetString()!);
                state = engine.Apply(GameType.ICallOn, state, Guid.Empty, GameEngine.TimeoutAction, null, null, Now).StateJson;
            }
            Assert.Equal(3, letters.Distinct().Count());
        }
    }

    [Fact]
    public void Old_games_without_played_letters_carry_on()
    {
        var state = System.Text.Json.Nodes.JsonNode.Parse(Start())!.AsObject();
        state.Remove("UsedLetters");
        var next = engine.Apply(GameType.ICallOn, state.ToJsonString(), Guid.Empty, GameEngine.TimeoutAction, null, null, Now);
        Assert.Equal(1, View(next.StateJson).GetProperty("round").GetInt32());
    }

    [Fact]
    public void Refusals_read_in_the_players_language()
    {
        using (Loc.Use("fr"))
        {
            var error = Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ICallOn, Start(), ada, "vet", """{"player":"x","category":"Name","allow":true}""", null, Now));
            Assert.NotEqual("There is nothing waiting for a verdict.", error.Message);
            var incomplete = Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ICallOn, Start(), ada, "submit", """{"Name":"Zara"}""", null, Now));
            Assert.NotEqual("Fill in every category first.", incomplete.Message);
        }
    }

    [Fact]
    public void A_garbled_verdict_or_answer_is_refused_kindly_not_as_a_crash()
    {
        var state = engine.Apply(GameType.ICallOn, Start(), ada, "submit", Answers("Zed", "Zanzibar", "Zither", "Zobo"), null, Now).StateJson;
        state = engine.Apply(GameType.ICallOn, state, tunde, "submit", Answers("Zainab", "Zaria", "Zoo", "Zest"), null, Now).StateJson;
        Assert.Equal("vetting", View(state).GetProperty("phase").GetString());
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ICallOn, state, ada, "vet", "[1,2]", null, Now));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ICallOn, Start(), ada, "submit", "\"just text\"", null, Now));
    }

    [Fact]
    public void A_long_or_multi_line_answer_is_kept_short_and_on_one_line()
    {
        var state = engine.Apply(GameType.ICallOn, Start(), ada, "submit", Answers("Zara\n\nZoe", new string('z', 300), "Zip", "Zest"), null, Now).StateJson;
        state = engine.Apply(GameType.ICallOn, state, tunde, "submit", Answers("Zed", "Zaria", "Zoo", "Zobo"), null, Now).StateJson;
        var answers = View(state).GetProperty("pendingVetting").EnumerateArray().Concat(View(state).TryGetProperty("reveal", out var reveal) && reveal.ValueKind == JsonValueKind.Object
            ? reveal.GetProperty("entries").EnumerateArray() : []).Select(item => item.GetProperty("answer").GetString()!).ToList();
        Assert.Contains("Zara Zoe", answers);
        Assert.All(answers, answer => Assert.True(answer.Length <= GameEngine.MaxAnswerLength));
    }

    [Theory]
    [InlineData("Food", """[{"meanings":[{"definitions":[{"definition":"A tropical fruit with sweet yellow flesh."}]}]}]""", true)]
    [InlineData("Food", """[{"meanings":[{"definitions":[{"definition":"A vehicle with two wheels."}]}]}]""", null)]
    [InlineData("Animal", """[{"meanings":[{"definitions":[{"definition":"A large African mammal with a long neck."}]}]}]""", true)]
    [InlineData("Animal", """[{"meanings":[{"definitions":[{"definition":"A device for holding papers together."}]}]}]""", null)]
    [InlineData("Thing", """[{"meanings":[{"definitions":[{"definition":"A device for holding papers together."}]}]}]""", true)]
    [InlineData("Food", "not json", null)]
    public void A_real_word_counts_as_a_food_or_an_animal_only_when_the_dictionary_says_it_is_one(string category, string entry, bool? expected)
        => Assert.Equal(expected, PublicGameWordValidator.Fits(category, entry));

    private GameSession Playing(string state)
    {
        var session = GameSession.Create(Guid.NewGuid(), GameType.ICallOn, ada, Now.AddMinutes(-1), """{"region":"global"}""");
        session.AcceptInvite(Now, Now.AddSeconds(-5));
        session.Start(ada, Now.AddSeconds(15), state, Now);
        return session;
    }

    private sealed class NoWords : IGameWordValidator
    {
        public Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(string stateJson, string answersJson, CancellationToken cancellationToken = default)
            => Task.FromResult<IReadOnlyDictionary<string, bool?>>(new Dictionary<string, bool?>());
    }
}
