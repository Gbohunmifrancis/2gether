using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Config;
using Twogether.Core.Entities;
using Twogether.Infrastructure.Account;
using Twogether.Infrastructure.Billing;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// Card payments in the app under Google Play's billing choice (docs/billing.md): the token is kept at checkout, the
/// webhook fills the outbox, and the worker's sender reports to Google, retrying until Google has each one once.
/// </summary>
public sealed class GoogleBillingChoiceTests : IDisposable
{
    private const string Secret = "whsec_test_secret";
    private const string Token = "play-user-choice-token-123";
    private static readonly DateTime Now = TestCouple.Now;
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(Now);
    private readonly FakeBachsBilling bachs = new();
    private readonly AttemptLimiter limiter;
    private readonly BachsSettings settings = new() { ApiKey = "sk_sandbox_test", WebhookSecret = Secret, MonthlyProductId = "prod_month", YearlyProductId = "prod_year" };
    private Func<HttpRequestMessage, HttpResponseMessage> google = _ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{}") };
    private readonly RecordingHandler handler;

    public GoogleBillingChoiceTests()
    {
        limiter = new AttemptLimiter(clock);
        handler = new RecordingHandler(request => google(request));
    }

    public void Dispose() => database.Dispose();

    private BachsBillingController Controller(ApplicationDbContext db, Guid userId, Guid? coupleId)
    {
        var entitlements = new EntitlementService(db, clock, new BillingSettings(), settings);
        return new BachsBillingController(new FakeCurrentUser(userId, coupleId), new BachsCheckoutService(db, bachs, entitlements, settings, clock),
            new BachsSubscriptionSync(db, bachs, settings, clock, NullLogger<BachsSubscriptionSync>.Instance), settings, clock, limiter, NullLogger<BachsBillingController>.Instance)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
        };
    }

    private async Task<int> DeliverAsync(string json)
    {
        await using var db = database.CreateContext();
        var controller = Controller(db, Guid.Empty, null);
        var bytes = Encoding.UTF8.GetBytes(json);
        controller.HttpContext.Request.Body = new MemoryStream(bytes);
        controller.HttpContext.Request.Headers[BachsSignature.Header] = BachsSignature.Sign(Secret, bytes, clock.UtcNow);
        var result = await controller.Webhook(default);
        return result is ObjectResult objectResult ? objectResult.StatusCode ?? 200 : ((StatusCodeResult)result).StatusCode;
    }

    private async Task<GoogleExternalTransactionSender.Outcome> SendAsync(bool configured = true)
    {
        await using var db = database.CreateContext();
        IGoogleExternalTransactionReporter reporter = configured
            ? new GoogleExternalTransactionReporter("app.heartpact", _ => Task.FromResult("access-token"), new HttpClient(handler), NullLogger<GoogleExternalTransactionReporter>.Instance)
            : new DisabledGoogleExternalTransactionReporter();
        return await new GoogleExternalTransactionSender(db, reporter, clock, NullLogger<GoogleExternalTransactionSender>.Instance).RunAsync(default);
    }

    private async Task<List<GoogleExternalTransaction>> RowsAsync()
    {
        await using var db = database.CreateContext();
        return await db.GoogleExternalTransactions.AsNoTracking().OrderBy(item => item.CreatedAtUtc).ThenBy(item => item.Kind).ToListAsync();
    }

    /// <summary>Maya in France, the switch on: card payment is offered in her app.</summary>
    private async Task<TestCouple> CoupleInFranceAsync()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        (await db.Users.SingleAsync(user => user.Id == couple.MayaId)).SetCountry("FR", Now);
        await db.SaveChangesAsync();
        await new FeatureFlagService(db, clock).SetAsync(new FeatureFlagsUpdate(BachsInApp: true), default);
        return couple;
    }

    private async Task<IActionResult> CheckoutAsync(TestCouple couple, string from = "app", string? token = Token)
    {
        await using var db = database.CreateContext();
        return await Controller(db, couple.MayaId, couple.CoupleId).Checkout(new BachsCheckoutStart("monthly", from, token), default);
    }

    private static string Iso(DateTime at) => at.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static string Created(string eventId, Guid userId, Guid? externalRef, string status = "active", string amount = "7.99", string subscriptionId = "sub_1", DateTime? at = null)
    {
        var when = at ?? Now;
        var metadata = new Dictionary<string, string> { ["user_id"] = userId.ToString() };
        if (externalRef is { } reference) metadata["external_ref"] = reference.ToString();
        return JsonSerializer.Serialize(new
        {
            id = eventId,
            type = "customer.subscription.created",
            created_at = Iso(when),
            data = new
            {
                subscription_id = subscriptionId,
                customer = new { customer_id = "cust_1" },
                product_id = "prod_month",
                status,
                amount,
                currency = "USD",
                current_period_end = Iso(when.AddDays(status == "trialing" ? 7 : 30)),
                trial_end = status == "trialing" ? Iso(when.AddDays(7)) : null,
                cancel_at_period_end = false,
                created_at = Iso(when),
                metadata,
            },
        });
    }

    private static string InvoicePaid(string eventId, string invoiceId, DateTime at, string amount = "7.99", string? chargeId = null, string subscriptionId = "sub_1")
        => JsonSerializer.Serialize(new
        {
            id = eventId,
            type = "invoice.paid",
            created_at = Iso(at),
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

    private static JsonElement BodyOf((HttpRequestMessage Request, string? Body) sent) => JsonDocument.Parse(sent.Body!).RootElement;

    [Fact]
    public async Task The_token_is_kept_before_bachs_is_called_and_only_our_row_id_goes_to_bachs()
    {
        var couple = await CoupleInFranceAsync();

        var result = await CheckoutAsync(couple);

        Assert.Equal(200, ((ObjectResult)result).StatusCode ?? 200);
        var row = Assert.Single(await RowsAsync());
        Assert.Equal((couple.MayaId, Token, "FR", GoogleExternalTransaction.Initial), (row.UserId, row.Token, row.RegionCode, row.Kind));
        Assert.Null(row.TransactionTimeUtc);
        Assert.Matches("^hp-[0-9a-f]{32}$", row.ExternalTransactionId);
        var request = Assert.Single(bachs.Checkouts).Request;
        Assert.Equal(row.Id, request.ExternalRef);
        Assert.EndsWith("?from=app", request.SuccessUrl);
        // Nothing is ready to report until the subscription exists.
        Assert.Equal(0, (await SendAsync()).Reported);
        Assert.Empty(handler.Sent);
    }

    [Fact]
    public async Task A_token_is_only_accepted_from_the_app()
    {
        var couple = await CoupleInFranceAsync();

        var result = await CheckoutAsync(couple, from: "web");

        Assert.Equal((400, "billing.invalid_from"), (((ObjectResult)result).StatusCode, ((ApiError)((ObjectResult)result).Value!).Code));
        Assert.Empty(await RowsAsync());
    }

    [Fact]
    public async Task The_initial_transaction_is_reported_once_the_subscription_starts()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        var reference = (await RowsAsync()).Single().Id;
        clock.UtcNow = Now.AddMinutes(3);

        Assert.Equal(200, await DeliverAsync(Created("evt_1", couple.MayaId, reference, at: Now.AddMinutes(2))));
        var outcome = await SendAsync();

        Assert.Equal(1, outcome.Reported);
        var sent = Assert.Single(handler.Sent);
        var row = Assert.Single(await RowsAsync());
        Assert.Equal(HttpMethod.Post, sent.Request.Method);
        Assert.Equal($"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/app.heartpact/externalTransactions?externalTransactionId={row.ExternalTransactionId}",
            sent.Request.RequestUri!.ToString());
        Assert.Equal("Bearer access-token", sent.Request.Headers.Authorization!.ToString());
        var body = BodyOf(sent);
        Assert.Equal(("7990000", "USD"), (body.GetProperty("originalPreTaxAmount").GetProperty("priceMicros").GetString(), body.GetProperty("originalPreTaxAmount").GetProperty("currency").GetString()));
        Assert.Equal("0", body.GetProperty("originalTaxAmount").GetProperty("priceMicros").GetString());
        Assert.Equal("2026-10-25T12:02:00Z", body.GetProperty("transactionTime").GetString());
        var recurring = body.GetProperty("recurringTransaction");
        Assert.Equal(Token, recurring.GetProperty("externalTransactionToken").GetString());
        Assert.Equal("RECURRING", recurring.GetProperty("externalSubscription").GetProperty("subscriptionType").GetString());
        Assert.False(recurring.TryGetProperty("initialExternalTransactionId", out _));
        Assert.Equal("FR", body.GetProperty("userTaxAddress").GetProperty("regionCode").GetString());
        Assert.Equal((Now.AddMinutes(3), (string?)null, "sub_1"), (row.ReportedAtUtc, row.Token, row.BachsSubscriptionId));
        Assert.Equal(0, (await SendAsync()).Reported);
        Assert.Single(handler.Sent);
    }

    [Fact]
    public async Task A_trial_start_is_reported_at_zero_and_the_first_paid_invoice_as_a_renewal()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        var reference = (await RowsAsync()).Single().Id;
        await DeliverAsync(Created("evt_1", couple.MayaId, reference, status: "trialing"));
        await SendAsync();
        clock.UtcNow = Now.AddDays(7);

        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now.AddDays(7))));
        await SendAsync();

        Assert.Equal("0", BodyOf(handler.Sent[0]).GetProperty("originalPreTaxAmount").GetProperty("priceMicros").GetString());
        var renewal = BodyOf(handler.Sent[1]);
        Assert.Equal("7990000", renewal.GetProperty("originalPreTaxAmount").GetProperty("priceMicros").GetString());
        Assert.EndsWith("externalTransactionId=hp-inv_1", handler.Sent[1].Request.RequestUri!.ToString());
    }

    [Fact]
    public async Task Renewals_point_at_the_initial_transaction_and_the_first_invoice_is_not_reported_twice()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        var initial = (await RowsAsync()).Single();
        await DeliverAsync(Created("evt_1", couple.MayaId, initial.Id));

        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now, chargeId: "ch_1")));
        clock.UtcNow = Now.AddDays(30);
        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_3", "inv_2", Now.AddDays(30), amount: "7.99", chargeId: "ch_2")));
        await SendAsync();

        var rows = await RowsAsync();
        Assert.Equal(2, rows.Count);
        Assert.Equal(("inv_1", "ch_1"), (rows[0].BachsInvoiceId, rows[0].BachsChargeId));
        var renewal = rows.Single(item => item.Kind == GoogleExternalTransaction.Renewal);
        Assert.Equal(("hp-inv_2", initial.ExternalTransactionId, "FR"), (renewal.ExternalTransactionId, renewal.InitialExternalTransactionId, renewal.RegionCode));
        Assert.Equal(2, handler.Sent.Count);
        var body = BodyOf(handler.Sent[1]);
        var recurring = body.GetProperty("recurringTransaction");
        Assert.Equal(initial.ExternalTransactionId, recurring.GetProperty("initialExternalTransactionId").GetString());
        Assert.False(recurring.TryGetProperty("externalTransactionToken", out _));
        Assert.Equal("RECURRING", recurring.GetProperty("externalSubscription").GetProperty("subscriptionType").GetString());
        Assert.Equal("2026-11-24T12:00:00Z", body.GetProperty("transactionTime").GetString());
    }

    [Fact]
    public async Task A_failed_report_is_retried_with_backoff_and_a_409_counts_as_reported()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        await DeliverAsync(Created("evt_1", couple.MayaId, (await RowsAsync()).Single().Id));
        google = _ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable) { Content = new StringContent("{\"error\":{\"status\":\"UNAVAILABLE\"}}") };

        var first = await SendAsync();
        var failed = Assert.Single(await RowsAsync());
        var soon = await SendAsync();
        clock.UtcNow = Now.AddMinutes(2);
        google = _ => new HttpResponseMessage(HttpStatusCode.Conflict) { Content = new StringContent("{\"error\":{\"status\":\"ALREADY_EXISTS\"}}") };
        var later = await SendAsync();

        Assert.Equal((0, 1), (first.Reported, first.Failed));
        Assert.Equal((1, "google.http_503_unavailable", Now.AddMinutes(1)), (failed.Attempts, failed.LastError, failed.NextAttemptAtUtc));
        Assert.Equal((0, 0), (soon.Reported, soon.Failed));
        Assert.Equal(1, later.Reported);
        Assert.Equal(2, handler.Sent.Count);
        var row = Assert.Single(await RowsAsync());
        Assert.Equal((Now.AddMinutes(2), (string?)null), (row.ReportedAtUtc, row.LastError));
    }

    [Fact]
    public async Task Without_google_credentials_rows_wait_and_are_counted_overdue_after_twelve_hours()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        await DeliverAsync(Created("evt_1", couple.MayaId, (await RowsAsync()).Single().Id));
        clock.UtcNow = Now.AddHours(13);

        var outcome = await SendAsync(configured: false);

        Assert.Equal((0, 0, 1), (outcome.Reported, outcome.Failed, outcome.Overdue));
        Assert.Null(Assert.Single(await RowsAsync()).ReportedAtUtc);
    }

    [Fact]
    public async Task With_the_card_switch_on_the_app_can_start_a_card_checkout_anywhere_without_Google()
    {
        await using (var db = database.CreateContext()) await TestCouple.AddAsync(db);
        var couple = await CoupleInFranceAsync();
        await using (var db = database.CreateContext())
        {
            (await db.Users.SingleAsync(user => user.Id == couple.MayaId)).SetCountry("NG", Now);
            await db.SaveChangesAsync();
        }

        var result = await CheckoutAsync(couple, from: "app", token: null);

        Assert.IsType<OkObjectResult>(result);
        Assert.EndsWith("?from=app", Assert.Single(bachs.Checkouts).Request.SuccessUrl);
        // Nothing came through Google's choice screen, so there is nothing to report to Google.
        await using var check = database.CreateContext();
        Assert.Empty(await check.GoogleExternalTransactions.ToListAsync());
    }

    [Fact]
    public async Task With_the_card_switch_off_the_app_cannot_start_a_card_checkout()
    {
        await using (var db = database.CreateContext()) await TestCouple.AddAsync(db);
        var couple = await CoupleInFranceAsync();
        await using (var db = database.CreateContext()) await new FeatureFlagService(db, clock).SetAsync(new FeatureFlagsUpdate(BachsInApp: false), default);

        var result = (ObjectResult)await CheckoutAsync(couple, from: "app", token: null);

        Assert.Equal((403, "billing.card_in_app_unavailable"), (result.StatusCode, ((ApiError)result.Value!).Code));
        Assert.Empty(bachs.Checkouts);
    }

    [Fact]
    public async Task A_web_purchase_reports_nothing()
    {
        await using (var db = database.CreateContext()) await TestCouple.AddAsync(db);
        var couple = await CoupleInFranceAsync();
        Assert.Equal(200, ((ObjectResult)await CheckoutAsync(couple, from: "web", token: null)).StatusCode ?? 200);

        Assert.Equal(200, await DeliverAsync(Created("evt_1", couple.MayaId, externalRef: null)));
        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now)));
        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_3", "inv_2", Now.AddDays(30))));
        await SendAsync();

        Assert.Empty(await RowsAsync());
        Assert.Empty(handler.Sent);
    }

    [Fact]
    public async Task A_redelivered_invoice_is_reported_once()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        await DeliverAsync(Created("evt_1", couple.MayaId, (await RowsAsync()).Single().Id, status: "trialing"));

        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now.AddDays(7))));
        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now.AddDays(7))));
        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_2b", "inv_1", Now.AddDays(7))));
        await SendAsync();
        await SendAsync();

        Assert.Single((await RowsAsync()).Where(item => item.Kind == GoogleExternalTransaction.Renewal));
        Assert.Equal(2, handler.Sent.Count);
    }

    [Fact]
    public async Task An_invoice_ahead_of_its_subscription_is_retried_until_the_subscription_arrives()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        var reference = (await RowsAsync()).Single().Id;

        Assert.Equal(500, await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now)));
        Assert.Equal(200, await DeliverAsync(Created("evt_1", couple.MayaId, reference, status: "trialing")));
        Assert.Equal(200, await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now)));

        Assert.Single((await RowsAsync()).Where(item => item.Kind == GoogleExternalTransaction.Renewal));
    }

    [Fact]
    public async Task A_bachs_refund_of_a_reported_payment_is_reported_to_google()
    {
        var couple = await CoupleInFranceAsync();
        await CheckoutAsync(couple);
        var initial = (await RowsAsync()).Single();
        await DeliverAsync(Created("evt_1", couple.MayaId, initial.Id));
        await DeliverAsync(InvoicePaid("evt_2", "inv_1", Now, chargeId: "ch_1"));
        await SendAsync();
        clock.UtcNow = Now.AddDays(2);
        var refund = JsonSerializer.Serialize(new
        {
            id = "evt_3",
            type = "refund.paid",
            created_at = Iso(Now.AddDays(2)),
            data = new { refund_id = "ref_1", charge_id = "ch_1", status = "paid", requested_amount = "7.99", refunded_amount = "7.99" },
        });

        Assert.Equal(200, await DeliverAsync(refund));
        await SendAsync();

        var sent = handler.Sent[1];
        Assert.Equal($"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/app.heartpact/externalTransactions/{initial.ExternalTransactionId}:refund", sent.Request.RequestUri!.ToString());
        var body = BodyOf(sent);
        Assert.Equal("2026-10-27T12:00:00Z", body.GetProperty("refundTime").GetString());
        Assert.Equal(JsonValueKind.Object, body.GetProperty("fullRefund").ValueKind);
        Assert.NotNull(Assert.Single(await RowsAsync()).RefundReportedAtUtc);
    }

    [Fact]
    public void A_partial_refund_names_its_amount()
    {
        var row = GoogleExternalTransaction.ForCheckout(Guid.NewGuid(), Token, "US", Now);
        row.Start("sub_1", 39_990_000, "USD", Now, Now);
        row.MarkReported(Now);
        row.RequestRefund("ref_9", 10_000_000, Now.AddDays(1), Now.AddDays(1));

        var body = GoogleExternalTransactionBodies.Refund(row);

        Assert.Equal("ref_9", body["partialRefund"]!["refundId"]!.GetValue<string>());
        Assert.Equal("10000000", body["partialRefund"]!["refundPreTaxAmount"]!["priceMicros"]!.GetValue<string>());
        Assert.Null(body["fullRefund"]);
    }

    [Fact]
    public async Task Deleting_the_account_keeps_only_what_google_still_has_to_be_told()
    {
        var couple = await CoupleInFranceAsync();
        // Two checkouts opened; only the first is paid.
        await CheckoutAsync(couple);
        var paid = (await RowsAsync()).Single().Id;
        await CheckoutAsync(couple);
        await DeliverAsync(Created("evt_1", couple.MayaId, paid));
        Assert.Equal(2, (await RowsAsync()).Count);

        await using (var db = database.CreateContext())
            Assert.NotNull(await new AccountEraser(db, clock).EraseAsync(couple.MayaId, default));

        var kept = Assert.Single(await RowsAsync());
        Assert.Equal("sub_1", kept.BachsSubscriptionId);
        Assert.Null(kept.ReportedAtUtc);
    }
}
