using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.UnitTests.Support;

namespace Twogether.IntegrationTests;

/// <summary>
/// The game history on real PostgreSQL (docs/games.md): every way a game can end races the others, and whichever
/// ending wins writes the game's one history row; the rest write nothing.
/// </summary>
public sealed class GameHistoryPostgresTests : IAsyncLifetime
{
    private readonly PostgresDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);
    private readonly GameEngine engine = new();

    public Task InitializeAsync() => database.InitializeAsync();
    public Task DisposeAsync() => database.DisposeAsync();

    private GameController Controller(Twogether.Infrastructure.Persistence.ApplicationDbContext db, Guid userId, Guid coupleId)
        => new(db, new FakeCurrentUser(userId, coupleId), clock, engine, new NoWords(), new GameSetupFactory(new NoQuestions()), new FakeHub());

    /// <summary>A duel in play, past its countdown, with one round scored by both (so an ending has scores to keep).</summary>
    private async Task<(TestCouple Couple, Guid Id)> DuelAsync()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var invite = Assert.IsType<GameSessionDto>(Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).Create(new CreateGameSessionRequest(GameType.SkillDuel), default)).Result).Value);
        var started = Assert.IsType<GameSessionDto>(Assert.IsType<OkObjectResult>((await Controller(db, couple.TheoId, couple.CoupleId).Accept(invite.Id, default)).Result).Value);
        clock.UtcNow = clock.UtcNow.AddSeconds(6);
        await Controller(db, couple.MayaId, couple.CoupleId).ApplyAction(invite.Id, new ApplyGameActionRequest(started.StateVersion, "score", """{"score":70}""", 0), default);
        await Controller(db, couple.TheoId, couple.CoupleId).ApplyAction(invite.Id, new ApplyGameActionRequest(started.StateVersion, "score", """{"score":60}""", 0), default);
        return (couple, invite.Id);
    }

    [PostgresFact]
    public async Task Both_partners_ending_a_game_at_once_leave_one_history_row()
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var (couple, id) = await DuelAsync();
            var ends = new[] { couple.MayaId, couple.TheoId }.Select(async user =>
            {
                await using var db = database.CreateContext();
                return (await Controller(db, user, couple.CoupleId).Abandon(id, default)).Result;
            }).ToList();
            var results = await Task.WhenAll(ends);

            Assert.Single(results, result => result is OkObjectResult);
            await using var check = database.CreateContext();
            var row = await check.GameResults.SingleAsync(item => item.GameSessionId == id);
            Assert.Equal((GameOutcome.Abandoned, (Guid?)null, 1), (row.Outcome, row.WinnerUserId, row.RoundsPlayed));
        }
    }

    [PostgresFact]
    public async Task A_last_move_and_an_end_racing_leave_one_history_row_from_whichever_won()
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var (couple, id) = await DuelAsync();
            // Every path that ends a game, at the same moment: the last move completing it, a partner ending it, and
            // the stale sweep. Each loads the session first, then all save at once.
            await using var move = database.CreateContext();
            await using var end = database.CreateContext();
            await using var sweep = database.CreateContext();
            var completing = await move.GameSessions.SingleAsync(item => item.Id == id);
            var ending = await end.GameSessions.SingleAsync(item => item.Id == id);
            var sweeping = await sweep.GameSessions.SingleAsync(item => item.Id == id);
            completing.Complete(completing.StateJson, clock.UtcNow);
            ending.EndBy(couple.MayaId, clock.UtcNow);
            sweeping.Abandon(clock.UtcNow);

            // As each path does: save the ending, and only if it went through, keep it in the history.
            var saves = await Task.WhenAll(new[] { (move, completing, GameOutcome.Completed), (end, ending, GameOutcome.Abandoned), (sweep, sweeping, GameOutcome.TimedOut) }.Select(async path =>
            {
                try { await path.Item1.SaveChangesAsync(); }
                catch (DbUpdateConcurrencyException) { return false; }
                await GameHistory.KeepAsync(path.Item1, engine, path.Item2, path.Item3, clock.UtcNow, default);
                return true;
            }));

            Assert.Single(saves, saved => saved);
            await using var check = database.CreateContext();
            var row = await check.GameResults.SingleAsync(item => item.GameSessionId == id);
            var session = await check.GameSessions.SingleAsync(item => item.Id == id);
            // The row says how the game actually ended.
            var ended = session.Status == GameSessionStatus.Completed ? GameOutcome.Completed : session.EndedByUserId is null ? GameOutcome.TimedOut : GameOutcome.Abandoned;
            Assert.Equal(ended, row.Outcome);
            Assert.Equal(1, await check.GameResults.CountAsync(item => item.GameSessionId == id));
        }
    }

    [PostgresFact]
    public async Task The_database_refuses_a_second_row_for_a_game_and_backfills_can_race()
    {
        var (couple, id) = await DuelAsync();
        await using (var db = database.CreateContext())
            Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).Abandon(id, default)).Result);

        await using (var db = database.CreateContext())
        {
            var session = await db.GameSessions.AsNoTracking().SingleAsync(item => item.Id == id);
            db.GameResults.Add(new GameOutcomeService(engine).Record(session, GameOutcome.Abandoned, null, clock.UtcNow));
            await Assert.ThrowsAsync<DbUpdateException>(() => db.SaveChangesAsync());
        }

        // A game finished before the history existed, read by both phones at once: one row.
        Guid legacyId;
        await using (var db = database.CreateContext())
        {
            var legacy = GameSession.Create(couple.CoupleId, GameType.MemoryMatch, couple.MayaId, clock.UtcNow, """{"region":"global"}""");
            legacy.Start(couple.MayaId, null, engine.Start(GameType.MemoryMatch, [couple.MayaId, couple.TheoId], clock.UtcNow).StateJson, clock.UtcNow);
            legacy.Complete(legacy.StateJson, clock.UtcNow);
            db.GameSessions.Add(legacy);
            await db.SaveChangesAsync();
            legacyId = legacy.Id;
        }
        await Task.WhenAll(Enumerable.Range(0, 4).Select(async _ =>
        {
            await using var db = database.CreateContext();
            await GameHistory.BackfillAsync(db, engine, couple.CoupleId, default);
        }));
        await using var check = database.CreateContext();
        Assert.Equal(1, await check.GameResults.CountAsync(item => item.GameSessionId == legacyId));
        Assert.Equal(1, await check.GameResults.CountAsync(item => item.GameSessionId == id));
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
