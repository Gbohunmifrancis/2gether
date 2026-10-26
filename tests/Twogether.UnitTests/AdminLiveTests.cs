using System.Security.Claims;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Abstractions;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Admin;
using Twogether.Api.Controllers.Admin;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Admin;
using Twogether.Application.Features.Admin.Live;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Admin.People;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Auth;
using Twogether.Infrastructure.Persistence;
using Twogether.Infrastructure.Push;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>The live feed and pulse on a real database: what shows, in which order, and who is left out.</summary>
public sealed class AdminLiveFeedTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now.AddHours(2));
    private readonly AdminSettings settings = new() { Emails = ["OPS@heartpact.app"] };

    public void Dispose() => database.Dispose();

    private AdminLiveFeed Feed(ApplicationDbContext db, AdminAlertMonitor? monitor = null) => new(db, clock, settings, monitor ?? new AdminAlertMonitor());

    private sealed record Scene(TestCouple Couple, Guid InternalId, Guid OccurrenceId);

    private async Task<Scene> SceneAsync()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);

        // Maya does the squats Theo set; Theo misses the push-ups Maya set.
        var squats = ExerciseGoal.Create(couple.CoupleId, couple.MayaId, couple.TheoId, ExerciseType.Squat, null, 15, new TimeOnly(12, 10), "UTC", 30, Now);
        var done = ExerciseOccurrence.Open(squats, DateOnly.FromDateTime(Now), Now.AddMinutes(10), Now.AddMinutes(10));
        done.Start(Now.AddMinutes(11));
        done.Complete(15, 0.9, Now.AddMinutes(12));
        var pushUps = ExerciseGoal.Create(couple.CoupleId, couple.TheoId, couple.MayaId, ExerciseType.PushUp, null, 10, new TimeOnly(12, 0), "UTC", 15, Now);
        var missed = ExerciseOccurrence.Open(pushUps, DateOnly.FromDateTime(Now), Now, Now);
        missed.Miss(Now.AddMinutes(20));
        var game = GameSession.Create(couple.CoupleId, GameType.GuessMyAnswer, couple.MayaId, Now.AddMinutes(30));
        game.Start(couple.MayaId, null, "{}", Now.AddMinutes(31));
        game.Complete("{}", Now.AddMinutes(40));
        var subscription = StoreSubscription.Create("token-theo-123456789", couple.TheoId, Now.AddMinutes(50));
        subscription.ApplyStoreState("heartpact_plus_monthly", StoreSubscriptionStatus.Active, Now.AddDays(30), true, true, null, Now.AddMinutes(50));
        db.AddRange(squats, done, pushUps, missed, game, subscription,
            AccountDeletion.Record(false, Now.AddMinutes(60)), AccountDeletion.Record(true, Now.AddMinutes(61)));

        // A test couple and an admin: they never show.
        var ivy = User.Create("ivy@heartpact.app", "Ivy", new DateOnly(1990, 1, 1), Now.AddMinutes(5));
        var ian = User.Create("ian@heartpact.app", "Ian", new DateOnly(1990, 1, 1), Now.AddMinutes(5));
        ivy.MarkInternal(true, Now);
        ian.MarkInternal(true, Now);
        var testCouple = Couple.Create(ivy.Id, Now.AddMinutes(5));
        testCouple.LinkPartner(ian.Id, Now.AddMinutes(6));
        ivy.SetCouple(testCouple.Id, Now);
        ian.SetCouple(testCouple.Id, Now);
        var testGoal = ExerciseGoal.Create(testCouple.Id, ivy.Id, ian.Id, ExerciseType.Squat, null, 5, new TimeOnly(12, 0), "UTC", 30, Now);
        var testDare = ExerciseOccurrence.Open(testGoal, DateOnly.FromDateTime(Now), Now.AddMinutes(7), Now.AddMinutes(7));
        testDare.Start(Now.AddMinutes(8));
        testDare.Complete(5, 0.9, Now.AddMinutes(9));
        var admin = User.Create("ops@heartpact.app", "Ops", new DateOnly(1990, 1, 1), Now.AddMinutes(70));
        db.AddRange(ivy, ian, testCouple, testGoal, testDare, admin);
        await db.SaveChangesAsync();
        return new Scene(couple, ivy.Id, done.Id);
    }

    [Fact]
    public async Task Events_are_newest_first_with_names_and_details_and_leave_out_internal_and_admin_accounts()
    {
        var scene = await SceneAsync();
        await using var db = database.CreateContext();
        var events = await Feed(db).SinceAsync(Now.AddMinutes(-1), 50, default);

        Assert.Equal(
            [LiveKind.AccountDeleted, LiveKind.PlusStarted, LiveKind.GameFinished, LiveKind.DareMissed, LiveKind.DareCompleted, LiveKind.CoupleLinked, LiveKind.SignedUp, LiveKind.SignedUp],
            events.Select(item => item.Kind));
        Assert.DoesNotContain(events, item => item.UserId == scene.InternalId || item.DisplayName is "Ivy" or "Ian" or "Ops");

        var dare = events.Single(item => item.Kind == LiveKind.DareCompleted);
        Assert.Equal($"dareCompleted:{scene.OccurrenceId}", dare.Id);
        Assert.Equal(("Maya", "Theo", "squat", LiveSeverity.Good), (dare.DisplayName, dare.PartnerName, dare.Detail, dare.Severity));
        var missed = events.Single(item => item.Kind == LiveKind.DareMissed);
        Assert.Equal(("Theo", "Maya", "pushUp", LiveSeverity.Warn), (missed.DisplayName, missed.PartnerName, missed.Detail, missed.Severity));
        var game = events.Single(item => item.Kind == LiveKind.GameFinished);
        Assert.Equal(("Maya", "Theo", "guessMyAnswer"), (game.DisplayName, game.PartnerName, game.Detail));
        Assert.Equal(("Theo", "heartpact_plus_monthly"), (events.Single(item => item.Kind == LiveKind.PlusStarted).DisplayName, events.Single(item => item.Kind == LiveKind.PlusStarted).Detail));
        var deleted = events.Single(item => item.Kind == LiveKind.AccountDeleted);
        Assert.Null(deleted.UserId);
        Assert.Null(deleted.DisplayName);
        Assert.Equal(DateTimeKind.Utc, deleted.AtUtc.Kind);
    }

    [Fact]
    public async Task Only_events_after_the_cursor_up_to_now_and_at_most_the_limit()
    {
        await SceneAsync();
        await using var db = database.CreateContext();
        var feed = Feed(db);
        Assert.Equal([LiveKind.AccountDeleted, LiveKind.PlusStarted, LiveKind.GameFinished], (await feed.SinceAsync(Now.AddMinutes(20), 50, default)).Select(item => item.Kind));
        Assert.Equal([LiveKind.AccountDeleted, LiveKind.PlusStarted], (await feed.SinceAsync(Now, 2, default)).Select(item => item.Kind));
        clock.UtcNow = Now.AddMinutes(45);
        Assert.Equal([LiveKind.GameFinished], (await feed.SinceAsync(Now.AddMinutes(20), 50, default)).Select(item => item.Kind));
    }

    [Fact]
    public async Task Raised_alerts_join_the_feed()
    {
        await SceneAsync();
        var monitor = new AdminAlertMonitor();
        monitor.Apply([new AlertCandidate("signupWave", AlertSeverity.Warn, "Sign-up wave", "12 sign-ups in the last 15 minutes.", "/users")], Now.AddMinutes(65));
        await using var db = database.CreateContext();
        var events = await Feed(db, monitor).SinceAsync(Now.AddMinutes(55), 50, default);
        Assert.Equal([LiveKind.Alert, LiveKind.AccountDeleted], events.Select(item => item.Kind));
        Assert.StartsWith("alert:signupWave:", events[0].Id);
        Assert.Equal("Sign-up wave: 12 sign-ups in the last 15 minutes.", events[0].Detail);
    }

    [Fact]
    public async Task The_pulse_counts_measured_people_only()
    {
        var scene = await SceneAsync();
        clock.UtcNow = Now.AddHours(2);
        await using (var db = database.CreateContext())
        {
            // Theo has the app open (a live connection); nobody's last-active is recent.
            var ringing = ExerciseGoal.Create(scene.Couple.CoupleId, scene.Couple.MayaId, scene.Couple.TheoId, ExerciseType.JumpingJack, null, 20, new TimeOnly(13, 55), "UTC", 30, Now);
            db.AddRange(ringing, ExerciseOccurrence.Open(ringing, DateOnly.FromDateTime(Now).AddDays(1), Now.AddHours(2).AddMinutes(-5), Now.AddHours(2).AddMinutes(-5)));
            db.UserActivityDays.AddRange(new UserActivityDay { UserId = scene.Couple.MayaId, Day = DateOnly.FromDateTime(Now) }, new UserActivityDay { UserId = scene.InternalId, Day = DateOnly.FromDateTime(Now) });
            await db.SaveChangesAsync();
        }
        await using var read = database.CreateContext();
        var pulse = await Feed(read).PulseAsync([scene.Couple.TheoId, scene.InternalId], default);
        Assert.Equal(1, pulse.OnlineNow);
        Assert.Equal(1, pulse.ActiveToday);
        Assert.Equal(1, pulse.RingingNow);
        Assert.Equal(0, pulse.OnCameraNow);
        Assert.Equal(1, pulse.HeartsToday);
        Assert.Equal(2, pulse.SignupsToday);
        Assert.Equal(1, pulse.CouplesLinkedToday);
        Assert.Equal(1, pulse.PlusStartedToday);
        Assert.Equal(8, pulse.Recent.Count);
    }
}

