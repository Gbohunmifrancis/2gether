using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Application.Features.Billing;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Billing;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.IntegrationTests;

/// <summary>Bachs webhooks on real PostgreSQL: the additive upgrade, and concurrent deliveries under the advisory lock.</summary>
public sealed class PostgresBachsTests : IAsyncLifetime
{
    private readonly PostgresDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);
    private readonly BachsSettings settings = new() { ApiKey = "sk_sandbox_x", WebhookSecret = "whsec_x", MonthlyProductId = "prod_month", YearlyProductId = "prod_year" };

    public Task InitializeAsync() => database.InitializeAsync();
    public Task DisposeAsync() => database.DisposeAsync();

    private static string Event(string id, DateTime at, Guid userId, string status) => JsonSerializer.Serialize(new
    {
        id,
        type = status == "canceled" ? "customer.subscription.deleted" : "customer.subscription.updated",
        created_at = at.ToString("O"),
        data = new
        {
            subscription_id = "sub_1",
            customer = new { customer_id = "cust_1" },
            product_id = "prod_month",
            status,
            current_period_end = at.AddDays(30).ToString("O"),
            cancel_at_period_end = false,
            canceled_at = status == "canceled" ? at.ToString("O") : null,
            metadata = new { user_id = userId.ToString() },
        },
    });

    [PostgresFact]
    public async Task An_older_database_gains_the_bachs_columns_and_tables_and_concurrent_deliveries_settle_on_the_newest()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await db.Database.ExecuteSqlRawAsync("""
            ALTER TABLE store_subscriptions DROP COLUMN "ExternalCustomerId", DROP COLUMN "LastEventAtUtc";
            DROP TABLE bachs_webhook_events; DROP TABLE google_external_transactions;
            """);
        await DatabaseSchemaUpgrade.ApplyAsync(db);
        await DatabaseSchemaUpgrade.ApplyAsync(db);

        // Twenty deliveries at once: ten copies of an older event and ten of a newer one, in any order.
        var older = Event("evt_old", TestCouple.Now, couple.MayaId, "active");
        var newer = Event("evt_new", TestCouple.Now.AddMinutes(1), couple.MayaId, "canceled");
        var outcomes = await Task.WhenAll(Enumerable.Range(0, 20).Select(async index =>
        {
            await using var context = database.CreateContext();
            var sync = new BachsSubscriptionSync(context, new FakeBachsBilling(), settings, clock, NullLogger<BachsSubscriptionSync>.Instance);
            return await sync.HandleAsync(index % 2 == 0 ? older : newer, default);
        }));

        Assert.All(outcomes, outcome => Assert.True(outcome.IsSuccess));
        await using var check = database.CreateContext();
        var row = await check.StoreSubscriptions.AsNoTracking().SingleAsync();
        Assert.Equal((StoreSubscriptionStatus.Expired, "cust_1"), (row.Status, row.ExternalCustomerId));
        Assert.Equal(2, await check.BachsWebhookEvents.CountAsync());
    }
}
