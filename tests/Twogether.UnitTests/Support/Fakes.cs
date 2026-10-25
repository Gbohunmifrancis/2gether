using System.Collections.Concurrent;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Twogether.Api.Hubs;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Food;
using Twogether.Application.Features.Memories;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Results;

namespace Twogether.UnitTests.Support;

// Shared with tests/Twogether.IntegrationTests (linked there), so nothing here may depend on SQLite.

/// <summary>Two linked partners, Maya (who started the couple) and Theo.</summary>
public sealed record TestCouple(Guid CoupleId, Guid MayaId, Guid TheoId)
{
    public static readonly DateTime Now = new(2026, 10, 25, 12, 0, 0, DateTimeKind.Utc);

    public static async Task<TestCouple> AddAsync(ApplicationDbContext db)
    {
        var maya = User.Create($"maya-{Guid.NewGuid():N}@example.com", "Maya", new DateOnly(1995, 3, 1), Now);
        var theo = User.Create($"theo-{Guid.NewGuid():N}@example.com", "Theo", new DateOnly(1994, 7, 9), Now);
        var couple = Couple.Create(maya.Id, Now);
        couple.LinkPartner(theo.Id, Now);
        maya.SetCouple(couple.Id, Now);
        theo.SetCouple(couple.Id, Now);
        db.AddRange(maya, theo, couple);
        await db.SaveChangesAsync();
        return new TestCouple(couple.Id, maya.Id, theo.Id);
    }
}

public sealed class FakeCurrentUser(Guid userId, Guid? coupleId) : ICurrentUser
{
    public Guid? UserId => userId;
    public Guid? CoupleId => coupleId;
    public bool IsAuthenticated => true;
}

public sealed class FakeClock(DateTime utcNow) : IDateTimeProvider
{
    public DateTime UtcNow { get; set; } = utcNow;
}

public sealed class FakeEntitlements(PlanLimits limits, Plan plan = Plan.Free) : IEntitlementService
{
    public PlanLimits Limits { get; set; } = limits;

    public Task<EntitlementDto> ForUserAsync(Guid userId, CancellationToken cancellationToken)
        => Task.FromResult(new EntitlementDto(plan, Limits, null, true));
}

/// <summary>Object storage in memory. <see cref="Put"/> is what a phone does with a signed upload link.</summary>
public sealed class FakeMediaStorage : IMediaStorage
{
    public ConcurrentDictionary<string, StoredObjectInfo> Objects { get; } = new();
    public bool IsConfigured => true;
    public Uri PresignUpload(string key, string contentType, TimeSpan lifetime) => new($"https://storage.test/put/{key}");
    public Uri PresignDownload(string key, TimeSpan lifetime) => new($"https://storage.test/get/{key}");
    public Task<StoredObjectInfo?> HeadAsync(string key, CancellationToken cancellationToken) => Task.FromResult(Objects.GetValueOrDefault(key));

    public Task DeleteAsync(string key, CancellationToken cancellationToken)
    {
        Objects.TryRemove(key, out _);
        return Task.CompletedTask;
    }

    public void Put(string key, long sizeBytes, string contentType) => Objects[key] = new StoredObjectInfo(sizeBytes, contentType);
}

/// <summary>A photo estimator that can be held, to stand in for a model call that takes seconds.</summary>
public sealed class FakeEstimator : IFoodPhotoEstimator
{
    private TaskCompletionSource gate = Open();
    private int calls;

    public int Calls => Volatile.Read(ref calls);
    public Func<Result<FoodEstimate>>? Next { get; set; }
    public bool IsConfigured => true;
    public string ModelName => "test-model";

    public void Hold() => gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    public void Release() => gate.TrySetResult();

    public async Task<Result<FoodEstimate>> EstimateAsync(FoodPhoto photo, string? hint, string? countryCode, CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref calls);
        await gate.Task.WaitAsync(cancellationToken);
        return Next is { } next ? next() : Estimate(isFood: true);
    }

    public static FoodEstimate Estimate(bool isFood)
        => new("Rice and beans", 520, 450, 600, 18, 80, 9, 0.8, [new FoodEstimateItem("Rice", "1 cup", 300)], isFood, 1200, 150);

    private static TaskCompletionSource Open()
    {
        var open = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        open.SetResult();
        return open;
    }
}

/// <summary>Records what would go out over SignalR.</summary>
public sealed class FakeHub : IHubContext<CoupleHub>
{
    public FakeHub() => Clients = new Recorder(this);

    public ConcurrentQueue<(string Group, string Method, object?[] Args)> Sent { get; } = new();
    public IHubClients Clients { get; }
    public IGroupManager Groups => throw new NotSupportedException();

    public IEnumerable<object?> Payloads(string method) => Sent.Where(item => item.Method == method).Select(item => item.Args.FirstOrDefault());

    private sealed class Recorder(FakeHub hub) : IHubClients
    {
        public IClientProxy Group(string groupName) => new Proxy(hub, groupName);
        public IClientProxy All => Group("*");
        public IClientProxy AllExcept(IReadOnlyList<string> excludedConnectionIds) => Group("*");
        public IClientProxy Client(string connectionId) => Group(connectionId);
        public IClientProxy Clients(IReadOnlyList<string> connectionIds) => Group("*");
        public IClientProxy GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => Group(groupName);
        public IClientProxy Groups(IReadOnlyList<string> groupNames) => Group(string.Join(",", groupNames));
        public IClientProxy User(string userId) => Group(userId);
        public IClientProxy Users(IReadOnlyList<string> userIds) => Group("*");
    }

    private sealed class Proxy(FakeHub hub, string group) : IClientProxy
    {
        public Task SendCoreAsync(string method, object?[] args, CancellationToken cancellationToken = default)
        {
            hub.Sent.Enqueue((group, method, args));
            return Task.CompletedTask;
        }
    }
}

/// <summary>Runs a statement as "someone else" right before the first save (or every save) of a context.</summary>
public sealed class InterferingWriter(string sql, bool everySave = false) : SaveChangesInterceptor
{
    public int Interfered { get; private set; }

    public override async ValueTask<InterceptionResult<int>> SavingChangesAsync(DbContextEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
    {
        if (everySave || Interfered == 0)
        {
            Interfered++;
            await eventData.Context!.Database.ExecuteSqlRawAsync(sql, cancellationToken);
        }
        return result;
    }
}
