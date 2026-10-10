using System.Text;
using System.Text.Json;
using Twogether.Application.Features.Billing;
using Twogether.Core.Billing;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class BillingTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);

    [Theory]
    [InlineData(StoreSubscriptionStatus.Active, 1, true)]
    [InlineData(StoreSubscriptionStatus.Canceled, 1, true)]        // auto-renew off, still paid up
    [InlineData(StoreSubscriptionStatus.InGracePeriod, 1, true)]   // payment retrying
    [InlineData(StoreSubscriptionStatus.Active, -1, false)]        // past expiry, whatever the state says
    [InlineData(StoreSubscriptionStatus.OnHold, 1, false)]
    [InlineData(StoreSubscriptionStatus.Paused, 1, false)]
    [InlineData(StoreSubscriptionStatus.Pending, 1, false)]
    [InlineData(StoreSubscriptionStatus.Expired, 1, false)]
    public void Only_paid_up_states_before_expiry_grant_plus(StoreSubscriptionStatus status, int days, bool grants)
        => Assert.Equal(grants, EntitlementRules.Grants(status, Now.AddDays(days), Now));

    [Fact]
    public void Google_states_map_and_unknown_ones_grant_nothing()
    {
        Assert.Equal(StoreSubscriptionStatus.Active, PlayStateMapping.Map("SUBSCRIPTION_STATE_ACTIVE"));
        Assert.Equal(StoreSubscriptionStatus.InGracePeriod, PlayStateMapping.Map("SUBSCRIPTION_STATE_IN_GRACE_PERIOD"));
        Assert.Equal(StoreSubscriptionStatus.OnHold, PlayStateMapping.Map("SUBSCRIPTION_STATE_ON_HOLD"));
        Assert.Equal(StoreSubscriptionStatus.Pending, PlayStateMapping.Map("SUBSCRIPTION_STATE_SOMETHING_NEW"));
        Assert.Equal(StoreSubscriptionStatus.Pending, PlayStateMapping.Map(null));
    }

    [Fact]
    public void Store_state_overwrites_everything_and_the_account_id_is_the_user_id()
    {
        var buyer = Guid.NewGuid();
        var subscription = StoreSubscription.Create(" token-1 ", buyer, Now);
        Assert.Equal("token-1", subscription.PurchaseToken);
        subscription.ApplyStoreState("heartpact_plus_yearly", StoreSubscriptionStatus.Active, Now.AddDays(365), true, false, " ", Now);
        Assert.True(subscription.GrantsAccess(Now));
        Assert.Null(subscription.LinkedPurchaseToken);
        subscription.ApplyStoreState("heartpact_plus_yearly", StoreSubscriptionStatus.OnHold, Now.AddDays(365), false, true, null, Now.AddDays(30));
        Assert.False(subscription.GrantsAccess(Now.AddDays(30)));
        Assert.Equal(buyer.ToString("N"), EntitlementRules.AccountId(buyer));
        Assert.Throws<ArgumentException>(() => StoreSubscription.Create("t", Guid.Empty, Now));
    }

    [Fact]
    public void Plan_limits_follow_the_plan()
    {
        var settings = new BillingSettings();
        Assert.Equal(3, settings.Limits(Plan.Free).FoodScansPerDay);
        Assert.Equal(20, settings.Limits(Plan.Plus).FoodScansPerDay);
        Assert.True(settings.Limits(Plan.Plus).MemoriesBytes > settings.Limits(Plan.Free).MemoriesBytes);
        Assert.Equal(7, settings.Limits(Plan.Free).QuestionHistoryDays);
    }

    private static JsonElement Envelope(object notification)
    {
        var data = Convert.ToBase64String(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(notification)));
        return JsonDocument.Parse(JsonSerializer.Serialize(new { message = new { data, messageId = "1" }, subscription = "projects/p/subscriptions/s" })).RootElement;
    }

    [Fact]
    public void Play_notifications_yield_a_token_only_for_our_subscription_events()
    {
        var token = PlayNotificationParser.ReadPurchaseToken(Envelope(new { packageName = "app.heartpact", subscriptionNotification = new { notificationType = 4, purchaseToken = "tok" } }), "app.heartpact", out var kind);
        Assert.Equal(("tok", "type_4"), (token, kind));
        Assert.Null(PlayNotificationParser.ReadPurchaseToken(Envelope(new { packageName = "app.heartpact", testNotification = new { version = "1.0" } }), "app.heartpact", out kind));
        Assert.Equal("test", kind);
        Assert.Null(PlayNotificationParser.ReadPurchaseToken(Envelope(new { packageName = "com.other", subscriptionNotification = new { purchaseToken = "tok" } }), "app.heartpact", out kind));
        Assert.Equal("other_package", kind);
        Assert.Null(PlayNotificationParser.ReadPurchaseToken(JsonDocument.Parse("{\"message\":{\"data\":\"%%%not base64\"}}").RootElement, null, out kind));
        Assert.Null(PlayNotificationParser.ReadPurchaseToken(JsonDocument.Parse("[]").RootElement, null, out _));
    }
}