public sealed class LiveStreamTests
{
    private static readonly DateTime Now = TestCouple.Now;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) } };

    private static LiveEvent Event(string id, int minute) => new(id, LiveKind.SignedUp, Now.AddMinutes(minute), null, "Maya", null, "GB", LiveSeverity.Good);

    [Fact]
    public void The_cursor_lets_each_id_through_once_oldest_first_and_asks_a_little_before_the_last_poll()
    {
        var cursor = new LiveCursor(Now.AddMinutes(-1));
        Assert.Equal(["b", "a"], cursor.Accept([Event("a", 2), Event("b", 1)], Now.AddMinutes(3)).Select(item => item.Id));
        Assert.Equal(Now.AddMinutes(3) - LiveCursor.Overlap, cursor.SinceUtc);
        // "a" is before what was asked for (a late copy of it): dropped, like a repeat.
        Assert.Equal(["c"], cursor.Accept([Event("a", 2), Event("c", 3)], Now.AddMinutes(4)).Select(item => item.Id));
        Assert.Empty(cursor.Accept([Event("c", 3)], Now.AddMinutes(4)));
        // Never moves back.
        cursor.Accept([], Now);
        Assert.Equal(Now.AddMinutes(4) - LiveCursor.Overlap, cursor.SinceUtc);
    }

    [Fact]
    public async Task The_stream_writes_pings_and_each_event_once_until_the_session_ends()
    {
        var output = new MemoryStream();
        var polls = 0;
        var batches = new[] { new[] { Event("one", 1) }, [Event("one", 1), Event("two", 2)], [Event("two", 2)] };
        var checks = 0;
        await LiveStream.RunAsync(
            output,
            new LiveCursor(Now),
            (_, _) => Task.FromResult<IReadOnlyList<LiveEvent>>(batches[Math.Min(polls++, batches.Length - 1)]),
            _ => Task.FromResult(++checks < 4),
            () => Now.AddMinutes(1),
            Json,
            new LiveStreamTiming(TimeSpan.FromMilliseconds(5), TimeSpan.Zero, TimeSpan.Zero),
            null,
            new CancellationTokenSource(TimeSpan.FromSeconds(10)).Token);

        var text = Encoding.UTF8.GetString(output.ToArray());
        var frames = text.Split("\n\n", StringSplitOptions.RemoveEmptyEntries);
        Assert.StartsWith("event: ping\ndata: {\"nowUtc\":", frames[0]);
        var live = frames.Where(frame => frame.StartsWith("event: live\n")).ToList();
        Assert.Equal(2, live.Count);
        Assert.Contains("\"id\":\"one\"", live[0]);
        Assert.Contains("\"kind\":\"signedUp\"", live[0]);
        Assert.Contains("\"severity\":\"good\"", live[0]);
        Assert.Contains("\"id\":\"two\"", live[1]);
        Assert.True(frames.Count(frame => frame.StartsWith("event: ping\n")) >= 2);
        Assert.Equal(4, checks);
    }

    [Fact]
    public async Task The_stream_survives_a_failed_poll_and_ends_quietly_when_cancelled()
    {
        var output = new MemoryStream();
        var errors = new List<Exception>();
        using var stop = new CancellationTokenSource();
        var polls = 0;
        await LiveStream.RunAsync(
            output,
            new LiveCursor(Now),
            (_, _) =>
            {
                if (++polls == 1) throw new InvalidOperationException("database blinked");
                if (polls == 3) stop.Cancel();
                return Task.FromResult<IReadOnlyList<LiveEvent>>([Event("one", 1)]);
            },
            _ => Task.FromResult(true),
            () => Now.AddMinutes(1),
            Json,
            new LiveStreamTiming(TimeSpan.FromMilliseconds(5), TimeSpan.FromHours(1), TimeSpan.FromHours(1)),
            errors.Add,
            stop.Token);
        Assert.Single(errors);
        Assert.Single(Encoding.UTF8.GetString(output.ToArray()).Split("\n\n"), frame => frame.StartsWith("event: live"));
    }
}

