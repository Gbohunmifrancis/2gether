using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// Same answer's "That counts!": either partner may count a revealed round that didn't match ("pizza", "pepperoni
/// pizza") as a match, once, for a heart each, during the game or while the finished game is still on screen.
/// </summary>
public sealed class SameAnswerCountsTests : IDisposable
{
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeHub hub = new();

    public void Dispose() => database.Dispose();

    private static string Setup(int rounds = 3)
        => JsonSerializer.Serialize(new { region = "global", prompts = Enumerable.Range(1, rounds).Select(i => $"What's our favourite food {i}?").ToArray() });

    private GameEngineResult Start(Guid first, Guid second, int rounds = 3) => engine.Start(GameType.CoupleQuiz, [first, second], now, Setup(rounds));

    private GameEngineResult Act(GameEngineResult from, Guid actor, string action, string? value = null, int? index = null)
        => engine.Apply(GameType.CoupleQuiz, from.StateJson, actor, action, value, index, now);

    private GameEngineResult Round(GameEngineResult from, string mine, string theirs)
        => Act(Act(from, maya, "answer", mine), theo, "answer", theirs);

    private JsonElement View(GameEngineResult result, Guid viewer)
        => JsonDocument.Parse(engine.ToPublicState(GameType.CoupleQuiz, result.StateJson, viewer)).RootElement;

    private static int Score(JsonElement view, Guid player) => view.GetProperty("scores").GetProperty(player.ToString()).GetInt32();

    [Fact]
    public void Counting_a_mismatch_gives_both_a_heart_and_shows_it_to_both_as_counted()
    {
        var revealed = Round(Start(maya, theo), "pizza", "pepperoni pizza");
        Assert.False(View(revealed, maya).GetProperty("last").GetProperty("match").GetBoolean());

        // Theo counts it, mid-game, while the next question waits: nothing else moves.
        var counted = Act(revealed, theo, "counts", index: 0);
        Assert.False(counted.Completed);
        Assert.Equal(maya, counted.CurrentTurnUserId);
        foreach (var viewer in new[] { maya, theo })
        {
            var view = View(counted, viewer);
            var last = view.GetProperty("last");
            Assert.True(last.GetProperty("match").GetBoolean());
            Assert.True(last.GetProperty("accepted").GetBoolean());
            Assert.Equal(theo, last.GetProperty("acceptedBy").GetGuid());
            Assert.True(view.GetProperty("history")[0].GetProperty("accepted").GetBoolean());
            Assert.Equal(1, Score(view, maya));
            Assert.Equal(1, Score(view, theo));
            Assert.Equal(1, view.GetProperty("round").GetInt32());
        }
        Assert.Equal(1, engine.Summarise(GameType.CoupleQuiz, counted.StateJson).Scores[maya]);
    }

    [Fact]
    public void A_round_counts_once_so_two_taps_that_cross_make_one_heart_each()
    {
        var revealed = Round(Start(maya, theo), "the beach", "seaside");
        var once = Act(revealed, maya, "counts", index: 0);
        var twice = Act(once, theo, "counts", index: 0);
        var view = View(twice, maya);
        Assert.Equal(1, Score(view, maya));
        Assert.Equal(1, Score(view, theo));
        // The first to count it stays the one who did.
        Assert.Equal(maya, view.GetProperty("last").GetProperty("acceptedBy").GetGuid());
    }

    [Fact]
    public void Only_a_revealed_round_that_did_not_match_can_be_counted()
    {
        var started = Start(maya, theo);
        // Nothing revealed yet, and Maya's answer alone is no round.
        Assert.Throws<InvalidOperationException>(() => Act(started, maya, "counts", index: 0));
        var half = Act(started, maya, "answer", "pizza");
        Assert.Throws<InvalidOperationException>(() => Act(half, theo, "counts", index: 0));

        var matched = Round(started, "Pizza", "pizza!");
        Assert.Throws<InvalidOperationException>(() => Act(matched, maya, "counts", index: 0));
        var both = Round(matched, "pasta", "noodles");
        Assert.Throws<InvalidOperationException>(() => Act(both, maya, "counts", index: 5));
        Assert.Throws<InvalidOperationException>(() => Act(both, maya, "counts"));
        Assert.Throws<InvalidOperationException>(() => Act(both, Guid.NewGuid(), "counts", index: 1));
        // An earlier round still counts while the game goes on.
        var third = Round(both, "tacos", "burritos");
        Assert.Equal(2, Score(View(Act(third, maya, "counts", index: 1), maya), maya));
    }

