using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Account;

/// <summary>
/// Password accounts confirm with their password. Accounts without one (Google sign-in) type their
/// email instead. A renewing Play subscription must be acknowledged: deleting the account does not
/// stop Google from charging, and the person has to cancel in the Play Store themselves.
/// </summary>
public sealed record DeleteAccountRequest(string? Password, string? ConfirmEmail, bool AcknowledgeActiveSubscription = false);

/// <summary>What the API still has to announce once the data is gone.</summary>
public sealed record AccountErasure(
    Guid UserId,
    string DisplayName,
    Guid? FormerPartnerId,
    Notification? PartnerNotice,
    IReadOnlyList<ExerciseOccurrence> CancelledAlarms,
    string? FormerPartnerName = null);

/// <summary>
/// Removes a user and everything about them in one transaction. An active couple is ended first, as
/// if they had broken up, so the partner is told and nothing keeps ringing.
/// </summary>
public interface IAccountEraser
{
    /// <summary>Null when the user no longer exists, for example after a concurrent delete.</summary>
    Task<AccountErasure?> EraseAsync(Guid userId, CancellationToken cancellationToken);
}

public static class AccountDeletionRules
{
    public static Result Confirm(User user, DeleteAccountRequest request, IPasswordHasher hasher)
    {
        if (user.PasswordHash is not null)
            return !string.IsNullOrEmpty(request.Password) && hasher.Verify(request.Password, user.PasswordHash)
                ? Result.Success()
                : Error.Unauthorized("account.confirmation_failed", Loc.T("Your password is incorrect."));
        return string.Equals(request.ConfirmEmail?.Trim(), user.Email, StringComparison.OrdinalIgnoreCase)
            ? Result.Success()
            : Error.Validation("account.confirmation_failed", Loc.T("Type your account email to confirm."));
    }

    /// <summary>
    /// A subscription Google may charge again. A cancelled one is paid up but will not renew, and an
    /// expired one is over, so deleting the account then costs nothing further. On hold and paused
    /// still count: Google resumes charging when they recover.
    /// </summary>
    public static bool WillRenew(StoreSubscription subscription)
        => subscription.AutoRenewing
           && subscription.Status is StoreSubscriptionStatus.Active or StoreSubscriptionStatus.InGracePeriod or StoreSubscriptionStatus.OnHold or StoreSubscriptionStatus.Paused;
}