public sealed class AdminAlertTests
{
    private static readonly DateTime Now = TestCouple.Now;

    private static AlertSignals Quiet(params WorkerHealthDto[] workers) => new(0, [], 0, 0, 0, 0, workers, 0, null);

    [Fact]
    public void Nothing_fires_on_a_quiet_day() => Assert.Empty(AlertRules.Evaluate(Quiet()));

    [Fact]
    public void Each_rule_fires_at_its_threshold_and_not_below()
    {
        Assert.Empty(AlertRules.Evaluate(Quiet() with { SignupsLast15Minutes = 9 }));
        Assert.Equal("signupWave", AlertRules.Evaluate(Quiet() with { SignupsLast15Minutes = 10 }).Single().Key);

        var maya = Guid.NewGuid();
        var flood = AlertRules.Evaluate(Quiet() with { FoodScanBursts = [new(maya, "Maya", 20), new(Guid.NewGuid(), "Theo", 19)] }).Single();
        Assert.Equal(($"foodScanFlood:{maya}", $"/users/{maya}"), (flood.Key, flood.Link));
        Assert.Contains("Maya made 20 food scans", flood.Detail);

        Assert.Empty(AlertRules.Evaluate(Quiet() with { VerifyFailuresLastHour = 2 }));
        Assert.Equal(AlertSeverity.Bad, AlertRules.Evaluate(Quiet() with { SubscriptionsNewlyInTrouble = 1 }).Single(item => item.Key == "paymentTrouble").Severity);
        Assert.Equal("paymentTrouble", AlertRules.Evaluate(Quiet() with { VerifyFailuresLastHour = 3 }).Single().Key);

        // At least ten attempts, and a fifth of them failed.
        Assert.Empty(AlertRules.Evaluate(Quiet() with { PushSentLastHour = 6, PushFailedLastHour = 3 }));
        Assert.Empty(AlertRules.Evaluate(Quiet() with { PushSentLastHour = 41, PushFailedLastHour = 10 }));
        Assert.Equal("pushFailing", AlertRules.Evaluate(Quiet() with { PushSentLastHour = 8, PushFailedLastHour = 2 }).Single().Key);

        var workers = AlertRules.Evaluate(Quiet(
            new("pushDispatch", Now, Now, "TimeoutException: boom", 2, WorkerStatus.Failing),
            new("locationSweep", Now.AddHours(-4), null, null, 3600, WorkerStatus.Late),
            new("gameTimeouts", Now, null, null, 1, WorkerStatus.Ok),
            new("memoryCleanup", null, null, null, 300, WorkerStatus.Unknown)));
        Assert.Equal([("worker:pushDispatch", AlertSeverity.Bad), ("worker:locationSweep", AlertSeverity.Warn)], workers.Select(item => (item.Key, item.Severity)));
        Assert.Equal("TimeoutException: boom", workers[0].Detail);

        Assert.Empty(AlertRules.Evaluate(Quiet() with { StorageBytes = 900_000_000_000 }));
        Assert.Empty(AlertRules.Evaluate(Quiet() with { StorageBytes = 7_900_000_000, StorageBudgetGb = 10 }));
        Assert.Equal(AlertSeverity.Warn, AlertRules.Evaluate(Quiet() with { StorageBytes = 8_000_000_000, StorageBudgetGb = 10 }).Single().Severity);
        Assert.Equal(AlertSeverity.Bad, AlertRules.Evaluate(Quiet() with { StorageBytes = 10_500_000_000, StorageBudgetGb = 10 }).Single().Severity);
    }

