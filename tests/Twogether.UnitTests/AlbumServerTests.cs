using System.Data.Common;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Background;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Memories;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Account;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>The album's quota and storage cleanup, through the real controllers and worker on a real database.</summary>
public sealed class AlbumServerTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private static readonly MediaSettings Settings = new();
    private readonly TestDatabase database = new();
    private readonly FakeMediaStorage storage = new();
    private readonly FakeClock clock = new(Now);
    private readonly FakeEntitlements entitlements = new(new PlanLimits(3, 10_000, 7));

    public void Dispose() => database.Dispose();

    private MemoriesController Controller(ApplicationDbContext db, Guid userId, Guid coupleId)
        => new(db, new FakeCurrentUser(userId, coupleId), clock, storage, Settings, entitlements, new FakeHub(), NullLogger<MemoriesController>.Instance);

    private MemoryCleanupWorker Worker()
    {
        var services = new ServiceCollection();
        services.AddScoped<IApplicationDbContext>(_ => database.CreateContext());
        return new MemoryCleanupWorker(services.BuildServiceProvider().GetRequiredService<IServiceScopeFactory>(), storage, Settings, clock, NullLogger<MemoryCleanupWorker>.Instance);
    }

    private async Task<TestCouple> CoupleAsync()
    {
        await using var db = database.CreateContext();
        return await TestCouple.AddAsync(db);
    }

    private async Task<Memory?> FindAsync(Guid memoryId)
    {
        await using var db = database.CreateContext();
        return await db.Memories.AsNoTracking().SingleOrDefaultAsync(item => item.Id == memoryId);
    }

    private async Task<Memory> ReserveAsync(TestCouple couple, long declaredBytes)
    {
        await using var db = database.CreateContext();
        var slot = Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).Reserve(new ReserveMemoryRequest("image/jpeg", declaredBytes, null, null), default)).Result);
        return (await FindAsync(Assert.IsType<MemoryUploadDto>(slot.Value).MemoryId))!;
    }

    private async Task<IActionResult?> CompleteAsync(TestCouple couple, Guid memoryId)
    {
        await using var db = database.CreateContext();
        return (await Controller(db, couple.MayaId, couple.CoupleId).Complete(memoryId, default)).Result;
    }

    [Fact]
    public async Task A_file_larger_than_declared_is_deleted_and_never_counts()
    {
        // The bypass: reserve at 1 byte, upload far more on the signed link, complete.
        var couple = await CoupleAsync();
        var memory = await ReserveAsync(couple, 1);
        storage.Put(memory.ObjectKey, 5_000_000, "image/jpeg");

        var result = await CompleteAsync(couple, memory.Id);

        Assert.Equal("memories.upload_rejected", Assert.IsType<ApiError>(Assert.IsType<UnprocessableEntityObjectResult>(result).Value).Code);
        Assert.False(storage.Objects.ContainsKey(memory.ObjectKey));
        Assert.Equal(MemoryStatus.Deleting, (await FindAsync(memory.Id))!.Status);
        await using var db = database.CreateContext();
        var usage = Assert.IsType<MemoryUsageDto>(Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).Usage(default)).Result).Value);
        Assert.Equal(0, usage.UsedBytes);
    }

    [Fact]
    public async Task A_smaller_file_is_kept_at_its_real_size()
    {
        var couple = await CoupleAsync();
        var memory = await ReserveAsync(couple, 4_000);
        storage.Put(memory.ObjectKey, 3_000, "image/jpeg");

        Assert.IsType<OkObjectResult>(await CompleteAsync(couple, memory.Id));

        var ready = (await FindAsync(memory.Id))!;
        Assert.Equal(MemoryStatus.Ready, ready.Status);
        Assert.Equal(3_000, ready.SizeBytes);
    }

    [Fact]
    public async Task Completing_rechecks_the_quota_with_the_real_size()
    {
        // The plan's room shrank while the photo was uploading (Plus lapsed, say).
        var couple = await CoupleAsync();
        var memory = await ReserveAsync(couple, 4_000);
        storage.Put(memory.ObjectKey, 3_500, "image/jpeg");
        entitlements.Limits = new PlanLimits(3, 3_000, 7);

        var result = await CompleteAsync(couple, memory.Id);

        Assert.Equal("memories.quota_exceeded", Assert.IsType<ApiError>(Assert.IsType<ConflictObjectResult>(result).Value).Code);
        Assert.False(storage.Objects.ContainsKey(memory.ObjectKey));
        Assert.Equal(MemoryStatus.Deleting, (await FindAsync(memory.Id))!.Status);
    }

    [Fact]
    public async Task Both_partners_adding_at_once_cannot_overfill_the_album()
    {
        // Room for one 6 KB photo. Every request reads the usage slowly, so without one
        // check-and-reserve at a time per couple they would all see an empty album.
        var couple = await CoupleAsync();
        var slow = new SlowUsageReads();
        var requests = Enumerable.Range(0, 6).Select(async index =>
        {
            await using var db = database.CreateContext(slow);
            if (index % 2 == 0)
            {
                var reserved = (await Controller(db, couple.MayaId, couple.CoupleId).Reserve(new ReserveMemoryRequest("image/jpeg", 6_000, null, null), default)).Result;
                return reserved is OkObjectResult;
            }
            var synced = Assert.IsType<OkObjectResult>((await Controller(db, couple.TheoId, couple.CoupleId)
                .Sync(new SyncMemoriesRequest([new SyncMemoryItem(Guid.NewGuid(), "image/jpeg", 6_000, null, null)]), default)).Result);
            return Assert.Single(Assert.IsType<SyncMemoriesResponse>(synced.Value).Items).Status == SyncItemStatus.Upload;
        }).ToList();

        var accepted = await Task.WhenAll(requests);

        Assert.True(slow.Delayed > 0);
        Assert.Single(accepted, ok => ok);
        await using var check = database.CreateContext();
        Assert.Equal(6_000, await check.Memories.Where(item => item.Status != MemoryStatus.Deleting).SumAsync(item => item.SizeBytes));
    }

    [Fact]
    public async Task A_deleted_memory_stays_a_tombstone_until_its_upload_links_expire()
    {
        var couple = await CoupleAsync();
        var memory = await ReserveAsync(couple, 4_000);
        storage.Put(memory.ObjectKey, 4_000, "image/jpeg");
        storage.Put(memory.ThumbnailKey, 2_000, "image/jpeg");
        Assert.IsType<OkObjectResult>(await CompleteAsync(couple, memory.Id));

        await using (var db = database.CreateContext())
            Assert.IsType<NoContentResult>(await Controller(db, couple.MayaId, couple.CoupleId).Delete(memory.Id, default));
        Assert.Empty(storage.Objects);
        Assert.Equal(MemoryStatus.Deleting, (await FindAsync(memory.Id))!.Status);

        // The upload link still works: a late PUT puts the bytes back.
        storage.Put(memory.ObjectKey, 4_000, "image/jpeg");
        clock.UtcNow = Now.AddMinutes(10);
        await Worker().SweepAsync(default);
        Assert.NotNull(await FindAsync(memory.Id));

        // Once the link has expired the worker deletes the object again, then the row.
        clock.UtcNow = Now.Add(Settings.UploadUrlLifetime).AddMinutes(1);
        Assert.Equal(1, await Worker().SweepAsync(default));
        Assert.Empty(storage.Objects);
        Assert.Null(await FindAsync(memory.Id));
    }

    [Fact]
    public async Task A_memory_deleted_after_its_links_expired_goes_at_once()
    {
        var couple = await CoupleAsync();
        var memory = await ReserveAsync(couple, 4_000);
        storage.Put(memory.ObjectKey, 4_000, "image/jpeg");
        Assert.IsType<OkObjectResult>(await CompleteAsync(couple, memory.Id));
        clock.UtcNow = Now.Add(Settings.UploadUrlLifetime).AddMinutes(1);
        await Worker().RecheckClosedUploadWindowsAsync(default);

        await using (var db = database.CreateContext())
            Assert.IsType<NoContentResult>(await Controller(db, couple.MayaId, couple.CoupleId).Delete(memory.Id, default));

        Assert.Empty(storage.Objects);
        Assert.Null(await FindAsync(memory.Id));
    }

    [Fact]
    public async Task Account_deletion_during_an_upload_leaves_nothing_in_storage()
    {
        var couple = await CoupleAsync();
        var memory = await ReserveAsync(couple, 4_000);
        storage.Put(memory.ObjectKey, 4_000, "image/jpeg");

        await using (var db = database.CreateContext())
            Assert.NotNull(await new AccountEraser(db, clock).EraseAsync(couple.MayaId, default));
        // The phone was still uploading: the link outlives the account.
        storage.Put(memory.ObjectKey, 4_000, "image/jpeg");

        clock.UtcNow = Now.AddMinutes(5);
        await Worker().SweepAsync(default);
        Assert.Equal(MemoryStatus.Deleting, (await FindAsync(memory.Id))!.Status);

        clock.UtcNow = Now.Add(Settings.UploadUrlLifetime).AddMinutes(1);
        await Worker().SweepAsync(default);
        Assert.Empty(storage.Objects);
        Assert.Null(await FindAsync(memory.Id));
    }

    [Fact]
    public async Task A_rejected_sync_upload_can_be_tried_again()
    {
        // The rejected memory waits as a tombstone, but it no longer holds the phone's id.
        var couple = await CoupleAsync();
        var clientId = Guid.NewGuid();
        var item = new SyncMemoryItem(clientId, "image/jpeg", 1_000, null, null);
        Guid firstId;
        await using (var db = database.CreateContext())
        {
            var synced = Assert.IsType<SyncMemoriesResponse>(Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).Sync(new SyncMemoriesRequest([item]), default)).Result).Value);
            firstId = Assert.Single(synced.Items).MemoryId!.Value;
        }
        storage.Put((await FindAsync(firstId))!.ObjectKey, 2_000, "image/jpeg");
        await using (var db = database.CreateContext())
        {
            var completed = Assert.IsType<CompleteSyncResponse>(Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).CompleteSync(new CompleteSyncRequest([firstId]), default)).Result).Value);
            Assert.Equal(SyncCompleteStatus.Rejected, Assert.Single(completed.Items).Status);
        }

        await using (var db = database.CreateContext())
        {
            var again = Assert.IsType<SyncMemoriesResponse>(Assert.IsType<OkObjectResult>((await Controller(db, couple.MayaId, couple.CoupleId).Sync(new SyncMemoriesRequest([item]), default)).Result).Value);
            var result = Assert.Single(again.Items);
            Assert.Equal(SyncItemStatus.Upload, result.Status);
            Assert.NotEqual(firstId, result.MemoryId);
        }
    }

    /// <summary>Makes every read of the album's usage take a while, as under load.</summary>
    private sealed class SlowUsageReads : DbCommandInterceptor
    {
        private int delayed;
        public int Delayed => delayed;

        public override async ValueTask<DbDataReader> ReaderExecutedAsync(DbCommand command, CommandExecutedEventData eventData, DbDataReader result, CancellationToken cancellationToken = default)
        {
            if (command.CommandText.Contains("SUM(", StringComparison.OrdinalIgnoreCase))
            {
                Interlocked.Increment(ref delayed);
                await Task.Delay(100, cancellationToken);
            }
            return result;
        }
    }
}
