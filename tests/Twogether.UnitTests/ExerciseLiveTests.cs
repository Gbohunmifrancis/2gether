using System.Security.Claims;
using System.Text.Json;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Hubs;
using Twogether.Api.Presence;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Exercise;
using Twogether.Application.Features.Location;
using Twogether.Application.Features.Push;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Core.Notifications;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// Watching live (docs/couple-goals.md): the exerciser's stick figure goes from the couple hub to the partner only, for
/// the exerciser's own dare in progress, capped and checked, never stored; "watching" and cheers go back the other way;
/// and the partner hears once that counting started.
/// </summary>
public sealed class ExerciseLiveTests : IDisposable
{
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now.AddMinutes(10));
    private readonly FakeHub hub = new();
    private ExerciseLiveGate gate = new();

    public void Dispose() => database.Dispose();

    /// <summary>A figure in the middle of the picture, the far wrist hidden.</summary>
    private static readonly int[] Joints = [500, 200, 450, 300, 550, 300, 440, 400, 560, 400, -1, -1, 570, 480, 470, 550, 530, 550, 470, 700, 530, 700, 470, 850, 530, 850];

    /// <summary>Maya's squats, set by Theo; in progress unless [started] is false (still ringing).</summary>
    private async Task<(TestCouple Couple, Guid OccurrenceId)> DareAsync(bool started = true, ExerciseType type = ExerciseType.Squat)
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var goal = ExerciseGoal.Create(couple.CoupleId, couple.MayaId, couple.TheoId, type, null, 15, new TimeOnly(12, 0), "UTC", 30, clock.UtcNow.AddDays(-1));
        var occurrence = ExerciseOccurrence.Open(goal, DateOnly.FromDateTime(clock.UtcNow), clock.UtcNow.AddMinutes(-5), clock.UtcNow.AddMinutes(-5));
        if (started) occurrence.Start(clock.UtcNow.AddMinutes(-1));
        db.AddRange(goal, occurrence);
        await db.SaveChangesAsync();
        return (couple, occurrence.Id);
    }

    private CoupleHub Hub(ApplicationDbContext db, Guid userId, Guid? coupleId, Recorder clients)
        => new(db, new FakeCurrentUser(userId, coupleId), clock, new CouplePresenceTracker(), new GameLiveState(), new HubConnectionRegistry(),
            new LocationSharingStopper(db, hub, clock), new LocationFixThrottle(), new GameInkGate(), gate)
        {
            Clients = clients,
            Context = new Caller(),
        };

    private async Task<bool> Frame(TestCouple couple, Guid userId, Guid occurrenceId, Recorder clients, string phase = "counting", int count = 3,
        string? cue = "tip.Shallow", int aspect = 750, int[]? joints = null, Guid? coupleId = null)
    {
        await using var db = database.CreateContext();
        return await Hub(db, userId, coupleId ?? couple.CoupleId, clients).ExerciseLive(occurrenceId, count, phase, cue, aspect, joints ?? Joints);
    }

    [Fact]
    public async Task A_frame_goes_to_the_partner_only_with_the_dare_from_the_server()
    {
        var (couple, occurrenceId) = await DareAsync();
        var clients = new Recorder();
        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, phase: "setup", count: 0, cue: "setup.TurnSideways"));

        var (group, method, args) = Assert.Single(clients.Sent);
        Assert.Equal(CoupleHub.UserGroupName(couple.TheoId), group);
        Assert.Equal("ExerciseLive", method);
        var frame = Assert.IsType<ExerciseLiveDto>(args[0]);
        Assert.Equal((occurrenceId, couple.MayaId, ExerciseType.Squat, 15, 0, "setup", "setup.TurnSideways", 750), (frame.OccurrenceId, frame.UserId, frame.ExerciseType, frame.Target, frame.Count, frame.Phase, frame.Cue, frame.Aspect));
        Assert.Equal(Joints, frame.Joints);

        // Nobody in the picture: no joints at all, and no cue is no cue.
        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, phase: "setup", count: 0, cue: "", joints: []));
        Assert.Null(Assert.IsType<ExerciseLiveDto>(clients.Sent[^1].Args[0]).Cue);

        // Nothing about a frame is kept.
        await using var db = database.CreateContext();
        Assert.Equal(0, (await db.ExerciseOccurrences.SingleAsync(item => item.Id == occurrenceId)).AchievedCount);
    }

    [Fact]
    public async Task Frames_for_a_dare_that_is_not_the_callers_own_in_progress_are_dropped()
    {
        var clients = new Recorder();
        // Still ringing: the camera hasn't started it.
        var (ringing, ringingId) = await DareAsync(started: false);
        Assert.False(await Frame(ringing, ringing.MayaId, ringingId, clients));

        // The partner can't send frames for it, and nobody outside the couple can.
        var (couple, occurrenceId) = await DareAsync();
        Assert.False(await Frame(couple, couple.TheoId, occurrenceId, clients));
        var other = await OtherCoupleAsync();
        Assert.False(await Frame(couple, other.MayaId, occurrenceId, clients, coupleId: other.CoupleId));
        Assert.False(await Frame(couple, other.MayaId, occurrenceId, clients, coupleId: null));

        // Past the time the camera had to finish it.
        clock.UtcNow = clock.UtcNow.AddMinutes(40);
        Assert.False(await Frame(couple, couple.MayaId, occurrenceId, clients));

        // No such dare.
        Assert.False(await Frame(couple, couple.MayaId, Guid.NewGuid(), clients));
        Assert.Empty(clients.Sent);
    }

    [Theory]
    [InlineData(-1, "counting", "tip.Shallow", 750, 26)]       // a negative count
    [InlineData(3, "dancing", "tip.Shallow", 750, 26)]         // an unknown phase
    [InlineData(3, "counting", "Keep going, love!", 750, 26)]  // words, not a code
    [InlineData(3, "counting", "tip.Shallow", 50, 26)]         // no picture is that shape
    [InlineData(3, "counting", "tip.Shallow", 750, 24)]        // a joint short
    [InlineData(3, "counting", "tip.Shallow", 750, 34)]        // more than the figure
    public async Task Malformed_frames_are_dropped(int count, string phase, string cue, int aspect, int numbers)
    {
        var (couple, occurrenceId) = await DareAsync();
        var clients = new Recorder();
        Assert.False(await Frame(couple, couple.MayaId, occurrenceId, clients, phase, count, cue, aspect, Enumerable.Repeat(500, numbers).ToArray()));
        Assert.False(await Frame(couple, couple.MayaId, occurrenceId, clients, joints: [.. Joints[..^2], 1200, 500]));
        Assert.False(await Frame(couple, couple.MayaId, occurrenceId, clients, joints: [.. Joints[..^2], -1, 500]));
        Assert.False(await Frame(couple, couple.MayaId, occurrenceId, clients, cue: new string('a', ExerciseLiveRules.MaxCueLength + 1)));
        Assert.Empty(clients.Sent);
    }

    [Fact]
    public async Task Frames_faster_than_the_cap_are_dropped()
    {
        var (couple, occurrenceId) = await DareAsync();
        var clients = new Recorder();
        var relayed = 0;
        for (var i = 0; i < ExerciseLiveGate.MaxFramesPerSecond + 6; i++)
            if (await Frame(couple, couple.MayaId, occurrenceId, clients, phase: "setup")) relayed++;
        Assert.Equal(ExerciseLiveGate.MaxFramesPerSecond, relayed);
        Assert.True(ExerciseLiveGate.AppRate < ExerciseLiveGate.MaxFramesPerSecond);

        clock.UtcNow = clock.UtcNow.AddSeconds(1);
        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, phase: "setup"));
    }

    [Fact]
    public async Task The_partner_hears_once_that_counting_started()
    {
        var (couple, occurrenceId) = await DareAsync();
        var clients = new Recorder();
        // Getting set and counting down tell nobody.
        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, phase: "setup"));
        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, phase: "countdown", cue: "count.2"));
        Assert.Empty(await StartedNoticesAsync());

        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, phase: "counting", count: 0, cue: null));
        var notice = Assert.Single(await StartedNoticesAsync());
        Assert.Equal(couple.TheoId, notice.UserId);
        Assert.Equal("Maya started their squats", notice.Title);
        Assert.Equal("Watch live and cheer them on.", notice.Body);
        using (var data = JsonDocument.Parse(notice.DataJson!))
        {
            Assert.Equal(occurrenceId, data.RootElement.GetProperty("occurrenceId").GetGuid());
            Assert.Equal("squat", data.RootElement.GetProperty("exercise").GetString());
        }
        Assert.Contains(clients.Sent, sent => sent.Method == "NotificationCreated" && sent.Group == CoupleHub.UserGroupName(couple.TheoId));

        // More counting frames, and a server that restarted (a fresh gate), don't tell again.
        for (var i = 0; i < 3; i++) { clock.UtcNow = clock.UtcNow.AddSeconds(1); Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, count: 4 + i)); }
        gate = new ExerciseLiveGate();
        clock.UtcNow = clock.UtcNow.AddSeconds(1);
        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients, count: 8));
        Assert.Single(await StartedNoticesAsync());
    }

    [Fact]
    public async Task A_partner_already_watching_gets_no_push()
    {
        var (couple, occurrenceId) = await DareAsync();
        var clients = new Recorder();
        Assert.True(await Watch(couple, couple.TheoId, occurrenceId, true, clients));
        Assert.True(await Frame(couple, couple.MayaId, occurrenceId, clients));
        Assert.Empty(await StartedNoticesAsync());
    }

    private async Task<List<Notification>> StartedNoticesAsync()
    {
        await using var db = database.CreateContext();
        return await db.Notifications.AsNoTracking().Where(item => item.Type == NotificationType.ExerciseStarted).ToListAsync();
    }

    private async Task<bool> Watch(TestCouple couple, Guid userId, Guid occurrenceId, bool watching, Recorder clients, Guid? coupleId = null)
    {
        await using var db = database.CreateContext();
        return await Hub(db, userId, coupleId ?? couple.CoupleId, clients).WatchExercise(occurrenceId, watching);
    }

    private async Task<bool> Cheer(TestCouple couple, Guid userId, Guid occurrenceId, string cheer, Recorder clients)
    {
        await using var db = database.CreateContext();
        return await Hub(db, userId, couple.CoupleId, clients).CheerExercise(occurrenceId, cheer);
    }

    [Fact]
    public async Task Watching_tells_the_exerciser_who_is_watching()
    {
        var (couple, occurrenceId) = await DareAsync(started: false);
        var clients = new Recorder();
        // Open while it still rings: the camera will show it once it starts.
        Assert.True(await Watch(couple, couple.TheoId, occurrenceId, true, clients));
        var (group, method, args) = Assert.Single(clients.Sent);
        Assert.Equal((CoupleHub.UserGroupName(couple.MayaId), "ExerciseWatching"), (group, method));
        var watching = Assert.IsType<ExerciseWatchingDto>(args[0]);
        Assert.Equal((occurrenceId, couple.TheoId, "Theo", true), (watching.OccurrenceId, watching.UserId, watching.UserName, watching.Watching));
        Assert.True(gate.IsWatched(occurrenceId, clock.UtcNow));

        // The exerciser can't watch their own dare; nobody outside the couple can watch it.
        Assert.False(await Watch(couple, couple.MayaId, occurrenceId, true, clients));
        var other = await OtherCoupleAsync();
        Assert.False(await Watch(couple, other.MayaId, occurrenceId, true, clients, other.CoupleId));

        // Closing is passed on, and forgotten.
        Assert.True(await Watch(couple, couple.TheoId, occurrenceId, false, clients));
        Assert.False(Assert.IsType<ExerciseWatchingDto>(clients.Sent[^1].Args[0]).Watching);
        Assert.False(gate.IsWatched(occurrenceId, clock.UtcNow));

        // A view left open without saying so lapses.
        Assert.True(await Watch(couple, couple.TheoId, occurrenceId, true, clients));
        Assert.False(gate.IsWatched(occurrenceId, clock.UtcNow + ExerciseLiveGate.WatchingFor));
    }

    [Fact]
    public async Task Watching_a_dare_that_is_over_is_refused()
    {
        var (couple, occurrenceId) = await DareAsync();
        await using (var db = database.CreateContext())
        {
            var occurrence = await db.ExerciseOccurrences.SingleAsync(item => item.Id == occurrenceId);
            occurrence.Complete(15, 0.9, clock.UtcNow);
            await db.SaveChangesAsync();
        }
        var clients = new Recorder();
        Assert.False(await Watch(couple, couple.TheoId, occurrenceId, true, clients));
        Assert.True(await Watch(couple, couple.TheoId, occurrenceId, false, clients));
    }

    [Fact]
    public async Task Cheers_go_to_the_exerciser_while_the_camera_counts_and_are_capped()
    {
        var clients = new Recorder();
        var (ringing, ringingId) = await DareAsync(started: false);
        Assert.False(await Cheer(ringing, ringing.TheoId, ringingId, "heart", clients));

        var (couple, occurrenceId) = await DareAsync();
        Assert.True(await Cheer(couple, couple.TheoId, occurrenceId, "gotThis", clients));
        var (group, method, args) = Assert.Single(clients.Sent);
        Assert.Equal((CoupleHub.UserGroupName(couple.MayaId), "ExerciseCheer"), (group, method));
        var cheer = Assert.IsType<ExerciseCheerDto>(args[0]);
        Assert.Equal((occurrenceId, couple.TheoId, "Theo", "gotThis"), (cheer.OccurrenceId, cheer.UserId, cheer.UserName, cheer.Cheer));

        // Only the three cheers; never from the exerciser to themselves.
        Assert.False(await Cheer(couple, couple.TheoId, occurrenceId, "You can do it", clients));
        Assert.False(await Cheer(couple, couple.MayaId, occurrenceId, "heart", clients));

        clock.UtcNow = clock.UtcNow.AddSeconds(30);
        var relayed = 0;
        for (var i = 0; i < ExerciseLiveGate.MaxCheers + 4; i++)
            if (await Cheer(couple, couple.TheoId, occurrenceId, "heart", clients)) relayed++;
        Assert.Equal(ExerciseLiveGate.MaxCheers, relayed);
    }

    private async Task<TestCouple> OtherCoupleAsync()
    {
        await using var db = database.CreateContext();
        return await TestCouple.AddAsync(db);
    }

    [Fact]
    public void Counting_started_is_an_exercise_notice_that_only_lasts_the_workout()
    {
        Assert.Equal(NotificationCategory.Exercise, NotificationRouting.CategoryOf(NotificationType.ExerciseStarted));
        var started = Notification.Create(Guid.NewGuid(), Guid.NewGuid(), NotificationType.ExerciseStarted, "Maya started their squats", "Watch live and cheer them on.", "{\"occurrenceId\":\"x\"}", clock.UtcNow);
        var push = PushPolicy.Build(started, "t", PushTokenKind.RegistrationToken, clock.UtcNow);
        Assert.Equal(("exerciseStarted", PushPolicy.GeneralChannel, false), (push.Data["type"], push.ChannelId, push.DataOnly));
        Assert.Equal(TimeSpan.FromMinutes(10), push.TimeToLive);
        Assert.True(PushPolicy.IsStale(started, clock.UtcNow.AddMinutes(11)));
    }

    /// <summary>The camera's pace for each exercise; the Android RepCounterTest pins the same numbers for minimumMillis.</summary>
    [Fact]
    public void The_catalog_has_the_new_exercises_at_the_paces_the_camera_counts()
    {
        Assert.Equal(13, ExerciseCatalog.All.Count);
        var paces = ExerciseCatalog.All.ToDictionary(item => item.Type, item => (item.Measure, item.DefaultTarget, item.MinTarget, item.MaxTarget, item.MinSecondsPerUnit));
        Assert.Equal((ExerciseMeasure.Reps, 20, 1, 200, 0.8), paces[ExerciseType.Lunge]);
        Assert.Equal((ExerciseMeasure.Reps, 15, 1, 200, 0.8), paces[ExerciseType.GluteBridge]);
        Assert.Equal((ExerciseMeasure.Reps, 30, 1, 400, 0.25), paces[ExerciseType.MountainClimber]);
        Assert.Equal((ExerciseMeasure.Reps, 10, 1, 100, 1.2), paces[ExerciseType.Burpee]);
        Assert.Equal((ExerciseMeasure.Seconds, 30, 5, 600, 0.9), paces[ExerciseType.WallSit]);
        Assert.Equal((ExerciseMeasure.Seconds, 20, 5, 300, 0.9), paces[ExerciseType.SidePlank]);
        Assert.Equal(["Lunges", "Glute bridges", "Mountain climbers", "Burpees", "Wall sit", "Side plank"], ExerciseCatalog.All.Skip(7).Select(item => item.DisplayName));
    }

    [Fact]
    public void New_exercises_are_checked_like_the_rest()
    {
        var started = clock.UtcNow;
        // 20 lunges take at least 16 seconds; a 60-second wall sit at least 54.
        Assert.Equal("exercise.too_fast", ExerciseVerification.Validate(ExerciseType.Lunge, 20, 20, 0.9, started, started.AddSeconds(15)).Error.Code);
        Assert.True(ExerciseVerification.Validate(ExerciseType.Lunge, 20, 20, 0.9, started, started.AddSeconds(16)).IsSuccess);
        Assert.Equal("exercise.too_fast", ExerciseVerification.Validate(ExerciseType.WallSit, 60, 60, 0.9, started, started.AddSeconds(53)).Error.Code);
        Assert.True(ExerciseVerification.Validate(ExerciseType.Burpee, 10, 10, 0.9, started, started.AddSeconds(12)).IsSuccess);
        Assert.Equal("exercise.invalid_target", ExerciseGoalRules.Validate(ExerciseType.Burpee, null, 101, "UTC", 30).Error.Code);
        Assert.Equal("exercise.invalid_target", ExerciseGoalRules.Validate(ExerciseType.SidePlank, null, 4, "UTC", 30).Error.Code);
        Assert.True(ExerciseGoalRules.Validate(ExerciseType.MountainClimber, null, 400, "UTC", 30).IsSuccess);
        Assert.Equal("wall sit", ExerciseText.InSentence("Wall sit", ExerciseType.WallSit));
        Assert.Equal("30 s", ExerciseText.Target(30, ExerciseType.SidePlank));
    }

    private sealed class Recorder : IHubCallerClients
    {
        public List<(string Group, string Method, object?[] Args)> Sent { get; } = [];

        public IClientProxy Caller => new Proxy(this, "caller");
        public IClientProxy Others => new Proxy(this, "others");
        public IClientProxy OthersInGroup(string groupName) => new Proxy(this, "others:" + groupName);
        public IClientProxy All => new Proxy(this, "*");
        public IClientProxy AllExcept(IReadOnlyList<string> excludedConnectionIds) => new Proxy(this, "*");
        public IClientProxy Client(string connectionId) => new Proxy(this, connectionId);
        public IClientProxy Clients(IReadOnlyList<string> connectionIds) => new Proxy(this, "*");
        public IClientProxy Group(string groupName) => new Proxy(this, groupName);
        public IClientProxy GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => new Proxy(this, groupName);
        public IClientProxy Groups(IReadOnlyList<string> groupNames) => new Proxy(this, string.Join(",", groupNames));
        public IClientProxy User(string userId) => new Proxy(this, userId);
        public IClientProxy Users(IReadOnlyList<string> userIds) => new Proxy(this, "*");

        private sealed class Proxy(Recorder recorder, string group) : IClientProxy
        {
            public Task SendCoreAsync(string method, object?[] args, CancellationToken cancellationToken = default)
            {
                recorder.Sent.Add((group, method, args));
                return Task.CompletedTask;
            }
        }
    }

    private sealed class Caller : HubCallerContext
    {
        public override string ConnectionId => "test-connection";
        public override string? UserIdentifier => null;
        public override ClaimsPrincipal? User => null;
        public override IDictionary<object, object?> Items { get; } = new Dictionary<object, object?>();
        public override IFeatureCollection Features { get; } = new FeatureCollection();
        public override CancellationToken ConnectionAborted => CancellationToken.None;
        public override void Abort() { }
    }
}