    [Fact]
    public void An_alert_is_raised_once_per_hour_stays_listed_for_the_hour_and_keeps_its_start()
    {
        var monitor = new AdminAlertMonitor();
        var wave = new AlertCandidate("signupWave", AlertSeverity.Warn, "Sign-up wave", "10 sign-ups in the last 15 minutes.", "/users");

        var current = monitor.Apply([wave], Now);
        Assert.Equal(Now, current.Single().SinceUtc);
        Assert.Single(monitor.RaisedSince(Now.AddMinutes(-1)));

        // Still holding ten minutes later: listed with the new detail, not raised again.
        current = monitor.Apply([wave with { Detail = "14 sign-ups in the last 15 minutes." }], Now.AddMinutes(10));
        Assert.Equal("14 sign-ups in the last 15 minutes.", current.Single().Detail);
        Assert.Single(monitor.RaisedSince(Now.AddMinutes(-1)));

        // Stopped holding: still listed until the hour is up, then gone.
        Assert.Single(monitor.Apply([], Now.AddMinutes(30)));
        Assert.Empty(monitor.Apply([], Now.AddMinutes(61)));

        // A new episode is raised again, from its own start.
        Assert.Equal(Now.AddMinutes(70), monitor.Apply([wave], Now.AddMinutes(70)).Single().SinceUtc);
        Assert.Equal(2, monitor.RaisedSince(Now.AddMinutes(-1)).Count);

        // Holding for over an hour without a break: raised again (a reminder), but since when it began.
        monitor.Apply([wave], Now.AddMinutes(100));
        monitor.Apply([wave], Now.AddMinutes(128));
        var again = monitor.Apply([wave], Now.AddMinutes(131));
        Assert.Equal(Now.AddMinutes(70), again.Single().SinceUtc);
        Assert.Equal(3, monitor.RaisedSince(Now.AddMinutes(-1)).Count);
        Assert.All(monitor.RaisedSince(Now.AddMinutes(-1)), item => Assert.Equal((LiveKind.Alert, LiveSeverity.Warn), (item.Kind, item.Severity)));
    }

