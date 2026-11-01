using System.Security.Claims;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
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
/// Draw and guess's live ink (docs/games.md): the drawer's stroke goes from the couple hub to the guesser only, as it's
/// drawn; only the round's drawer may send it, only for the couple's own game in play, and it carries points, never the word.
/// </summary>
public sealed class DrawAndGuessInkTests : IDisposable
{
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now.AddMinutes(10));
    private readonly FakeHub hub = new();
    private readonly GameEngine engine = new();
    private readonly GameInkGate inkGate = new();

    public void Dispose() => database.Dispose();

    private static readonly float[] Line = [0.1f, 0.2f, 0.15f, 0.25f, 0.2f, 0.31f];

    /// <summary>A game in play after its countdown; Maya (the first player) draws round 0.</summary>
    private async Task<(TestCouple Couple, Guid SessionId)> PlayingAsync(GameType type = GameType.Handwriting, bool countingDown = false)
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

    private CoupleHub Hub(ApplicationDbContext db, Guid userId, Guid? coupleId, Recorder clients)
        => new(db, new FakeCurrentUser(userId, coupleId), clock, new CouplePresenceTracker(), new GameLiveState(), new HubConnectionRegistry(),
            new LocationSharingStopper(db, hub, clock), new LocationFixThrottle(), inkGate, new ExerciseLiveGate())
        {
            Clients = clients,
            Context = new Caller(),
        };

    private async Task<bool> Ink(TestCouple couple, Guid userId, Guid sessionId, Recorder clients, int cell = 0, int stroke = 1, float[]? points = null)
    {
        await using var db = database.CreateContext();
        return await Hub(db, userId, couple.CoupleId, clients).Ink(sessionId, cell, stroke, points ?? Line);
    }

    [Fact]
    public async Task The_drawers_stroke_goes_to_the_guesser_only_and_carries_nothing_but_points()
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        Assert.True(await Ink(couple, couple.MayaId, sessionId, clients, cell: 3, stroke: 7));

        var (group, method, args) = Assert.Single(clients.Sent);
        Assert.Equal(CoupleHub.UserGroupName(couple.TheoId), group);
        Assert.Equal("GameInk", method);
        var ink = Assert.IsType<GameInkDto>(Assert.Single(args));
        Assert.Equal((sessionId, couple.MayaId, 3, 7), (ink.SessionId, ink.UserId, ink.Cell, ink.Stroke));
        Assert.Equal(Line, ink.Points);
        // Never the word: the message is the place, the stroke number and the points, nothing else.
        var fields = JsonDocument.Parse(JsonSerializer.Serialize(ink, new JsonSerializerOptions(JsonSerializerDefaults.Web))).RootElement.EnumerateObject().Select(p => p.Name);
        Assert.Equal(["sessionId", "userId", "cell", "stroke", "points"], fields);

        // A late place in a long drawing is fine; an undo or a clear (-1, from a place on) goes the same way.
        Assert.True(await Ink(couple, couple.MayaId, sessionId, clients, cell: DrawAndGuessRules.MaxStrokesPerRound - 1));
        Assert.True(await Ink(couple, couple.MayaId, sessionId, clients, cell: -1, stroke: 2, points: []));
        Assert.All(clients.Sent, sent => Assert.Equal(CoupleHub.UserGroupName(couple.TheoId), sent.Group));

        // Nothing went to the couple's shared group, nor back to the drawer.
        Assert.DoesNotContain(clients.Sent, sent => sent.Group == CoupleHub.GroupName(couple.CoupleId) || sent.Group == CoupleHub.UserGroupName(couple.MayaId));
    }

    [Fact]
    public async Task The_guesser_may_not_ink()
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        Assert.False(await Ink(couple, couple.TheoId, sessionId, clients));
        // Not even after the drawer has been cleared to ink on the same game.
        Assert.True(await Ink(couple, couple.MayaId, sessionId, clients));
        Assert.False(await Ink(couple, couple.TheoId, sessionId, clients));
        Assert.Single(clients.Sent);
    }

    [Fact]
    public async Task A_new_round_swaps_who_may_ink_at_once()
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        Assert.True(await Ink(couple, couple.MayaId, sessionId, clients));

        // Maya passes: round 1 is Theo's to draw. Her pass to ink is forgotten with the turn, not seconds later.
        var services = new ServiceCollection().AddSingleton(inkGate).BuildServiceProvider();
        await using (var db = database.CreateContext())
        {
            var controller = new GameController(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, engine, new NoWords(), null!, hub)
            {
                ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext { RequestServices = services } },
            };
            var version = (await db.GameSessions.AsNoTracking().SingleAsync(item => item.Id == sessionId)).StateVersion;
            Assert.IsType<OkObjectResult>((await controller.ApplyAction(sessionId, new ApplyGameActionRequest(version, "pass", null, null), default)).Result);
        }

        clients.Sent.Clear();
        Assert.False(await Ink(couple, couple.MayaId, sessionId, clients));
        Assert.True(await Ink(couple, couple.TheoId, sessionId, clients));
        var (group, _, _) = Assert.Single(clients.Sent);
        Assert.Equal(CoupleHub.UserGroupName(couple.MayaId), group);
    }

    [Fact]
    public async Task Ink_for_another_couples_game_or_a_game_not_in_play_is_refused()
    {
        var clients = new Recorder();
        var (couple, sessionId) = await PlayingAsync();

        // Someone from another couple, even naming this game; and someone with no couple at all.
        TestCouple other;
        await using (var setup = database.CreateContext()) other = await TestCouple.AddAsync(setup);
        Assert.False(await Ink(other, other.MayaId, sessionId, clients));
        await using (var db = database.CreateContext())
            Assert.False(await Hub(db, couple.MayaId, null, clients).Ink(sessionId, 0, 1, Line));

        // No such game.
        Assert.False(await Ink(couple, couple.MayaId, Guid.NewGuid(), clients));

        // Still counting down.
        var (waiting, countingDown) = await PlayingAsync(countingDown: true);
        Assert.False(await Ink(waiting, waiting.MayaId, countingDown, clients));

        // Ended.
        var (ended, endedId) = await PlayingAsync();
        await using (var db = database.CreateContext())
        {
            var session = await db.GameSessions.SingleAsync(item => item.Id == endedId);
            session.EndBy(ended.TheoId, clock.UtcNow);
            await db.SaveChangesAsync();
        }
        Assert.False(await Ink(ended, ended.MayaId, endedId, clients));

        // A game without live ink.
        var (connect, connectFour) = await PlayingAsync(GameType.ConnectFour);
        Assert.False(await Ink(connect, connect.MayaId, connectFour, clients));

        Assert.Empty(clients.Sent);
    }

    [Fact]
    public async Task Each_game_keeps_its_own_board()
    {
        // A place past Tic-tac-toe's nine squares is a stroke's place in a drawing, never a square.
        var (couple, tictactoe) = await PlayingAsync(GameType.TicTacToe);
        var clients = new Recorder();
        Assert.True(await Ink(couple, couple.MayaId, tictactoe, clients, cell: 8));
        Assert.False(await Ink(couple, couple.MayaId, tictactoe, clients, cell: 9));
        Assert.False(await Ink(couple, couple.MayaId, tictactoe, clients, cell: 40));
        Assert.Single(clients.Sent);

        var (drawing, sessionId) = await PlayingAsync();
        Assert.False(await Ink(drawing, drawing.MayaId, sessionId, clients, cell: DrawAndGuessRules.MaxStrokesPerRound));
    }

    [Theory]
    [InlineData(-2, 0, 4)]   // below -1
    [InlineData(0, -1, 4)]   // a negative stroke number
    [InlineData(0, 1, 3)]    // half a point
    [InlineData(0, 1, 66)]   // more than 32 points
    public async Task Malformed_ink_is_refused(int cell, int stroke, int numbers)
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        Assert.False(await Ink(couple, couple.MayaId, sessionId, clients, cell, stroke, Enumerable.Repeat(0.5f, numbers).ToArray()));
        Assert.False(await Ink(couple, couple.MayaId, sessionId, clients, points: [0.5f, -0.1f]));
        Assert.False(await Ink(couple, couple.MayaId, sessionId, clients, points: [float.PositiveInfinity, 0.5f]));
        await using (var db = database.CreateContext())
            Assert.False(await Hub(db, couple.MayaId, couple.CoupleId, clients).Ink(sessionId, 0, 1, null!));
        Assert.Empty(clients.Sent);
    }

    [Fact]
    public async Task Ink_faster_than_the_cap_is_dropped()
    {
        var (couple, sessionId) = await PlayingAsync();
        var clients = new Recorder();
        var relayed = 0;
        for (var i = 0; i < GameInkGate.MaxPerSecond + 10; i++)
            if (await Ink(couple, couple.MayaId, sessionId, clients, stroke: i)) relayed++;
        Assert.Equal(GameInkGate.MaxPerSecond, relayed);
        Assert.True(GameInkGate.AppRate < GameInkGate.MaxPerSecond);

        clock.UtcNow = clock.UtcNow.AddSeconds(1);
        Assert.True(await Ink(couple, couple.MayaId, sessionId, clients));
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
