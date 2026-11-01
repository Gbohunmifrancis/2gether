using System.Globalization;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Billing;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Billing;

/// <summary>
/// "Plus is on" (docs/billing.md, Plus started): told to both partners once, when Plus newly starts for the couple. A
/// store subscription tells it the first time it grants (never for a renewal), a complimentary grant when it is
/// given; neither when the couple already had Plus. <c>source</c> in the data is from the recipient's side:
/// <c>bought</c>, <c>partner</c> or <c>gift</c>.
/// </summary>
public static class PlusStartedNotices
{
    public const string Bought = "bought";
    public const string FromPartner = "partner";
    public const string Gift = "gift";

    /// <summary>
    /// True when a subscription moving from <paramref name="before"/> (null: not stored yet) to a granting state is its
    /// first grant: a new row, or one that was waiting for payment. A renewal, a lapse caught up late, or a recovery
    /// from hold or pause was granting before, so it is not.
    /// </summary>
    public static bool IsFirstGrant(StoreSubscriptionStatus? before, StoreSubscriptionStatus status, DateTime expiresAtUtc, DateTime utcNow)
        => before is null or StoreSubscriptionStatus.Pending && EntitlementRules.Grants(status, expiresAtUtc, utcNow);

    /// <summary>
    /// Whether <paramref name="userId"/> or their current partner already has Plus: a granting subscription of either
    /// of them (other than <paramref name="exceptSubscriptionId"/>) or a live grant on their couple.
    /// </summary>
    public static async Task<bool> CoupleHasPlusAsync(IApplicationDbContext db, Guid userId, Guid? exceptSubscriptionId, DateTime utcNow, CancellationToken cancellationToken)
    {
        var coupleId = await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => user.CoupleId).SingleOrDefaultAsync(cancellationToken);
        var members = new List<Guid> { userId };
        if (coupleId is { } id && await PartnerAsync(db, id, userId, cancellationToken) is { } partner) members.Add(partner);
        var subscriptions = await db.StoreSubscriptions.AsNoTracking()
            .Where(item => members.Contains(item.UserId) && item.ExpiresAtUtc > utcNow && item.Id != exceptSubscriptionId)
            .ToListAsync(cancellationToken);
        if (subscriptions.Any(item => item.GrantsAccess(utcNow))) return true;
        return coupleId is { } current && await HasGrantAsync(db, current, utcNow, cancellationToken);
    }

    /// <summary>Whether <paramref name="couple"/> has Plus now: a granting subscription of a member, or a live grant on it.</summary>
    public static async Task<bool> CoupleHasPlusAsync(IApplicationDbContext db, Core.Entities.Couple couple, DateTime utcNow, CancellationToken cancellationToken)
    {
        var members = couple.Status == CoupleStatus.Active ? new[] { couple.UserAId, couple.UserBId }.OfType<Guid>().ToList() : [couple.UserAId];
        var subscriptions = await db.StoreSubscriptions.AsNoTracking()
            .Where(item => members.Contains(item.UserId) && item.ExpiresAtUtc > utcNow).ToListAsync(cancellationToken);
        return subscriptions.Any(item => item.GrantsAccess(utcNow)) || await HasGrantAsync(db, couple.Id, utcNow, cancellationToken);
    }

    /// <summary>Whether a live complimentary grant covers the couple.</summary>
    public static Task<bool> HasGrantAsync(IApplicationDbContext db, Guid coupleId, DateTime utcNow, CancellationToken cancellationToken)
        => db.PlusGrants.AsNoTracking().AnyAsync(item => item.CoupleId == coupleId && item.RevokedAtUtc == null && item.ExpiresAtUtc > utcNow, cancellationToken);

    /// <summary>
    /// The buyer's and their partner's notices for a purchase, not yet added. Nobody is told when the buyer is in no
    /// couple; with no partner yet only the buyer is. <paramref name="untilUtc"/> is when Plus ends if it won't renew.
    /// </summary>
    public static async Task<IReadOnlyList<Notification>> ForPurchaseAsync(IApplicationDbContext db, Guid buyerId, DateTime? untilUtc, DateTime utcNow, CancellationToken cancellationToken)
    {
        var buyer = await db.Users.AsNoTracking().Where(user => user.Id == buyerId)
            .Select(user => new { user.DisplayName, user.CoupleId }).SingleOrDefaultAsync(cancellationToken);
        if (buyer?.CoupleId is not { } coupleId) return [];
        var notices = new List<Notification>();
        using (Loc.Use(await db.LanguageOfAsync(buyerId, cancellationToken)))
        {
            notices.Add(Notification.Create(buyerId, coupleId, NotificationType.PlusStarted, Loc.T("Welcome to Heartpact Plus"),
                Loc.T("Plus is on for both of you: unlimited dares, more photo estimates, a bigger album and longer question history."),
                Data(Bought, untilUtc), utcNow));
        }
        if (await PartnerAsync(db, coupleId, buyerId, cancellationToken) is { } partnerId)
        {
            using var _ = Loc.Use(await db.LanguageOfAsync(partnerId, cancellationToken));
            (string, object?) name = ("name", buyer.DisplayName);
            notices.Add(Notification.Create(partnerId, coupleId, NotificationType.PlusStarted, Loc.T("{name} got you Heartpact Plus", name),
                Loc.T("{name} got Plus for both of you: unlimited dares, more photo estimates, a bigger album and longer question history.", name),
                Data(FromPartner, untilUtc), utcNow));
        }
        return notices;
    }

    /// <summary>Both partners' notices for a complimentary grant on <paramref name="couple"/>, not yet added.</summary>
    public static async Task<IReadOnlyList<Notification>> ForGrantAsync(IApplicationDbContext db, Core.Entities.Couple couple, DateTime untilUtc, DateTime utcNow, CancellationToken cancellationToken)
    {
        if (couple.Status == CoupleStatus.Dissolved) return [];
        var members = new[] { couple.UserAId, couple.UserBId }.OfType<Guid>().Distinct().ToList();
        var people = await db.Users.AsNoTracking().Where(user => members.Contains(user.Id))
            .Select(user => new { user.Id, user.Language, user.TimeZoneId, user.CountryCode }).ToListAsync(cancellationToken);
        var notices = new List<Notification>();
        foreach (var person in people)
        {
            using var _ = Loc.Use(person.Language);
            notices.Add(Notification.Create(person.Id, couple.Id, NotificationType.PlusStarted, Loc.T("A gift from Heartpact"),
                Loc.T("You both have Heartpact Plus until {date}. Enjoy it together.", ("date", ShortDate(untilUtc, person.TimeZoneId, person.CountryCode))),
                Data(Gift, untilUtc), utcNow));
        }
        return notices;
    }

    /// <summary>The day in the reader's time zone, as their language (and country, where it has its own order) writes it.</summary>
    public static string ShortDate(DateTime utc, string? timeZoneId, string? countryCode)
    {
        var local = TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(utc, DateTimeKind.Utc), ExerciseSchedule.ResolveTimeZone(timeZoneId));
        var culture = (CultureInfo)CultureFor(Loc.Language, countryCode).Clone();
        // Some regional cultures default to another calendar; everyone reads this date on a Gregorian one.
        if (culture.DateTimeFormat.Calendar is not GregorianCalendar) culture.DateTimeFormat.Calendar = new GregorianCalendar();
        return local.ToString("d", culture);
    }

    private static CultureInfo CultureFor(string language, string? countryCode)
    {
        if (!string.IsNullOrWhiteSpace(countryCode))
        {
            try { return CultureInfo.GetCultureInfo($"{language}-{countryCode.Trim().ToUpperInvariant()}", predefinedOnly: true); }
            catch (CultureNotFoundException) { }
        }
        return CultureInfo.GetCultureInfo(language);
    }

    private static string Data(string source, DateTime? untilUtc)
        => JsonSerializer.Serialize(new { source, untilUtc = untilUtc is { } until ? DateTime.SpecifyKind(until, DateTimeKind.Utc) : (DateTime?)null });

    private static async Task<Guid?> PartnerAsync(IApplicationDbContext db, Guid coupleId, Guid userId, CancellationToken cancellationToken)
    {
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, cancellationToken);
        if (couple is null) return null;
        return couple.UserAId == userId ? couple.UserBId : couple.UserBId == userId ? couple.UserAId : null;
    }
}