    [Fact]
    public void Bad_alerts_come_first()
    {
        var monitor = new AdminAlertMonitor();
        var current = monitor.Apply([
            new AlertCandidate("signupWave", AlertSeverity.Warn, "Sign-up wave", "x", null),
            new AlertCandidate("pushFailing", AlertSeverity.Bad, "Push failing", "y", null)], Now);
        Assert.Equal(["pushFailing", "signupWave"], current.Select(item => item.Key));
        Assert.True(monitor.IsFresh(Now.AddSeconds(10), AdminAlerts.MaxAge));
        Assert.False(monitor.IsFresh(Now.AddSeconds(31), AdminAlerts.MaxAge));
    }
}

public sealed class WorkerHeartbeatTests
{
    private static readonly DateTime Now = TestCouple.Now;

    [Fact]
    public void A_worker_is_unknown_until_it_runs_then_ok_late_after_three_intervals_and_failing_when_its_last_loop_failed()
    {
        var clock = new FakeClock(Now);
        var beats = new WorkerHeartbeats(clock);
        var every = TimeSpan.FromMinutes(5);
        beats.Register("memoryCleanup", every);
        Assert.Equal(WorkerStatus.Unknown, beats.Snapshot().Single().Status);
        Assert.Equal(300, beats.Snapshot().Single().EverySeconds);

        beats.Ok("memoryCleanup", every);
        Assert.Equal((WorkerStatus.Ok, (DateTime?)Now), (beats.Snapshot().Single().Status, beats.Snapshot().Single().LastRunUtc));
        clock.UtcNow = Now.AddMinutes(15);
        Assert.Equal(WorkerStatus.Ok, beats.Snapshot().Single().Status);
        clock.UtcNow = Now.AddMinutes(16);
        Assert.Equal(WorkerStatus.Late, beats.Snapshot().Single().Status);

        beats.Failed("memoryCleanup", every, new TimeoutException("storage did not answer"));
        var failing = beats.Snapshot().Single();
        Assert.Equal((WorkerStatus.Failing, "TimeoutException: storage did not answer", (DateTime?)Now.AddMinutes(16)), (failing.Status, failing.LastError, failing.LastErrorUtc));

        // Recovered: ok again, the last error kept for the record.
        beats.Ok("memoryCleanup", every);
        Assert.Equal((WorkerStatus.Ok, "TimeoutException: storage did not answer"), (beats.Snapshot().Single().Status, beats.Snapshot().Single().LastError));
    }

    [Fact]
    public void A_worker_that_never_runs_turns_late_and_fast_workers_get_a_floor()
    {
        var clock = new FakeClock(Now);
        var beats = new WorkerHeartbeats(clock);
        beats.Register("pushDispatch", TimeSpan.FromSeconds(2));
        beats.Register("exerciseAlarms", TimeSpan.FromSeconds(1));
        beats.Ok("exerciseAlarms", TimeSpan.FromSeconds(1));
        clock.UtcNow = Now.AddSeconds(29);
        Assert.All(beats.Snapshot(), item => Assert.NotEqual(WorkerStatus.Late, item.Status));
        clock.UtcNow = Now.AddSeconds(31);
        Assert.Equal([("exerciseAlarms", WorkerStatus.Late), ("pushDispatch", WorkerStatus.Late)], beats.Snapshot().Select(item => (item.Name, item.Status)));
        Assert.Equal(TimeSpan.FromHours(3), WorkerHeartbeats.LateAfter(TimeSpan.FromHours(1)));
    }

