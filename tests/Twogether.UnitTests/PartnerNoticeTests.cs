using System.Security.Claims;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Admin;
using Twogether.Api.Controllers;
using Twogether.Api.Controllers.Admin;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Location;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Notifications;
using Twogether.Infrastructure.Billing;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Results;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// The partner notices (docs/push-notifications.md): "Plus is on" once when Plus newly starts (a first Google Play or
/// card purchase, or a gift), "sharing their location" when a partner starts sharing, at most twice a day, and
/// "sharing their cycle" when the owner goes from sharing nothing to sharing something. Real controllers and syncs on a
/// real database.
/// </summary>
public sealed class PartnerNoticeTests : IDisposable
{
    private static readonly DateTime Now = TestCouple.Now;
    private const string Monthly = "heartpact_plus_monthly";
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);
    private readonly FakeHub hub = new();
    private readonly PlayStore play = new();
    private readonly BillingSettings billing = new() { ProductIds = [Monthly] };
    private readonly BachsSettings bachsSettings = new() { ApiKey = "sk_sandbox_test", WebhookSecret = "whsec_test", MonthlyProductId = "prod_month", YearlyProductId = "prod_year" };

    public void Dispose() => database.Dispose();

    private async Task<TestCouple> CoupleAsync()
    {
        await using var db = database.CreateContext();
        return await TestCouple.AddAsync(db);
    }

    private async Task<List<Notification>> NoticesAsync(NotificationType type)
    {
        await using var db = database.CreateContext();
        return await db.Notifications.AsNoTracking().Where(item => item.Type == type).OrderBy(item => item.CreatedAtUtc).ToListAsync();
    }

    private static (string? Source, DateTime? Until) DataOf(Notification notice)
    {
        using var json = JsonDocument.Parse(notice.DataJson!);
        var until = json.RootElement.GetProperty("untilUtc");
        return (json.RootElement.GetProperty("source").GetString(), until.ValueKind == JsonValueKind.Null ? null : until.GetDateTime().ToUniversalTime());
    }

    [Fact]
    public void The_new_types_are_placed_where_they_belong()
    {
        Assert.Equal(NotificationCategory.Couple, NotificationRouting.CategoryOf(NotificationType.PlusStarted));
        Assert.Equal(NotificationCategory.Couple, NotificationRouting.CategoryOf(NotificationType.LocationShared));
        Assert.Equal(NotificationCategory.Cycle, NotificationRouting.CategoryOf(NotificationType.CycleShared));
        Assert.Equal(new[] { "plusStarted", "locationShared", "cycleShared" },
            new[] { NotificationType.PlusStarted, NotificationType.LocationShared, NotificationType.CycleShared }.Select(type => JsonNamingPolicy.CamelCase.ConvertName(type.ToString())));
    }

    // Plus started: Google Play

    /// <summary>Google Play as a test controls it: each token answers whatever was set for it last.</summary>
    private sealed class PlayStore : IGooglePlayBilling
    {
        public Dictionary<string, PlaySubscriptionSnapshot> Purchases { get; } = [];
        public bool IsConfigured => true;
        public Task<Result<PlaySubscriptionSnapshot>> GetAsync(string purchaseToken, CancellationToken cancellationToken)
            => Task.FromResult(Purchases.TryGetValue(purchaseToken, out var state) ? Result.Success(state) : Result.Failure<PlaySubscriptionSnapshot>(Error.NotFound("billing.unknown_purchase", "unknown")));
        public Task<Result> AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken) => Task.FromResult(Result.Success());
        public Task<Result> CancelAsync(string productId, string purchaseToken, CancellationToken cancellationToken) => Task.FromResult(Result.Success());
        public Task<Result> RefundAsync(string productId, string purchaseToken, CancellationToken cancellationToken) => Task.FromResult(Result.Success());
        public Task<Result> RevokeAsync(string productId, string purchaseToken, CancellationToken cancellationToken) => Task.FromResult(Result.Success());

        public void Set(string token, Guid buyer, StoreSubscriptionStatus status, DateTime expires, bool renews = true)
            => Purchases[token] = new PlaySubscriptionSnapshot(Monthly, status, expires, renews, true, buyer.ToString("N"), null);
    }

    private async Task<int> VerifyAsync(Guid userId, Guid coupleId, string token)
    {
        await using var db = database.CreateContext();
        var controller = new BillingController(new FakeCurrentUser(userId, coupleId), new EntitlementService(db, clock, billing),
            new GooglePlaySubscriptionSync(db, play, clock, billing), play, billing, NullLogger<BillingController>.Instance, hub: hub);
        var result = await controller.VerifyGoogle(new VerifyGooglePurchaseRequest(Monthly, token), default);
        return result.Result is ObjectResult objectResult ? objectResult.StatusCode ?? 200 : 0;
    }

    /// <summary>What Google's real-time notification does: the same sync, with no caller.</summary>
    private async Task NotifiedByGoogleAsync(string token)
    {
        await using var db = database.CreateContext();
        Assert.True((await new GooglePlaySubscriptionSync(db, play, clock, billing).SyncAsync(token, null, default)).IsSuccess);
    }

    [Fact]
    public async Task A_first_play_purchase_tells_the_buyer_and_the_partner_once()
    {
        var couple = await CoupleAsync();
        play.Set("tok-1", couple.MayaId, StoreSubscriptionStatus.Active, Now.AddDays(30));

        Assert.Equal(200, await VerifyAsync(couple.MayaId, couple.CoupleId, "tok-1"));
        // Google's notification of the same purchase, a second verify (app start) and the renewals say nothing more,
        // even a renewal that reaches us after the old period had run out.
        await NotifiedByGoogleAsync("tok-1");
        Assert.Equal(200, await VerifyAsync(couple.MayaId, couple.CoupleId, "tok-1"));
        clock.UtcNow = Now.AddDays(30).AddHours(2);
        play.Set("tok-1", couple.MayaId, StoreSubscriptionStatus.Active, Now.AddDays(60));
        await NotifiedByGoogleAsync("tok-1");

        var notices = await NoticesAsync(NotificationType.PlusStarted);
        Assert.Equal(2, notices.Count);
        var maya = Assert.Single(notices, item => item.UserId == couple.MayaId);
        var theo = Assert.Single(notices, item => item.UserId == couple.TheoId);
        Assert.Equal(("Welcome to Heartpact Plus", "Plus is on for both of you: unlimited dares, more photo estimates, a bigger album and longer question history."), (maya.Title, maya.Body));
        Assert.Equal(("Maya got you Heartpact Plus", "Maya got Plus for both of you: unlimited dares, more photo estimates, a bigger album and longer question history."), (theo.Title, theo.Body));
        Assert.Equal(("bought", (DateTime?)null), DataOf(maya));
        Assert.Equal(("partner", (DateTime?)null), DataOf(theo));
        Assert.All(notices, item => Assert.Equal(couple.CoupleId, item.CoupleId));
        // Shown live too, each to its own reader.
        Assert.Equal(2, hub.Payloads(ExerciseEvents.NotificationCreated).Count());
    }

    [Fact]
    public async Task A_pending_play_payment_tells_when_it_goes_through_and_a_recovery_from_hold_does_not()
    {
        var couple = await CoupleAsync();
        play.Set("tok-1", couple.MayaId, StoreSubscriptionStatus.Pending, Now.AddDays(30));
        Assert.Equal(200, await VerifyAsync(couple.MayaId, couple.CoupleId, "tok-1"));
        Assert.Empty(await NoticesAsync(NotificationType.PlusStarted));

        play.Set("tok-1", couple.MayaId, StoreSubscriptionStatus.Canceled, Now.AddDays(30), renews: false);
        await NotifiedByGoogleAsync("tok-1");
        Assert.Equal(2, (await NoticesAsync(NotificationType.PlusStarted)).Count);
        var maya = Assert.Single(await NoticesAsync(NotificationType.PlusStarted), item => item.UserId == couple.MayaId);
        Assert.Equal(("bought", (DateTime?)Now.AddDays(30)), DataOf(maya));

        play.Set("tok-1", couple.MayaId, StoreSubscriptionStatus.OnHold, Now.AddDays(30));
        await NotifiedByGoogleAsync("tok-1");
        play.Set("tok-1", couple.MayaId, StoreSubscriptionStatus.Active, Now.AddDays(60));
        await NotifiedByGoogleAsync("tok-1");
        Assert.Equal(2, (await NoticesAsync(NotificationType.PlusStarted)).Count);
    }

    [Fact]
    public async Task Nobody_is_told_when_the_partner_already_pays()
    {
        var couple = await CoupleAsync();
        play.Set("theo", couple.TheoId, StoreSubscriptionStatus.Active, Now.AddDays(30));
        await NotifiedByGoogleAsync("theo");
        Assert.Equal(2, (await NoticesAsync(NotificationType.PlusStarted)).Count);

        play.Set("maya", couple.MayaId, StoreSubscriptionStatus.Active, Now.AddDays(30));
        Assert.Equal(200, await VerifyAsync(couple.MayaId, couple.CoupleId, "maya"));

        Assert.Equal(2, (await NoticesAsync(NotificationType.PlusStarted)).Count);
        Assert.DoesNotContain(await NoticesAsync(NotificationType.PlusStarted), item => DataOf(item).Source == "bought" && item.UserId == couple.MayaId);
    }

    [Fact]
    public async Task Someone_without_a_partner_yet_hears_alone()
    {
        Guid userId, coupleId;
        await using (var db = database.CreateContext())
        {
            var ana = User.Create("ana@example.com", "Ana", new DateOnly(1996, 1, 2), Now);
            var waiting = Couple.Create(ana.Id, Now);
            ana.SetCouple(waiting.Id, Now);
            db.AddRange(ana, waiting);
            await db.SaveChangesAsync();
            (userId, coupleId) = (ana.Id, waiting.Id);
        }
        play.Set("tok-1", userId, StoreSubscriptionStatus.Active, Now.AddDays(30));

        Assert.Equal(200, await VerifyAsync(userId, coupleId, "tok-1"));

        var notice = Assert.Single(await NoticesAsync(NotificationType.PlusStarted));
        Assert.Equal((userId, coupleId, "bought"), (notice.UserId, notice.CoupleId, DataOf(notice).Source));
    }

    // Plus started: card (Bachs)

    private static string Iso(DateTime at) => at.ToString("yyyy-MM-dd'T'HH:mm:ss.ffffff'+00:00'");

    private static string SubscriptionEvent(string eventId, string type, DateTime at, Guid userId, string status, string subscriptionId = "sub_1")
        => JsonSerializer.Serialize(new
        {
            id = eventId,
            type,
            created_at = Iso(at),
            data = new Dictionary<string, object?>
            {
                ["subscription_id"] = subscriptionId,
                ["customer"] = new { customer_id = "cust_1" },
                ["product_id"] = "prod_month",
                ["status"] = status,
                ["currency"] = "USD",
                ["amount"] = "7.99",
                ["current_period_end"] = Iso(at.AddDays(30)),
                ["cancel_at_period_end"] = false,
                ["created_at"] = Iso(Now),
                ["metadata"] = new Dictionary<string, string> { ["user_id"] = userId.ToString() },
            },
        });

    private async Task<IReadOnlyList<Notification>> DeliverAsync(string json)
    {
        await using var db = database.CreateContext();
        var sync = new BachsSubscriptionSync(db, new FakeBachsBilling(), bachsSettings, clock, NullLogger<BachsSubscriptionSync>.Instance);
        Assert.True((await sync.HandleAsync(json, default)).IsSuccess);
        return sync.Notices.ToList();
    }

    [Fact]
    public async Task A_card_subscription_tells_both_when_it_is_created_and_not_on_updates()
    {
        var couple = await CoupleAsync();

        var created = await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId, "trialing"));
        Assert.Equal(2, created.Count);
        clock.UtcNow = Now.AddDays(31);
        Assert.Empty(await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionUpdated, Now.AddDays(31), couple.MayaId, "active")));
        Assert.Empty(await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionUpdated, Now.AddDays(31), couple.MayaId, "active")));

        var notices = await NoticesAsync(NotificationType.PlusStarted);
        Assert.Equal(new string?[] { "bought", "partner" }, notices.OrderBy(item => item.UserId == couple.MayaId ? 0 : 1).Select(item => DataOf(item).Source));
    }

    [Fact]
    public async Task A_card_subscription_that_starts_unpaid_tells_on_its_first_update_to_active()
    {
        var couple = await CoupleAsync();

        Assert.Empty(await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId, "incomplete")));
        Assert.Equal(2, (await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionUpdated, Now.AddMinutes(1), couple.MayaId, "active"))).Count);
        Assert.Empty(await DeliverAsync(SubscriptionEvent("evt_3", BachsEvents.SubscriptionUpdated, Now.AddMinutes(2), couple.MayaId, "past_due")));
        Assert.Empty(await DeliverAsync(SubscriptionEvent("evt_4", BachsEvents.SubscriptionUpdated, Now.AddMinutes(3), couple.MayaId, "active")));

        Assert.Equal(2, (await NoticesAsync(NotificationType.PlusStarted)).Count);
    }

    [Fact]
    public async Task A_card_subscription_during_a_gift_tells_nobody()
    {
        var couple = await CoupleAsync();
        await using (var db = database.CreateContext())
        {
            db.PlusGrants.Add(PlusGrant.Create(couple.CoupleId, Now.AddDays(5), "support", Guid.NewGuid(), Now));
            await db.SaveChangesAsync();
        }

        Assert.Empty(await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.TheoId, "active")));
        Assert.Empty(await NoticesAsync(NotificationType.PlusStarted));
    }

    // Plus started: a gift from Heartpact

    private AdminSubscriptionsController Admin(ApplicationDbContext db)
    {
        var http = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, Guid.NewGuid().ToString()), new Claim(ClaimTypes.Email, "ops@heartpact.me")], "test")),
        };
        var disabled = new DisabledGooglePlayBilling();
        return new AdminSubscriptionsController(db, clock, new AdminAudit(db, clock, new HttpContextAccessor { HttpContext = http }), disabled,
            new GooglePlaySubscriptionSync(db, disabled, clock, new BillingSettings()), new FakeBachsBilling(),
            new BachsSubscriptionSync(db, new FakeBachsBilling(), bachsSettings, clock, NullLogger<BachsSubscriptionSync>.Instance), hub)
        {
            ControllerContext = new ControllerContext { HttpContext = http },
        };
    }

    private async Task GrantAsync(Guid coupleId, int days)
    {
        await using var db = database.CreateContext();
        Assert.IsType<OkObjectResult>((await Admin(db).Grant(coupleId, new AdminGrantRequest(days, "a thank-you"), default)).Result);
    }

    [Fact]
    public async Task A_gift_tells_both_in_their_own_language_and_date_and_a_second_gift_says_nothing()
    {
        var couple = await CoupleAsync();
        await using (var db = database.CreateContext())
        {
            (await db.Users.SingleAsync(user => user.Id == couple.MayaId)).SetCountry("GB", Now);
            var french = await db.Users.SingleAsync(user => user.Id == couple.TheoId);
            french.SetLanguage("fr", Now);
            french.SetTimeZone("Pacific/Auckland", Now);
            await db.SaveChangesAsync();
        }

        await GrantAsync(couple.CoupleId, 30); // until 24 Nov 12:00 UTC: already the 25th in Auckland
        await GrantAsync(couple.CoupleId, 60);

        var notices = await NoticesAsync(NotificationType.PlusStarted);
        Assert.Equal(2, notices.Count);
        var maya = Assert.Single(notices, item => item.UserId == couple.MayaId);
        var theo = Assert.Single(notices, item => item.UserId == couple.TheoId);
        Assert.Equal(("A gift from Heartpact", "You both have Heartpact Plus until 24/11/2026. Enjoy it together."), (maya.Title, maya.Body));
        Assert.Equal(("Un cadeau de Heartpact", "Vous avez tous les deux Heartpact Plus jusqu'au 25/11/2026. Profitez-en ensemble."), (theo.Title, theo.Body));
        Assert.All(notices, item => Assert.Equal(("gift", (DateTime?)Now.AddDays(30)), DataOf(item)));
        Assert.Equal(2, hub.Payloads(ExerciseEvents.NotificationCreated).Count());
    }

    [Fact]
    public async Task A_gift_while_paying_tells_nobody()
    {
        var couple = await CoupleAsync();
        play.Set("tok-1", couple.TheoId, StoreSubscriptionStatus.Active, Now.AddDays(30));
        await NotifiedByGoogleAsync("tok-1");

        await GrantAsync(couple.CoupleId, 30);

        Assert.DoesNotContain(await NoticesAsync(NotificationType.PlusStarted), item => DataOf(item).Source == "gift");
    }

    // Location shared

    private LocationController Location(ApplicationDbContext db, TestCouple couple, Guid userId)
        => new(db, new FakeCurrentUser(userId, couple.CoupleId), clock, hub, new LocationSharingStopper(db, hub, clock));

    private async Task LocationAsync(TestCouple couple, Guid userId, Func<LocationController, Task> act)
    {
        await using var db = database.CreateContext();
        await act(Location(db, couple, userId));
    }

    [Fact]
    public async Task Starting_to_share_tells_the_partner_at_most_once_in_12_hours()
    {
        var couple = await CoupleAsync();

        await LocationAsync(couple, couple.MayaId, controller => controller.Start(default));
        var notice = Assert.Single(await NoticesAsync(NotificationType.LocationShared));
        Assert.Equal((couple.TheoId, "Maya is sharing their location", "Open the map to see each other. Share yours too, and you can both pause any time."),
            (notice.UserId, notice.Title, notice.Body));
        Assert.Contains(hub.Sent, item => item.Method == ExerciseEvents.NotificationCreated && item.Group == Twogether.Api.Hubs.CoupleHub.UserGroupName(couple.TheoId));

        // Confirming, a heartbeat, a pause and its resume, and stopping and starting again the same evening: nothing more.
        clock.UtcNow = Now.AddMinutes(1);
        await LocationAsync(couple, couple.MayaId, controller => controller.Start(default));
        await LocationAsync(couple, couple.MayaId, controller => controller.Heartbeat(default));
        await LocationAsync(couple, couple.MayaId, controller => controller.Pause(new PauseLocationRequest(30), default));
        await LocationAsync(couple, couple.MayaId, controller => controller.Start(default));
        await LocationAsync(couple, couple.MayaId, controller => controller.Pause(new PauseLocationRequest(30), default));
        await LocationAsync(couple, couple.MayaId, controller => controller.Resume(default));
        await LocationAsync(couple, couple.MayaId, controller => controller.Stop(default));
        clock.UtcNow = Now.AddHours(11);
        await LocationAsync(couple, couple.MayaId, controller => controller.Start(default));
        Assert.Single(await NoticesAsync(NotificationType.LocationShared));

        // Theo starting is his own: Maya hears about it.
        await LocationAsync(couple, couple.TheoId, controller => controller.Start(default));
        Assert.Single(await NoticesAsync(NotificationType.LocationShared), item => item.UserId == couple.MayaId);

        // After 12 hours Maya starting again (her phone had gone quiet) is news again.
        clock.UtcNow = Now.AddHours(12).AddMinutes(1);
        await LocationAsync(couple, couple.MayaId, controller => controller.Start(default));
        Assert.Equal(2, (await NoticesAsync(NotificationType.LocationShared)).Count(item => item.UserId == couple.TheoId));
    }

    // Cycle shared

    private async Task SaveCycleAsync(TestCouple couple, ShareLevel level)
    {
        await using var db = database.CreateContext();
        var result = await new CycleController(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, hub)
            .UpdateProfile(new UpdateCycleProfileRequest(28, 5, new DateOnly(2026, 10, 10), level), default);
        Assert.IsType<OkObjectResult>(result.Result);
    }

    [Fact]
    public async Task The_partner_hears_only_when_the_cycle_goes_from_hidden_to_shared()
    {
        var couple = await CoupleAsync();
        await using (var db = database.CreateContext())
        {
            (await db.Users.SingleAsync(user => user.Id == couple.MayaId)).ClaimCycleOwnership(Now);
            (await db.Users.SingleAsync(user => user.Id == couple.TheoId)).SetLanguage("es", Now);
            await db.SaveChangesAsync();
        }

        await SaveCycleAsync(couple, ShareLevel.None);
        Assert.Empty(await NoticesAsync(NotificationType.CycleShared));

        await SaveCycleAsync(couple, ShareLevel.PredictionsOnly);
        var notice = Assert.Single(await NoticesAsync(NotificationType.CycleShared));
        Assert.Equal((couple.TheoId, "Maya está compartiendo su ciclo contigo", "Verás lo que Maya eligió compartir en Nosotros → Ciclo de Maya."),
            (notice.UserId, notice.Title, notice.Body));

        // Saving again, or showing more, isn't news.
        await SaveCycleAsync(couple, ShareLevel.PredictionsOnly);
        await SaveCycleAsync(couple, ShareLevel.FullDetail);
        Assert.Single(await NoticesAsync(NotificationType.CycleShared));

        // Hidden and shared again is.
        await SaveCycleAsync(couple, ShareLevel.None);
        await SaveCycleAsync(couple, ShareLevel.FullDetail);
        Assert.Equal(2, (await NoticesAsync(NotificationType.CycleShared)).Count);
    }
}
