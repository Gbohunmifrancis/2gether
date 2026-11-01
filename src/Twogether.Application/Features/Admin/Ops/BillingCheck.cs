using System.Globalization;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Config;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Admin.Ops;

// ---- What the stores hold, as the ports read it ----

/// <summary>
/// Why a store read failed, kept to what is safe to show: our short code ("google.http_403", "google.timeout"), the
/// HTTP status and the store's one-word reason ("permissionDenied"). Never a message body, a key or a token.
/// </summary>
public sealed record StoreReadError(string Code, int? HttpStatus = null, string? Reason = null);

public sealed record PlayRegionInfo(string RegionCode, bool NewSubscribers, string? Price);

public sealed record PlayBasePlanInfo(string BasePlanId, string State, string Type, string? BillingPeriod, IReadOnlyList<PlayRegionInfo> Regions);

public sealed record PlayOfferInfo(string BasePlanId, string OfferId, string State);

public sealed record PlaySubscriptionInfo(
    string ProductId,
    bool Archived,
    IReadOnlyList<PlayBasePlanInfo> BasePlans,
    IReadOnlyList<PlayOfferInfo> Offers,
    StoreReadError? OffersError = null);

/// <summary>Every subscription Play Console has for the app (archived ones too), or why it couldn't be read.</summary>
public sealed record PlayCatalogRead(IReadOnlyList<PlaySubscriptionInfo> Subscriptions, StoreReadError? Error = null);

/// <summary>
/// Play Console's subscription catalog for the app (monetization.subscriptions), read with the billing service
/// account. Each call to Google is bounded by <see cref="BillingCheckService.CallTimeout"/>; failures come back as
/// <see cref="PlayCatalogRead.Error"/>, never as exceptions.
/// </summary>
public interface IPlayCatalogReader
{
    /// <summary>A package name and a service account key are set.</summary>
    bool IsConfigured { get; }

    /// <summary>Lists the subscriptions, and the offers of those named in <paramref name="offersFor"/>.</summary>
    Task<PlayCatalogRead> ReadAsync(IReadOnlyCollection<string> offersFor, CancellationToken cancellationToken);
}

// ---- What the dashboard gets (GET api/admin/billing/check) ----

public sealed record CheckErrorDto(string Code, int? HttpStatus, string? Reason, string Hint);

public sealed record RegionPriceDto(string Region, string Price);

public sealed record PlayBasePlanCheckDto(
    string BasePlanId,
    // ACTIVE, DRAFT or INACTIVE, as Google says it.
    string State,
    // autoRenewing, prepaid or installments.
    string Type,
    // ISO 8601, "P1M" or "P1Y".
    string? BillingPeriod,
    // How many regions new subscribers can buy it in.
    int RegionCount,
    // Those regions' codes.
    IReadOnlyList<string> Regions,
    // Regions it has a price in but that are closed to new subscribers.
    IReadOnlyList<string> ClosedRegions,
    // The price in each region new subscribers can buy it in, "NGN 4,500".
    IReadOnlyList<RegionPriceDto> Prices);

public sealed record PlayOfferCheckDto(string BasePlanId, string OfferId, string State);

public sealed record PlayProductCheckDto(
    string ProductId,
    bool Found,
    bool Archived,
    IReadOnlyList<PlayBasePlanCheckDto> BasePlans,
    IReadOnlyList<PlayOfferCheckDto> Offers,
    CheckErrorDto? OffersError);

public sealed record GooglePlayCheckDto(
    bool Configured,
    string? Provider,
    string? PackageName,
    IReadOnlyList<string> ProductIds,
    CheckErrorDto? Error,
    IReadOnlyList<PlayProductCheckDto> Products,
    // Subscriptions Play Console has that aren't in Billing:ProductIds (a likely id typo).
    IReadOnlyList<string> ExtraProducts);

public sealed record BachsProductCheckDto(
    // monthly or yearly.
    string Plan,
    string? ProductId,
    // False when Bachs isn't set up, so nothing was asked.
    bool Checked,
    bool Found,
    bool Active,
    string? Name,
    string? Price,
    string? Interval,
    string? Trial,
    CheckErrorDto? Error);

