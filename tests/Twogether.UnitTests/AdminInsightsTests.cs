using Twogether.Application.Features.Admin;
using Twogether.Application.Features.Admin.Insights;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>The admin insight queries on a real (SQLite) database: what each page computes, and who it leaves out.</summary>
public sealed class AdminInsightsTests : IDisposable
{
    /// <summary>A Wednesday; its week began on Monday 26 October.</summary>
    private static readonly DateTime Now = new(2026, 10, 28, 12, 0, 0, DateTimeKind.Utc);
    private const string AdminEmail = "ops@heartpact.me";

    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);
    private readonly AdminSettings settings = new() { Emails = ["Ops@Heartpact.me"] };
    private readonly BillingSettings billing = new() { Free = new PlanLimits(3, 1000, 7) };

    public void Dispose() => database.Dispose();

    private static DateTime At(int month, int day, int hour = 0, int minute = 0) => new(2026, month, day, hour, minute, 0, DateTimeKind.Utc);

    private async Task SeedAsync(Action<InsightsData> build)
    {
        await using var db = database.CreateContext();
        var data = new InsightsData(db);
        build(data);
        await data.SaveAsync();
    }

    private async Task<T> QueryAsync<T>(Func<AdminInsights, Task<T>> query)
    {
        await using var db = database.CreateContext();
        return await query(new AdminInsights(db, clock, settings, billing));
    }

    /// <summary>A completed dare that ends exactly at <paramref name="completedAtUtc"/> (rings 3 minutes before).</summary>
    private static void DoneAt(InsightsData data, Twogether.Core.Entities.ExerciseGoal goal, DateTime completedAtUtc)
        => data.Dare(goal, completedAtUtc.AddMinutes(-3), ExerciseOccurrenceStatus.Completed, TimeSpan.FromMinutes(2), TimeSpan.FromMinutes(1));

    [Fact]
    public async Task Funnel_follows_each_new_person_in_order_with_median_hours_between_steps()
    {
        var t0 = At(10, 18, 12);
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", t0);
            var theo = data.Person("Theo", t0.AddHours(1));
            data.Person("Zed", t0);
            data.Person("Old", Now.AddDays(-40));
            var tester = data.Person("Tester", t0, internalAccount: true);
            data.Invite(data.Link(tester, data.Person("Admin", t0, email: AdminEmail), t0), tester, t0);

            var couple = data.Link(maya, theo, t0.AddHours(5));
            data.Invite(couple, maya, t0.AddHours(2));
            var forTheo = data.Goal(couple, theo, maya, t0.AddHours(6));
            DoneAt(data, forTheo, t0.AddHours(30));
            var forMaya = data.Goal(couple, maya, theo, t0.AddHours(31));
            DoneAt(data, forMaya, t0.AddHours(40));
            data.ActiveOn(maya, new DateOnly(2026, 10, 26)); // day 9 after signing up
            data.Subscription(maya, At(10, 27), StoreSubscriptionStatus.Active, At(11, 27));
        });

        var funnel = await QueryAsync(insights => insights.FunnelAsync(30, default));

        Assert.Equal(AdminInsights.FunnelKeys, funnel.Steps.Select(step => step.Key));
        Assert.Equal([3, 2, 2, 2, 2, 2, 1, 1], funnel.Steps.Select(step => step.Count));
        Assert.Equal(new double?[] { null, 3, 1.5, 1, 24, 10, 140, 24 }, funnel.Steps.Select(step => step.MedianHoursFromPrevious));
    }

    [Fact]
    public async Task Retention_counts_couples_by_link_week_with_future_weeks_null()
    {
        await SeedAsync(data =>
        {
            var a1 = data.Person("A1", At(10, 1));
            var a2 = data.Person("A2", At(10, 1));
            var first = data.Link(a1, a2, At(10, 6, 9));
            data.ActiveOn(a1, new DateOnly(2026, 10, 14));
            data.Game(first, GameType.TicTacToe, At(10, 21, 20), GameSessionStatus.Completed); // the couple's, in week 2
            data.Meal(first, a2, At(10, 27, 13));

            var b1 = data.Person("B1", At(10, 1));
            var b2 = data.Person("B2", At(10, 1));
            var second = data.Link(b1, b2, At(10, 7, 9));
            second.Dissolve(At(10, 13));
            data.ActiveOn(b1, new DateOnly(2026, 10, 15)); // after they ended: doesn't count

            data.Link(data.Person("C1", At(10, 26)), data.Person("C2", At(10, 26)), At(10, 26, 10));
            data.Link(data.Person("T1", At(10, 1), internalAccount: true), data.Person("T2", At(10, 1)), At(10, 6));
        });

        var retention = await QueryAsync(insights => insights.RetentionAsync(4, default));

        Assert.Equal(4, retention.Weeks);
        Assert.Equal(["2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26"], retention.Cohorts.Select(cohort => cohort.WeekStart));
        Assert.Equal([2, 0, 0, 1], retention.Cohorts.Select(cohort => cohort.Size));
        Assert.Equal(new double?[] { 1, 0.5, 0.5, 0.5 }, retention.Cohorts[0].Retained);
        Assert.All(retention.Cohorts[1].Retained, Assert.Null);
        Assert.Equal(new double?[] { 1, null, null, null }, retention.Cohorts[3].Retained);
    }

    [Fact]
    public async Task Rhythm_puts_actions_dares_and_rings_in_weekday_hour_buckets()
    {
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(9, 1));
            var theo = data.Person("Theo", At(9, 1));
            var couple = data.Link(maya, theo, At(9, 2));
            var goal = data.Goal(couple, theo, maya, At(9, 3));
            data.Dare(goal, At(10, 26, 8), ExerciseOccurrenceStatus.Completed, TimeSpan.FromMinutes(2)); // Monday 08:00
            data.Dare(goal, At(10, 27, 7), ExerciseOccurrenceStatus.Completed, onCamera: TimeSpan.FromMinutes(1), early: true); // opened Tuesday 06:00
            data.Dare(data.Goal(couple, maya, theo, At(9, 3)), At(10, 27, 9), ExerciseOccurrenceStatus.Missed); // rang Tuesday 09:00, never started
            data.Meal(couple, maya, At(10, 25, 19, 30)); // Sunday
            data.Game(couple, GameType.TicTacToe, At(10, 24, 20, 50), GameSessionStatus.Completed, TimeSpan.FromMinutes(10)); // Saturday 21:00

            var tester = data.Person("Tester", At(9, 1), internalAccount: true);
            data.Meal(couple, tester, At(10, 25, 19));
        });

        var rhythm = await QueryAsync(insights => insights.RhythmAsync(30, default));

        Assert.Equal(6, rhythm.Actions.Sum(row => row.Sum()));
        Assert.Equal(2, rhythm.Actions[0][8]);
        Assert.Equal(2, rhythm.Actions[1][6]);
        Assert.Equal(1, rhythm.Actions[6][19]);
        Assert.Equal(1, rhythm.Actions[5][21]);
        Assert.Equal(2, rhythm.DaresDone.Sum(row => row.Sum()));
        Assert.Equal(1, rhythm.DaresDone[0][8]);
        Assert.Equal(1, rhythm.DaresDone[1][6]);
        Assert.Equal(2, rhythm.Rings.Sum(row => row.Sum()));
        Assert.Equal(1, rhythm.Rings[0][8]);
        Assert.Equal(1, rhythm.Rings[1][9]);
        Assert.All(rhythm.Actions, row => Assert.Equal(24, row.Length));
        Assert.Equal(7, rhythm.Rings.Length);
    }

    [Fact]
    public async Task Dare_insights_time_the_alarm_the_camera_and_the_checks()
    {
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(9, 1));
            var theo = data.Person("Theo", At(9, 1));
            var couple = data.Link(maya, theo, At(9, 2));
            var squats = data.Goal(couple, theo, maya, At(9, 3), ExerciseType.Squat, 15);
            data.Dare(squats, At(10, 20, 8), ExerciseOccurrenceStatus.Completed, TimeSpan.FromSeconds(60), TimeSpan.FromSeconds(120), 0.8);
            data.Dare(squats, At(10, 21, 8), ExerciseOccurrenceStatus.Completed, TimeSpan.FromSeconds(180), TimeSpan.FromSeconds(60), 0.6, new ExerciseCheckCounts(0, 0, 1, 1, 0, 0));
            data.Dare(squats, At(10, 22, 8), ExerciseOccurrenceStatus.Missed);
            data.Dare(squats, At(10, 23, 8), ExerciseOccurrenceStatus.Cancelled);
            data.Dare(squats, At(10, 24, 8), ExerciseOccurrenceStatus.Completed, onCamera: TimeSpan.FromSeconds(30), confidence: 1.0, integrity: ExerciseIntegrity.Missing, early: true);
            var plank = data.Goal(couple, maya, theo, At(9, 3), ExerciseType.Plank, 30);
            data.Dare(plank, At(10, 25, 8), ExerciseOccurrenceStatus.Missed);

            // A couple on a three-day streak, and one with no dares at all.
            var cara = data.Person("Cara", At(9, 1));
            var dan = data.Person("Dan", At(9, 1));
            var streaking = data.Link(cara, dan, At(9, 2));
            var pushUps = data.Goal(streaking, dan, cara, At(9, 3), ExerciseType.PushUp, 15);
            foreach (var day in new[] { 26, 27, 28 }) data.Dare(pushUps, At(10, day, 8), ExerciseOccurrenceStatus.Completed);
            data.Link(data.Person("Eli", At(9, 1)), data.Person("Fay", At(9, 1)), At(9, 2));

            var tester = data.Person("Tester", At(9, 1), internalAccount: true);
            var testers = data.Link(tester, data.Person("Partner", At(9, 1)), At(9, 2));
            data.Dare(data.Goal(testers, tester, tester, At(9, 3)), At(10, 26, 8), ExerciseOccurrenceStatus.Completed);
        });

        var dares = await QueryAsync(insights => insights.DaresAsync(30, default));

        var squat = Assert.Single(dares.Exercises, item => item.Exercise == "squat");
        Assert.Equal((5, 3, 1, 1), (squat.Set, squat.Completed, squat.Missed, squat.CalledOff));
        Assert.Equal(0.75, squat.CompletionRate);
        Assert.Equal(120, squat.MedianSecondsToStart);
        Assert.Equal(60, squat.MedianSecondsOnCamera);
        Assert.Equal(0.8, squat.AverageConfidence);
        Assert.Equal(2, squat.ChecksFailed);
        var plankRow = Assert.Single(dares.Exercises, item => item.Exercise == "plank");
        Assert.Equal(0, plankRow.CompletionRate);
        Assert.Null(plankRow.AverageConfidence);
        Assert.Null(plankRow.MedianSecondsToStart);
        Assert.Equal(1, dares.EarlyStarts);
        Assert.Equal(15, dares.MedianRepsTarget);
        Assert.Equal(InsightsMath.StreakBuckets, dares.Streaks.Select(bucket => bucket.Length));
        Assert.Equal([2, 0, 1, 0, 0, 0], dares.Streaks.Select(bucket => bucket.Couples));
    }

    [Fact]
    public async Task Game_insights_bucket_skill_scores_and_measure_duels()
    {
        Guid mayaId = default, theoId = default;
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(9, 1));
            var theo = data.Person("Theo", At(9, 1));
            (mayaId, theoId) = (maya.Id, theo.Id);
            var couple = data.Link(maya, theo, At(9, 2));
            data.Game(couple, GameType.SkillDuel, At(10, 20), GameSessionStatus.Completed, stateJson: InsightsData.Duel(maya.Id, theo.Id, [50, 60, 70, 80, 100], [55, 60, 65, 80, 95]));
            data.Game(couple, GameType.SkillDuel, At(10, 21), GameSessionStatus.Completed, stateJson: InsightsData.Duel(maya.Id, theo.Id, [10, 20, 30, 40, 50], [50, 40, 30, 20, 10]));
            data.Game(couple, GameType.SkillDuel, At(10, 22), GameSessionStatus.InProgress, stateJson: InsightsData.Duel(maya.Id, theo.Id, [5, null, null, null, null], [null, null, null, null, null]));
            data.Game(couple, GameType.TicTacToe, At(10, 23), GameSessionStatus.Completed, TimeSpan.FromMinutes(10));
            data.Game(couple, GameType.TicTacToe, At(10, 24), GameSessionStatus.Completed, TimeSpan.FromMinutes(20));
            data.Game(couple, GameType.TicTacToe, At(10, 25), GameSessionStatus.Abandoned);
            data.Game(couple, GameType.TicTacToe, Now.AddDays(-40), GameSessionStatus.Completed);
        });

        var games = await QueryAsync(insights => insights.GamesAsync(30, default));

        var ticTacToe = Assert.Single(games.Types, item => item.Type == "ticTacToe");
        Assert.Equal((3, 2, 1, 15.0), (ticTacToe.Started, ticTacToe.Finished, ticTacToe.Abandoned, ticTacToe.MedianMinutes));
        var duelType = Assert.Single(games.Types, item => item.Type == "skillDuel");
        Assert.Equal((3, 2, 0), (duelType.Started, duelType.Finished, duelType.Abandoned));

        Assert.Equal(SkillDuelRules.Kinds, games.Skills.Select(skill => skill.Kind));
        Assert.Equal(21, games.Skills.Sum(skill => skill.Rounds));
        Assert.All(games.Skills, skill => Assert.Equal(skill.Rounds, skill.Buckets.Sum()));
        Assert.Equal(2, games.Skills.Sum(skill => skill.Buckets[9])); // 95 and a perfect 100
        var firstKind = SkillDuelRules.Plan(7)[0].Kind;
        var first = Assert.Single(games.Skills, skill => skill.Kind == firstKind);
        Assert.Equal(5, first.Rounds);
        Assert.Equal(34, first.AverageScore); // 50, 55, 10, 50, 5
        // Five kinds played; every other kind is listed, empty.
        var unplayed = games.Skills.Where(skill => SkillDuelRules.Plan(7).All(round => round.Kind != skill.Kind)).ToList();
        Assert.Equal(SkillDuelRules.Kinds.Count - SkillDuelRules.Rounds, unplayed.Count);
        Assert.All(unplayed, skill => Assert.Equal(0, skill.Rounds));
        Assert.All(unplayed, skill => Assert.Null(skill.AverageScore));

        // Hearts 4 to 3 (a margin of one), then 3 each on 150 points each: a tie, a margin of none.
        Assert.Equal(new DuelDto(2, 1, 0.5), games.Duels);
    }

    [Fact]
    public async Task Growth_follows_new_people_to_plus_and_counts_renewals()
    {
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(8, 1), "US");
            var theo = data.Person("Theo", At(8, 1), "GB");
            var couple = data.Link(maya, theo, At(8, 2));
            data.Subscription(maya, At(8, 3), StoreSubscriptionStatus.Active, At(11, 10)); // renewed on 10 October
            data.Scans(theo, couple, At(9, 5, 9), 3);
            data.Photo(couple, maya, At(9, 6), 900); // 90% of the Free album

            var ana = data.Person("Ana", At(10, 20), "FR");
            data.Subscription(ana, At(10, 20), StoreSubscriptionStatus.Active, At(11, 20)); // still its first month
            data.Scans(ana, couple, At(10, 21, 9), 2); // under the limit

            var cara = data.Person("Cara", At(10, 1), "US");
            var dan = data.Person("Dan", At(10, 1), "US");
            var given = data.Link(cara, dan, At(10, 2));
            data.Grant(given, At(10, 5), At(11, 20));

            var bob = data.Person("Bob", At(1, 1), "US");
            data.Subscription(bob, At(1, 2), StoreSubscriptionStatus.Expired, At(10, 1)); // lapsed
            data.Subscription(bob, At(10, 2), StoreSubscriptionStatus.Canceled, At(11, 2), autoRenewing: false); // ends in 5 days

            var ivy = data.Person("Ivy", At(10, 1), "US", internalAccount: true);
            data.Subscription(ivy, At(8, 1), StoreSubscriptionStatus.Active, At(11, 15));
        });

        var growth = await QueryAsync(insights => insights.GrowthAsync(90, default));

        Assert.Equal((1, 2), (growth.EndingIn7Days, growth.EndingIn30Days));
        Assert.Equal((1, 1, 0.5), (growth.Renewed, growth.Lapsed, growth.RenewalRate));
        Assert.Equal(["signedUp", "linked", "hitScanLimit", "nearAlbumLimit", "plus"], growth.Path.Select(step => step.Key));
        Assert.Equal([5, 4, 1, 2, 5], growth.Path.Select(step => step.Count));
        Assert.Equal(2, growth.MedianDaysToPlus); // 2, 2, 0, 4, 4
        // Plus couples now, in each partner's country: Maya (US) and Theo (GB), Cara and Dan (US). Ana has no couple.
        Assert.Equal([new NamedCount("US", 2), new NamedCount("GB", 1)], growth.PlusByCountry);
    }

    [Fact]
    public async Task Renewal_rate_is_null_when_nothing_was_due()
    {
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(10, 20));
            data.Subscription(maya, At(10, 20), StoreSubscriptionStatus.Active, At(11, 20));
        });

        var growth = await QueryAsync(insights => insights.GrowthAsync(30, default));

        Assert.Equal((0, 0), (growth.Renewed, growth.Lapsed));
        Assert.Null(growth.RenewalRate);
        Assert.Equal(0, growth.MedianDaysToPlus);
    }

    [Fact]
    public async Task World_counts_a_couple_once_in_each_partners_country()
    {
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(9, 1), "US");
            var theo = data.Person("Theo", At(9, 1), "GB");
            var mixed = data.Link(maya, theo, At(9, 2));
            data.Grant(mixed, At(9, 3), At(12, 1));
            data.ActiveOn(maya, new DateOnly(2026, 10, 27));

            var cara = data.Person("Cara", At(9, 1), "US");
            var dan = data.Person("Dan", At(9, 1), "US");
            var american = data.Link(cara, dan, At(9, 2));
            data.Meal(american, dan, At(10, 26, 12));

            var ana = data.Person("Ana", At(9, 1), "FR");
            data.ActiveOn(ana, new DateOnly(2026, 10, 18));
            data.Person("Eli", At(9, 1));
            data.Person("Ivy", At(9, 1), "US", internalAccount: true);
            data.Person("Ops", At(9, 1), "US", email: AdminEmail);
        });

        var world = await QueryAsync(insights => insights.WorldAsync(default));

        Assert.Equal(
            [new CountryDto("US", 3, 2, 1, 2), new CountryDto("FR", 1, 0, 0, 0), new CountryDto("GB", 1, 1, 1, 0)],
            world.Countries);
        Assert.Equal(1, world.Unknown);
    }

    [Fact]
    public async Task Top_couples_rank_by_hearts_then_games()
    {
        Guid first = default, second = default;
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(9, 1));
            var theo = data.Person("Theo", At(9, 1));
            var leaders = data.Link(maya, theo, At(9, 2));
            first = leaders.Id;
            var goal = data.Goal(leaders, theo, maya, At(10, 10));
            foreach (var day in new[] { 26, 27, 28 }) data.Dare(goal, At(10, day, 8), ExerciseOccurrenceStatus.Completed);
            data.Grant(leaders, At(10, 1), At(12, 1));

            var cara = data.Person("Cara", At(9, 1));
            var dan = data.Person("Dan", At(9, 1));
            var players = data.Link(cara, dan, At(9, 3));
            second = players.Id;
            data.Dare(data.Goal(players, dan, cara, At(9, 4)), At(10, 20, 8), ExerciseOccurrenceStatus.Completed);
            data.Game(players, GameType.TicTacToe, At(10, 21), GameSessionStatus.Completed);
            data.Game(players, GameType.TicTacToe, At(10, 22), GameSessionStatus.Completed);

            var eli = data.Person("Eli", At(9, 1));
            var fay = data.Person("Fay", At(9, 1));
            var setters = data.Link(eli, fay, At(9, 3));
            data.Dare(data.Goal(setters, fay, eli, At(10, 15)), At(10, 20, 8), ExerciseOccurrenceStatus.Completed);
            data.Goal(setters, eli, fay, At(10, 16));

            var tester = data.Person("Tester", At(9, 1), internalAccount: true);
            var testers = data.Link(tester, data.Person("Partner", At(9, 1)), At(9, 2));
            var testGoal = data.Goal(testers, tester, tester, At(10, 1));
            foreach (var day in Enumerable.Range(10, 10)) data.Dare(testGoal, At(10, day, 8), ExerciseOccurrenceStatus.Completed);
        });

        var top = await QueryAsync(insights => insights.TopCouplesAsync(30, 2, default));

        Assert.Equal([first, second], top.Select(couple => couple.CoupleId));
        Assert.Equal(["Maya", "Theo"], top[0].Names);
        Assert.Equal((3, 1, 0, 3, true), (top[0].Hearts, top[0].DaresSet, top[0].Games, top[0].Streak, top[0].Plus));
        Assert.Equal((1, 0, 2, false), (top[1].Hearts, top[1].DaresSet, top[1].Games, top[1].Plus));
        Assert.Equal(At(9, 2), top[0].LinkedAtUtc);
        Assert.Equal(DateTimeKind.Utc, top[0].LinkedAtUtc.Kind);
    }

    [Fact]
    public async Task Existing_overview_engagement_and_revenue_leave_out_internal_and_admin_accounts()
    {
        await SeedAsync(data =>
        {
            var maya = data.Person("Maya", At(10, 20));
            var theo = data.Person("Theo", At(10, 20));
            var couple = data.Link(maya, theo, At(10, 21));
            data.Dare(data.Goal(couple, theo, maya, At(10, 21)), At(10, 25, 8), ExerciseOccurrenceStatus.Completed);
            data.Subscription(maya, At(10, 21), StoreSubscriptionStatus.Active, At(11, 21));

            var tester = data.Person("Tester", At(10, 20), internalAccount: true);
            var admin = data.Person("Ops", At(10, 20), email: AdminEmail);
            var testers = data.Link(tester, admin, At(10, 21));
            data.Dare(data.Goal(testers, tester, admin, At(10, 21)), At(10, 25, 8), ExerciseOccurrenceStatus.Completed);
            data.Subscription(tester, At(10, 21), StoreSubscriptionStatus.Active, At(11, 21));
            data.ActiveOn(tester, new DateOnly(2026, 10, 28));
            data.Grant(testers, At(10, 22), At(12, 1));
        });

        await using var db = database.CreateContext();
        var analytics = new AdminAnalytics(db, clock, settings);
        var overview = await analytics.OverviewAsync(30, default);
        var engagement = await analytics.EngagementAsync(30, default);
        var revenue = await analytics.RevenueAsync(30, default);

        Assert.Equal((2, 2, 1, 0, 1), (overview.TotalUsers, overview.NewUsers, overview.ActiveCouples, overview.ActiveToday, overview.PlusCouples));
        Assert.Equal(1, engagement.DaresCompleted);
        Assert.Equal((1, 0), (revenue.PayingCouples, revenue.ComplimentaryCouples));
        Assert.Equal(1, revenue.ActiveByProduct.Sum(item => item.Count));
    }

    [Fact]
    public void Medians_buckets_and_bounds()
    {
        Assert.Null(InsightsMath.Median([]));
        Assert.Equal(2, InsightsMath.Median([3, 1, 2]));
        Assert.Equal(2.5, InsightsMath.Median([4, 1, 2, 3]));
        Assert.Equal(15, InsightsMath.WeightedMedian([(15, 3), (40, 2)]));
        Assert.Equal(27.5, InsightsMath.WeightedMedian([(15, 2), (40, 2)]));
        Assert.Null(InsightsMath.WeightedMedian([(15, 0)]));
        Assert.Equal(["0", "1-2", "3-6", "7-13", "14-29", "30+"], new[] { 0, 2, 3, 13, 29, 30 }.Select(InsightsMath.StreakBucket));
        Assert.Equal([0, 0, 9, 9, 5], new[] { 0, 9, 90, 100, 59 }.Select(InsightsMath.ScoreBucket));
        Assert.Equal((1, 365, 1, 26, 1, 50), (InsightsMath.ClampDays(0), InsightsMath.ClampDays(9999), InsightsMath.ClampWeeks(-3), InsightsMath.ClampWeeks(99), InsightsMath.ClampLimit(0), InsightsMath.ClampLimit(500)));
        Assert.Null(InsightsMath.Rate(0, 0));
        Assert.Equal(new DateOnly(2026, 10, 26), InsightsMath.WeekStart(new DateOnly(2026, 11, 1)));
        Assert.Equal(TimeSpan.FromDays(366), AdminInsights.Period("heartpact_plus_yearly"));
    }
}
