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

public sealed class CoupleDissolutionTests
{
    [Fact]
    public void Dissolving_records_when_the_couple_ended_once()
    {
        var start = new DateTime(2026, 10, 1, 9, 0, 0, DateTimeKind.Utc);
        var couple = Couple.Create(Guid.NewGuid(), start);
        couple.LinkPartner(Guid.NewGuid(), start);

        couple.Dissolve(start.AddDays(3));
        couple.Dissolve(start.AddDays(9));

        Assert.Equal(CoupleStatus.Dissolved, couple.Status);
        // A second call must not restart the post-breakup download window.
        Assert.Equal(start.AddDays(3), couple.DissolvedAtUtc);
    }
}

public sealed class CoupleLinkingTests
{
    private static readonly DateTime Now = new(2026, 10, 11, 9, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void Only_a_couple_waiting_for_its_second_person_can_take_one()
    {
        var owner = Guid.NewGuid();
        var couple = Couple.Create(owner, Now);
        Assert.True(couple.CanAcceptPartner);
        Assert.Throws<InvalidOperationException>(() => couple.LinkPartner(owner, Now));

        couple.LinkPartner(Guid.NewGuid(), Now);
        Assert.False(couple.CanAcceptPartner);
        Assert.Throws<InvalidOperationException>(() => couple.LinkPartner(Guid.NewGuid(), Now));
    }

    [Fact]
    public void An_ended_couple_never_comes_back()
    {
        var solo = Couple.Create(Guid.NewGuid(), Now);
        solo.Dissolve(Now);

        Assert.False(solo.CanAcceptPartner);
        Assert.Throws<InvalidOperationException>(() => solo.LinkPartner(Guid.NewGuid(), Now));
        Assert.Equal(CoupleStatus.Dissolved, solo.Status);
    }
}

public sealed class GameInputTests
{
    [Theory]
    [InlineData("timeout", true)]
    [InlineData(" TimeOut ", true)]
    [InlineData("submit", false)]
    [InlineData(null, false)]
    public void Timeout_is_reserved_for_the_server(string? action, bool serverOnly)
        => Assert.Equal(serverOnly, Twogether.Application.Features.Games.GameEngine.IsServerOnlyAction(action));

    [Theory]
    [InlineData(null, true)]
    [InlineData("B", true)]
    [InlineData("z", true)]
    [InlineData("AB", false)]
    [InlineData("", false)]
    [InlineData("é", false)]
    [InlineData("<script>", false)]
    public void A_requested_letter_is_one_of_A_to_Z(string? letter, bool valid)
        => Assert.Equal(valid, Twogether.Application.Features.Games.GameEngine.IsValidLetter(letter));
}
