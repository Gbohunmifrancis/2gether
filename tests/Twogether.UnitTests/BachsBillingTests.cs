using System.Net;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Admin;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Api.Controllers.Admin;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Config;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Account;
using Twogether.Infrastructure.Billing;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Results;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// Bachs card subscriptions (docs/billing.md, Bachs) through the real controllers on a real database: the webhook's
/// signature, idempotence and ordering, what grants Plus, checkout and the portal, admin actions and account deletion.
/// </summary>
public sealed class BachsBillingTests : IDisposable
{
    private const string Secret = "whsec_test_secret";
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);
    private readonly FakeBachsBilling bachs = new();
    private readonly AttemptLimiter limiter;
    private readonly BachsSettings settings = new()
    {
        ApiKey = "sk_sandbox_test",
        WebhookSecret = Secret,
        MonthlyProductId = "prod_month",
        YearlyProductId = "prod_year",
    };

    public BachsBillingTests() => limiter = new AttemptLimiter(clock);

    public void Dispose() => database.Dispose();

    private BachsSubscriptionSync Sync(ApplicationDbContext db, BachsSettings? with = null)
        => new(db, bachs, with ?? settings, clock, NullLogger<BachsSubscriptionSync>.Instance);

    private BachsBillingController Controller(ApplicationDbContext db, Guid userId, Guid? coupleId, BachsSettings? with = null)
    {
        var used = with ?? settings;
        var entitlements = new EntitlementService(db, clock, new BillingSettings(), used);
        return new BachsBillingController(new FakeCurrentUser(userId, coupleId), new BachsCheckoutService(db, bachs, entitlements, used, clock),
            Sync(db, used), used, clock, limiter, NullLogger<BachsBillingController>.Instance)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
        };
    }

    private static int StatusOf(IActionResult result) => result switch
    {
        ObjectResult objectResult => objectResult.StatusCode ?? 200,
        StatusCodeResult status => status.StatusCode,
        _ => 0
    };

    private static string? CodeOf(IActionResult result) => (result as ObjectResult)?.Value is ApiError error ? error.Code : null;

    private async Task<TestCouple> CoupleAsync()
    {
        await using var db = database.CreateContext();
        return await TestCouple.AddAsync(db);
    }

    private async Task<int> DeliverAsync(string json, string? header = null)
    {
        await using var db = database.CreateContext();
        var controller = Controller(db, Guid.Empty, null);
        var bytes = Encoding.UTF8.GetBytes(json);
        controller.HttpContext.Request.Body = new MemoryStream(bytes);
        controller.HttpContext.Request.Headers[BachsSignature.Header] = header ?? BachsSignature.Sign(Secret, bytes, clock.UtcNow);
        return StatusOf(await controller.Webhook(default));
    }

    private static string Iso(DateTime at) => at.ToString("yyyy-MM-dd'T'HH:mm:ss.ffffff'+00:00'");

    /// <summary>A customer.subscription.* event, shaped as the Bachs docs show it.</summary>
    private static string SubscriptionEvent(string eventId, string type, DateTime createdAt, Guid userId, string status = "active",
        string subscriptionId = "sub_1", string product = "prod_month", bool cancelAtPeriodEnd = false, DateTime? periodEnd = null,
        DateTime? trialEnd = null, DateTime? canceledAt = null, Guid? externalRef = null, string amount = "7.99", bool idField = false)
        => JsonSerializer.Serialize(new
        {
            id = eventId,
            type,
            created_at = Iso(createdAt),
            data = new Dictionary<string, object?>
            {
                [idField ? "id" : "subscription_id"] = subscriptionId,
                ["customer"] = new { customer_id = "cust_1", email = "maya@example.com" },
                [idField ? "product" : "product_id"] = idField ? new { id = product } : product,
                ["status"] = status,
                ["currency"] = "USD",
                ["amount"] = amount,
                ["current_period_end"] = Iso(periodEnd ?? createdAt.AddDays(30)),
                ["trial_end"] = trialEnd is { } trial ? Iso(trial) : null,
                ["cancel_at_period_end"] = cancelAtPeriodEnd,
                ["canceled_at"] = canceledAt is { } canceled ? Iso(canceled) : null,
                ["created_at"] = Iso(createdAt),
                ["metadata"] = externalRef is { } reference
                    ? new Dictionary<string, string> { ["user_id"] = userId.ToString(), ["external_ref"] = reference.ToString() }
                    : new Dictionary<string, string> { ["user_id"] = userId.ToString() },
            },
        });

    private static string InvoicePaid(string eventId, DateTime createdAt, string invoiceId, string amount = "7.99", string subscriptionId = "sub_1", string? chargeId = null)
        => JsonSerializer.Serialize(new
        {
            id = eventId,
            type = "invoice.paid",
            created_at = Iso(createdAt),
            data = new
            {
                invoice_id = invoiceId,
                subscription = new { subscription_id = subscriptionId },
                customer = new { customer_id = "cust_1" },
                charge = chargeId is null ? null : new { id = chargeId },
                status = "paid",
                currency = "USD",
                total = amount,
                amount_paid = amount,
            },
        });

    private async Task<EntitlementDto> EntitlementAsync(Guid userId)
    {
        await using var db = database.CreateContext();
        return await new EntitlementService(db, clock, new BillingSettings(), settings).ForUserAsync(userId, default);
    }

    // The signature

    [Fact]
    public void A_signature_verifies_only_with_the_right_secret_a_fresh_timestamp_and_the_raw_body()
    {
        var body = Encoding.UTF8.GetBytes("{\"id\":\"evt_1\"}");
        var header = BachsSignature.Sign(Secret, body, Now);

        Assert.True(BachsSignature.Verify(header, body, Secret, Now));
        Assert.True(BachsSignature.Verify(header, body, Secret, Now.AddSeconds(299)));
        Assert.False(BachsSignature.Verify(header, body, "whsec_other", Now));
        Assert.False(BachsSignature.Verify(header, body, Secret, Now.AddSeconds(301)));
        Assert.False(BachsSignature.Verify(header, body, Secret, Now.AddSeconds(-301)));
        Assert.False(BachsSignature.Verify(header, Encoding.UTF8.GetBytes("{\"id\": \"evt_1\"}"), Secret, Now));
        Assert.False(BachsSignature.Verify(null, body, Secret, Now));
        Assert.False(BachsSignature.Verify("t=abc,v1=00", body, Secret, Now));
        Assert.False(BachsSignature.Verify(header.Split(',')[0], body, Secret, Now));
    }

    [Fact]
    public void During_a_secret_rotation_any_v1_value_may_match()
    {
        var body = Encoding.UTF8.GetBytes("{}");
        var current = BachsSignature.Sign(Secret, body, Now);
        var previous = BachsSignature.Sign("whsec_previous", body, Now);
        var both = previous + "," + current.Split(',')[1];

        Assert.True(BachsSignature.Verify(both, body, Secret, Now));
        Assert.True(BachsSignature.Verify(both, body, "whsec_previous", Now));
        Assert.True(BachsSignature.Verify(both.ToUpperInvariant().Replace("T=", "t=").Replace("V1=", "v1="), body, Secret, Now));
        Assert.False(BachsSignature.Verify(both, body, "whsec_third", Now));
    }

    [Fact]
    public async Task A_bad_signature_is_400_and_changes_nothing()
    {
        var couple = await CoupleAsync();
        var json = SubscriptionEvent("evt_bad", BachsEvents.SubscriptionCreated, Now, couple.MayaId);

        Assert.Equal(400, await DeliverAsync(json, BachsSignature.Sign("whsec_wrong", Encoding.UTF8.GetBytes(json), Now)));
        Assert.Equal(400, await DeliverAsync(json, BachsSignature.Sign(Secret, Encoding.UTF8.GetBytes(json), Now.AddMinutes(-10))));
        Assert.Equal(400, await DeliverAsync(json, ""));

        await using var db = database.CreateContext();
        Assert.Empty(await db.StoreSubscriptions.ToListAsync());
        Assert.Empty(await db.BachsWebhookEvents.ToListAsync());
    }

    // What grants Plus

    [Fact]
    public async Task A_new_subscription_gives_both_partners_plus_and_remembers_the_customer()
    {
        var couple = await CoupleAsync();

        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId, product: "prod_year", periodEnd: Now.AddDays(365))));

        var maya = await EntitlementAsync(couple.MayaId);
        var theo = await EntitlementAsync(couple.TheoId);
        Assert.Equal(Plan.Plus, maya.Plan);
        Assert.Equal(Plan.Plus, theo.Plan);
        Assert.Equal(("bachs", "heartpact_plus_yearly", true), (maya.Subscription!.Store, maya.Subscription.ProductId, maya.Subscription.PurchasedByMe));
        Assert.False(theo.CanPurchase);
        await using var db = database.CreateContext();
        var row = await db.StoreSubscriptions.SingleAsync();
        Assert.Equal(("sub_1", "cust_1", couple.MayaId, StoreSubscriptionStatus.Active, true), (row.PurchaseToken, row.ExternalCustomerId, row.UserId, row.Status, row.AutoRenewing));
        Assert.Equal(Now.AddDays(365), row.ExpiresAtUtc);
        Assert.Equal("customer.subscription.created", (await db.BachsWebhookEvents.SingleAsync()).Type);
    }

    [Fact]
    public async Task Both_field_spellings_in_the_docs_are_read()
    {
        var couple = await CoupleAsync();

        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId, subscriptionId: "sub_alt", idField: true)));

        await using var db = database.CreateContext();
        var row = await db.StoreSubscriptions.SingleAsync();
        Assert.Equal(("sub_alt", "heartpact_plus_monthly"), (row.PurchaseToken, row.ProductId));
    }

    [Fact]
    public async Task A_duplicate_event_is_answered_ok_and_changes_nothing()
    {
        var couple = await CoupleAsync();
        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId)));

        // Same id again, even with a different body: already handled.
        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionDeleted, Now.AddMinutes(1), couple.MayaId, status: "canceled", canceledAt: Now.AddMinutes(1))));

        Assert.Equal(Plan.Plus, (await EntitlementAsync(couple.MayaId)).Plan);
        await using var db = database.CreateContext();
        Assert.Single(await db.BachsWebhookEvents.ToListAsync());
    }

    [Fact]
    public async Task An_older_event_delivered_late_does_not_overwrite_a_newer_state()
    {
        var couple = await CoupleAsync();
        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionDeleted, Now.AddMinutes(5), couple.MayaId, status: "canceled", canceledAt: Now.AddMinutes(5))));

        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId)));

        Assert.Equal(Plan.Free, (await EntitlementAsync(couple.MayaId)).Plan);
        await using var db = database.CreateContext();
        var row = await db.StoreSubscriptions.SingleAsync();
        Assert.Equal(StoreSubscriptionStatus.Expired, row.Status);
        Assert.Equal(Now.AddMinutes(5), row.LastEventAtUtc);
        Assert.Equal(2, await db.BachsWebhookEvents.CountAsync());
    }

    [Fact]
    public async Task A_canceled_subscription_gives_nothing()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId));
        clock.UtcNow = Now.AddDays(3);

        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionDeleted, Now.AddDays(3), couple.MayaId, status: "canceled", canceledAt: Now.AddDays(3))));

        Assert.Equal(Plan.Free, (await EntitlementAsync(couple.MayaId)).Plan);
        Assert.Equal(Plan.Free, (await EntitlementAsync(couple.TheoId)).Plan);
    }

    [Fact]
    public async Task Cancelling_at_period_end_keeps_plus_until_the_period_ends()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId, periodEnd: Now.AddDays(10)));

        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionUpdated, Now.AddMinutes(1), couple.MayaId, cancelAtPeriodEnd: true, periodEnd: Now.AddDays(10))));

        var during = await EntitlementAsync(couple.TheoId);
        Assert.Equal(Plan.Plus, during.Plan);
        Assert.Equal((StoreSubscriptionStatus.Canceled, false), (during.Subscription!.Status, during.Subscription.AutoRenewing));
        clock.UtcNow = Now.AddDays(10).AddMinutes(1);
        Assert.Equal(Plan.Free, (await EntitlementAsync(couple.TheoId)).Plan);
    }

    [Theory]
    [InlineData("trialing", false, StoreSubscriptionStatus.Active, true)]
    [InlineData("active", false, StoreSubscriptionStatus.Active, true)]
    [InlineData("active", true, StoreSubscriptionStatus.Canceled, false)]
    [InlineData("past_due", false, StoreSubscriptionStatus.InGracePeriod, true)]
    [InlineData("unpaid", false, StoreSubscriptionStatus.OnHold, true)]
    [InlineData("canceled", false, StoreSubscriptionStatus.Expired, false)]
    [InlineData("something_new", false, StoreSubscriptionStatus.Pending, true)]
    public void Bachs_statuses_map_onto_the_play_vocabulary(string status, bool cancelAtPeriodEnd, StoreSubscriptionStatus expected, bool renews)
    {
        var snapshot = new BachsSubscriptionSnapshot("sub_1", "cust_1", "prod_month", status, Now.AddDays(30), null, cancelAtPeriodEnd, null, null, null, null, null, null);
        var state = BachsStatusMapping.Map(snapshot, Now);
        Assert.Equal((expected, renews), (state.Status, state.AutoRenewing));
    }

    [Fact]
    public void A_trial_lasts_to_the_later_of_the_trial_and_the_period()
    {
        var trial = new BachsSubscriptionSnapshot("sub_1", null, "prod_year", "trialing", Now.AddDays(3), Now.AddDays(7), false, null, null, null, null, null, null);
        Assert.Equal(Now.AddDays(7), BachsStatusMapping.Map(trial, Now).ExpiresAtUtc);
        Assert.True(EntitlementRules(BachsStatusMapping.Map(trial, Now)));
        var unknownEnd = trial with { Status = "active", CurrentPeriodEnd = null, TrialEnd = null };
        Assert.False(EntitlementRules(BachsStatusMapping.Map(unknownEnd, Now)));

        bool EntitlementRules(BachsStatusMapping.State state) => Core.Billing.EntitlementRules.Grants(state.Status, state.ExpiresAtUtc, Now);
    }

    [Fact]
    public async Task Unknown_products_and_unknown_people_are_recorded_and_ignored()
    {
        var couple = await CoupleAsync();

        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId, product: "prod_cheap_test")));
        Assert.Equal(200, await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionCreated, Now, Guid.NewGuid(), subscriptionId: "sub_2")));

        await using var db = database.CreateContext();
        Assert.Empty(await db.StoreSubscriptions.ToListAsync());
        Assert.Equal(2, await db.BachsWebhookEvents.CountAsync());
    }

    [Fact]
    public async Task A_signed_event_that_cannot_be_read_is_5xx_so_bachs_retries()
    {
        Assert.Equal(500, await DeliverAsync("{\"type\":\"customer.subscription.created\"}"));
        await using var db = database.CreateContext();
        Assert.Empty(await db.BachsWebhookEvents.ToListAsync());
    }

    [Fact]
    public async Task Without_bachs_set_up_the_webhook_is_503()
    {
        await using var db = database.CreateContext();
        var controller = Controller(db, Guid.Empty, null, new BachsSettings());
        controller.HttpContext.Request.Body = new MemoryStream("{}"u8.ToArray());
        Assert.Equal(503, StatusOf(await controller.Webhook(default)));
    }

    // Checkout and the portal

    [Fact]
    public async Task Checkout_sends_the_configured_product_the_person_and_their_user_id()
    {
        var couple = await CoupleAsync();
        await using var db = database.CreateContext();

        var result = await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("yearly", "web"), default);

        Assert.Equal(200, StatusOf(result));
        Assert.Equal("https://checkout.bachs.io/c/test", Assert.IsType<BachsCheckoutDto>(((ObjectResult)result).Value).CheckoutUrl);
        var (request, key) = Assert.Single(bachs.Checkouts);
        Assert.Equal(("prod_year", couple.MayaId, (string?)null, "Maya"), (request.ProductId, request.UserId, request.CustomerId, request.Name));
        Assert.StartsWith("maya-", request.Email);
        Assert.Equal(("https://heartpact.me/plus/thanks/", "https://heartpact.me/plus/"), (request.SuccessUrl, request.CancelUrl));
        Assert.Null(request.ExternalRef);
        Assert.StartsWith("hp-checkout-", key);
        // The same checkout again within the half hour shares the key, so Bachs hands back the same session.
        await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("yearly", "web"), default);
        Assert.Equal(key, bachs.Checkouts[1].IdempotencyKey);
        await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("monthly", "web"), default);
        Assert.NotEqual(key, bachs.Checkouts[2].IdempotencyKey);
    }

    [Fact]
    public void A_client_cannot_name_a_price_or_a_product()
    {
        var parsed = JsonSerializer.Deserialize<BachsCheckoutStart>("{\"plan\":\"monthly\",\"from\":\"web\",\"amount\":\"0.01\",\"productId\":\"prod_cheap\",\"price\":1}",
            new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        Assert.Equal(new BachsCheckoutStart("monthly", "web"), parsed);
        Assert.Null(settings.BachsProductFor("prod_cheap"));
    }

    [Fact]
    public async Task A_returning_customer_is_sent_by_id_not_by_email()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId));
        await DeliverAsync(SubscriptionEvent("evt_2", BachsEvents.SubscriptionDeleted, Now.AddMinutes(1), couple.MayaId, status: "canceled", canceledAt: Now.AddMinutes(1)));
        await using var db = database.CreateContext();

        Assert.Equal(200, StatusOf(await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("monthly"), default)));

        var request = Assert.Single(bachs.Checkouts).Request;
        Assert.Equal(("cust_1", (string?)null, (string?)null), (request.CustomerId, request.Email, request.Name));
    }

    [Fact]
    public async Task Checkout_is_refused_when_the_couple_already_has_plus_whoever_pays()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId));
        await using var db = database.CreateContext();

        var mine = await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("monthly"), default);
        var partners = await Controller(db, couple.TheoId, couple.CoupleId).Checkout(new BachsCheckoutStart("yearly"), default);

        Assert.Equal((409, "billing.already_plus"), (StatusOf(mine), CodeOf(mine)));
        Assert.Equal((409, "billing.already_plus"), (StatusOf(partners), CodeOf(partners)));
        Assert.Empty(bachs.Checkouts);
    }

    [Fact]
    public async Task Checkout_is_refused_with_a_complimentary_grant()
    {
        var couple = await CoupleAsync();
        await using var db = database.CreateContext();
        db.PlusGrants.Add(PlusGrant.Create(couple.CoupleId, Now.AddDays(30), "support", Guid.NewGuid(), Now));
        await db.SaveChangesAsync();

        var result = await Controller(db, couple.TheoId, couple.CoupleId).Checkout(new BachsCheckoutStart("monthly"), default);

        Assert.Equal((409, "billing.already_plus"), (StatusOf(result), CodeOf(result)));
    }

    [Theory]
    [InlineData("weekly", null, "billing.unknown_plan")]
    [InlineData(null, null, "billing.unknown_plan")]
    [InlineData("monthly", "desktop", "billing.invalid_from")]
    public async Task Unknown_plans_and_origins_are_400(string? plan, string? from, string code)
    {
        var couple = await CoupleAsync();
        await using var db = database.CreateContext();

        var result = await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart(plan, from), default);

        Assert.Equal((400, code), (StatusOf(result), CodeOf(result)));
        Assert.Empty(bachs.Checkouts);
    }

    [Fact]
    public async Task Without_bachs_set_up_checkout_and_portal_are_503()
    {
        var couple = await CoupleAsync();
        await using var db = database.CreateContext();
        var partial = new BachsSettings { ApiKey = "sk_sandbox_x", WebhookSecret = Secret, MonthlyProductId = "prod_month" };

        var checkout = await Controller(db, couple.MayaId, couple.CoupleId, partial).Checkout(new BachsCheckoutStart("monthly"), default);
        var portal = await Controller(db, couple.MayaId, couple.CoupleId, partial).Portal(default);

        Assert.Equal((503, "billing.bachs_unavailable"), (StatusOf(checkout), CodeOf(checkout)));
        Assert.Equal((503, "billing.bachs_unavailable"), (StatusOf(portal), CodeOf(portal)));
        Assert.False(partial.IsConfigured);
    }

    [Fact]
    public async Task The_portal_opens_only_for_the_callers_own_customer()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId));
        await using var db = database.CreateContext();

        var theo = await Controller(db, couple.TheoId, couple.CoupleId).Portal(default);
        var maya = await Controller(db, couple.MayaId, couple.CoupleId).Portal(default);

        Assert.Equal((404, "billing.no_bachs_customer"), (StatusOf(theo), CodeOf(theo)));
        Assert.Equal(200, StatusOf(maya));
        Assert.Equal("https://portal.bachs.io/s/test", Assert.IsType<BachsPortalDto>(((ObjectResult)maya).Value).Url);
        Assert.Equal(["cust_1"], bachs.Portals);
    }

    [Fact]
    public async Task Too_many_checkouts_are_429()
    {
        var couple = await CoupleAsync();
        await using var db = database.CreateContext();
        for (var i = 0; i < AttemptRules.BachsCheckoutPerUser.MaxFailures; i++)
            await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("weekly"), default);

        var result = await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("monthly"), default);

        Assert.Equal(429, StatusOf(result));
    }

    // The entitlement's new fields

    [Fact]
    public async Task Card_in_app_needs_bachs_the_flag_and_a_billing_choice_country()
    {
        var couple = await CoupleAsync();
        await using (var db = database.CreateContext())
        {
            (await db.Users.SingleAsync(user => user.Id == couple.MayaId)).SetCountry("FR", Now);
            (await db.Users.SingleAsync(user => user.Id == couple.TheoId)).SetCountry("NG", Now);
            await db.SaveChangesAsync();
        }

        var before = await EntitlementAsync(couple.MayaId);
        Assert.Equal((true, false), (before.WebCheckout, before.CardInApp));

        await using (var db = database.CreateContext())
            await new FeatureFlagService(db, clock).SetAsync(new FeatureFlagsUpdate(BachsInApp: true), default);

        Assert.True((await EntitlementAsync(couple.MayaId)).CardInApp);
        Assert.False((await EntitlementAsync(couple.TheoId)).CardInApp);
        await using (var db = database.CreateContext())
        {
            var off = await new EntitlementService(db, clock, new BillingSettings(), new BachsSettings()).ForUserAsync(couple.MayaId, default);
            Assert.Equal((false, false), (off.WebCheckout, off.CardInApp));
        }
        var json = JsonSerializer.Serialize(before, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.Contains("\"webCheckout\":true", json);
        Assert.Contains("\"cardInApp\":false", json);
        Assert.Equal(32, BachsPlans.CardInAppCountries.Count);
    }

    [Fact]
    public async Task Card_checkout_from_the_app_needs_card_in_app()
    {
        var couple = await CoupleAsync();
        await using var db = database.CreateContext();

        var result = await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("monthly", "app"), default);

        Assert.Equal((403, "billing.card_in_app_unavailable"), (StatusOf(result), CodeOf(result)));
        Assert.Empty(bachs.Checkouts);
    }

    // Admin actions and account deletion

    private AdminSubscriptionsController Admin(ApplicationDbContext db)
    {
        var http = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, Guid.NewGuid().ToString()), new Claim(ClaimTypes.Email, "ops@heartpact.me")], "test")),
        };
        var play = new DisabledGooglePlayBilling();
        return new AdminSubscriptionsController(db, clock, new AdminAudit(db, clock, new HttpContextAccessor { HttpContext = http }), play,
            new GooglePlaySubscriptionSync(db, play, clock, new BillingSettings()), bachs, Sync(db))
        {
            ControllerContext = new ControllerContext { HttpContext = http },
        };
    }

    [Fact]
    public async Task Admins_refund_card_subscriptions_in_the_bachs_dashboard_and_stop_renewal_through_bachs()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId, periodEnd: Now.AddDays(20)));
        await using var db = database.CreateContext();
        var id = (await db.StoreSubscriptions.SingleAsync()).Id;
        bachs.OnCancel = (subscriptionId, atPeriodEnd) => new BachsSubscriptionSnapshot(subscriptionId, "cust_1", "prod_month", "active",
            Now.AddDays(20), null, atPeriodEnd, null, couple.MayaId.ToString(), null, 7_990_000, "USD", Now);

        var refund = await Admin(db).Refund(id, new AdminReasonRequest("asked"), default);
        var revoke = await Admin(db).Revoke(id, new AdminReasonRequest("asked"), default);
        var cancel = await Admin(db).Cancel(id, new AdminReasonRequest("asked"), default);

        Assert.Equal((409, "admin.bachs_dashboard"), (StatusOf(refund), CodeOf(refund)));
        Assert.Equal((409, "admin.bachs_dashboard"), (StatusOf(revoke), CodeOf(revoke)));
        Assert.Equal(200, StatusOf(cancel));
        Assert.Equal([("sub_1", true)], bachs.Cancels);
        var row = Assert.IsType<AdminSubscriptionRow>(((ObjectResult)cancel).Value);
        Assert.Equal((StoreSubscriptionStatus.Canceled, false), (row.Status, row.AutoRenewing));
        Assert.Equal(Plan.Plus, (await EntitlementAsync(couple.TheoId)).Plan);
        Assert.Contains(await db.AdminAuditEvents.AsNoTracking().Select(item => item.Action).ToListAsync(), action => action == "subscription.canceled");
    }

    [Fact]
    public async Task Deleting_an_account_warns_then_cancels_a_renewing_card_subscription_now()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId));
        bachs.OnCancel = (subscriptionId, atPeriodEnd) => new BachsSubscriptionSnapshot(subscriptionId, "cust_1", "prod_month", "canceled",
            Now.AddDays(30), null, false, Now, couple.MayaId.ToString(), null, 7_990_000, "USD", Now);
        await using var db = database.CreateContext();
        var subscriptions = await db.StoreSubscriptions.AsNoTracking().ToListAsync();

        var warning = AccountDeletionRules.RenewalWarning(subscriptions);
        var cancelled = await new AccountEraser(db, clock, Sync(db)).CancelCardSubscriptionsAsync(couple.MayaId, default);
        var erased = await new AccountEraser(db, clock, Sync(db)).EraseAsync(couple.MayaId, default);

        Assert.Contains("card subscription", warning);
        Assert.True(cancelled.IsSuccess);
        Assert.Equal([("sub_1", false)], bachs.Cancels);
        Assert.NotNull(erased);
        Assert.Empty(await db.StoreSubscriptions.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task When_bachs_cannot_cancel_nothing_is_deleted()
    {
        var couple = await CoupleAsync();
        await DeliverAsync(SubscriptionEvent("evt_1", BachsEvents.SubscriptionCreated, Now, couple.MayaId));
        bachs.OnCancel = (_, _) => new Error("billing.bachs_failed", "down", ErrorType.Network);
        await using var db = database.CreateContext();

        var cancelled = await new AccountEraser(db, clock, Sync(db)).CancelCardSubscriptionsAsync(couple.MayaId, default);

        Assert.Equal("account.subscription_cancel_failed", cancelled.Error.Code);
    }

    // The HTTP adapter

    [Fact]
    public async Task The_client_sends_checkout_with_an_idempotency_key_and_no_amount()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent("{\"checkout_id\":\"chk_1\",\"checkout_url\":\"https://checkout.bachs.io/c/abc\",\"status\":\"open\"}"),
        });
        using var client = new BachsBilling(settings, NullLogger<BachsBilling>.Instance, handler);
        var userId = Guid.NewGuid();
        var reference = Guid.NewGuid();

        var created = await client.CreateCheckoutAsync(new BachsCheckoutRequest("prod_month", userId, null, "maya@example.com", "Maya",
            "https://heartpact.me/plus/thanks/", "https://heartpact.me/plus/", reference), "hp-checkout-abc", default);

        Assert.Equal(("chk_1", "https://checkout.bachs.io/c/abc"), (created.Value.CheckoutId, created.Value.CheckoutUrl));
        var (request, body) = Assert.Single(handler.Sent);
        Assert.Equal((HttpMethod.Post, "https://sandbox-api.bachs.io/v1/checkout-sessions"), (request.Method, request.RequestUri!.ToString()));
        Assert.Equal("Bearer sk_sandbox_test", request.Headers.Authorization!.ToString());
        Assert.Equal("hp-checkout-abc", Assert.Single(request.Headers.GetValues("Idempotency-Key")));
        using var json = JsonDocument.Parse(body!);
        var root = json.RootElement;
        Assert.Equal("prod_month", root.GetProperty("product_cart")[0].GetProperty("product_id").GetString());
        Assert.Equal(1, root.GetProperty("product_cart")[0].GetProperty("quantity").GetInt32());
        Assert.Equal("USD", root.GetProperty("billing_currency").GetString());
        Assert.Equal(("maya@example.com", "Maya"), (root.GetProperty("customer").GetProperty("email").GetString(), root.GetProperty("customer").GetProperty("name").GetString()));
        Assert.Equal(userId.ToString(), root.GetProperty("metadata").GetProperty("user_id").GetString());
        Assert.Equal(reference.ToString(), root.GetProperty("metadata").GetProperty("external_ref").GetString());
        Assert.Equal("https://heartpact.me/plus/thanks/", root.GetProperty("success_url").GetString());
        Assert.DoesNotContain("amount", body);
        Assert.DoesNotContain("price", body);
    }

    [Fact]
    public async Task The_client_reuses_a_customer_and_cancels_at_period_end_explicitly()
    {
        var subscription = "{\"id\":\"sub_9\",\"status\":\"active\",\"cancel_at_period_end\":true,\"current_period_end\":\"2026-11-25T12:00:00Z\",\"product\":{\"id\":\"prod_month\"},\"customer\":{\"customer_id\":\"cust_9\"},\"metadata\":{}}";
        var handler = new RecordingHandler(request => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(request.RequestUri!.AbsolutePath.Contains("portal") ? "{\"id\":\"psn_1\",\"url\":\"https://portal.bachs.io/s/x\"}"
                : request.RequestUri.AbsolutePath.Contains("checkout") ? "{\"checkout_id\":\"chk_1\",\"checkout_url\":\"https://checkout.bachs.io/c/abc\"}"
                : subscription),
        });
        using var client = new BachsBilling(new BachsSettings { ApiKey = "sk_live_key", WebhookSecret = Secret, MonthlyProductId = "prod_month", YearlyProductId = "prod_year" },
            NullLogger<BachsBilling>.Instance, handler);

        await client.CreateCheckoutAsync(new BachsCheckoutRequest("prod_year", Guid.NewGuid(), "cust_9", null, null, "https://s/", "https://c/"), "key", default);
        var portal = await client.CreatePortalSessionAsync("cust_9", default);
        var cancelled = await client.CancelSubscriptionAsync("sub_9", atPeriodEnd: true, default);
        await client.CancelSubscriptionAsync("sub_9", atPeriodEnd: false, default);

        Assert.Equal("https://portal.bachs.io/s/x", portal.Value);
        Assert.Equal(("sub_9", "prod_month", "cust_9", true), (cancelled.Value.SubscriptionId, cancelled.Value.ProductId, cancelled.Value.CustomerId, cancelled.Value.CancelAtPeriodEnd));
        Assert.All(handler.Sent, sent => Assert.StartsWith("https://api.bachs.io/v1/", sent.Request.RequestUri!.ToString()));
        Assert.Equal("{\"customer_id\":\"cust_9\"}", JsonDocument.Parse(handler.Sent[0].Body!).RootElement.GetProperty("customer").GetRawText());
        Assert.Equal((HttpMethod.Post, "/v1/customers/cust_9/portal-sessions"), (handler.Sent[1].Request.Method, handler.Sent[1].Request.RequestUri!.AbsolutePath));
        Assert.True(handler.Sent[1].Request.Headers.Contains("Idempotency-Key"));
        Assert.Equal((HttpMethod.Delete, "{\"cancel_at_period_end\":true}"), (handler.Sent[2].Request.Method, handler.Sent[2].Body));
        Assert.Equal("{\"cancel_at_period_end\":false}", handler.Sent[3].Body);
    }

    [Fact]
    public async Task A_refusal_from_bachs_is_an_error_and_nothing_of_its_body_comes_back()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.BadRequest)
        {
            Content = new StringContent("{\"detail\":\"customer maya@example.com is blocked\",\"error_code\":\"CUSTOMER_BLOCKED\"}"),
        });
        using var client = new BachsBilling(settings, NullLogger<BachsBilling>.Instance, handler);

        var result = await client.CreatePortalSessionAsync("cust_1", default);

        Assert.Equal("billing.bachs_refused", result.Error.Code);
        Assert.DoesNotContain("maya", result.Error.Message);
    }
}
