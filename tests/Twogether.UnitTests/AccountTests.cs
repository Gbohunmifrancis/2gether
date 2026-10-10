using Twogether.Application.Features.Account;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Security;
using Twogether.Shared.Results;

namespace Twogether.UnitTests;

public sealed class AccountTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);
    private readonly PasswordHasher hasher = new();

    [Fact]
    public void Password_accounts_confirm_with_the_password()
    {
        var user = User.Create("ada@example.com", "Ada", new DateOnly(1995, 1, 1), Now, hasher.Hash("correct horse"));

        Assert.True(AccountDeletionRules.Confirm(user, new DeleteAccountRequest("correct horse", null), hasher).IsSuccess);
        var wrong = AccountDeletionRules.Confirm(user, new DeleteAccountRequest("wrong", null), hasher);
        Assert.Equal(ErrorType.Unauthorized, wrong.Error.Type);
        // Typing the email is not enough when a password exists.
        Assert.True(AccountDeletionRules.Confirm(user, new DeleteAccountRequest(null, "ada@example.com"), hasher).IsFailure);
    }

    [Fact]
    public void Accounts_without_a_password_confirm_with_their_email()
    {
        var user = User.Create("ada@example.com", "Ada", new DateOnly(1995, 1, 1), Now, googleId: "google-1");

        Assert.True(AccountDeletionRules.Confirm(user, new DeleteAccountRequest(null, "  ADA@example.com "), hasher).IsSuccess);
        Assert.True(AccountDeletionRules.Confirm(user, new DeleteAccountRequest(null, "someone@example.com"), hasher).IsFailure);
        Assert.True(AccountDeletionRules.Confirm(user, new DeleteAccountRequest(null, null), hasher).IsFailure);
    }

    [Theory]
    [InlineData(StoreSubscriptionStatus.Active, true, true)]
    [InlineData(StoreSubscriptionStatus.InGracePeriod, true, true)]
    [InlineData(StoreSubscriptionStatus.OnHold, true, true)]
    [InlineData(StoreSubscriptionStatus.Paused, true, true)]
    [InlineData(StoreSubscriptionStatus.Canceled, false, false)]
    [InlineData(StoreSubscriptionStatus.Expired, false, false)]
    [InlineData(StoreSubscriptionStatus.Active, false, false)]
    public void Only_subscriptions_google_will_charge_again_need_acknowledging(StoreSubscriptionStatus status, bool autoRenewing, bool expected)
    {
        var subscription = StoreSubscription.Create("token", Guid.NewGuid(), Now);
        subscription.ApplyStoreState("heartpact_plus_monthly", status, Now.AddDays(10), autoRenewing, true, null, Now);

        Assert.Equal(expected, AccountDeletionRules.WillRenew(subscription));
    }
}
