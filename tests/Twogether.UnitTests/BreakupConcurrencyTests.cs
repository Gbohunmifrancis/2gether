using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Account;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// Breakup and account deletion racing another writer (the alarm worker opening a dare, the
/// partner deleting their account): one fresh retry, then a clean 409, never a 500.
/// </summary>
public sealed class BreakupConcurrencyTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    // What the alarm worker does to a ringing dare in the same moment.
    private const string WorkerTouchesDares = "UPDATE exercise_occurrences SET \"StateVersion\" = \"StateVersion\" + 1";
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);
    private readonly FakeHub hub = new();

    public void Dispose() => database.Dispose();

    /// <summary>A couple where both partners have a dare ringing right now.</summary>
    private async Task<TestCouple> CoupleWithRingingDaresAsync()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        foreach (var (assigned, setter) in new[] { (couple.MayaId, couple.TheoId), (couple.TheoId, couple.MayaId) })
        {
            var goal = ExerciseGoal.Create(couple.CoupleId, assigned, setter, ExerciseType.Squat, null, 15, new TimeOnly(12, 0), "UTC", 30, Now);
            db.ExerciseGoals.Add(goal);
            db.ExerciseOccurrences.Add(ExerciseOccurrence.Open(goal, DateOnly.FromDateTime(Now), Now, Now));
        }
        await db.SaveChangesAsync();
        return couple;
    }

    private CoupleController Controller(ApplicationDbContext db, TestCouple couple)
        => new(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, null!, hub, new AttemptLimiter(clock), null!, NullLogger<CoupleController>.Instance);

    private async Task<Couple> CoupleRowAsync(TestCouple couple)
    {
        await using var db = database.CreateContext();
        return await db.Couples.AsNoTracking().SingleAsync(item => item.Id == couple.CoupleId);
    }

    [Fact]
    public async Task Breakup_racing_the_alarm_worker_retries_from_fresh_state()
    {
        var couple = await CoupleWithRingingDaresAsync();
        var worker = new InterferingWriter(WorkerTouchesDares);
        await using var db = database.CreateContext(worker);

        Assert.IsType<NoContentResult>(await Controller(db, couple).BreakUp(default));

        Assert.Equal(1, worker.Interfered);
        Assert.Equal(CoupleStatus.Dissolved, (await CoupleRowAsync(couple)).Status);
        await using var check = database.CreateContext();
        Assert.All(await check.ExerciseOccurrences.ToListAsync(), item => Assert.Equal(ExerciseOccurrenceStatus.Cancelled, item.Status));
        // One breakup notification for Theo, not two.
        Assert.Single(await check.Notifications.Where(item => item.UserId == couple.TheoId).ToListAsync());
    }

    [Fact]
    public async Task Breakup_that_keeps_losing_the_race_answers_409_and_changes_nothing()
    {
        var couple = await CoupleWithRingingDaresAsync();
        await using var db = database.CreateContext(new InterferingWriter(WorkerTouchesDares, everySave: true));

        var result = await Controller(db, couple).BreakUp(default);

        Assert.Equal("couple.breakup_conflict", Assert.IsType<ApiError>(Assert.IsType<ConflictObjectResult>(result).Value).Code);
        Assert.Equal(CoupleStatus.Active, (await CoupleRowAsync(couple)).Status);
        Assert.Empty(hub.Sent);
    }

    [Fact]
    public async Task Cancelled_dares_after_a_breakup_carry_their_owners_names()
    {
        var couple = await CoupleWithRingingDaresAsync();
        await using var db = database.CreateContext();

        Assert.IsType<NoContentResult>(await Controller(db, couple).BreakUp(default));

        var cancelled = hub.Payloads(ExerciseEvents.Cancelled).Cast<ExerciseOccurrenceDto>().ToList();
        Assert.Equal(2, cancelled.Count);
        Assert.Equal("Maya", cancelled.Single(item => item.UserId == couple.MayaId).UserName);
        Assert.Equal("Theo", cancelled.Single(item => item.UserId == couple.TheoId).UserName);
    }

    [Fact]
    public async Task Account_deletion_racing_another_writer_retries_from_fresh_state()
    {
        var couple = await CoupleWithRingingDaresAsync();
        var worker = new InterferingWriter(WorkerTouchesDares);
        await using var db = database.CreateContext(worker);

        var erased = await new AccountEraser(db, clock).EraseAsync(couple.MayaId, default);

        Assert.NotNull(erased);
        Assert.Equal(1, worker.Interfered);
        Assert.Equal("Theo", erased.FormerPartnerName);
        await using var check = database.CreateContext();
        Assert.False(await check.Users.AnyAsync(item => item.Id == couple.MayaId));
        Assert.Null((await check.Users.SingleAsync(item => item.Id == couple.TheoId)).CoupleId);
    }

    [Fact]
    public async Task Account_deletion_that_keeps_losing_the_race_answers_409_and_deletes_nothing()
    {
        var couple = await CoupleWithRingingDaresAsync();
        await using var db = database.CreateContext(new InterferingWriter(WorkerTouchesDares, everySave: true));
        var maya = await db.Users.AsNoTracking().SingleAsync(item => item.Id == couple.MayaId);
        var controller = new AccountController(db, null!, new FakeCurrentUser(couple.MayaId, couple.CoupleId), new AccountEraser(db, clock), null!, hub, new AttemptLimiter(clock));

        var result = await controller.Delete(new DeleteAccountRequest(null, maya.Email), default);

        Assert.Equal("account.delete_conflict", Assert.IsType<ApiError>(Assert.IsType<ConflictObjectResult>(result).Value).Code);
        await using var check = database.CreateContext();
        Assert.True(await check.Users.AnyAsync(item => item.Id == couple.MayaId));
        Assert.Equal(CoupleStatus.Active, (await CoupleRowAsync(couple)).Status);
        Assert.Empty(hub.Sent);
    }

    [Fact]
    public async Task Cancelled_dares_after_an_account_deletion_carry_their_owners_names()
    {
        var couple = await CoupleWithRingingDaresAsync();
        await using var db = database.CreateContext();
        var erased = (await new AccountEraser(db, clock).EraseAsync(couple.MayaId, default))!;

        await AccountController.AnnounceErasureAsync(hub, erased, default);

        var cancelled = hub.Payloads(ExerciseEvents.Cancelled).Cast<ExerciseOccurrenceDto>().ToList();
        Assert.Equal("Maya", cancelled.Single(item => item.UserId == couple.MayaId).UserName);
        Assert.Equal("Theo", cancelled.Single(item => item.UserId == couple.TheoId).UserName);
    }
}