    [Fact]
    public void Long_errors_are_cut()
    {
        var beats = new WorkerHeartbeats(new FakeClock(Now));
        beats.Failed("x", TimeSpan.FromSeconds(1), new InvalidOperationException(new string('a', 1000)));
        Assert.Equal(WorkerHeartbeats.MaxErrorLength, beats.Snapshot().Single().LastError!.Length);
    }
}

public sealed class AdminJourneyTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();

    public void Dispose() => database.Dispose();

    [Fact]
    public void Points_group_by_day_and_kind_newest_day_first_and_outside_the_window_is_dropped()
    {
        var day = new DateOnly(2026, 10, 20);
        var at = day.ToDateTime(new TimeOnly(9, 0), DateTimeKind.Utc);
        var items = AdminJourney.Group(
        [
            new(at, JourneyKind.DareCompleted, "squat"),
            new(at.AddHours(3), JourneyKind.DareCompleted, "squat"),
            new(at.AddHours(4), JourneyKind.DareCompleted, "pushUp"),
            new(at, JourneyKind.Active, null),
            new(at.AddDays(2), JourneyKind.MealLogged, null),
            new(at.AddDays(-30), JourneyKind.SignedUp, null),
        ], day.AddDays(-7), day.AddDays(7));
        Assert.Equal(["2026-10-22", "2026-10-20"], items.Select(item => item.Day));
        Assert.Equal(
            [new JourneyEventDto(JourneyKind.Active, 1, null), new(JourneyKind.DareCompleted, 1, "pushUp"), new(JourneyKind.DareCompleted, 2, "squat")],
            items[1].Events);
    }

    [Fact]
    public async Task A_journey_counts_what_the_person_did_without_content()
    {
        Guid mayaId;
        await using (var db = database.CreateContext())
        {
            var couple = await TestCouple.AddAsync(db);
            mayaId = couple.MayaId;
            var goal = ExerciseGoal.Create(couple.CoupleId, couple.TheoId, couple.MayaId, ExerciseType.Squat, null, 15, new TimeOnly(12, 0), "UTC", 30, Now);
            db.AddRange(goal,
                new UserActivityDay { UserId = mayaId, Day = DateOnly.FromDateTime(Now) },
                AdminAuditEvent.Record(null, "ops@heartpact.app", "user.viewed", "user", mayaId.ToString(), null, null, true, Now),
                AdminAuditEvent.Record(null, "ops@heartpact.app", "plus.granted", "couple", couple.CoupleId.ToString(), "30 days: sorry", null, true, Now),
                PlusGrant.Create(couple.CoupleId, Now.AddDays(30), "sorry", Guid.NewGuid(), Now));
            await db.SaveChangesAsync();
        }
        await using var read = database.CreateContext();
        var journey = await new AdminJourney(read, new FakeClock(Now.AddDays(1))).GetAsync(mayaId, 1, default);
        Assert.NotNull(journey);
        Assert.Equal(7, journey.Days);
        var today = journey.Items.Single();
        Assert.Equal("2026-10-25", today.Day);
        Assert.Equal(
            [(JourneyKind.SignedUp, null), (JourneyKind.Linked, null), (JourneyKind.Active, null), (JourneyKind.DareSet, "squat"),
             (JourneyKind.PlusStarted, "complimentary"), (JourneyKind.AdminAction, "plus.granted")],
            today.Events.Select(item => (item.Kind, item.Detail)));
        Assert.Null(await new AdminJourney(read, new FakeClock(Now)).GetAsync(Guid.NewGuid(), 90, default));
    }
}

