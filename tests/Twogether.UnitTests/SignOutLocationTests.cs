using System.Security.Claims;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Admin;
using Twogether.Api.Controllers;
using Twogether.Api.Controllers.Admin;
using Twogether.Api.Hubs;
using Twogether.Api.Presence;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Location;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// Signing out stops location sharing on all of the person's phones: the session that ends tells the server, the
/// partner stops seeing the point, and the person's other phones hear that sharing is off and stop.
/// </summary>
public sealed class SignOutLocationTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now.AddMinutes(10));
    private readonly FakeHub hub = new();

    public void Dispose() => database.Dispose();

    /// <summary>Both partners sharing, each with a point.</summary>
    private async Task<TestCouple> BothSharingAsync()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        foreach (var userId in new[] { couple.MayaId, couple.TheoId })
        {
            var row = CoupleLocation.Start(couple.CoupleId, userId, clock.UtcNow);
            Assert.True(row.TryRecordFix(6.45, 3.39, 12, clock.UtcNow, clock.UtcNow));
            db.CoupleLocations.Add(row);
        }
        await db.SaveChangesAsync();
        return couple;
    }

    private AdminUsersController Admin(ApplicationDbContext db)
    {
        var http = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, Guid.NewGuid().ToString()), new Claim(ClaimTypes.Email, "ops@heartpact.me")], "test")),
        };
        return new AdminUsersController(db, clock, new AdminAudit(db, clock, new HttpContextAccessor { HttpContext = http }), new HubConnectionRegistry(), null!, hub, null!, null!,
            new LocationSharingStopper(db, hub, clock))
        {
            ControllerContext = new ControllerContext { HttpContext = http },
        };
    }

    private LocationController Location(ApplicationDbContext db, TestCouple couple, Guid userId)
        => new(db, new FakeCurrentUser(userId, couple.CoupleId), clock, hub, new LocationSharingStopper(db, hub, clock));

    private async Task AssertStoppedAsync(TestCouple couple)
    {
        await using var db = database.CreateContext();
        var maya = await db.CoupleLocations.AsNoTracking().SingleAsync(item => item.UserId == couple.MayaId);
        Assert.False(maya.SharingEnabled);
        Assert.Null(maya.Latitude); // the point goes, as with Stop
        Assert.True((await db.CoupleLocations.AsNoTracking().SingleAsync(item => item.UserId == couple.TheoId)).SharingEnabled);
        // Every phone in the couple drops Maya, and the indicator turns off for both of them.
        Assert.Contains(hub.Sent, item => item.Method == LocationEvents.Stopped && item.Group == CoupleHub.GroupName(couple.CoupleId) && Equals(item.Args[0], couple.MayaId));
        var state = Assert.IsType<LocationSharingStateDto>(Assert.Single(hub.Payloads(LocationEvents.SharingChanged)));
        Assert.Equal((couple.MayaId, LocationSharingStatus.Off), (state.UserId, state.Status));

        // Theo's map no longer has her, and her other phone's next heartbeat is told to stop.
        var theirs = Assert.IsAssignableFrom<IReadOnlyList<LocationDto>>(Assert.IsType<OkObjectResult>((await Location(db, couple, couple.TheoId).List(default)).Result).Value);
        Assert.DoesNotContain(theirs, point => point.UserId == couple.MayaId);
        var beat = Assert.IsType<LocationSharingStatusDto>(Assert.IsType<OkObjectResult>((await Location(db, couple, couple.MayaId).Heartbeat(default)).Result).Value);
        Assert.Equal(LocationSharingStatus.Off, beat.Me.Status);
        Assert.False((await db.CoupleLocations.AsNoTracking().SingleAsync(item => item.UserId == couple.MayaId)).SharingEnabled);
    }

    [Fact]
    public async Task Signing_out_everywhere_from_the_admin_console_stops_sharing()
    {
        var couple = await BothSharingAsync();
        await using (var db = database.CreateContext())
            Assert.IsType<NoContentResult>(await Admin(db).SignOut(couple.MayaId, default));
        await AssertStoppedAsync(couple);
    }

    [Fact]
    public async Task Disabling_an_account_stops_its_sharing()
    {
        var couple = await BothSharingAsync();
        await using (var db = database.CreateContext())
            Assert.IsType<NoContentResult>(await Admin(db).Disable(couple.MayaId, new AdminReasonRequest("spam"), default));
        await AssertStoppedAsync(couple);
    }

    [Fact]
    public async Task The_app_signing_out_stops_sharing_on_every_phone_of_the_person()
    {
        // The app's sign-out sends Stop with the session that is ending; the share is the person's, not the phone's.
        var couple = await BothSharingAsync();
        await using (var db = database.CreateContext())
            Assert.IsType<NoContentResult>(await Location(db, couple, couple.MayaId).Stop(default));
        await AssertStoppedAsync(couple);
    }

    [Fact]
    public async Task A_phone_still_sending_after_sharing_stopped_elsewhere_is_told_it_is_off()
    {
        var couple = await BothSharingAsync();
        await using (var db = database.CreateContext())
            await new LocationSharingStopper(db, hub, clock).StopAsync(couple.MayaId, default);
        hub.Sent.Clear();

        await using (var db = database.CreateContext())
        {
            var caller = new CallerRecorder();
            var coupleHub = new CoupleHub(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, new CouplePresenceTracker(), new GameLiveState(), new HubConnectionRegistry(),
                new LocationSharingStopper(db, hub, clock), new LocationFixThrottle(), new GameInkGate())
            {
                Clients = caller,
                Context = new TestCallerContext(),
            };
            Assert.Null(await coupleHub.UpdateLocationAt(6.46, 3.40, 10, new DateTimeOffset(clock.UtcNow).ToUnixTimeMilliseconds()));

            var (method, args) = Assert.Single(caller.ToCaller);
            Assert.Equal(LocationEvents.SharingChanged, method);
            var state = Assert.IsType<LocationSharingStateDto>(args[0]);
            Assert.Equal((couple.MayaId, LocationSharingStatus.Off), (state.UserId, state.Status));
            Assert.Empty(caller.ToOthers); // Theo hears nothing: nothing changed for him
        }
        await using (var check = database.CreateContext())
            Assert.Null((await check.CoupleLocations.AsNoTracking().SingleAsync(item => item.UserId == couple.MayaId)).Latitude); // and the fix was dropped
    }

    private sealed class CallerRecorder : IHubCallerClients
    {
        public List<(string Method, object?[] Args)> ToCaller { get; } = [];
        public List<(string Method, object?[] Args)> ToOthers { get; } = [];

        public IClientProxy Caller => new Proxy(ToCaller);
        public IClientProxy Others => new Proxy(ToOthers);
        public IClientProxy OthersInGroup(string groupName) => new Proxy(ToOthers);
        public IClientProxy All => new Proxy(ToOthers);
        public IClientProxy AllExcept(IReadOnlyList<string> excludedConnectionIds) => new Proxy(ToOthers);
        public IClientProxy Client(string connectionId) => new Proxy(ToOthers);
        public IClientProxy Clients(IReadOnlyList<string> connectionIds) => new Proxy(ToOthers);
        public IClientProxy Group(string groupName) => new Proxy(ToOthers);
        public IClientProxy GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => new Proxy(ToOthers);
        public IClientProxy Groups(IReadOnlyList<string> groupNames) => new Proxy(ToOthers);
        public IClientProxy User(string userId) => new Proxy(ToOthers);
        public IClientProxy Users(IReadOnlyList<string> userIds) => new Proxy(ToOthers);

        private sealed class Proxy(List<(string, object?[])> sent) : IClientProxy
        {
            public Task SendCoreAsync(string method, object?[] args, CancellationToken cancellationToken = default)
            {
                sent.Add((method, args));
                return Task.CompletedTask;
            }
        }
    }

    private sealed class TestCallerContext : HubCallerContext
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
