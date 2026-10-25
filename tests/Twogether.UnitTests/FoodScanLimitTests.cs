using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Controllers;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Food;
using Twogether.Core.Entities;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Results;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// The daily photo-scan limit through the real controller on a real database. Every scan that
/// passed the limit check counts for the day, however it ends: success, a model error, an
/// unexpected exception, or the phone giving up.
/// </summary>
public sealed class FoodScanLimitTests : IDisposable
{
    private static readonly FoodScanRequest Photo = new(Convert.ToBase64String([0xFF, 0xD8, 0xFF, 0xE0]), "image/jpeg", null, null);
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);
    private readonly FakeEntitlements entitlements = new(new PlanLimits(3, 10_000, 7));
    private readonly FakeEstimator estimator = new();

    public void Dispose() => database.Dispose();

    private FoodController Controller(ApplicationDbContext db, TestCouple couple)
        => new(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, new FakeHub(), estimator, new FoodSettings(), entitlements, NullLogger<FoodController>.Instance);

    private async Task<TestCouple> CoupleAsync()
    {
        await using var db = database.CreateContext();
        return await TestCouple.AddAsync(db);
    }

    private async Task<List<FoodScan>> ScansAsync(TestCouple couple)
    {
        await using var db = database.CreateContext();
        return await db.FoodScans.AsNoTracking().Where(item => item.UserId == couple.MayaId).ToListAsync();
    }

    private async Task<ActionResult?> ScanAsync(TestCouple couple, CancellationToken cancellationToken = default)
    {
        await using var db = database.CreateContext();
        return (await Controller(db, couple).Scan(Photo, cancellationToken)).Result;
    }

    private static int StatusOf(ActionResult? result) => result switch
    {
        ObjectResult objectResult => objectResult.StatusCode ?? 200,
        StatusCodeResult status => status.StatusCode,
        _ => 0
    };

    [Fact]
    public async Task Parallel_scans_cannot_get_past_the_daily_limit()
    {
        var couple = await CoupleAsync();
        estimator.Hold();
        var scans = Enumerable.Range(0, 10).Select(_ => ScanAsync(couple)).ToList();

        // The model is slow: the limit has to be settled before anyone reaches it.
        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (estimator.Calls + scans.Count(task => task.IsCompleted) < scans.Count && DateTime.UtcNow < deadline) await Task.Delay(20);
        var calledWhileHeld = estimator.Calls;
        estimator.Release();
        var results = await Task.WhenAll(scans);

        Assert.Equal(3, calledWhileHeld);
        Assert.Equal(3, results.Count(result => StatusOf(result) == 200));
        Assert.Equal(7, results.Count(result => StatusOf(result) == 429));
        var recorded = await ScansAsync(couple);
        Assert.Equal(3, recorded.Count);
        Assert.All(recorded, scan => Assert.True(scan.Succeeded));
    }

    [Fact]
    public async Task A_scan_that_fails_still_counts()
    {
        var couple = await CoupleAsync();
        estimator.Next = () => Error.Failure("food.estimation_failed", "The estimate failed.");
        Assert.Equal(400, StatusOf(await ScanAsync(couple)));

        var recorded = Assert.Single(await ScansAsync(couple));
        Assert.Equal("food.estimation_failed", recorded.ErrorCode);
    }

    [Fact]
    public async Task An_unexpected_error_still_counts_and_says_so()
    {
        var couple = await CoupleAsync();
        entitlements.Limits = new PlanLimits(1, 10_000, 7);
        estimator.Next = () => throw new HttpRequestException("connection reset");

        await Assert.ThrowsAsync<HttpRequestException>(() => ScanAsync(couple));
        estimator.Next = null;
        var again = await ScanAsync(couple);

        Assert.Equal(429, StatusOf(again));
        Assert.Equal(1, estimator.Calls);
        Assert.Equal("food.scan_error", Assert.Single(await ScansAsync(couple)).ErrorCode);
    }

    [Fact]
    public async Task A_scan_the_phone_gave_up_on_still_counts()
    {
        var couple = await CoupleAsync();
        entitlements.Limits = new PlanLimits(1, 10_000, 7);
        estimator.Hold();
        using var abort = new CancellationTokenSource();
        var scan = ScanAsync(couple, abort.Token);
        while (estimator.Calls == 0) await Task.Delay(10);
        await abort.CancelAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => scan);
        estimator.Release();

        Assert.Equal(429, StatusOf(await ScanAsync(couple)));
        Assert.Equal("food.scan_cancelled", Assert.Single(await ScansAsync(couple)).ErrorCode);
    }

    [Fact]
    public async Task Not_food_counts_and_a_good_scan_reports_what_is_left()
    {
        var couple = await CoupleAsync();
        estimator.Next = () => FakeEstimator.Estimate(isFood: false);
        Assert.Equal(422, StatusOf(await ScanAsync(couple)));
        estimator.Next = null;

        var ok = Assert.IsType<OkObjectResult>(await ScanAsync(couple));

        Assert.Equal(1, Assert.IsType<FoodScanResultDto>(ok.Value).ScansRemainingToday);
        Assert.Equal(2, (await ScansAsync(couple)).Count);
    }
}
