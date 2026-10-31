using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Background;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Exercise;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Results;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// The free plan's one daily dare each, and the alerts that a limit is near (docs/billing.md): the rules on their own,
/// the dare limit through the real controller, and the alerts through the real worker on a real database.
/// </summary>
public sealed class PlanLimitAlertTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);

    public void Dispose() => database.Dispose();

    [Fact]
    public void One_dare_fits_on_free_and_any_number_on_plus()
    {
        Assert.True(DareLimits.Fits(1, 0));
        Assert.False(DareLimits.Fits(1, 1));
        Assert.True(DareLimits.Fits(null, 12));
    }

    [Fact]
    public void Over_the_limit_the_oldest_dares_keep_ringing()
    {
        var oldest = (Guid.NewGuid(), Now.AddDays(-9));
        var middle = (Guid.NewGuid(), Now.AddDays(-5));
        var newest = (Guid.NewGuid(), Now.AddDays(-1));

        Assert.Equal([middle.Item1, newest.Item1], DareLimits.ToPause(1, [newest, oldest, middle]));
        Assert.Empty(DareLimits.ToPause(null, [newest, oldest, middle]));
        Assert.Empty(DareLimits.ToPause(1, [oldest]));
    }

    [Theory]
    [InlineData(399, null)]
    [InlineData(400, 80)]
    [InlineData(474, 80)]
    [InlineData(475, 95)]
    [InlineData(600, 95)]
    public void The_album_is_told_at_80_and_95_percent(long used, int? level)
        => Assert.Equal(level, PlanAlerts.AlbumLevel(used, 500));

    [Fact]
    public void An_album_level_is_told_once_and_a_higher_one_again()
    {
        Assert.True(PlanAlerts.ShouldTellAlbum(80, []));
        Assert.False(PlanAlerts.ShouldTellAlbum(80, [80]));
        Assert.True(PlanAlerts.ShouldTellAlbum(95, [80]));
        Assert.False(PlanAlerts.ShouldTellAlbum(80, [95]));
    }

    [Fact]
    public void Plus_warns_only_when_it_ends_soon_without_renewing()
    {
        EntitlementDto Paid(bool renews, int days) => new(Plan.Plus, new BillingSettings().Plus,
            new SubscriptionSummaryDto("googlePlay", "heartpact_plus_monthly", StoreSubscriptionStatus.Canceled, Now.AddDays(days), renews, Guid.NewGuid(), true), true);

        Assert.Equal(Now.AddDays(2), PlanAlerts.PlusEndsSoon(Paid(renews: false, days: 2), Now));
        Assert.Null(PlanAlerts.PlusEndsSoon(Paid(renews: true, days: 2), Now));
        Assert.Null(PlanAlerts.PlusEndsSoon(Paid(renews: false, days: 10), Now));
        var gift = new EntitlementDto(Plan.Plus, new BillingSettings().Plus, null, true, Complimentary: true, ComplimentaryUntilUtc: Now.AddDays(1));
        Assert.Equal(Now.AddDays(1), PlanAlerts.PlusEndsSoon(gift, Now));
        Assert.Null(PlanAlerts.PlusEndsSoon(new EntitlementDto(Plan.Free, new BillingSettings().Free, null, true), Now));
    }

    [Fact]
    public void Sizes_read_like_people_say_them()
    {
        Assert.Equal("450 MB", PlanAlerts.Size(450L * 1024 * 1024));
        Assert.Equal("2 GB", PlanAlerts.Size(2L * 1024 * 1024 * 1024));
        Assert.Equal("1.5 GB", PlanAlerts.Size(1536L * 1024 * 1024));
    }

    private ExerciseController Exercise(ApplicationDbContext db, Guid userId, Guid coupleId, Plan plan)
        => new(db, new FakeCurrentUser(userId, coupleId), clock, new FakeHub(), new DisabledPlayIntegrityVerifier(),
            new FakeEntitlements(new BillingSettings().Limits(plan), plan), NullLogger<ExerciseController>.Instance);

    private static CreateExerciseGoalRequest Dare(Guid assignee, string at = "07:00") => new(assignee, ExerciseType.Squat, null, 20, at, "UTC");

    private static int StatusOf(ActionResult? result) => result switch
    {
        ObjectResult objectResult => objectResult.StatusCode ?? 200,
        StatusCodeResult status => status.StatusCode,
        _ => 0
    };

    private static string? CodeOf(ActionResult? result) => (result as ObjectResult)?.Value is ApiError error ? error.Code : null;

    [Fact]
    public async Task On_free_each_partner_has_one_daily_dare()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var maya = Exercise(db, couple.MayaId, couple.CoupleId, Plan.Free);

        Assert.Equal(200, StatusOf((await maya.CreateGoal(Dare(couple.TheoId), default)).Result));
        // Theo's slot is taken, but Maya's own is still free.
        var second = (await maya.CreateGoal(Dare(couple.TheoId, "19:00"), default)).Result;
        Assert.Equal(403, StatusOf(second));
        Assert.Equal("exercise.dare_limit", CodeOf(second));
        Assert.Equal(200, StatusOf((await maya.CreateGoal(Dare(couple.MayaId), default)).Result));
    }

    [Fact]
    public async Task On_plus_dares_are_unlimited()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var maya = Exercise(db, couple.MayaId, couple.CoupleId, Plan.Plus);

        foreach (var at in new[] { "07:00", "12:00", "19:00" })
            Assert.Equal(200, StatusOf((await maya.CreateGoal(Dare(couple.TheoId, at), default)).Result));
    }

    [Fact]
    public async Task On_free_resuming_a_paused_dare_needs_a_free_slot()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var running = ExerciseGoal.Create(couple.CoupleId, couple.TheoId, couple.MayaId, ExerciseType.Squat, null, 20, new TimeOnly(7, 0), "UTC", 30, Now.AddDays(-3));
        var paused = ExerciseGoal.Create(couple.CoupleId, couple.TheoId, couple.MayaId, ExerciseType.PushUp, null, 10, new TimeOnly(19, 0), "UTC", 30, Now.AddDays(-2));
        paused.Deactivate(Now.AddDays(-1));
        db.AddRange(running, paused);
        await db.SaveChangesAsync();
        var maya = Exercise(db, couple.MayaId, couple.CoupleId, Plan.Free);
        var resume = new UpdateExerciseGoalRequest(couple.TheoId, ExerciseType.PushUp, null, 10, "19:00", "UTC", 30, IsActive: true);

        Assert.Equal("exercise.dare_limit", CodeOf((await maya.UpdateGoal(paused.Id, resume, default)).Result));

        // Pausing the running one frees the slot.
        var pause = new UpdateExerciseGoalRequest(couple.TheoId, ExerciseType.Squat, null, 20, "07:00", "UTC", 30, IsActive: false);
        Assert.Equal(200, StatusOf((await maya.UpdateGoal(running.Id, pause, default)).Result));
        Assert.Equal(200, StatusOf((await maya.UpdateGoal(paused.Id, resume, default)).Result));
    }

    private PlanAlertsWorker Worker()
    {
        var services = new ServiceCollection();
        services.AddScoped<IApplicationDbContext>(_ => database.CreateContext());
        var provider = services.BuildServiceProvider();
        return new PlanAlertsWorker(provider.GetRequiredService<IServiceScopeFactory>(), new FakeHub(), clock,
            new BillingSettings { Free = new PlanLimits(3, 1000, 7, 1) }, NullLogger<PlanAlertsWorker>.Instance);
    }

    private async Task<List<Notification>> NotificationsAsync(NotificationType type)
    {
        await using var db = database.CreateContext();
        return await db.Notifications.AsNoTracking().Where(item => item.Type == type).ToListAsync();
    }

    private async Task AddPhotoAsync(TestCouple couple, long bytes)
    {
        await using var db = database.CreateContext();
        var memory = Memory.Reserve(couple.CoupleId, couple.MayaId, Twogether.Core.Memories.MemoryRules.Kind("image/jpeg")!, bytes, null, null, MemorySource.Camera, Now);
        memory.MarkReady(bytes, Now);
        db.Add(memory);
        await db.SaveChangesAsync();
    }

    [Fact]
    public async Task A_filling_album_tells_both_partners_once_per_level()
    {
        TestCouple couple;
        await using (var db = database.CreateContext()) couple = await TestCouple.AddAsync(db);
        var worker = Worker();

        await AddPhotoAsync(couple, 700);
        Assert.Equal(0, await worker.RunAsync(default));

        await AddPhotoAsync(couple, 150); // 85%
        Assert.Equal(2, await worker.RunAsync(default));
        Assert.Equal(0, await worker.RunAsync(default));

        await AddPhotoAsync(couple, 120); // 97%
        Assert.Equal(2, await worker.RunAsync(default));
        var told = await NotificationsAsync(NotificationType.AlbumAlmostFull);
        Assert.Equal(4, told.Count);
        Assert.Equal(2, told.Count(item => item.UserId == couple.MayaId));
        Assert.Equal(2, told.Count(item => item.UserId == couple.TheoId));
        Assert.Contains(told, item => item.Title == "Your shared album is almost full");
    }

    [Fact]
    public async Task A_complimentary_plus_running_out_is_told_three_days_ahead()
    {
        TestCouple couple;
        await using (var db = database.CreateContext())
        {
            couple = await TestCouple.AddAsync(db);
            db.Add(PlusGrant.Create(couple.CoupleId, Now.AddDays(5), "thank you", Guid.NewGuid(), Now.AddDays(-25)));
            await db.SaveChangesAsync();
        }
        var worker = Worker();

        Assert.Equal(0, await worker.RunAsync(default));
        clock.UtcNow = Now.AddDays(3);
        Assert.Equal(2, await worker.RunAsync(default));
        Assert.Equal(0, await worker.RunAsync(default));
        Assert.All(await NotificationsAsync(NotificationType.PlusEnding), item => Assert.Equal("Heartpact Plus ends soon", item.Title));
    }
}
