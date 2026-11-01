using System.Net;
using System.Security.Claims;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Admin;
using Twogether.Api.Controllers.Admin;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Config;
using Twogether.Infrastructure.Billing;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Results;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>
/// The admin billing check (docs/billing.md, "Why Plus isn't on sale"): what Play Console and Bachs hold, read through
/// fakes, and the plain-English next steps it computes from that.
/// </summary>
public sealed class BillingCheckTests : IDisposable
{
    private const string Monthly = "heartpact_plus_monthly";
    private const string Yearly = "heartpact_plus_yearly";

    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);
    private readonly FakePlayCatalog play = new();
    private readonly FakeBachsBilling bachs = new();

    private readonly BillingSettings billing = new()
    {
        Provider = "googleplay",
        GooglePlayPackageName = "app.heartpact",
        GooglePlayCredentialsJson = "{}",
        ProductIds = [Monthly, Yearly],
    };

    private BachsSettings bachsSettings = new() { ApiKey = "sk_live_key", WebhookSecret = "whsec_x", MonthlyProductId = "prod_month", YearlyProductId = "prod_year" };

    public BillingCheckTests()
    {
        bachs.OnGetProduct = id => id switch
        {
            "prod_month" => new BachsProductSnapshot(id, "Heartpact Plus (monthly)", "active", false, "USD 7.99", "month", 1, null),
            "prod_year" => new BachsProductSnapshot(id, "Heartpact Plus (yearly)", "active", false, "USD 39.99", "year", 1, "7 days"),
            _ => Result.Failure<BachsProductSnapshot>(Error.NotFound("billing.bachs_not_found", "not found")),
        };
    }

    public void Dispose() => database.Dispose();

    private BillingCheckService Service(ApplicationDbContext db, BillingSettings? settings = null)
        => new(play, bachs, settings ?? billing, bachsSettings, new FeatureFlagService(db, clock), clock);

    private async Task<BillingCheckDto> RunAsync(string? country = "NG", BillingSettings? settings = null, bool bachsInApp = false)
    {
        await using var db = database.CreateContext();
        if (bachsInApp) await new FeatureFlagService(db, clock).SetAsync(new FeatureFlagsUpdate(BachsInApp: true), default);
        return await Service(db, settings).RunAsync(country, default);
    }

    private static PlayBasePlanInfo Plan(string id, string state = "ACTIVE", params string[] regions)
        => new(id, state, "autoRenewing", id == "yearly" ? "P1Y" : "P1M",
            (regions.Length == 0 ? ["NG", "US", "GB"] : regions).Select(region => new PlayRegionInfo(region, true, region == "NG" ? "NGN 4,500" : "USD 4.99")).ToList());

    private static PlaySubscriptionInfo Subscription(string productId, params PlayBasePlanInfo[] plans)
        => new(productId, false, plans.Length == 0 ? [Plan(productId == Yearly ? "yearly" : "monthly")] : plans, []);

    private void PlayHas(params PlaySubscriptionInfo[] subscriptions) => play.Read = new PlayCatalogRead(subscriptions);

    private static IEnumerable<BillingHintDto> Problems(BillingCheckDto result, string area = BillingCheckHints.GooglePlay)
        => result.Hints.Where(hint => hint.Area == area && hint.Level == HintLevel.Problem);

    [Fact]
    public async Task All_good_says_so_with_the_tester_steps_and_shows_prices_per_region()
    {
        PlayHas(Subscription(Monthly), Subscription(Yearly));

        var result = await RunAsync();

        Assert.Empty(Problems(result));
        var ok = Assert.Single(result.Hints, hint => hint.Area == BillingCheckHints.GooglePlay && hint.Level == HintLevel.Ok);
        Assert.Contains("No problems found with the products in Nigeria", ok.Text);
        Assert.Contains("License testing", ok.Text);
        Assert.Contains("internal testing opt-in link", ok.Text);
        var monthly = result.GooglePlay.Products[0];
        Assert.Equal((Monthly, true, false), (monthly.ProductId, monthly.Found, monthly.Archived));
        var plan = Assert.Single(monthly.BasePlans);
        Assert.Equal(("monthly", "ACTIVE", "autoRenewing", "P1M", 3), (plan.BasePlanId, plan.State, plan.Type, plan.BillingPeriod, plan.RegionCount));
        Assert.Equal(["GB", "NG", "US"], plan.Regions);
        Assert.Contains(new RegionPriceDto("NG", "NGN 4,500"), plan.Prices);
        Assert.Empty(result.GooglePlay.ExtraProducts);
        Assert.Equal(("NG", "app.heartpact", true), (result.Country, result.GooglePlay.PackageName, result.GooglePlay.Configured));
        Assert.Equal([Monthly, Yearly], play.OffersAskedFor);
    }

    [Fact]
    public async Task A_missing_product_says_to_create_it_with_exactly_that_id()
    {
        PlayHas(Subscription(Yearly));

        var result = await RunAsync();

        var problem = Assert.Single(Problems(result));
        Assert.Equal($"{Monthly} doesn't exist in Play Console: Monetize with Play → Products → Subscriptions → Create subscription with exactly this id.", problem.Text);
        Assert.False(result.GooglePlay.Products.Single(item => item.ProductId == Monthly).Found);
        Assert.DoesNotContain(result.Hints, hint => hint.Area == BillingCheckHints.GooglePlay && hint.Level == HintLevel.Ok);
    }

    [Fact]
    public async Task A_draft_base_plan_says_to_activate_it()
    {
        PlayHas(Subscription(Monthly, Plan("monthly", "DRAFT")), Subscription(Yearly));

        var result = await RunAsync();

        Assert.Equal($"Base plan monthly of {Monthly} is DRAFT: open it and press Activate.", Assert.Single(Problems(result)).Text);
    }

    [Fact]
    public async Task A_draft_base_plan_next_to_an_active_one_is_only_worth_knowing()
    {
        PlayHas(Subscription(Monthly, Plan("monthly"), Plan("monthly-old", "INACTIVE")), Subscription(Yearly));

        var result = await RunAsync();

        Assert.Empty(Problems(result));
        Assert.Contains(result.Hints, hint => hint.Level == HintLevel.Info && hint.Text.StartsWith("Base plan monthly-old of heartpact_plus_monthly is INACTIVE", StringComparison.Ordinal));
    }

    [Fact]
    public async Task A_product_not_sold_in_the_country_says_to_add_it_to_the_base_plan()
    {
        PlayHas(Subscription(Monthly, Plan("monthly", "ACTIVE", "US", "GB")), Subscription(Yearly));

        var result = await RunAsync("ng");

        Assert.Equal($"{Monthly} isn't available in Nigeria (NG): edit base plan monthly's prices and countries and add Nigeria.", Assert.Single(Problems(result)).Text);
        Assert.Equal("NG", result.Country);
    }

    [Fact]
    public async Task A_base_plan_with_no_region_or_closed_to_new_subscribers_is_explained()
    {
        var closed = new PlayBasePlanInfo("monthly", "ACTIVE", "autoRenewing", "P1M", [new PlayRegionInfo("NG", false, "NGN 4,500"), new PlayRegionInfo("US", true, "USD 4.99")]);
        var none = new PlayBasePlanInfo("yearly", "ACTIVE", "autoRenewing", "P1Y", []);
        PlayHas(Subscription(Monthly, closed), Subscription(Yearly, none));

        var result = await RunAsync();

        var problems = Problems(result).Select(hint => hint.Text).ToList();
        Assert.Contains(problems, text => text.StartsWith($"{Monthly} has a price in Nigeria but is closed to new subscribers there", StringComparison.Ordinal));
        Assert.Contains(problems, text => text.StartsWith($"{Yearly} isn't available in any country", StringComparison.Ordinal));
        var plan = Assert.Single(result.GooglePlay.Products[0].BasePlans);
        Assert.Equal(["US"], plan.Regions);
        Assert.Equal(["NG"], plan.ClosedRegions);
        Assert.Equal(1, plan.RegionCount);
        Assert.DoesNotContain(plan.Prices, price => price.Region == "NG");
    }

    [Fact]
    public async Task An_id_typo_in_play_console_is_pointed_out_once()
    {
        PlayHas(Subscription("heartpact_plus_montly"), Subscription(Yearly), Subscription("old_test_product"));

        var result = await RunAsync();

        Assert.Equal(["heartpact_plus_montly", "old_test_product"], result.GooglePlay.ExtraProducts);
        var problem = Assert.Single(Problems(result));
        Assert.Contains($"{Monthly} doesn't exist in Play Console, but heartpact_plus_montly does", problem.Text);
        Assert.Contains("Billing__ProductIds", problem.Text);
        var extra = Assert.Single(result.Hints, hint => hint.Level == HintLevel.Info && hint.Text.Contains("Play Console also has", StringComparison.Ordinal));
        Assert.Contains("old_test_product", extra.Text);
    }

    [Theory]
    [InlineData(403, null, "Users and permissions")]
    [InlineData(401, "permissionDenied", "Manage orders and subscriptions")]
    [InlineData(403, "accessNotConfigured", "Google Play Android Developer API")]
    [InlineData(404, null, "no app with this package name")]
    public async Task An_access_error_from_google_explains_what_to_fix(int status, string? reason, string expected)
    {
        play.Read = new PlayCatalogRead([], new StoreReadError($"google.http_{status}", status, reason));

        var result = await RunAsync();

        var error = result.GooglePlay.Error!;
        Assert.Equal(($"google.http_{status}", status, reason), (error.Code, error.HttpStatus, error.Reason));
        Assert.Contains(expected, error.Hint);
        Assert.Equal(error.Hint, Assert.Single(Problems(result)).Text);
        Assert.All(result.GooglePlay.Products, product => Assert.False(product.Found));
    }

    [Fact]
    public async Task A_reader_that_throws_becomes_an_error_field_not_a_500()
    {
        play.Throw = new InvalidOperationException("boom");

        var result = await RunAsync();

        Assert.Equal("google.failed_invalidoperationexception", result.GooglePlay.Error!.Code);
        Assert.Single(Problems(result));
    }

    [Fact]
    public async Task Without_a_service_account_nothing_is_asked_and_the_settings_are_named()
    {
        play.IsConfigured = false;

        var result = await RunAsync(settings: new BillingSettings { Provider = "googleplay", GooglePlayPackageName = "app.heartpact", ProductIds = [Monthly] });

        Assert.False(result.GooglePlay.Configured);
        Assert.Contains("Billing__GooglePlayCredentialsJson", Assert.Single(Problems(result)).Text);
        Assert.Equal(0, play.Reads);
    }

    [Fact]
    public async Task A_provider_other_than_googleplay_and_no_product_ids_are_problems()
    {
        PlayHas(Subscription(Monthly));

        var result = await RunAsync(settings: new BillingSettings { Provider = "fake", GooglePlayPackageName = "app.heartpact", GooglePlayCredentialsJson = "{}" });

        var problems = Problems(result).Select(hint => hint.Text).ToList();
        Assert.Contains(problems, text => text.StartsWith("Billing__Provider is \"fake\"", StringComparison.Ordinal));
        Assert.Contains(problems, text => text.StartsWith("Billing__ProductIds is empty", StringComparison.Ordinal));
        Assert.Equal([Monthly], result.GooglePlay.ExtraProducts);
    }

    [Fact]
    public async Task Draft_offers_are_listed_and_noted()
    {
        play.Read = new PlayCatalogRead([Subscription(Monthly) with { Offers = [new PlayOfferInfo("monthly", "trial-week", "DRAFT")] }, Subscription(Yearly)]);

        var result = await RunAsync();

        Assert.Empty(Problems(result));
        Assert.Equal(new PlayOfferCheckDto("monthly", "trial-week", "DRAFT"), Assert.Single(result.GooglePlay.Products[0].Offers));
        Assert.Contains(result.Hints, hint => hint.Level == HintLevel.Info && hint.Text.StartsWith("Offer trial-week on heartpact_plus_monthly / monthly is DRAFT", StringComparison.Ordinal));
    }

    // Bachs

    [Fact]
    public async Task Bachs_ready_with_the_flag_off_says_to_turn_on_card_payments()
    {
        PlayHas(Subscription(Monthly), Subscription(Yearly));

        var result = await RunAsync();

        Assert.Equal((true, "live", false), (result.Bachs.Configured, result.Bachs.Mode, result.Bachs.BachsInApp));
        Assert.Empty(result.Bachs.Missing);
        var monthly = result.Bachs.Products[0];
        Assert.Equal(("monthly", "prod_month", true, true, "USD 7.99", "1 month"), (monthly.Plan, monthly.ProductId, monthly.Found, monthly.Active, monthly.Price, monthly.Interval));
        Assert.Equal("7 days", result.Bachs.Products[1].Trial);
        var hint = Assert.Single(result.Hints, item => item.Area == BillingCheckHints.Bachs);
        Assert.Equal((HintLevel.Todo, "Turn on Card payments in the app (bachsInApp) under Features once this build is live."), (hint.Level, hint.Text));
    }

    [Fact]
    public async Task Bachs_ready_with_the_flag_on_is_ok()
    {
        PlayHas(Subscription(Monthly), Subscription(Yearly));

        var result = await RunAsync(bachsInApp: true);

        Assert.True(result.Bachs.BachsInApp);
        Assert.Equal(HintLevel.Ok, Assert.Single(result.Hints, item => item.Area == BillingCheckHints.Bachs).Level);
    }

    [Fact]
    public async Task Bachs_on_a_sandbox_key_with_a_missing_and_a_wrongly_billed_product_says_what_to_fix()
    {
        bachsSettings = new BachsSettings { ApiKey = "sk_sandbox_key", WebhookSecret = "whsec_x", MonthlyProductId = "prod_gone", YearlyProductId = "prod_month" };
        PlayHas(Subscription(Monthly), Subscription(Yearly));

        var result = await RunAsync();

        Assert.Equal("sandbox", result.Bachs.Mode);
        var problems = Problems(result, BillingCheckHints.Bachs).Select(hint => hint.Text).ToList();
        Assert.Contains(problems, text => text.StartsWith("Bachs (sandbox) has no product prod_gone for the monthly plan", StringComparison.Ordinal) && text.Contains("Bachs__MonthlyProductId", StringComparison.Ordinal));
        Assert.Contains(problems, text => text.StartsWith("Bachs product prod_month for the yearly plan bills every 1 month", StringComparison.Ordinal));
        Assert.Contains(result.Hints, hint => hint.Level == HintLevel.Todo && hint.Text.StartsWith("Bachs is on a sandbox key", StringComparison.Ordinal));
        Assert.DoesNotContain(result.Hints, hint => hint.Text.Contains("Turn on Card payments", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Bachs_not_set_up_names_the_missing_settings_and_asks_nothing()
    {
        bachsSettings = new BachsSettings { MonthlyProductId = "prod_month" };
        bachs.IsConfigured = false;
        PlayHas(Subscription(Monthly), Subscription(Yearly));

        var result = await RunAsync(bachsInApp: true);

        Assert.Equal((false, (string?)null), (result.Bachs.Configured, result.Bachs.Mode));
        Assert.Equal(["Bachs__ApiKey", "Bachs__WebhookSecret", "Bachs__YearlyProductId"], result.Bachs.Missing);
        Assert.All(result.Bachs.Products, product => Assert.False(product.Checked));
        Assert.Equal("prod_month", result.Bachs.Products[0].ProductId);
        Assert.Equal(2, Problems(result, BillingCheckHints.Bachs).Count());
        Assert.Contains(Problems(result, BillingCheckHints.Bachs), hint => hint.Text.Contains("bachsInApp) is on, but Bachs isn't set up", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Bachs_failing_to_answer_is_an_error_on_the_product()
    {
        bachs.OnGetProduct = _ => Result.Failure<BachsProductSnapshot>(new Error("billing.bachs_failed", "x", ErrorType.Network));
        PlayHas(Subscription(Monthly), Subscription(Yearly));

        var result = await RunAsync();

        Assert.All(result.Bachs.Products, product => Assert.Equal("billing.bachs_failed", product.Error!.Code));
        Assert.Equal(2, Problems(result, BillingCheckHints.Bachs).Count());
    }

    // Pieces

    [Theory]
    [InlineData("NGN", 4500L, 0, "NGN 4,500")]
    [InlineData("usd", 4L, 990_000_000, "USD 4.99")]
    [InlineData("GBP", 23L, 990_000_000, "GBP 23.99")]
    public void Prices_read_like_the_play_console(string currency, long units, int nanos, string expected)
        => Assert.Equal(expected, BillingCheckHints.Price(currency, units, nanos));

    [Theory]
    [InlineData("heartpact_plus_montly", true)]
    [InlineData("Heartpact-Plus-Monthly", true)]
    [InlineData("heartpact_plus_yearly", false)]
    [InlineData("old_test_product", false)]
    public void Typos_are_spotted(string candidate, bool similar) => Assert.Equal(similar, BillingCheckHints.Similar(candidate, Monthly));

    [Theory]
    [InlineData(null, "NG")]
    [InlineData("gb", "GB")]
    [InlineData("Nigeria", "NG")]
    [InlineData("1x", "NG")]
    public void The_country_is_two_letters_and_defaults_to_nigeria(string? input, string expected) => Assert.Equal(expected, BillingCheckService.NormalizeCountry(input));

    [Fact]
    public void A_bachs_product_object_is_read_with_its_price_cadence_and_trial()
    {
        using var json = System.Text.Json.JsonDocument.Parse(
            "{\"id\":\"prod_year\",\"name\":\"Heartpact Plus (yearly)\",\"price\":{\"currency\":\"USD\",\"amount\":\"39.99\"},\"billing_cycle\":{\"interval\":\"year\",\"frequency\":1},\"trial_period\":{\"interval\":\"day\",\"frequency\":7},\"status\":\"active\",\"archived_at\":null}");

        var product = BachsEvents.ReadProduct(json.RootElement)!;

        Assert.Equal(("prod_year", "USD 39.99", "1 year", "7 days", true), (product.ProductId, product.Price, product.Interval, product.Trial, product.Active));
    }

    [Fact]
    public async Task The_bachs_client_reads_a_product_and_a_404_is_not_found()
    {
        var handler = new RecordingHandler(request => request.RequestUri!.AbsolutePath.EndsWith("prod_month", StringComparison.Ordinal)
            ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"id\":\"prod_month\",\"status\":\"archived\",\"archived_at\":\"2026-10-01T00:00:00Z\",\"price\":{\"currency\":\"USD\",\"amount\":\"7.99\"},\"billing_cycle\":{\"interval\":\"month\",\"frequency\":1}}") }
            : new HttpResponseMessage(HttpStatusCode.NotFound) { Content = new StringContent("{\"error_code\":\"NOT_FOUND\"}") });
        using var client = new BachsBilling(bachsSettings, NullLogger<BachsBilling>.Instance, handler);

        var found = await client.GetProductAsync("prod_month", default);
        var missing = await client.GetProductAsync("prod_gone", default);

        Assert.Equal(("USD 7.99", false), (found.Value.Price, found.Value.Active));
        Assert.Equal(ErrorType.NotFound, missing.Error.Type);
        Assert.Equal((HttpMethod.Get, "https://api.bachs.io/v1/products/prod_month"), (handler.Sent[0].Request.Method, handler.Sent[0].Request.RequestUri!.ToString()));
    }

    [Fact]
    public async Task The_endpoint_answers_the_check_and_audits_the_run()
    {
        PlayHas(Subscription(Yearly));
        await using var db = database.CreateContext();
        var http = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, Guid.NewGuid().ToString()), new Claim(ClaimTypes.Email, "ops@heartpact.me")], "test")),
        };
        var controller = new AdminBillingController(Service(db), new AdminAudit(db, clock, new HttpContextAccessor { HttpContext = http }), db)
        {
            ControllerContext = new ControllerContext { HttpContext = http },
        };

        var answer = await controller.Check("gb", default);

        var result = Assert.IsType<BillingCheckDto>(Assert.IsType<OkObjectResult>(answer.Result).Value);
        Assert.Equal("GB", result.Country);
        var audit = Assert.Single(await db.AdminAuditEvents.ToListAsync());
        Assert.Equal(("billing.checked", "billing", "GB: 1 problem(s)"), (audit.Action, audit.TargetType, audit.Details));
    }

    [Fact]
    public void A_catalog_reader_without_a_key_is_not_configured()
    {
        var reader = new GooglePlayCatalogReader(new BillingSettings { GooglePlayPackageName = "app.heartpact" }, NullLogger<GooglePlayCatalogReader>.Instance);
        Assert.False(reader.IsConfigured);
    }

    [Fact]
    public async Task A_broken_service_account_key_is_reported_not_thrown()
    {
        using var reader = new GooglePlayCatalogReader(new BillingSettings { GooglePlayPackageName = "app.heartpact", GooglePlayCredentialsJson = "{not json" }, NullLogger<GooglePlayCatalogReader>.Instance);

        var read = await reader.ReadAsync([Monthly], default);

        Assert.True(reader.IsConfigured);
        Assert.Equal("google.credentials_invalid", read.Error!.Code);
    }

    private sealed class FakePlayCatalog : IPlayCatalogReader
    {
        public bool IsConfigured { get; set; } = true;
        public PlayCatalogRead Read { get; set; } = new([]);
        public Exception? Throw { get; set; }
        public int Reads { get; private set; }
        public IReadOnlyCollection<string> OffersAskedFor { get; private set; } = [];

        public Task<PlayCatalogRead> ReadAsync(IReadOnlyCollection<string> offersFor, CancellationToken cancellationToken)
        {
            Reads++;
            OffersAskedFor = offersFor;
            if (Throw is not null) throw Throw;
            return Task.FromResult(Read);
        }
    }
}
