using Twogether.Application.Features.Admin;
using Twogether.Application.Features.Admin.Insights;
using Twogether.Application.Features.Billing;
using Twogether.Core.Enums;
using Twogether.UnitTests.Support;

namespace Twogether.IntegrationTests;

/// <summary>
/// The admin insight queries on PostgreSQL, which runs them in production: every one translates, and the parts the
/// providers do differently (UTC weekday and hour of a timestamptz, day grouping, COUNT DISTINCT, HAVING) agree with
/// SQLite under the unit tests.
/// </summary>
public sealed class AdminInsightsPostgresTests : IAsyncLifetime
{
    private static readonly DateTime Now = new(2026, 10, 28, 12, 0, 0, DateTimeKind.Utc);
    private readonly PostgresDatabase database = new();
    private readonly FakeClock clock = new(Now);
    private readonly AdminSettings settings = new() { Emails = ["ops@heartpact.me"] };
    private readonly BillingSettings billing = new() { Free = new PlanLimits(3, 1000, 7) };

    public Task InitializeAsync() => database.InitializeAsync();
    public Task DisposeAsync() => database.DisposeAsync();

    private static DateTime At(int month, int day, int hour = 0, int minute = 0) => new(2026, month, day, hour, minute, 0, DateTimeKind.Utc);

    [PostgresFact]
    public async Task Every_insight_runs_and_agrees_with_the_unit_tests()
    {
        await using (var db = database.CreateContext())
        {
            var data = new InsightsData(db);
            var maya = data.Person("Maya", At(10, 18, 12), "US");
            var theo = data.Person("Theo", At(10, 18, 13), "GB");
            var couple = data.Link(maya, theo, At(10, 18, 17));
            data.Invite(couple, maya, At(10, 18, 14));
            var forTheo = data.Goal(couple, theo, maya, At(10, 18, 18));
            data.Dare(forTheo, At(10, 26, 8), ExerciseOccurrenceStatus.Completed, TimeSpan.FromMinutes(2)); // Monday 08:00
            data.Dare(forTheo, At(10, 27, 7), ExerciseOccurrenceStatus.Completed, early: true); // Tuesday 06:00
            data.Dare(forTheo, At(10, 28, 8), ExerciseOccurrenceStatus.Completed, TimeSpan.FromMinutes(1));
            var forMaya = data.Goal(couple, maya, theo, At(10, 19));
            data.Dare(forMaya, At(10, 25, 9), ExerciseOccurrenceStatus.Missed);
            data.Meal(couple, maya, At(10, 25, 19, 30)); // Sunday
            data.Game(couple, GameType.TicTacToe, At(10, 24, 20, 50), GameSessionStatus.Completed); // Saturday 21:00
            data.Game(couple, GameType.SkillDuel, At(10, 22), GameSessionStatus.Completed, stateJson: InsightsData.Duel(maya.Id, theo.Id, [50, 60, 70, 80, 100], [55, 60, 65, 80, 95]));
            data.Scans(theo, couple, At(10, 20, 9), 3);
            data.Photo(couple, maya, At(10, 21), 900);
            data.Subscription(maya, At(10, 27), StoreSubscriptionStatus.Active, At(11, 27));
            data.ActiveOn(maya, new DateOnly(2026, 10, 26));
            data.Set(maya, "LastActiveAtUtc", At(10, 28, 11));
            data.Person("Ivy", At(10, 20), "US", internalAccount: true);
            await data.SaveAsync();
        }

        await using var context = database.CreateContext();
        var insights = new AdminInsights(context, clock, settings, billing);

        var rhythm = await insights.RhythmAsync(30, default);
        Assert.Equal(2, rhythm.Actions[0][8]);
        Assert.Equal(2, rhythm.Actions[1][6]);
        Assert.Equal(1, rhythm.Actions[6][19]);
        Assert.Equal(1, rhythm.Actions[5][21]);
        Assert.Equal(1, rhythm.Rings[6][9]);
        Assert.Equal(3, rhythm.DaresDone.Sum(row => row.Sum()));

        var funnel = await insights.FunnelAsync(30, default);
        Assert.Equal([2, 2, 2, 2, 2, 0, 0, 0], funnel.Steps.Select(step => step.Count));
        Assert.Equal(3, funnel.Steps[1].MedianHoursFromPrevious); // Maya invited after 2 hours, Theo joined after 4
        Assert.Equal(1.5, funnel.Steps[2].MedianHoursFromPrevious);

        var retention = await insights.RetentionAsync(4, default);
        Assert.Equal(1, retention.Cohorts.Single(cohort => cohort.WeekStart == "2026-10-12").Size);

        var dares = await insights.DaresAsync(30, default);
        var squat = Assert.Single(dares.Exercises);
        Assert.Equal((4, 3, 1), (squat.Set, squat.Completed, squat.Missed));
        Assert.Equal(1, dares.EarlyStarts);
        Assert.Equal(1, dares.Streaks.Single(bucket => bucket.Length == "3-6").Couples);
        Assert.Equal(0.75, squat.CompletionRate);

        var games = await insights.GamesAsync(30, default);
        Assert.Equal(10, games.Skills.Sum(skill => skill.Rounds));
        Assert.Equal(new DuelDto(1, 0, 1), games.Duels); // 4 hearts to 3

        var growth = await insights.GrowthAsync(30, default);
        Assert.Equal([2, 2, 1, 2, 2], growth.Path.Select(step => step.Count));
        Assert.Equal([new NamedCount("GB", 1), new NamedCount("US", 1)], growth.PlusByCountry.OrderBy(item => item.Name));

        var world = await insights.WorldAsync(default);
        Assert.Equal([new CountryDto("GB", 1, 1, 1, 1), new CountryDto("US", 1, 1, 1, 1)], world.Countries);

        var top = Assert.Single(await insights.TopCouplesAsync(30, 12, default));
        Assert.Equal((3, 2, 2, 3, true), (top.Hearts, top.DaresSet, top.Games, top.Streak, top.Plus));

        var analytics = new AdminAnalytics(context, clock, settings);
        Assert.Equal(2, (await analytics.OverviewAsync(30, default)).TotalUsers);
        Assert.Equal(3, (await analytics.EngagementAsync(30, default)).DaresCompleted);
        Assert.Equal(1, (await analytics.RevenueAsync(30, default)).PayingCouples);
    }
}
