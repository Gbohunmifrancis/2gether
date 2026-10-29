using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>What a partner is shown of the owner's cycle, and the one-off reset of levels left over from old breakups.</summary>
public sealed class CycleSharingDatabaseTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now; // 25 Oct 2026
    private static readonly DateOnly Today = DateOnly.FromDateTime(Now);
    private static readonly DateOnly LastStart = new(2026, 9, 23); // due 21 Oct: four days late today
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);

    public void Dispose() => database.Dispose();

    /// <summary>Maya owns the cycle (28/5, last start 23 Sep, flow logged for it) and shares <paramref name="level"/> with Theo.</summary>
    private async Task<TestCouple> MayaSharesAsync(ShareLevel level)
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var maya = await db.Users.SingleAsync(user => user.Id == couple.MayaId);
        maya.ClaimCycleOwnership(Now);
        var profile = CycleProfile.Create(maya.Id, Now);
        profile.SetCycleSettings(28, 5, LastStart, Now);
        profile.SetShareLevel(level, Now);
        db.CycleProfiles.Add(profile);
        db.CycleDayLogs.AddRange(Enumerable.Range(0, 5).Select(day => CycleDayLog.Create(maya.Id, LastStart.AddDays(day), FlowLevel.Medium, null, null, null, null, null, Now)));
        await db.SaveChangesAsync();
        return couple;
    }

    private async Task<SharedCycleDto> TheoSeesAsync(TestCouple couple)
    {
        await using var db = database.CreateContext();
        var result = await new CycleController(db, new FakeCurrentUser(couple.TheoId, couple.CoupleId), clock).GetShared(Today, default);
        return Assert.IsType<SharedCycleDto>(Assert.IsType<OkObjectResult>(result.Result).Value);
    }

    private async Task<CyclePredictionDto> MayaSeesAsync(TestCouple couple)
    {
        await using var db = database.CreateContext();
        var result = await new CycleController(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock).GetPrediction(Today, default);
        return Assert.IsType<CyclePredictionDto>(Assert.IsType<OkObjectResult>(result.Result).Value);
    }

    [Theory]
    [InlineData(ShareLevel.PredictionsOnly)]
    [InlineData(ShareLevel.FullDetail)]
    public async Task A_partner_shown_predictions_sees_the_same_late_period_as_the_owner(ShareLevel level)
    {
        var couple = await MayaSharesAsync(level);
        var mine = await MayaSeesAsync(couple);
        var shared = (await TheoSeesAsync(couple)).Prediction!;
        Assert.Equal((4, 33, new DateOnly(2026, 10, 21)), (shared.DaysLate, shared.CycleDay, shared.NextPeriodStart));
        Assert.Equal(mine, shared);
    }

    [Fact]
    public async Task A_partner_shown_nothing_gets_no_hint()
    {
        var couple = await MayaSharesAsync(ShareLevel.None);
        var shared = await TheoSeesAsync(couple);
        Assert.Null(shared.Profile);
        Assert.Null(shared.Prediction);
        Assert.Empty(shared.Logs);
    }

    [Fact]
    public async Task A_logged_period_clears_the_late_state_for_both()
    {
        var couple = await MayaSharesAsync(ShareLevel.PredictionsOnly);
        await using (var db = database.CreateContext())
        {
            db.CycleDayLogs.Add(CycleDayLog.Create(couple.MayaId, Today.AddDays(-1), FlowLevel.Light, null, null, null, null, null, Now));
            await db.SaveChangesAsync();
        }
        var shared = (await TheoSeesAsync(couple)).Prediction!;
        Assert.Equal(((int?)null, 2, Today.AddDays(-1)), (shared.DaysLate, shared.CycleDay, shared.CycleStart));
        Assert.Equal(await MayaSeesAsync(couple), shared);
    }

    [Fact]
    public async Task A_pregnancy_shared_with_the_couple_pauses_predictions_as_for_the_owner()
    {
        var couple = await MayaSharesAsync(ShareLevel.PredictionsOnly);
        await AddPregnancyAsync(couple, shareWithPartner: true);
        var empty = new CyclePredictionDto(null, null, null);
        Assert.Equal(empty, await MayaSeesAsync(couple));
        Assert.Equal(empty, (await TheoSeesAsync(couple)).Prediction);
    }

    [Fact]
    public async Task A_private_pregnancy_never_shows_the_partner_a_growing_late_count()
    {
        // The owner sees no predictions; the partner must see nothing different from an ordinary cycle, and a period
        // late by weeks would say what she chose not to. The forecast rolls forward instead, as before.
        var couple = await MayaSharesAsync(ShareLevel.PredictionsOnly);
        await AddPregnancyAsync(couple, shareWithPartner: false);
        var shared = (await TheoSeesAsync(couple)).Prediction!;
        Assert.Equal(((int?)null, 5, new DateOnly(2026, 10, 21)), (shared.DaysLate, shared.CycleDay, shared.CycleStart));
    }

    private async Task AddPregnancyAsync(TestCouple couple, bool shareWithPartner)
    {
        await using var db = database.CreateContext();
        db.Pregnancies.Add(Pregnancy.Start(couple.CoupleId, couple.MayaId, new DateOnly(2027, 6, 1), shareWithPartner, Now));
        await db.SaveChangesAsync();
    }

    // The one-off reset in DatabaseSchemaUpgrade.

    private static readonly DateTime Jan = new(2026, 1, 10, 9, 0, 0, DateTimeKind.Utc);
    private static readonly DateTime Mar = new(2026, 3, 10, 9, 0, 0, DateTimeKind.Utc);
    private static readonly DateTime Apr = new(2026, 4, 10, 9, 0, 0, DateTimeKind.Utc);
    private static readonly DateTime May = new(2026, 5, 10, 9, 0, 0, DateTimeKind.Utc);

    private static User Person(ApplicationDbContext db, string name)
    {
        var user = User.Create($"{name.ToLowerInvariant()}-{Guid.NewGuid():N}@example.com", name, new DateOnly(1995, 1, 1), Jan, cycleOwner: true, gender: Gender.Female);
        db.Users.Add(user);
        return user;
    }

    private static void Shares(ApplicationDbContext db, User user, ShareLevel level, DateTime savedAt)
    {
        var profile = CycleProfile.Create(user.Id, Jan);
        profile.SetCycleSettings(28, 5, new DateOnly(2026, 1, 1), Jan);
        profile.SetShareLevel(level, savedAt);
        db.CycleProfiles.Add(profile);
    }

    /// <summary>A couple of <paramref name="a"/> and someone new, linked at <paramref name="linkedAt"/> (January by default); ended at <paramref name="endedAt"/> when given.</summary>
    private static Couple Linked(ApplicationDbContext db, User a, DateTime? endedAt, DateTime? linkedAt = null)
    {
        var partner = Person(db, "Ex");
        var at = linkedAt ?? Jan;
        var couple = Couple.Create(a.Id, at);
        couple.LinkPartner(partner.Id, at);
        if (endedAt is { } ended) couple.Dissolve(ended);
        else { a.SetCouple(couple.Id, at); partner.SetCouple(couple.Id, at); }
        db.Couples.Add(couple);
        return couple;
    }

    [Fact]
    public async Task Old_breakups_stop_sharing_but_a_level_chosen_since_or_in_a_first_couple_stays()
    {
        Guid leftOver, leftOverInviting, choseWhileSingle, inheritedByNewPartner, firstCouple, choseInNewCouple, neverLinked, onlyAnInvite;
        await using (var db = database.CreateContext())
        {
            // Broke up in March, before the fix; the level is still the one her ex saw.
            var ana = Person(db, "Ana");
            Shares(db, ana, ShareLevel.FullDetail, Jan);
            Linked(db, ana, endedAt: Mar);
            leftOver = ana.Id;

            // The same, and now waiting on a new invite: the next partner would have inherited it.
            var bea = Person(db, "Bea");
            Shares(db, bea, ShareLevel.PredictionsOnly, Jan);
            Linked(db, bea, endedAt: Mar);
            var invite = Couple.Create(bea.Id, May);
            db.Couples.Add(invite);
            bea.SetCouple(invite.Id, May);
            leftOverInviting = bea.Id;

            // Broke up in March, then chose a level herself in May while single.
            var cara = Person(db, "Cara");
            Shares(db, cara, ShareLevel.PredictionsOnly, May);
            Linked(db, cara, endedAt: Mar);
            choseWhileSingle = cara.Id;

            // Broke up in March and linked with someone new in May: the new partner inherited her ex's level.
            var dee = Person(db, "Dee");
            Shares(db, dee, ShareLevel.FullDetail, Jan);
            Linked(db, dee, endedAt: Mar);
            Linked(db, dee, endedAt: null, linkedAt: May);
            inheritedByNewPartner = dee.Id;

            // Still with her first partner: nothing ended, nothing to undo.
            var gia = Person(db, "Gia");
            Shares(db, gia, ShareLevel.FullDetail, Jan);
            Linked(db, gia, endedAt: null);
            firstCouple = gia.Id;

            // Broke up in March, linked again in April, then chose a level for the new partner in May.
            var hana = Person(db, "Hana");
            Shares(db, hana, ShareLevel.PredictionsOnly, May);
            Linked(db, hana, endedAt: Mar);
            Linked(db, hana, endedAt: null, linkedAt: Apr);
            choseInNewCouple = hana.Id;

            // Never in a couple at all.
            var eve = Person(db, "Eve");
            Shares(db, eve, ShareLevel.PredictionsOnly, Jan);
            neverLinked = eve.Id;

            // Only ever an invite nobody accepted, closed in March: no one was ever shown anything.
            var fay = Person(db, "Fay");
            Shares(db, fay, ShareLevel.PredictionsOnly, Jan);
            var unanswered = Couple.Create(fay.Id, Jan);
            unanswered.Dissolve(Mar);
            db.Couples.Add(unanswered);
            onlyAnInvite = fay.Id;
            await db.SaveChangesAsync();
        }

        for (var boot = 0; boot < 2; boot++)
        {
            await using (var db = database.CreateContext())
                await db.Database.ExecuteSqlRawAsync(DatabaseSchemaUpgrade.StopCycleSharingAfterOldBreakupsSql);

            await using var check = database.CreateContext();
            var levels = await check.CycleProfiles.AsNoTracking().ToDictionaryAsync(profile => profile.UserId, profile => profile.ShareLevelWithPartner);
            Assert.Equal(ShareLevel.None, levels[leftOver]);
            Assert.Equal(ShareLevel.None, levels[leftOverInviting]);
            Assert.Equal(ShareLevel.PredictionsOnly, levels[choseWhileSingle]);
            Assert.Equal(ShareLevel.None, levels[inheritedByNewPartner]);
            Assert.Equal(ShareLevel.FullDetail, levels[firstCouple]);
            Assert.Equal(ShareLevel.PredictionsOnly, levels[choseInNewCouple]);
            Assert.Equal(ShareLevel.PredictionsOnly, levels[neverLinked]);
            Assert.Equal(ShareLevel.PredictionsOnly, levels[onlyAnInvite]);
        }
    }
}