/// <summary>Destructive admin actions and the fresh authenticator code they need.</summary>
public sealed class AdminStepUpTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);
    private readonly AdminSecretProtector secrets = new("admin-key");
    private readonly AttemptLimiter limiter;
    private string secret = "";
    private Guid adminId;

    public AdminStepUpTests() => limiter = new AttemptLimiter(clock);

    public void Dispose() => database.Dispose();

    private async Task EnrollAsync()
    {
        await using var db = database.CreateContext();
        var admin = User.Create("ops@heartpact.app", "Ops", new DateOnly(1990, 1, 1), Now, "hash");
        secret = Totp.NewSecret();
        var account = AdminAccount.Pending(admin.Id, secrets.Protect(secret), Now);
        // Signed in a minute ago with that minute's code.
        account.Accept(Totp.StepAt(Now.AddMinutes(-1)), Now.AddMinutes(-1));
        db.AddRange(admin, account);
        await db.SaveChangesAsync();
        adminId = admin.Id;
    }

    private static DefaultHttpContext Http(Guid adminId, string? code)
    {
        var http = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, adminId.ToString()), new Claim(ClaimTypes.Email, "ops@heartpact.app")], "test")),
        };
        http.Request.Path = "/api/admin/users/x/disable";
        if (code is not null) http.Request.Headers[StepUpFilter.Header] = code;
        return http;
    }

    private async Task<(IActionResult? Result, bool Ran)> RunAsync(string? code)
    {
        await using var db = database.CreateContext();
        var http = Http(adminId, code);
        var filter = new StepUpFilter(db, clock, secrets, limiter, new AdminAudit(db, clock, new HttpContextAccessor { HttpContext = http }));
        var action = new ActionContext(http, new RouteData(), new ActionDescriptor());
        var context = new ActionExecutingContext(action, [], new Dictionary<string, object?>(), controller: new object());
        var ran = false;
        await filter.OnActionExecutionAsync(context, () =>
        {
            ran = true;
            return Task.FromResult(new ActionExecutedContext(action, [], new object()));
        });
        return (context.Result, ran);
    }

    private static string? CodeOf(IActionResult? result) => (result as ObjectResult)?.Value is Twogether.Api.Common.ApiError error ? error.Code : null;
    private static int? StatusOf(IActionResult? result) => (result as ObjectResult)?.StatusCode;

    [Fact]
    public async Task Missing_wrong_reused_and_right_codes()
    {
        await EnrollAsync();
        var code = Totp.Code(secret, Totp.StepAt(Now));

        var missing = await RunAsync(null);
        Assert.False(missing.Ran);
        Assert.Equal((403, StepUpFilter.RequiredCode), (StatusOf(missing.Result), CodeOf(missing.Result)));

        var wrong = await RunAsync(code == "000000" ? "111111" : "000000");
        Assert.False(wrong.Ran);
        Assert.Equal((403, StepUpFilter.InvalidCode), (StatusOf(wrong.Result), CodeOf(wrong.Result)));

        // The code used to sign in a minute ago is spent.
        var signInCode = await RunAsync(Totp.Code(secret, Totp.StepAt(Now.AddMinutes(-1))));
        Assert.Equal(StepUpFilter.InvalidCode, CodeOf(signInCode.Result));

        var right = await RunAsync(code);
        Assert.True(right.Ran);
        Assert.Null(right.Result);

        var reused = await RunAsync(code);
        Assert.False(reused.Ran);
        Assert.Equal(StepUpFilter.InvalidCode, CodeOf(reused.Result));

        await using var db = database.CreateContext();
        Assert.Equal(Totp.StepAt(Now), (await db.AdminAccounts.SingleAsync()).LastUsedStep);
        Assert.Equal(3, await db.AdminAuditEvents.CountAsync(item => item.Action == "admin.step_up_failed" && !item.Succeeded));
    }

    [Fact]
    public async Task Too_many_wrong_codes_lock_step_up_for_a_while()
    {
        await EnrollAsync();
        for (var i = 0; i < AttemptRules.AdminStepUpPerAdmin.MaxFailures; i++) await RunAsync("000001");
        var locked = await RunAsync(Totp.Code(secret, Totp.StepAt(Now)));
        Assert.False(locked.Ran);
        Assert.Equal(429, StatusOf(locked.Result));
    }

    [Fact]
    public void Every_destructive_admin_action_requires_step_up()
    {
        bool Requires(Type controller, string method) => controller.GetMethod(method)!.GetCustomAttributes(typeof(RequireStepUpAttribute), false).Length == 1;
        Assert.True(Requires(typeof(AdminUsersController), nameof(AdminUsersController.Disable)));
        Assert.True(Requires(typeof(AdminUsersController), nameof(AdminUsersController.Delete)));
        Assert.True(Requires(typeof(AdminSubscriptionsController), nameof(AdminSubscriptionsController.Cancel)));
        Assert.True(Requires(typeof(AdminSubscriptionsController), nameof(AdminSubscriptionsController.Refund)));
        Assert.True(Requires(typeof(AdminSubscriptionsController), nameof(AdminSubscriptionsController.Revoke)));
        Assert.True(Requires(typeof(AdminSubscriptionsController), nameof(AdminSubscriptionsController.Grant)));
        Assert.True(Requires(typeof(AdminSystemController), nameof(AdminSystemController.ResetAuthenticator)));
        Assert.True(Requires(typeof(AdminPeopleController), nameof(AdminPeopleController.Internal)));
        Assert.False(Requires(typeof(AdminUsersController), nameof(AdminUsersController.Enable)));
    }
}

