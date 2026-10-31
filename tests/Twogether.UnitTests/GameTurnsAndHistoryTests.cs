using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Background;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Api.Presence;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// docs/games.md: who goes first alternates across a couple's games of a kind; Connect four starts its next game by
/// itself after a beat; Heart to heart reminds the partner once when one has played all five; and every game that ends
/// leaves exactly one history row, which the history reads back past the last 30 sessions.
/// </summary>
public sealed class GameTurnsAndHistoryTests : IDisposable
{
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);
    private readonly FakeHub hub = new();
    private readonly GameEngine engine = new();
    /// <summary>The coin for a couple's first game of a kind: 0 picks the inviter.</summary>
    private int coin;

    public void Dispose() => database.Dispose();

    private GameController Controller(ApplicationDbContext db, Guid userId, Guid coupleId, CouplePresenceTracker? presence = null)
        => new(db, new FakeCurrentUser(userId, coupleId), clock, engine, new NoWords(), new GameSetupFactory(new NoQuestions(), _ => coin), hub, presence: presence);

    private GameTimeoutWorker Worker()
    {
        var services = new ServiceCollection();
        services.AddScoped<IApplicationDbContext>(_ => database.CreateContext());
        return new GameTimeoutWorker(services.BuildServiceProvider().GetRequiredService<IServiceScopeFactory>(), hub, engine, clock, NullLogger<GameTimeoutWorker>.Instance);
    }

    private static GameSessionDto Ok(ActionResult<GameSessionDto> result) => Assert.IsType<GameSessionDto>(Assert.IsType<OkObjectResult>(result.Result).Value);

    private static string Code(ActionResult<GameSessionDto> result) => Assert.IsType<ApiError>(Assert.IsAssignableFrom<ObjectResult>(result.Result).Value).Code;

    /// <summary>An invite from <paramref name="inviter"/>, accepted by the partner, past its countdown.</summary>
    private async Task<GameSessionDto> PlayAsync(ApplicationDbContext db, TestCouple couple, GameType type, Guid inviter)
    {
        var partner = inviter == couple.MayaId ? couple.TheoId : couple.MayaId;
        var invite = Ok(await Controller(db, inviter, couple.CoupleId).Create(new CreateGameSessionRequest(type), default));
        var started = Ok(await Controller(db, partner, couple.CoupleId).Accept(invite.Id, default));
        clock.UtcNow = clock.UtcNow.AddSeconds(6);
        return started;
    }

    private async Task<GameSessionDto> ActAsync(ApplicationDbContext db, TestCouple couple, Guid actor, Guid id, string action, string? value, int? index, CouplePresenceTracker? presence = null)
    {
        var session = await db.GameSessions.AsNoTracking().SingleAsync(item => item.Id == id);
        return Ok(await Controller(db, actor, couple.CoupleId, presence).ApplyAction(id, new ApplyGameActionRequest(session.StateVersion, action, value, index), default));
    }

    // ---- Who goes first ----

    [Fact]
    public void The_first_player_is_the_other_one_from_last_time_and_a_coin_the_first_time()
    {
        Guid a = Guid.NewGuid(), b = Guid.NewGuid();
        Assert.Equal(b, GameFirstPlayer.Choose([a, b], a));
        Assert.Equal(a, GameFirstPlayer.Choose([a, b], b));
        Assert.Equal(a, GameFirstPlayer.Choose([a, b], null, _ => 0));
        Assert.Equal(b, GameFirstPlayer.Choose([a, b], null, _ => 1));
        // Someone who isn't one of the two (a partner from before) says nothing: the coin decides.
        Assert.Equal(b, GameFirstPlayer.Choose([a, b], Guid.NewGuid(), _ => 1));
    }

    [Fact]
    public async Task Turn_by_turn_setups_name_the_first_player_and_games_played_at_once_do_not()
    {
        Guid a = Guid.NewGuid(), b = Guid.NewGuid();
        var factory = new GameSetupFactory(new NoQuestions(), _ => 0);
        var context = new GameSetupContext(["en", "en"], [], [a, b], a);
        foreach (var type in GameFirstPlayer.Alternating)
            Assert.Equal(b, GameFirstPlayer.Of(await factory.CreateAsync(type, GameRegion.Global, null, default, context)));
        foreach (var type in new[] { GameType.CoupleQuiz, GameType.WouldYouRather, GameType.TicTacToe, GameType.SkillDuel, GameType.SpotTheDifference })
            Assert.Null(GameFirstPlayer.Of(await factory.CreateAsync(type, GameRegion.Global, null, default, context)));
        // Without the players (an older caller) the setup is as it was.
        Assert.Equal("""{"region":"global"}""", await factory.CreateAsync(GameType.ConnectFour, GameRegion.Global, null, default));
    }

    [Theory]
    [InlineData(GameType.MemoryMatch)]
    [InlineData(GameType.GuessMyAnswer)]
    [InlineData(GameType.ConnectFour)]
    [InlineData(GameType.TruthOrDare)]
    [InlineData(GameType.WordDash)]
    [InlineData(GameType.Handwriting)]
    [InlineData(GameType.SnakesAndLadders)]
    public async Task Every_turn_by_turn_game_starts_with_the_player_its_setup_names(GameType type)
    {
        Guid older = Guid.NewGuid(), newer = Guid.NewGuid();
        var factory = new GameSetupFactory(new NoQuestions());
        foreach (var first in new[] { older, newer })
        {
            var setup = await factory.CreateAsync(type, GameRegion.Global, null, default, new GameSetupContext(["en", "en"], [], [older, newer], first == older ? newer : older));
            Assert.Equal(first, GameFirstPlayer.Of(setup));
            // The players arrive oldest account first, as the server lists them.
            var start = engine.Start(type, [older, newer], TestCouple.Now, setup);
            Assert.Equal(first, start.CurrentTurnUserId);
            Assert.Equal(first, GameFirstPlayer.FirstOf(setup, start.StateJson));
        }
    }

    [Fact]
    public async Task Who_goes_first_alternates_across_a_couples_games_of_a_kind_whoever_invites()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        coin = 1; // Their first memory match: the coin picks Theo (the partner of Maya, who invites).

        var firsts = new List<Guid>();
        foreach (var inviter in new[] { couple.MayaId, couple.MayaId, couple.TheoId, couple.MayaId })
        {
            var game = await PlayAsync(db, couple, GameType.MemoryMatch, inviter);
            firsts.Add(game.CurrentTurnUserId!.Value);
            Ok(await Controller(db, inviter, couple.CoupleId).Abandon(game.Id, default));
            // An invite turned down in between was never played: it doesn't count as a turn to go first.
            var declined = Ok(await Controller(db, couple.TheoId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default));
            Ok(await Controller(db, couple.MayaId, couple.CoupleId).Abandon(declined.Id, default));
            // Nor does a game of another kind.
            var other = await PlayAsync(db, couple, GameType.GuessMyAnswer, couple.TheoId);
            Ok(await Controller(db, couple.TheoId, couple.CoupleId).Abandon(other.Id, default));
        }

        Assert.Equal([couple.TheoId, couple.MayaId, couple.TheoId, couple.MayaId], firsts);
    }

    [Fact]
    public async Task A_game_from_before_the_setup_named_a_first_player_still_counts()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        // An older server: no "first" in the setup, and Maya (the older account) first in the players, as always then.
        var old = GameSession.Create(couple.CoupleId, GameType.MemoryMatch, couple.TheoId, clock.UtcNow, """{"region":"global"}""");
        old.Start(couple.MayaId, null, engine.Start(GameType.MemoryMatch, [couple.MayaId, couple.TheoId], clock.UtcNow).StateJson, clock.UtcNow);
        old.Complete(old.StateJson, clock.UtcNow);
        db.GameSessions.Add(old);
        await db.SaveChangesAsync();
        clock.UtcNow = clock.UtcNow.AddMinutes(1);

        Assert.Equal(couple.MayaId, await GameFirstPlayer.PreviousAsync(db, couple.CoupleId, GameType.MemoryMatch, default));
        var next = await PlayAsync(db, couple, GameType.MemoryMatch, couple.MayaId);
        Assert.Equal(couple.TheoId, next.CurrentTurnUserId);
        // Snakes and ladders tossed a coin then: its state says who it picked.
        Assert.Equal(couple.TheoId, GameFirstPlayer.FirstOf("""{"region":"global"}""", $$"""{"Players":["{{couple.MayaId}}","{{couple.TheoId}}"],"First":"{{couple.TheoId}}"}"""));
    }

    // ---- Connect four: the next game starts by itself ----

    [Fact]
    public async Task Connect_four_shows_the_result_for_a_beat_then_the_worker_starts_the_next_game()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        coin = 0;
        var game = await PlayAsync(db, couple, GameType.ConnectFour, couple.MayaId);
        var starter = game.CurrentTurnUserId!.Value;
        var other = starter == couple.MayaId ? couple.TheoId : couple.MayaId;

        // Four down column 0 for the starter.
        foreach (var (who, column) in new[] { (starter, 0), (other, 1), (starter, 0), (other, 1), (starter, 0), (other, 1), (starter, 0) })
            await ActAsync(db, couple, who, game.Id, "drop", null, column);
        var over = await db.GameSessions.AsNoTracking().SingleAsync(item => item.Id == game.Id);
        Assert.Equal(GameSessionStatus.InProgress, over.Status);
        Assert.Equal(clock.UtcNow.AddMilliseconds(GridMatchRules.NextGameDelayMs), over.DeadlineUtc);
        Assert.Equal(other, over.CurrentTurnUserId);

        // Too soon for the next starter's disc: the result is still showing.
        var early = await Controller(db, other, couple.CoupleId).ApplyAction(game.Id, new ApplyGameActionRequest(over.StateVersion, "drop", null, 3), default);
        Assert.Equal("game.invalid_action", Code(early));

        // The beat passes: the worker deals a fresh board and tells both phones.
        clock.UtcNow = over.DeadlineUtc!.Value;
        await Worker().AdvanceExpiredRounds(default);
        await using var check = database.CreateContext();
        var fresh = await check.GameSessions.AsNoTracking().SingleAsync(item => item.Id == game.Id);
        var state = JsonDocument.Parse(engine.ToPublicState(GameType.ConnectFour, fresh.StateJson, other)).RootElement;
        Assert.Equal(("playing", 1), (state.GetProperty("phase").GetString(), state.GetProperty("game").GetInt32()));
        Assert.All(state.GetProperty("cells").EnumerateArray(), cell => Assert.Equal(JsonValueKind.Null, cell.ValueKind));
        Assert.Null(fresh.DeadlineUtc);
        Assert.Equal(other, fresh.CurrentTurnUserId);
        Assert.NotEmpty(hub.Payloads("GameStateChanged"));
        Ok(await Controller(check, other, couple.CoupleId).ApplyAction(game.Id, new ApplyGameActionRequest(fresh.StateVersion, "drop", null, 3), default));
    }

    // ---- Heart to heart: one reminder ----

    private async Task<GameSessionDto> DuelAsync(ApplicationDbContext db, TestCouple couple)
    {
        var game = await PlayAsync(db, couple, GameType.SkillDuel, couple.MayaId);
        await db.Notifications.ExecuteDeleteAsync();
        return game;
    }

    private async Task PlayAllAsync(ApplicationDbContext db, TestCouple couple, Guid player, Guid id, CouplePresenceTracker? presence = null)
    {
        for (var round = 0; round < SkillDuelRules.Rounds; round++)
        {
            await ActAsync(db, couple, player, id, SkillDuelRules.OpenAction, null, round, presence);
            await ActAsync(db, couple, player, id, "score", """{"score":60}""", round, presence);
        }
    }

    [Fact]
    public async Task Playing_all_five_first_reminds_a_partner_away_from_the_app_once()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var duel = await DuelAsync(db, couple);
        // Theo plays one round first: still no reminder, Maya has rounds to go.
        await ActAsync(db, couple, couple.TheoId, duel.Id, "score", """{"score":50}""", 0);

        await PlayAllAsync(db, couple, couple.MayaId, duel.Id);

        var reminder = await db.Notifications.SingleAsync();
        Assert.Equal((couple.TheoId, NotificationType.GameTurn), (reminder.UserId, reminder.Type));
        Assert.Equal("Your turn in Heart to heart", reminder.Title);
        Assert.Equal("Maya has played all five rounds. Play yours to see who wins!", reminder.Body);
        Assert.Contains(duel.Id.ToString(), reminder.DataJson);
        Assert.Contains(hub.Sent, item => item.Method == "NotificationCreated");

        // Theo finishing ends the duel: nothing more, to either of them.
        for (var round = 1; round < SkillDuelRules.Rounds; round++)
            await ActAsync(db, couple, couple.TheoId, duel.Id, "score", """{"score":50}""", round);
        Assert.Equal(1, await db.Notifications.CountAsync());
        Assert.Equal(GameSessionStatus.Completed, (await db.GameSessions.AsNoTracking().SingleAsync(item => item.Id == duel.Id)).Status);
    }

    [Fact]
    public async Task No_reminder_for_a_partner_in_the_app()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var duel = await DuelAsync(db, couple);
        var presence = new CouplePresenceTracker();
        presence.Connected(couple.CoupleId, couple.TheoId);

        await PlayAllAsync(db, couple, couple.MayaId, duel.Id, presence);

        Assert.Empty(await db.Notifications.ToListAsync());
    }

    [Fact]
    public async Task The_reminder_reads_in_the_partners_language()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        (await db.Users.SingleAsync(user => user.Id == couple.TheoId)).SetLanguage("fr", clock.UtcNow);
        await db.SaveChangesAsync();
        var duel = await DuelAsync(db, couple);

        await PlayAllAsync(db, couple, couple.MayaId, duel.Id);

        var reminder = await db.Notifications.SingleAsync();
        Assert.NotEqual("Your turn in Heart to heart", reminder.Title);
        Assert.Contains("Maya", reminder.Body);
        Assert.DoesNotContain("has played all five rounds", reminder.Body);
    }

    // ---- History ----

    [Fact]
    public async Task A_game_played_to_the_end_leaves_one_history_row_with_its_winner_and_scores()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var duel = await DuelAsync(db, couple);
        await PlayAllAsync(db, couple, couple.MayaId, duel.Id);
        for (var round = 0; round < SkillDuelRules.Rounds; round++)
            await ActAsync(db, couple, couple.TheoId, duel.Id, "score", """{"score":40}""", round);

        var row = await db.GameResults.SingleAsync();
        Assert.Equal((duel.Id, GameType.SkillDuel, GameOutcome.Completed, (Guid?)couple.MayaId), (row.GameSessionId, row.GameType, row.Outcome, row.WinnerUserId));
        Assert.Equal(5, row.RoundsPlayed);
        var scores = JsonSerializer.Deserialize<Dictionary<Guid, int>>(row.ScoresJson)!;
        Assert.Equal((5, 0), (scores[couple.MayaId], scores[couple.TheoId]));
        Assert.NotNull(row.StartedAtUtc);
    }

    [Fact]
    public async Task A_finished_game_changed_after_its_end_has_its_row_worked_out_again()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var duel = await DuelAsync(db, couple);
        await PlayAllAsync(db, couple, couple.MayaId, duel.Id);
        for (var round = 0; round < SkillDuelRules.Rounds; round++)
            await ActAsync(db, couple, couple.TheoId, duel.Id, "score", """{"score":40}""", round);
        var ended = (await db.GameResults.AsNoTracking().SingleAsync()).EndedAtUtc;

        // A move the rules allow after the end (Same answer's "That counts!", GameSession.Amend) changes the finished
        // game: here Theo's rounds turn out higher. The next save's history write works the row out again.
        var session = await db.GameSessions.SingleAsync(item => item.Id == duel.Id);
        var state = System.Text.Json.Nodes.JsonNode.Parse(session.StateJson)!.AsObject();
        state["Scores"]![couple.TheoId.ToString()] = new System.Text.Json.Nodes.JsonArray(90, 90, 90, 90, 90);
        await db.GameSessions.Where(item => item.Id == duel.Id).ExecuteUpdateAsync(set => set.SetProperty(item => item.StateJson, state.ToJsonString()));
        clock.UtcNow = clock.UtcNow.AddMinutes(2);
        Assert.True(await GameHistory.KeepAsync(db, engine, session, GameOutcome.Completed, clock.UtcNow, default));
        Assert.False(await GameHistory.KeepAsync(db, engine, session, GameOutcome.Completed, clock.UtcNow, default));

        await using var check = database.CreateContext();
        var row = await check.GameResults.SingleAsync();
        Assert.Equal((GameOutcome.Completed, (Guid?)couple.TheoId, ended), (row.Outcome, row.WinnerUserId, row.EndedAtUtc));
        var scores = JsonSerializer.Deserialize<Dictionary<Guid, int>>(row.ScoresJson)!;
        Assert.Equal((0, 5), (scores[couple.MayaId], scores[couple.TheoId]));
    }

    [Fact]
    public async Task A_game_ended_part_way_is_kept_without_a_winner_and_an_invite_leaves_nothing()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var invite = Ok(await Controller(db, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default));
        Ok(await Controller(db, couple.TheoId, couple.CoupleId).Abandon(invite.Id, default));
        Assert.Empty(await db.GameResults.ToListAsync());

        var duel = await DuelAsync(db, couple);
        await ActAsync(db, couple, couple.MayaId, duel.Id, "score", """{"score":90}""", 0);
        await ActAsync(db, couple, couple.TheoId, duel.Id, "score", """{"score":10}""", 0);
        Ok(await Controller(db, couple.TheoId, couple.CoupleId).Abandon(duel.Id, default));

        var row = await db.GameResults.SingleAsync();
        Assert.Equal((duel.Id, GameOutcome.Abandoned, (Guid?)null, 1), (row.GameSessionId, row.Outcome, row.WinnerUserId, row.RoundsPlayed));
        // Ending it again is refused, so it can't be written twice.
        Assert.Equal("game.already_ended", Code(await Controller(db, couple.MayaId, couple.CoupleId).Abandon(duel.Id, default)));
        Assert.Single(await db.GameResults.ToListAsync());
    }

    [Fact]
    public async Task A_game_left_alone_for_a_week_is_kept_as_timed_out_and_a_stale_invite_is_not()
    {
        Guid gameId;
        await using (var db = database.CreateContext())
        {
            var couple = await TestCouple.AddAsync(db);
            gameId = (await PlayAsync(db, couple, GameType.MemoryMatch, couple.MayaId)).Id;
            var other = await TestCouple.AddAsync(db);
            Ok(await Controller(db, other.MayaId, other.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default));
        }
        clock.UtcNow = clock.UtcNow.AddDays(8);

        await Worker().EndStaleGames(default);

        await using var check = database.CreateContext();
        var row = await check.GameResults.SingleAsync();
        Assert.Equal((gameId, GameOutcome.TimedOut, (Guid?)null), (row.GameSessionId, row.Outcome, row.WinnerUserId));
        Assert.Equal(2, await check.GameSessions.CountAsync(item => item.Status == GameSessionStatus.Abandoned));
    }

    [Fact]
    public async Task A_game_the_couple_ending_ends_is_kept()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var game = await PlayAsync(db, couple, GameType.MemoryMatch, couple.MayaId);
        var controller = new CoupleController(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, null!, hub, new Twogether.Application.Common.Security.AttemptLimiter(clock),
            null!, NullLogger<CoupleController>.Instance);

        await controller.BreakUp(default);

        var row = await db.GameResults.SingleAsync();
        Assert.Equal((game.Id, GameOutcome.Abandoned), (row.GameSessionId, row.Outcome));
    }

    [Fact]
    public async Task Keeping_a_game_twice_writes_one_row_and_an_open_game_none()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var game = await PlayAsync(db, couple, GameType.MemoryMatch, couple.MayaId);
        var session = await db.GameSessions.SingleAsync(item => item.Id == game.Id);
        Assert.False(await GameHistory.KeepAsync(db, engine, session, GameOutcome.Completed, clock.UtcNow, default));

        session.EndBy(couple.MayaId, clock.UtcNow);
        await db.SaveChangesAsync();
        Assert.True(await GameHistory.KeepAsync(db, engine, session, GameOutcome.Abandoned, clock.UtcNow, default));
        Assert.False(await GameHistory.KeepAsync(db, engine, session, GameOutcome.Abandoned, clock.UtcNow, default));
        await using var other = database.CreateContext();
        Assert.False(await GameHistory.KeepAsync(other, engine, await other.GameSessions.SingleAsync(item => item.Id == game.Id), GameOutcome.Abandoned, clock.UtcNow, default));
        Assert.Single(await db.GameResults.ToListAsync());
    }

    [Fact]
    public async Task The_history_reaches_past_the_last_thirty_sessions_and_takes_in_games_from_before()
    {
        TestCouple couple;
        Guid legacyId, playedId;
        await using (var db = database.CreateContext())
        {
            couple = await TestCouple.AddAsync(db);
            // A game finished on an older server, which wrote no history row.
            var legacy = GameSession.Create(couple.CoupleId, GameType.MemoryMatch, couple.MayaId, clock.UtcNow, """{"region":"global"}""");
            legacy.Start(couple.MayaId, null, engine.Start(GameType.MemoryMatch, [couple.MayaId, couple.TheoId], clock.UtcNow).StateJson, clock.UtcNow);
            legacy.Complete(legacy.StateJson, clock.UtcNow);
            db.GameSessions.Add(legacy);
            await db.SaveChangesAsync();
            legacyId = legacy.Id;
            clock.UtcNow = clock.UtcNow.AddMinutes(5);

            var duel = await DuelAsync(db, couple);
            await PlayAllAsync(db, couple, couple.MayaId, duel.Id);
            await PlayAllAsync(db, couple, couple.TheoId, duel.Id);
            playedId = duel.Id;
            // Then 31 invites nobody took up: the sessions list no longer reaches either game.
            for (var i = 0; i < 31; i++)
            {
                clock.UtcNow = clock.UtcNow.AddMinutes(1);
                var invite = Ok(await Controller(db, couple.TheoId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.Trivia), default));
                Ok(await Controller(db, couple.MayaId, couple.CoupleId).Abandon(invite.Id, default));
            }
            var sessions = Assert.IsAssignableFrom<IReadOnlyList<GameSessionDto>>(Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).List(default)).Result).Value);
            Assert.Equal(30, sessions.Count);
            Assert.DoesNotContain(sessions, item => item.Id == playedId || item.Id == legacyId);
        }

        await using var read = database.CreateContext();
        var history = new GameHistoryController(read, new FakeCurrentUser(couple.MayaId, couple.CoupleId), engine);
        var items = Assert.IsAssignableFrom<IReadOnlyList<GameHistoryItemDto>>(Assert.IsType<OkObjectResult>((await history.List(null, null, default)).Result).Value);

        Assert.Equal([playedId, legacyId], items.Select(item => item.SessionId));
        Assert.All(items, item => Assert.Equal(GameOutcome.Completed, item.Outcome));
        Assert.Equal(GameSessionStatus.Completed, items[0].Session!.Status);
        Assert.Equal(GameType.SkillDuel, items[0].Session!.GameType);
        Assert.Equal(2, await read.GameResults.CountAsync());

        // Reading again writes nothing more; a page before the newest gives the one from before.
        var again = Assert.IsAssignableFrom<IReadOnlyList<GameHistoryItemDto>>(Assert.IsType<OkObjectResult>((await history.List(1, items[0].EndedAtUtc, default)).Result).Value);
        Assert.Equal([legacyId], again.Select(item => item.SessionId));
        Assert.Equal(2, await read.GameResults.CountAsync());
    }

    private sealed class NoWords : IGameWordValidator
    {
        public Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(string stateJson, string answersJson, CancellationToken cancellationToken = default)
            => Task.FromResult<IReadOnlyDictionary<string, bool?>>(new Dictionary<string, bool?>());
    }

    private sealed class NoQuestions : IGameQuestionProvider
    {
        public Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<string>>(["What is our song?"]);
        public Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<TriviaQuestion>>([]);
    }
}
