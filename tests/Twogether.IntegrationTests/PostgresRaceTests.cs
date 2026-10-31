using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Controllers;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Food;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Memories;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Account;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.IntegrationTests;

/// <summary>
/// Races that only real PostgreSQL can show: the advisory locks behind the album quota and the
/// daily scan limit (SQLite, under the unit tests, serialises every write anyway), and two
/// account deletions clashing on each other's rows.
/// </summary>
public sealed class PostgresRaceTests : IAsyncLifetime
{
    private readonly PostgresDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);

    public Task InitializeAsync() => database.InitializeAsync();
    public Task DisposeAsync() => database.DisposeAsync();

    [PostgresFact]
    public async Task A_second_transaction_waits_for_the_same_key_and_not_for_another()
    {
        var key = Guid.NewGuid();
        await using var first = database.CreateContext();
        await using var second = database.CreateContext();
        await using var third = database.CreateContext();
        await using var held = await first.Database.BeginTransactionAsync();
        await first.Database.LockAsync(LockScope.FoodScans, key, default);

        await using var waiting = await second.Database.BeginTransactionAsync();
        var queued = second.Database.LockAsync(LockScope.FoodScans, key, default);
        await using (await third.Database.BeginTransactionAsync())
        {
            // Another person, and the same id under another scope, go straight through.
            await third.Database.LockAsync(LockScope.FoodScans, Guid.NewGuid(), default);
            await third.Database.LockAsync(LockScope.AlbumQuota, key, default);
        }
        await Task.Delay(300);
        Assert.False(queued.IsCompleted);

        await held.CommitAsync();
        await queued.WaitAsync(TimeSpan.FromSeconds(10));
    }

    [PostgresFact]
    public async Task Parallel_food_scans_cannot_get_past_the_daily_limit()
    {
        TestCouple couple;
        await using (var db = database.CreateContext()) couple = await TestCouple.AddAsync(db);
        var estimator = new FakeEstimator();
        estimator.Hold();
        var entitlements = new FakeEntitlements(new PlanLimits(3, 10_000, 7));
        var photo = new FoodScanRequest(Convert.ToBase64String([0xFF, 0xD8, 0xFF, 0xE0]), "image/jpeg", null, null);
        var scans = Enumerable.Range(0, 12).Select(async _ =>
        {
            await using var db = database.CreateContext();
            var controller = new FoodController(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, new FakeHub(), estimator, new FoodSettings(), entitlements, NullLogger<FoodController>.Instance);
            return (await controller.Scan(photo, default)).Result;
        }).ToList();

        var deadline = DateTime.UtcNow.AddSeconds(15);
        while (estimator.Calls + scans.Count(task => task.IsCompleted) < scans.Count && DateTime.UtcNow < deadline) await Task.Delay(20);
        var calledWhileHeld = estimator.Calls;
        estimator.Release();
        var results = await Task.WhenAll(scans);

        Assert.Equal(3, calledWhileHeld);
        Assert.Equal(3, results.Count(result => result is OkObjectResult));
        await using var check = database.CreateContext();
        Assert.Equal(3, await check.FoodScans.CountAsync(item => item.UserId == couple.MayaId));
    }

    [PostgresFact]
    public async Task Both_partners_deleting_their_accounts_at_once_both_go()
    {
        // The second eraser finds its partner row already deleted under it: it starts over from
        // what is there now (no couple left to end) instead of failing.
        for (var round = 0; round < 5; round++)
        {
            TestCouple couple;
            await using (var db = database.CreateContext()) couple = await TestCouple.AddAsync(db);
            var erasures = new[] { couple.MayaId, couple.TheoId }.Select(async userId =>
            {
                await using var db = database.CreateContext();
                return await new AccountEraser(db, clock).EraseAsync(userId, default);
            }).ToList();

            var erased = await Task.WhenAll(erasures);

            Assert.All(erased, Assert.NotNull);
            await using var check = database.CreateContext();
            Assert.False(await check.Users.AnyAsync(item => item.Id == couple.MayaId || item.Id == couple.TheoId));
            Assert.False(await check.Couples.AnyAsync(item => item.Id == couple.CoupleId));
        }
    }

    [PostgresFact]
    public async Task Both_partners_adding_at_once_cannot_overfill_the_album()
    {
        TestCouple couple;
        await using (var db = database.CreateContext()) couple = await TestCouple.AddAsync(db);
        var storage = new FakeMediaStorage();
        var entitlements = new FakeEntitlements(new PlanLimits(3, 10_000, 7));
        var requests = Enumerable.Range(0, 12).Select(async index =>
        {
            await using var db = database.CreateContext();
            var userId = index % 2 == 0 ? couple.MayaId : couple.TheoId;
            var controller = new MemoriesController(db, new FakeCurrentUser(userId, couple.CoupleId), clock, storage, new MediaSettings(), entitlements, new FakeHub(), NullLogger<MemoriesController>.Instance);
            return (await controller.Reserve(new ReserveMemoryRequest("image/jpeg", 6_000, null, null), default)).Result is OkObjectResult;
        }).ToList();

        var accepted = await Task.WhenAll(requests);

        Assert.Single(accepted, ok => ok);
        await using var check = database.CreateContext();
        Assert.Equal(6_000, await check.Memories.Where(item => item.Status != MemoryStatus.Deleting).SumAsync(item => item.SizeBytes));
    }

    [PostgresFact]
    public async Task Both_partners_inviting_at_once_open_one_game()
    {
        TestCouple couple;
        await using (var db = database.CreateContext()) couple = await TestCouple.AddAsync(db);
        var invites = Enumerable.Range(0, 10).Select(async index =>
        {
            await using var db = database.CreateContext();
            var userId = index % 2 == 0 ? couple.MayaId : couple.TheoId;
            var controller = new GameController(db, new FakeCurrentUser(userId, couple.CoupleId), clock, new GameEngine(), new NoWords(), new GameSetupFactory(new NoQuestions()), new FakeHub());
            return (await controller.Create(new CreateGameSessionRequest(GameType.MemoryMatch), default)).Result is OkObjectResult;
        }).ToList();

        var opened = await Task.WhenAll(invites);

        Assert.Single(opened, ok => ok);
        await using var check = database.CreateContext();
        Assert.Equal(1, await check.GameSessions.CountAsync(item => item.CoupleId == couple.CoupleId));
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