public sealed class AdminInternalAccountTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now.AddHours(1));

    public void Dispose() => database.Dispose();

    private AdminPeopleController Controller(ApplicationDbContext db)
    {
        var http = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, Guid.NewGuid().ToString()), new Claim(ClaimTypes.Email, "ops@heartpact.app")], "test")),
        };
        return new AdminPeopleController(db, clock, new AdminAudit(db, clock, new HttpContextAccessor { HttpContext = http }), new AdminJourney(db, clock))
        {
            ControllerContext = new ControllerContext { HttpContext = http },
        };
    }

    [Fact]
    public async Task Marking_internal_needs_a_reason_is_audited_and_takes_the_account_out_of_the_feed()
    {
        TestCouple couple;
        await using (var db = database.CreateContext()) couple = await TestCouple.AddAsync(db);

        await using (var db = database.CreateContext())
        {
            Assert.IsType<UnprocessableEntityObjectResult>(await Controller(db).Internal(couple.MayaId, new AdminInternalRequest(true, " "), default));
            Assert.IsType<NotFoundResult>(await Controller(db).Internal(Guid.NewGuid(), new AdminInternalRequest(true, "test account"), default));
            Assert.IsType<NoContentResult>(await Controller(db).Internal(couple.MayaId, new AdminInternalRequest(true, "  QA phone  "), default));
        }

        await using var read = database.CreateContext();
        Assert.True((await read.Users.SingleAsync(user => user.Id == couple.MayaId)).IsInternal);
        var audit = await read.AdminAuditEvents.SingleAsync();
        Assert.Equal(("user.marked_internal", "user", couple.MayaId.ToString(), "QA phone", "ops@heartpact.app"), (audit.Action, audit.TargetType, audit.TargetId, audit.Details, audit.AdminEmail));

        // Theo is still counted; Maya and the couple she is in are not.
        var events = await new AdminLiveFeed(read, clock, new AdminSettings(), new AdminAlertMonitor()).SinceAsync(Now.AddMinutes(-1), 50, default);
        Assert.Equal([(LiveKind.SignedUp, "Theo")], events.Select(item => (item.Kind, item.DisplayName)));
    }
}

public sealed class AdminHealthTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();

    public void Dispose() => database.Dispose();

    [Fact]
    public async Task Health_reports_probes_workers_queues_storage_and_cost()
    {
        var clock = new FakeClock(Now);
        await using (var db = database.CreateContext())
        {
            var couple = await TestCouple.AddAsync(db);
            db.Notifications.Add(Notification.Create(couple.MayaId, couple.CoupleId, NotificationType.Breakup, "t", "b", null, Now.AddMinutes(-5)));
            var scan = FoodScan.Start(couple.MayaId, couple.CoupleId, "m", "image/jpeg", 100, Now.AddMinutes(-10));
            scan.Succeed(1_000_000, 200_000, 900, "{}", Now.AddMinutes(-10));
            db.FoodScans.Add(scan);
            await db.SaveChangesAsync();
        }
        var beats = new WorkerHeartbeats(clock);
        beats.Ok("pushDispatch", TimeSpan.FromSeconds(2));
        var settings = new AdminSettings { Currency = "EUR", FoodScanPricePerMillionInput = 3m, FoodScanPricePerMillionOutput = 15m };
        await using var read = database.CreateContext();
        var health = await new AdminHealth(read, clock, settings, beats, new FakeMediaStorage(), new DisabledPushSender(), new DisabledEmailSender(), new FakeEstimator()).GetAsync(30, default);

        Assert.Equal(["database", "storage", "push", "email", "foodModel"], health.Probes.Select(item => item.Name));
        Assert.Equal([ProbeStatus.Up, ProbeStatus.Up, ProbeStatus.Off, ProbeStatus.Off, ProbeStatus.Up], health.Probes.Select(item => item.Status));
        Assert.NotNull(health.Probes[0].LatencyMs);
        Assert.Equal(("pushDispatch", WorkerStatus.Ok), (health.Workers.Single().Name, health.Workers.Single().Status));
        var outbox = health.Queues.Single(item => item.Name == "pushOutbox");
        Assert.Equal(1, outbox.Waiting);
        Assert.Equal(DateTimeKind.Utc, outbox.OldestUtc!.Value.Kind);
        Assert.Equal((1_000_000L, 200_000L, (decimal?)6.00m, "EUR"), (health.FoodScanCost.InputTokens, health.FoodScanCost.OutputTokens, health.FoodScanCost.EstimatedCost, health.FoodScanCost.Currency));
        Assert.NotEmpty(health.NotMonitored);
        Assert.Null(AdminHealth.Cost(30, 1, 1, new AdminSettings { FoodScanPricePerMillionInput = 3m }).EstimatedCost);
    }
}
