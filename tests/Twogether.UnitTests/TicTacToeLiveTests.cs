using System.Security.Claims;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Api.Hubs;
using Twogether.Api.Presence;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Location;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// Real-time tic-tac-toe through the API: moves made on an older board still count (both play at once), and live ink
/// goes from the couple hub to the partner only, for the couple's own game in play (docs/games.md).
/// </summary>
public sealed class TicTacToeLiveTests : IDisposable
{
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now.AddMinutes(10));
    private readonly FakeHub hub = new();
    private readonly GameEngine engine = new();
    private readonly GameInkGate inkGate = new();

    public void Dispose() => database.Dispose();

    private async Task<(TestCouple Couple, Guid SessionId)> PlayingAsync(GameType type = GameType.TicTacToe, bool countingDown = false)
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var session = GameSession.Create(couple.CoupleId, type, couple.MayaId, clock.UtcNow.AddMinutes(-1), """{"region":"global"}""");
        var countdownEnds = countingDown ? clock.UtcNow.AddSeconds(3) : clock.UtcNow.AddSeconds(-1);
        session.AcceptInvite(countdownEnds, clock.UtcNow.AddSeconds(-6));
        var start = engine.Start(type, [couple.MayaId, couple.TheoId], countdownEnds, session.SetupJson);
        session.Start(start.CurrentTurnUserId!.Value, start.DeadlineUtc, start.StateJson, clock.UtcNow.AddSeconds(-6));
        db.GameSessions.Add(session);
        await db.SaveChangesAsync();
        return (couple, session.Id);
    }

    private GameController Controller(ApplicationDbContext db, TestCouple couple, Guid userId)
        => new(db, new FakeCurrentUser(userId, couple.CoupleId), clock, engine, new NoWords(), null!, hub);

    private async Task<ActionResult<GameSessionDto>> Place(TestCouple couple, Guid userId, Guid sessionId, long version, int cell)
    {
        await using var db = database.CreateContext();
        return await Controller(db, couple, userId).ApplyAction(sessionId, new ApplyGameActionRequest(version, "place", """{"game":0}""", cell), default);
    }

    [Fact]
    public async Task Marks_made_on_the_same_board_at_once_both_land_and_a_taken_square_is_refused()
    {
        var (couple, sessionId) = await PlayingAsync();
        var seen = 1L; // the version both phones have on screen
        var maya = Assert.IsType<OkObjectResult>((await Place(couple, couple.MayaId, sessionId, seen, 0)).Result);
        Assert.Equal(2, Assert.IsType<GameSessionDto>(maya.Value).StateVersion);

        // Theo's mark was made on the same board as Maya's: it still counts.
        var theo = Assert.IsType<OkObjectResult>((await Place(couple, couple.TheoId, sessionId, seen, 4)).Result);
        Assert.Equal(3, Assert.IsType<GameSessionDto>(theo.Value).StateVersion);

        // Both partners hear about every mark.
        Assert.Equal(4, hub.Sent.Count(item => item.Method == "GameStateChanged"));

        // A claim on a square that's gone fails as a plain move error, which the app shows as "Too slow!".
        clock.UtcNow = clock.UtcNow.AddSeconds(1);
        var late = Assert.IsType<UnprocessableEntityObjectResult>((await Place(couple, couple.TheoId, sessionId, seen, 0)).Result);
        var error = Assert.IsType<ApiError>(late.Value);
        Assert.Equal("game.invalid_action", error.Code);
        Assert.Equal("That square is taken.", error.Message);
    }

    [Fact]
    public async Task Other_games_still_refuse_a_move_made_on_an_older_board()
    {
        var (couple, sessionId) = await PlayingAsync(GameType.ConnectFour);
        await using var db = database.CreateContext();
        var stale = await Controller(db, couple, couple.MayaId).ApplyAction(sessionId, new ApplyGameActionRequest(0, "drop", null, 3), default);
        var conflict = Assert.IsType<ConflictObjectResult>(stale.Result);
        Assert.Equal("game.version_conflict", Assert.IsType<ApiError>(conflict.Value).Code);
    }

    private CoupleHub Hub(ApplicationDbContext db, TestCouple couple, Guid userId, Recorder clients, Guid? coupleId = null)
        => new(db, new FakeCurrentUser(userId, coupleId ?? couple.CoupleId), clock, new CouplePresenceTracker(), new GameLiveState(), new HubConnectionRegistry(),
            new LocationSharingStopper(db, hub, clock), new LocationFixThrottle(), inkGate, new ExerciseLiveGate())
        {
            Clients = clients,
            Context = new Caller(),
        };

    private static readonly float[] Stroke = [0.1f, 0.2f, 0.3f, 0.35f];

    [Fact]
    public async Task Ink_goes_to_the_partner_only()
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        await using var db = database.CreateContext();
        Assert.True(await Hub(db, couple, couple.MayaId, clients).Ink(sessionId, 4, 0, Stroke));

        var (group, method, args) = Assert.Single(clients.Sent);
        Assert.Equal(CoupleHub.UserGroupName(couple.TheoId), group);
        Assert.Equal("GameInk", method);
        var ink = Assert.IsType<GameInkDto>(args[0]);
        Assert.Equal((sessionId, couple.MayaId, 4, 0), (ink.SessionId, ink.UserId, ink.Cell, ink.Stroke));
        Assert.Equal(Stroke, ink.Points);

        // Lifting the pen without a mark clears it.
        Assert.True(await Hub(db, couple, couple.MayaId, clients).Ink(sessionId, -1, 0, []));
    }

    [Fact]
    public async Task Ink_from_someone_outside_the_couple_is_refused()
    {
        var (couple, sessionId) = await PlayingAsync();
        Guid otherCoupleId;
        Guid stranger;
        await using (var setup = database.CreateContext())
        {
            var other = await TestCouple.AddAsync(setup);
            (otherCoupleId, stranger) = (other.CoupleId, other.MayaId);
        }
        var clients = new Recorder();
        await using var db = database.CreateContext();
        Assert.False(await Hub(db, couple, stranger, clients, otherCoupleId).Ink(sessionId, 4, 0, Stroke));
        Assert.False(await new CoupleHub(db, new FakeCurrentUser(stranger, null), clock, new CouplePresenceTracker(), new GameLiveState(), new HubConnectionRegistry(),
            new LocationSharingStopper(db, hub, clock), new LocationFixThrottle(), inkGate, new ExerciseLiveGate()) { Clients = clients, Context = new Caller() }.Ink(sessionId, 4, 0, Stroke));
        Assert.Empty(clients.Sent);
    }

    [Fact]
    public async Task Ink_for_a_game_not_in_play_is_refused()
    {
        var clients = new Recorder();

        // Still counting down.
        var (couple, countingDown) = await PlayingAsync(countingDown: true);
        await using (var db = database.CreateContext())
            Assert.False(await Hub(db, couple, couple.MayaId, clients).Ink(countingDown, 4, 0, Stroke));

        // Ended.
        var (ended, endedId) = await PlayingAsync();
        await using (var db = database.CreateContext())
        {
            var session = await db.GameSessions.SingleAsync(item => item.Id == endedId);
            session.EndBy(ended.MayaId, clock.UtcNow);
            await db.SaveChangesAsync();
            Assert.False(await Hub(db, ended, ended.TheoId, clients).Ink(endedId, 4, 0, Stroke));
        }

        // A game without live ink.
        var (other, connectFour) = await PlayingAsync(GameType.ConnectFour);
        await using (var db = database.CreateContext())
            Assert.False(await Hub(db, other, other.MayaId, clients).Ink(connectFour, 4, 0, Stroke));

        // No such game.
        await using (var db = database.CreateContext())
            Assert.False(await Hub(db, other, other.MayaId, clients).Ink(Guid.NewGuid(), 4, 0, Stroke));

        Assert.Empty(clients.Sent);
    }

    [Theory]
    [InlineData(9, 0, 4)]   // off the board
    [InlineData(-2, 0, 4)]
    [InlineData(4, -1, 4)]  // a negative stroke number
    [InlineData(4, 0, 3)]   // half a point
    [InlineData(4, 0, 66)]  // more than 32 points
    public async Task Malformed_ink_is_refused(int cell, int stroke, int numbers)
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        await using var db = database.CreateContext();
        Assert.False(await Hub(db, couple, couple.MayaId, clients).Ink(sessionId, cell, stroke, Enumerable.Repeat(0.5f, numbers).ToArray()));
        Assert.False(await Hub(db, couple, couple.MayaId, clients).Ink(sessionId, 4, 0, [0.5f, 1.5f]));
        Assert.False(await Hub(db, couple, couple.MayaId, clients).Ink(sessionId, 4, 0, [float.NaN, 0.5f]));
        Assert.Empty(clients.Sent);
    }

    [Fact]
    public async Task Ink_faster_than_the_cap_is_dropped()
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        await using var db = database.CreateContext();
        var relayed = 0;
        for (var i = 0; i < GameInkGate.MaxPerSecond + 5; i++)
            if (await Hub(db, couple, couple.MayaId, clients).Ink(sessionId, 4, 0, Stroke)) relayed++;
        Assert.Equal(GameInkGate.MaxPerSecond, relayed);

        clock.UtcNow = clock.UtcNow.AddSeconds(1);
        Assert.True(await Hub(db, couple, couple.MayaId, clients).Ink(sessionId, 4, 0, Stroke));
    }

    private sealed class NoWords : IGameWordValidator
    {
        public Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(string stateJson, string answersJson, CancellationToken cancellationToken = default)
            => Task.FromResult<IReadOnlyDictionary<string, bool?>>(new Dictionary<string, bool?>());
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