    [Fact]
    public void The_last_round_can_be_counted_once_the_game_is_over()
    {
        var state = Round(Round(Start(maya, theo, 2), "Paris", "paris"), "pizza", "pepperoni pizza");
        Assert.True(state.Completed);
        var counted = Act(state, maya, "counts", index: 1);
        Assert.True(counted.Completed);
        Assert.Null(counted.CurrentTurnUserId);
        Assert.Equal(2, engine.Summarise(GameType.CoupleQuiz, counted.StateJson).Scores[theo]);
        Assert.True(((IGameEngine)engine).PlayableAfterEnd(GameType.CoupleQuiz, "counts"));
        Assert.False(((IGameEngine)engine).PlayableAfterEnd(GameType.CoupleQuiz, "answer"));
        Assert.False(((IGameEngine)engine).PlayableAfterEnd(GameType.Trivia, "counts"));
    }

    [Fact]
    public void A_round_kept_before_counting_existed_reads_as_not_counted_and_can_be_counted()
    {
        var old = JsonSerializer.Serialize(new
        {
            Players = new[] { maya, theo },
            Scores = new Dictionary<Guid, int> { [maya] = 0, [theo] = 0 },
            Round = 1,
            Prompt = "Q2",
            Questions = new[] { "Q1", "Q2" },
            Region = "global",
            History = new[] { new { Round = 0, Prompt = "Q1", Answers = new Dictionary<Guid, string> { [maya] = "a", [theo] = "b" }, Match = false } },
        });
        var view = JsonDocument.Parse(engine.ToPublicState(GameType.CoupleQuiz, old, maya)).RootElement.GetProperty("last");
        Assert.False(view.GetProperty("accepted").GetBoolean());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("acceptedBy").ValueKind);
        var counted = engine.Apply(GameType.CoupleQuiz, old, theo, "counts", null, 0, now);
        Assert.Equal(1, engine.Summarise(GameType.CoupleQuiz, counted.StateJson).Scores[maya]);
    }

    private async Task<(TestCouple Couple, GameSession Session)> FinishedGameAsync(DateTime endedAt)
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var session = GameSession.Create(couple.CoupleId, GameType.CoupleQuiz, couple.MayaId, endedAt.AddMinutes(-10), Setup(1));
        session.AcceptInvite(endedAt.AddMinutes(-9), endedAt.AddMinutes(-9));
        var start = engine.Start(GameType.CoupleQuiz, [couple.MayaId, couple.TheoId], endedAt.AddMinutes(-9), session.SetupJson);
        session.Start(start.CurrentTurnUserId!.Value, null, start.StateJson, endedAt.AddMinutes(-9));
        var one = engine.Apply(GameType.CoupleQuiz, start.StateJson, couple.MayaId, "answer", "pizza", null, endedAt);
        var done = engine.Apply(GameType.CoupleQuiz, one.StateJson, couple.TheoId, "answer", "pepperoni pizza", null, endedAt);
        session.Complete(done.StateJson, endedAt);
        db.GameSessions.Add(session);
        await db.SaveChangesAsync();
        return (couple, session);
    }

    private GameController Controller(Twogether.Infrastructure.Persistence.ApplicationDbContext db, TestCouple couple, Guid user, DateTime at)
        => new(db, new FakeCurrentUser(user, couple.CoupleId), new FakeClock(at), engine, new NoWords(), null!, hub);

    [Fact]
    public async Task A_count_after_the_end_updates_the_game_history()
    {
        var (couple, session) = await FinishedGameAsync(now);
        await using (var db = database.CreateContext())
        {
            // The history row the game's ending wrote: no match, no points yet.
            var tracked = await db.GameSessions.SingleAsync(item => item.Id == session.Id);
            Assert.True(await GameHistory.KeepAsync(db, engine, tracked, GameOutcome.Completed, now, default));
            var before = await db.GameResults.AsNoTracking().SingleAsync();
            Assert.Equal(0, JsonSerializer.Deserialize<Dictionary<Guid, int>>(before.ScoresJson)![couple.MayaId]);
        }

        await using (var db = database.CreateContext())
            Assert.IsType<OkObjectResult>((await Controller(db, couple, couple.TheoId, now.AddMinutes(5))
                .ApplyAction(session.Id, new ApplyGameActionRequest(session.StateVersion, "counts", null, 0), default)).Result);

        await using var check = database.CreateContext();
        var row = await check.GameResults.AsNoTracking().SingleAsync();
        var scores = JsonSerializer.Deserialize<Dictionary<Guid, int>>(row.ScoresJson)!;
        Assert.Equal((1, 1), (scores[couple.MayaId], scores[couple.TheoId]));
        Assert.Equal(now, row.EndedAtUtc);
    }

    [Fact]
    public async Task A_finished_game_takes_a_count_while_it_is_on_screen_and_tells_both()
    {
        var (couple, session) = await FinishedGameAsync(now);
        await using var db = database.CreateContext();
        var result = await Controller(db, couple, couple.TheoId, now.AddMinutes(5))
            .ApplyAction(session.Id, new ApplyGameActionRequest(session.StateVersion, "counts", null, 0), default);

        var dto = Assert.IsType<GameSessionDto>(Assert.IsType<OkObjectResult>(result.Result).Value);
        Assert.Equal(GameSessionStatus.Completed, dto.Status);
        Assert.Equal(session.StateVersion + 1, dto.StateVersion);
        Assert.Equal(now, dto.EndedAtUtc);
        var view = JsonDocument.Parse(dto.StateJson).RootElement;
        Assert.Equal(1, Score(view, couple.MayaId));
        Assert.True(view.GetProperty("last").GetProperty("accepted").GetBoolean());
        Assert.Equal(2, hub.Payloads("GameCompleted").Count());
    }

    [Fact]
    public async Task A_finished_game_takes_no_count_once_it_has_left_the_screen()
    {
        var (couple, session) = await FinishedGameAsync(now);
        await using (var db = database.CreateContext())
        {
            var late = await Controller(db, couple, couple.MayaId, now.Add(GameController.FinishedGameAmendWindow).AddMinutes(1))
                .ApplyAction(session.Id, new ApplyGameActionRequest(session.StateVersion, "counts", null, 0), default);
            Assert.Equal("game.not_in_progress", Assert.IsType<ApiError>(Assert.IsType<ConflictObjectResult>(late.Result).Value).Code);
            // Any other move on a finished game is refused as before.
            var answer = await Controller(db, couple, couple.MayaId, now.AddMinutes(1))
                .ApplyAction(session.Id, new ApplyGameActionRequest(session.StateVersion, "answer", "pizza", null), default);
            Assert.IsType<ConflictObjectResult>(answer.Result);
        }

        // The next game invited: the finished one is off screen, so it stays as it was.
        await using (var db = database.CreateContext())
        {
            db.GameSessions.Add(GameSession.Create(couple.CoupleId, GameType.Trivia, couple.TheoId, now.AddMinutes(2)));
            await db.SaveChangesAsync();
            var after = await Controller(db, couple, couple.MayaId, now.AddMinutes(3))
                .ApplyAction(session.Id, new ApplyGameActionRequest(session.StateVersion, "counts", null, 0), default);
            Assert.IsType<ConflictObjectResult>(after.Result);
        }
    }

    private sealed class NoWords : IGameWordValidator
    {
        public Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(string stateJson, string answersJson, CancellationToken cancellationToken = default)
            => Task.FromResult<IReadOnlyDictionary<string, bool?>>(new Dictionary<string, bool?>());
    }
}