public sealed record BachsCheckDto(
    bool Configured,
    // live or sandbox, from the key; null without a key.
    string? Mode,
    // The settings that aren't set, by name ("Bachs__WebhookSecret"). Never a value.
    IReadOnlyList<string> Missing,
    // The "Card payments in the app" feature flag.
    bool BachsInApp,
    IReadOnlyList<BachsProductCheckDto> Products);

public static class HintLevel
{
    /// <summary>Stops people buying: fix it.</summary>
    public const string Problem = "problem";
    /// <summary>Something to do, but nothing is broken.</summary>
    public const string Todo = "todo";
    /// <summary>Worth knowing.</summary>
    public const string Info = "info";
    /// <summary>All good.</summary>
    public const string Ok = "ok";
}

public sealed record BillingHintDto(
    // googlePlay or bachs.
    string Area,
    string Level,
    string Text);

public sealed record BillingCheckDto(DateTime CheckedAtUtc, string Country, GooglePlayCheckDto GooglePlay, BachsCheckDto Bachs, IReadOnlyList<BillingHintDto> Hints);

/// <summary>
/// The admin "Billing check": reads what Play Console and Bachs actually hold for our products and says, in plain
/// words, what stops Plus from selling (docs/billing.md, "Why Plus isn't on sale"). Read-only, cached nowhere, and
/// every failure is a field of the answer, never an exception.
/// </summary>
public sealed class BillingCheckService(
    IPlayCatalogReader play,
    IBachsBilling bachs,
    BillingSettings billing,
    BachsSettings bachsSettings,
    FeatureFlagService flags,
    IDateTimeProvider clock)
{
    public const string DefaultCountry = "NG";
    public static readonly TimeSpan CallTimeout = TimeSpan.FromSeconds(15);

    /// <summary>"ng" → "NG"; anything that isn't two letters → <see cref="DefaultCountry"/>.</summary>
    public static string NormalizeCountry(string? country)
        => country?.Trim() is { Length: 2 } code && code.All(char.IsAsciiLetter) ? code.ToUpperInvariant() : DefaultCountry;

    public async Task<BillingCheckDto> RunAsync(string? country, CancellationToken cancellationToken)
    {
        var region = NormalizeCountry(country);
        var googlePlay = await GooglePlayAsync(cancellationToken);
        var card = await BachsAsync(cancellationToken);
        return new BillingCheckDto(clock.UtcNow, region, googlePlay, card, BillingCheckHints.For(googlePlay, card, region));
    }

    private async Task<GooglePlayCheckDto> GooglePlayAsync(CancellationToken cancellationToken)
    {
        var productIds = billing.ProductIds.Where(id => !string.IsNullOrWhiteSpace(id)).Select(id => id.Trim()).Distinct(StringComparer.Ordinal).ToList();
        var provider = string.IsNullOrWhiteSpace(billing.Provider) ? null : billing.Provider.Trim();
        var packageName = string.IsNullOrWhiteSpace(billing.GooglePlayPackageName) ? null : billing.GooglePlayPackageName.Trim();
        if (!play.IsConfigured)
            return new GooglePlayCheckDto(false, provider, packageName, productIds, null, productIds.Select(Missing).ToList(), []);

        PlayCatalogRead read;
        try
        {
            read = await play.ReadAsync(productIds, cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            read = new PlayCatalogRead([], new StoreReadError("google.failed_" + exception.GetType().Name.ToLowerInvariant()));
        }
        if (read.Error is { } error)
            return new GooglePlayCheckDto(true, provider, packageName, productIds, BillingCheckHints.Explain(error), productIds.Select(Missing).ToList(), []);

        var byId = read.Subscriptions.GroupBy(item => item.ProductId, StringComparer.Ordinal).ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);
        var products = productIds.Select(id => byId.TryGetValue(id, out var found) ? Product(found) : Missing(id)).ToList();
        var extras = read.Subscriptions.Select(item => item.ProductId).Where(id => !productIds.Contains(id, StringComparer.Ordinal)).Distinct().Order(StringComparer.Ordinal).ToList();
        return new GooglePlayCheckDto(true, provider, packageName, productIds, null, products, extras);
    }

    private static PlayProductCheckDto Missing(string productId) => new(productId, false, false, [], [], null);

    private static PlayProductCheckDto Product(PlaySubscriptionInfo info) => new(
        info.ProductId,
        true,
        info.Archived,
        info.BasePlans.Select(plan =>
        {
            var open = plan.Regions.Where(item => item.NewSubscribers).OrderBy(item => item.RegionCode, StringComparer.Ordinal).ToList();
            return new PlayBasePlanCheckDto(
                plan.BasePlanId,
                string.IsNullOrWhiteSpace(plan.State) ? "STATE_UNSPECIFIED" : plan.State.ToUpperInvariant(),
                plan.Type,
                plan.BillingPeriod,
                open.Count,
                open.Select(item => item.RegionCode).ToList(),
                plan.Regions.Where(item => !item.NewSubscribers).Select(item => item.RegionCode).Order(StringComparer.Ordinal).ToList(),
                open.Where(item => item.Price is not null).Select(item => new RegionPriceDto(item.RegionCode, item.Price!)).ToList());
        }).ToList(),
        info.Offers.Select(offer => new PlayOfferCheckDto(offer.BasePlanId, offer.OfferId, string.IsNullOrWhiteSpace(offer.State) ? "STATE_UNSPECIFIED" : offer.State.ToUpperInvariant())).ToList(),
        info.OffersError is null ? null : BillingCheckHints.Explain(info.OffersError));

    private async Task<BachsCheckDto> BachsAsync(CancellationToken cancellationToken)
    {
        var flagsNow = await flags.GetAsync(cancellationToken);
        var key = bachsSettings.ApiKey?.Trim();
        var mode = string.IsNullOrEmpty(key) ? null : key.StartsWith("sk_live_", StringComparison.Ordinal) ? "live" : "sandbox";
        var missing = new List<string>();
        if (string.IsNullOrWhiteSpace(bachsSettings.ApiKey)) missing.Add("Bachs__ApiKey");
        if (string.IsNullOrWhiteSpace(bachsSettings.WebhookSecret)) missing.Add("Bachs__WebhookSecret");
        if (string.IsNullOrWhiteSpace(bachsSettings.MonthlyProductId)) missing.Add("Bachs__MonthlyProductId");
        if (string.IsNullOrWhiteSpace(bachsSettings.YearlyProductId)) missing.Add("Bachs__YearlyProductId");
        var configured = bachsSettings.IsConfigured && bachs.IsConfigured;
        var products = new List<BachsProductCheckDto>();
        foreach (var plan in new[] { "monthly", "yearly" })
        {
            var id = bachsSettings.BachsProductFor(plan);
            if (!configured || string.IsNullOrEmpty(id))
            {
                products.Add(new BachsProductCheckDto(plan, string.IsNullOrEmpty(id) ? null : id, false, false, false, null, null, null, null, null));
                continue;
            }
            Result<BachsProductSnapshot> read;
            try
            {
                read = await bachs.GetProductAsync(id, cancellationToken);
            }
            catch (Exception exception) when (exception is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
            {
                read = Result.Failure<BachsProductSnapshot>(Error.Failure("bachs.failed_" + exception.GetType().Name.ToLowerInvariant(), "failed"));
            }
            if (read.IsSuccess)
            {
                var product = read.Value;
                products.Add(new BachsProductCheckDto(plan, id, true, true, product.Active, product.Name, product.Price, product.Interval, product.Trial, null));
            }
            else if (read.Error.Type == ErrorType.NotFound)
                products.Add(new BachsProductCheckDto(plan, id, true, false, false, null, null, null, null, null));
            else
                products.Add(new BachsProductCheckDto(plan, id, true, false, false, null, null, null, null, BillingCheckHints.ExplainBachs(read.Error.Code)));
        }
        return new BachsCheckDto(configured, mode, missing, flagsNow.BachsInApp, products);
    }
}

/// <summary>The plain-English next steps, as pure functions of what the check found. Unit tested.</summary>
public static class BillingCheckHints
{
    public const string GooglePlay = "googlePlay";
    public const string Bachs = "bachs";

    private const string CreateSubscription = "Monetize with Play → Products → Subscriptions → Create subscription";

    /// <summary>What a failed Google read means and what to do about it.</summary>
    public static CheckErrorDto Explain(StoreReadError error)
    {
        var hint = error switch
        {
            { Reason: "accessNotConfigured" or "SERVICE_DISABLED" } =>
                "The Google Play Android Developer API is off in the service account's Google Cloud project: APIs & Services → Library → Google Play Android Developer API → Enable, wait a few minutes, then run the check again.",
            { HttpStatus: 401 or 403 } =>
                "The service account has no access to this app in Play Console: Users and permissions → invite it with View app information and View financial data, Manage orders and subscriptions.",
            { HttpStatus: 404 } =>
                "Play Console has no app with this package name, or it has no subscriptions yet. Check Billing__GooglePlayPackageName, and that the app has been uploaded to a testing track.",
            { Code: "google.timeout" } => "Google Play didn't answer within 15 seconds: run the check again.",
            { Code: "google.credentials_invalid" } =>
                "Billing__GooglePlayCredentialsJson isn't a usable service account key: paste the whole JSON key file of the service account.",
            { Code: "google.token_refused" } =>
                "Google refused the service account's key (deleted, disabled or from another project): create a new JSON key for the service account and put it in Billing__GooglePlayCredentialsJson.",
            { Code: "google.unreachable" } => "Google Play couldn't be reached from the server: run the check again shortly.",
            { HttpStatus: { } status } => $"Google Play answered {status}: run the check again; if it keeps failing, look at the API's logs.",
            _ => "Google Play couldn't be read: run the check again; if it keeps failing, look at the API's logs.",
        };
        return new CheckErrorDto(error.Code, error.HttpStatus, error.Reason, hint);
    }

    public static CheckErrorDto ExplainBachs(string code) => new(code, null, null, code switch
    {
        "billing.bachs_refused" => "Bachs refused to read the product: check the id in the Bachs dashboard.",
        _ => "Bachs couldn't be reached or refused the key: run the check again; if it keeps failing, check Bachs__ApiKey.",
    });

    /// <summary>The English name of a region ("NG" → "Nigeria"), or the code when .NET doesn't know it.</summary>
    public static string CountryName(string code)
    {
        try
        {
            return new RegionInfo(code).EnglishName;
        }
        catch (ArgumentException)
        {
            return code;
        }
    }

    public static IReadOnlyList<BillingHintDto> For(GooglePlayCheckDto play, BachsCheckDto bachs, string country)
    {
        var hints = new List<BillingHintDto>();
        GooglePlayHints(play, country, hints);
        BachsHints(bachs, hints);
        return hints;
    }

    private static void GooglePlayHints(GooglePlayCheckDto play, string country, List<BillingHintDto> hints)
    {
        void Add(string level, string text) => hints.Add(new BillingHintDto(GooglePlay, level, text));
        var start = hints.Count;
        if (!play.Configured)
        {
            var missing = new List<string>();
            if (play.PackageName is null) missing.Add("Billing__GooglePlayPackageName");
            missing.Add("Billing__GooglePlayCredentialsJson");
            Add(HintLevel.Problem, $"The server can't read Play Console: set {string.Join(" and ", missing)} (the service account's JSON key).");
        }
        if (!string.Equals(play.Provider, "googleplay", StringComparison.OrdinalIgnoreCase))
            Add(HintLevel.Problem, $"Billing__Provider is {(play.Provider is null ? "not set" : $"\"{play.Provider}\"")}, so the server doesn't verify Google Play purchases: set it to googleplay.");
        if (play.ProductIds.Count == 0)
            Add(HintLevel.Problem, "Billing__ProductIds is empty, so the app has no products to ask Google Play for: set it to heartpact_plus_monthly,heartpact_plus_yearly.");
        if (!play.Configured) return;
        if (play.Error is { } error)
        {
            Add(HintLevel.Problem, error.Hint);
            return;
        }

        var name = CountryName(country);
        var typos = new HashSet<string>(StringComparer.Ordinal);
        foreach (var product in play.Products)
        {
            if (!product.Found)
            {
                var similar = play.ExtraProducts.Where(extra => Similar(extra, product.ProductId)).OrderBy(extra => Distance(extra, product.ProductId)).FirstOrDefault();
                if (similar is not null)
                {
                    typos.Add(similar);
                    Add(HintLevel.Problem, $"{product.ProductId} doesn't exist in Play Console, but {similar} does: a product id can't be renamed, so either create {product.ProductId} ({CreateSubscription}) or set Billing__ProductIds to the id Play has.");
                }
                else Add(HintLevel.Problem, $"{product.ProductId} doesn't exist in Play Console: {CreateSubscription} with exactly this id.");
                continue;
            }
            if (product.Archived)
            {
                Add(HintLevel.Problem, $"{product.ProductId} is archived in Play Console, so Google Play doesn't sell it: restore it, or create a new subscription and put its id in Billing__ProductIds.");
                continue;
            }
            if (product.BasePlans.Count == 0)
            {
                Add(HintLevel.Problem, $"{product.ProductId} has no base plan: open it in Play Console, add an auto-renewing base plan with its prices and countries, and press Activate.");
                continue;
            }
            var active = product.BasePlans.Where(plan => plan.State == "ACTIVE").ToList();
            foreach (var plan in product.BasePlans.Where(plan => plan.State != "ACTIVE"))
                Add(active.Count == 0 ? HintLevel.Problem : HintLevel.Info,
                    $"Base plan {plan.BasePlanId} of {product.ProductId} is {plan.State}: open it and press Activate{(active.Count == 0 ? "." : " (the product still sells through its other base plan).")}");
            if (active.Count > 0 && !active.Any(plan => plan.Regions.Contains(country, StringComparer.OrdinalIgnoreCase)))
            {
                var closed = active.FirstOrDefault(plan => plan.ClosedRegions.Contains(country, StringComparer.OrdinalIgnoreCase));
                if (closed is not null)
                    Add(HintLevel.Problem, $"{product.ProductId} has a price in {name} but is closed to new subscribers there: edit base plan {closed.BasePlanId}'s prices and countries and make {name} available to new subscribers.");
                else if (active.All(plan => plan.RegionCount == 0))
                    Add(HintLevel.Problem, $"{product.ProductId} isn't available in any country: edit base plan {active[0].BasePlanId}'s prices and countries, add {name} and the other countries you sell in, and save.");
                else
                    Add(HintLevel.Problem, $"{product.ProductId} isn't available in {name} ({country}): edit base plan {active[0].BasePlanId}'s prices and countries and add {name}.");
            }
            foreach (var offer in product.Offers.Where(offer => offer.State != "ACTIVE"))
                Add(HintLevel.Info, $"Offer {offer.OfferId} on {product.ProductId} / {offer.BasePlanId} is {offer.State}: activate it if the Plus screen should show it (the plan sells without it).");
            if (product.OffersError is { } offersError)
                Add(HintLevel.Info, $"The offers of {product.ProductId} couldn't be read ({offersError.Code}); the base plans above were.");
        }
        foreach (var extra in play.ExtraProducts.Where(extra => !typos.Contains(extra)))
            Add(HintLevel.Info, $"Play Console also has {extra}, which isn't in Billing__ProductIds: the app never asks for it and the server won't accept it. If it's meant to be Plus, add it there or fix the id.");

        if (play.ProductIds.Count > 0 && !hints.Skip(start).Any(hint => hint.Level == HintLevel.Problem))
            Add(HintLevel.Ok, $"No problems found with the products in {name}: the account on the phone must be a tester who opened the internal testing opt-in link and installed the app from Play; add it under License testing too.");
    }

    private static void BachsHints(BachsCheckDto bachs, List<BillingHintDto> hints)
    {
        void Add(string level, string text) => hints.Add(new BillingHintDto(Bachs, level, text));
        if (!bachs.Configured)
        {
            if (bachs.BachsInApp)
                Add(HintLevel.Problem, "Card payments in the app (bachsInApp) is on, but Bachs isn't set up on the server, so the app offers Google Play only.");
            Add(bachs.BachsInApp || bachs.Missing.Count < 4 ? HintLevel.Problem : HintLevel.Info,
                bachs.Missing.Count == 0
                    ? "Bachs isn't set up on the server: check the Bachs settings (docs/billing.md)."
                    : $"Card payments (Bachs) are off until {string.Join(", ", bachs.Missing)} {(bachs.Missing.Count == 1 ? "is" : "are")} set (docs/billing.md, Bachs dashboard setup).");
            return;
        }
        var mode = bachs.Mode ?? "sandbox";
        var ready = true;
        foreach (var product in bachs.Products)
        {
            var setting = product.Plan == "monthly" ? "Bachs__MonthlyProductId" : "Bachs__YearlyProductId";
            if (product.Error is { } error)
            {
                ready = false;
                Add(HintLevel.Problem, $"The {product.Plan} product couldn't be read from Bachs ({error.Code}): {error.Hint}");
            }
            else if (!product.Found)
            {
                ready = false;
                Add(HintLevel.Problem, $"Bachs ({mode}) has no product {product.ProductId} for the {product.Plan} plan: create it in the Bachs dashboard ({mode}; sandbox and live share nothing) and put its id in {setting}.");
            }
            else if (!product.Active)
            {
                ready = false;
                Add(HintLevel.Problem, $"Bachs product {product.ProductId} ({product.Plan}) is archived or inactive: unarchive it in the Bachs dashboard.");
            }
            else if (!IntervalFits(product.Plan, product.Interval))
            {
                ready = false;
                Add(HintLevel.Problem, $"Bachs product {product.ProductId} for the {product.Plan} plan bills {(product.Interval is null ? "once, not every period" : $"every {product.Interval}")}: point {setting} at a product billed every {(product.Plan == "monthly" ? "month" : "year")}.");
            }
        }
        if (bachs.Mode == "sandbox")
            Add(HintLevel.Todo, "Bachs is on a sandbox key, so real cards can't pay: when going live, swap in the sk_live_ key and re-create the products and the webhook in production.");
        if (!ready) return;
        if (!bachs.BachsInApp)
            Add(HintLevel.Todo, "Turn on Card payments in the app (bachsInApp) under Features once this build is live.");
        else
            Add(HintLevel.Ok, $"Bachs ({mode}) has both products and card payments in the app are on.");
    }

    private static bool IntervalFits(string plan, string? interval) => plan == "monthly"
        ? interval == "1 month"
        : interval is "1 year" or "12 months";

    /// <summary>The same id once case and separators are ignored, or a couple of letters off: a likely typo.</summary>
    public static bool Similar(string candidate, string wanted)
    {
        static string Plain(string value) => new(value.ToLowerInvariant().Where(char.IsAsciiLetterOrDigit).ToArray());
        if (Plain(candidate) == Plain(wanted)) return true;
        return Distance(candidate.ToLowerInvariant(), wanted.ToLowerInvariant()) <= Math.Max(2, wanted.Length / 8);
    }

    /// <summary>Levenshtein distance.</summary>
    public static int Distance(string a, string b)
    {
        var previous = Enumerable.Range(0, b.Length + 1).ToArray();
        var current = new int[b.Length + 1];
        for (var i = 1; i <= a.Length; i++)
        {
            current[0] = i;
            for (var j = 1; j <= b.Length; j++)
                current[j] = Math.Min(Math.Min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1));
            (previous, current) = (current, previous);
        }
        return previous[b.Length];
    }

    /// <summary>"NGN 4,500", "USD 4.99": Google's Money as the dashboard shows it.</summary>
    public static string Price(string? currency, long? units, int? nanos)
    {
        var value = (units ?? 0) + (nanos ?? 0) / 1_000_000_000m;
        return $"{(currency ?? "?").ToUpperInvariant()} {value.ToString(value == decimal.Truncate(value) ? "N0" : "N2", CultureInfo.InvariantCulture)}";
    }
}
