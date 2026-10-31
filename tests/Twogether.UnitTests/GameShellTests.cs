using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Background;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Api.Presence;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// The shell every couple game shares (docs/games.md): one open game per couple, invites taken back or declined,
/// accepts and ends that cross a move, the "your turn" nudge, and the timeout worker's saves.
/// </summary>
public sealed class GameShellTests : IDisposable
{
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);
    private readonly FakeHub hub = new();
    private readonly GameEngine engine = new();

    public void Dispose() => database.Dispose();

    private GameController Controller(ApplicationDbContext db, Guid userId, Guid coupleId, CouplePresenceTracker? presence = null)
        => new(db, new FakeCurrentUser(userId, coupleId), clock, engine, new NoWords(), new GameSetupFactory(new NoQuestions()), hub, presence: presence);

    private static GameSessionDto Ok(ActionResult<GameSessionDto> result) => Assert.IsType<GameSessionDto>(Assert.IsType<OkObjectResult>(result.Result).Value);

    private static string Code(ActionResult<GameSessionDto> result) => Assert.IsType<ApiError>(Assert.IsAssignableFrom<ObjectResult>(result.Result).Value).Code;

    [Fact]
    public async Task A_couple_has_one_open_game_whoever_invites_second()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        Ok(await Controller(db, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default));

        // Theo invites at the same moment: refused, so the phone reloads and shows Maya's invite instead.
        var second = await Controller(db, couple.TheoId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.SkillDuel), default);
        Assert.Equal("game.active_session", Code(second));
        Assert.Equal(1, await db.GameSessions.CountAsync());
    }

    [Fact]
    public async Task An_invite_taken_back_sends_no_push_but_tells_both_phones()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var invite = Ok(await Controller(db, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default));

        Ok(await Controller(db, couple.MayaId, couple.CoupleId).Abandon(invite.Id, default));

        // Only the invite itself reached Theo: nothing about it being taken back.
        Assert.Equal([NotificationType.GameInvite], await db.Notifications.Where(item => item.UserId == couple.TheoId).Select(item => item.Type).ToListAsync());
        Assert.Single(hub.Payloads("GameEnded"));
    }

    [Fact]
    public async Task A_declined_invite_tells_whoever_sent_it_in_their_language()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        (await db.Users.SingleAsync(user => user.Id == couple.MayaId)).SetLanguage("fr", clock.UtcNow);
        await db.SaveChangesAsync();
        var invite = Ok(await Controller(db, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default));

        Ok(await Controller(db, couple.TheoId, couple.CoupleId).Abandon(invite.Id, default));

        var told = await db.Notifications.SingleAsync(item => item.UserId == couple.MayaId);
        Assert.Equal(NotificationType.GameEnded, told.Type);
        Assert.Equal("Pas maintenant", told.Title);
        Assert.Contains("Theo", told.Body);
    }

    [Fact]
    public async Task Ending_a_game_part_way_tells_the_partner()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var invite = Ok(await Controller(db, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default));
        Ok(await Controller(db, couple.TheoId, couple.CoupleId).Accept(invite.Id, default));

        Ok(await Controller(db, couple.TheoId, couple.CoupleId).Abandon(invite.Id, default));

        var told = await db.Notifications.SingleAsync(item => item.UserId == couple.MayaId);
        Assert.Equal("Game ended", told.Title);
        Assert.Equal(GameSessionStatus.Abandoned, (await db.GameSessions.SingleAsync()).Status);
    }

    [Fact]
    public async Task An_accept_that_crosses_the_invite_being_taken_back_is_a_conflict_not_an_error()
    {
        TestCouple couple;
        Guid id;
        await using (var setup = database.CreateContext())
        {
            couple = await TestCouple.AddAsync(setup);
            id = Ok(await Controller(setup, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default)).Id;
        }
        // Maya takes it back just as Theo's accept is saved.
        var cancel = new InterferingWriter("UPDATE game_sessions SET \"Status\" = 'Abandoned', \"StateVersion\" = \"StateVersion\" + 1");
        await using var db = database.CreateContext(cancel);

        var accepted = await Controller(db, couple.TheoId, couple.CoupleId).Accept(id, default);

        Assert.Equal(1, cancel.Interfered);
        Assert.Equal("game.not_waiting", Code(accepted));
    }

    [Fact]
    public async Task Ending_a_game_as_a_move_lands_is_a_conflict_not_an_error()
    {
        TestCouple couple;
        Guid id;
        await using (var setup = database.CreateContext())
        {
            couple = await TestCouple.AddAsync(setup);
            id = Ok(await Controller(setup, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default)).Id;
            Ok(await Controller(setup, couple.TheoId, couple.CoupleId).Accept(id, default));
        }
        var move = new InterferingWriter("UPDATE game_sessions SET \"StateVersion\" = \"StateVersion\" + 1");
        await using var db = database.CreateContext(move);

        var ended = await Controller(db, couple.TheoId, couple.CoupleId).Abandon(id, default);

        Assert.Equal("game.version_conflict", Code(ended));
    }

    /// <summary>Plays memory match moves for whoever's turn it is until one misses (the turn passes).</summary>
    private async Task<GameSessionDto> MissAsync(ApplicationDbContext db, TestCouple couple, Guid sessionId, CouplePresenceTracker? presence = null)
    {
        var session = await db.GameSessions.AsNoTracking().SingleAsync(item => item.Id == sessionId);
        var mover = session.CurrentTurnUserId!.Value;
        var cards = Cards(session.StateJson);
        // Two cards with different pictures, neither matched.
        var first = cards.First(card => !card.Matched);
        var second = cards.First(card => !card.Matched && card.Value != first.Value);
        var controller = Controller(db, mover, couple.CoupleId, presence);
        var after = Ok(await controller.ApplyAction(sessionId, new ApplyGameActionRequest(session.StateVersion, "flip", null, first.Index), default));
        return Ok(await controller.ApplyAction(sessionId, new ApplyGameActionRequest(after.StateVersion, "flip", null, second.Index), default));
    }

    private static List<(int Index, int Value, bool Matched)> Cards(string stateJson)
    {
        using var state = JsonDocument.Parse(stateJson);
        return state.RootElement.GetProperty("Cards").EnumerateArray()
            .Select((card, index) => (index, card.GetProperty("Value").GetInt32(), card.GetProperty("Matched").GetBoolean())).ToList();
    }

    /// <summary>A memory match in play, past its countdown, with no notifications yet. [First] moves first, [Second] next.</summary>
    private async Task<(TestCouple Couple, Guid Id, Guid First, Guid Second)> MemoryGameAsync(ApplicationDbContext db)
    {
        var couple = await TestCouple.AddAsync(db);
        var id = Ok(await Controller(db, couple.TheoId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default)).Id;
        var started = Ok(await Controller(db, couple.MayaId, couple.CoupleId).Accept(id, default));
        clock.UtcNow = clock.UtcNow.AddSeconds(6);
        await db.Notifications.ExecuteDeleteAsync();
        var first = started.CurrentTurnUserId!.Value;
        return (couple, id, first, first == couple.MayaId ? couple.TheoId : couple.MayaId);
    }

    [Fact]
    public async Task A_turn_passing_to_a_partner_away_from_the_app_nudges_them_once()
    {
        await using var db = database.CreateContext();
        var (couple, id, first, second) = await MemoryGameAsync(db);
        var firstName = first == couple.MayaId ? "Maya" : "Theo";

        await MissAsync(db, couple, id); // The first player misses: the second's turn.
        var nudge = await db.Notifications.SingleAsync();
        Assert.Equal((second, NotificationType.GameTurn, "Your turn"), (nudge.UserId, nudge.Type, nudge.Title));
        Assert.Contains(firstName, nudge.Body);

        await MissAsync(db, couple, id); // The second misses: the first's own first nudge.
        await MissAsync(db, couple, id); // The first again: the second hasn't read theirs yet, so nothing more.
        Assert.Equal(1, await db.Notifications.CountAsync(item => item.UserId == second));
        Assert.Equal(1, await db.Notifications.CountAsync(item => item.UserId == first));

        // Once read, the next turn may nudge again.
        (await db.Notifications.SingleAsync(item => item.UserId == second)).MarkRead(clock.UtcNow);
        await db.SaveChangesAsync();
        await MissAsync(db, couple, id);
        await MissAsync(db, couple, id);
        Assert.Equal(2, await db.Notifications.CountAsync(item => item.UserId == second));
    }

    [Fact]
    public async Task No_turn_nudge_for_a_partner_in_the_app()
    {
        await using var db = database.CreateContext();
        var (couple, id, _, second) = await MemoryGameAsync(db);
        var presence = new CouplePresenceTracker();
        presence.Connected(couple.CoupleId, second);

        await MissAsync(db, couple, id, presence);

        Assert.Empty(await db.Notifications.ToListAsync());
    }

    [Fact]
    public async Task Heart_to_heart_scores_sent_at_the_same_moment_both_land()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var invite = Ok(await Controller(db, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.SkillDuel), default));
        var started = Ok(await Controller(db, couple.TheoId, couple.CoupleId).Accept(invite.Id, default));
        clock.UtcNow = clock.UtcNow.AddSeconds(6);

        // Both phones send on the version they saw; the second arrives on an older one.
        Ok(await Controller(db, couple.MayaId, couple.CoupleId).ApplyAction(invite.Id, new ApplyGameActionRequest(started.StateVersion, "score", """{"score":80}""", 0), default));
        var theo = Ok(await Controller(db, couple.TheoId, couple.CoupleId).ApplyAction(invite.Id, new ApplyGameActionRequest(started.StateVersion, "score", """{"score":70}""", 0), default));

        using var state = JsonDocument.Parse(theo.StateJson);
        var round = state.RootElement.GetProperty("rounds")[0];
        Assert.Equal((70, 80), (round.GetProperty("mine").GetInt32(), round.GetProperty("partner").GetInt32()));
        // Duels are played at once, each on their own time: no "your turn" pushes.
        Assert.DoesNotContain(await db.Notifications.ToListAsync(), item => item.Type == NotificationType.GameTurn);
    }

    [Fact]
    public async Task The_timeout_worker_drops_a_save_a_move_got_to_first_and_keeps_going()
    {
        TestCouple couple;
        Guid id;
        await using (var setup = database.CreateContext())
        {
            couple = await TestCouple.AddAsync(setup);
            id = Ok(await Controller(setup, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.MemoryMatch), default)).Id;
        }
        var move = new InterferingWriter("UPDATE game_sessions SET \"StateVersion\" = \"StateVersion\" + 1");
        await using var db = database.CreateContext(move);
        var session = await db.GameSessions.SingleAsync();
        session.Abandon(clock.UtcNow);

        Assert.False(await GameTimeoutWorker.SavedAsync(db, default));
        // The dropped change is forgotten, so the next save in the same batch goes through.
        Assert.DoesNotContain(db.ChangeTracker.Entries(), entry => entry.State != EntityState.Unchanged);
        Assert.True(await GameTimeoutWorker.SavedAsync(db, default));
        await using var check = database.CreateContext();
        Assert.Equal(GameSessionStatus.WaitingForPartner, (await check.GameSessions.SingleAsync()).Status);
    }

    private sealed class NoWords : IGameWordValidator
    {
        public Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(string stateJson, string answersJson, CancellationToken cancellationToken = default)
            => Task.FromResult<IReadOnlyDictionary<string, bool?>>(new Dictionary<string, bool?>());
    }

    private sealed class NoQuestions : IGameQuestionProvider
    {
        public Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<string>>([]);
        public Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyList<TriviaQuestion>>([]);
    }
}
