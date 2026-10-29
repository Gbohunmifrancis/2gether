using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Live;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.People;

public enum JourneyKind
{
    SignedUp, Linked, Unlinked, Active, DareSet, DareCompleted, DareMissed, MealLogged,
    PhotoSynced, QuestionAnswered, GameFinished, PlusStarted, PlusEnded, AdminAction,
}

public sealed record JourneyEventDto(JourneyKind Kind, int Count, string? Detail);
public sealed record JourneyDayDto(string Day, IReadOnlyList<JourneyEventDto> Events);
public sealed record JourneyDto(int Days, IReadOnlyList<JourneyDayDto> Items);

/// <summary>One timestamped thing a person did (or had done to their account).</summary>
public readonly record struct JourneyPoint(DateTime AtUtc, JourneyKind Kind, string? Detail);

/// <summary>
/// One person's days in the app: counts per kind of action, from the timestamps the tables keep. Only
/// the exercise, game type, product id or admin action name is ever named; never what they wrote,
/// logged or photographed.
/// </summary>
public sealed class AdminJourney(IApplicationDbContext db, IDateTimeProvider clock)
{
    public static int ClampDays(int days) => Math.Clamp(days, 7, 365);

    /// <summary>Per UTC day, newest day first; within a day in the order of <see cref="JourneyKind"/>. Days with nothing are left out.</summary>
    public static IReadOnlyList<JourneyDayDto> Group(IEnumerable<JourneyPoint> points, DateOnly from, DateOnly to)
        => points
            .Select(point => (Day: DateOnly.FromDateTime(point.AtUtc), point.Kind, point.Detail))
            .Where(point => point.Day >= from && point.Day <= to)
            .GroupBy(point => point.Day)
            .OrderByDescending(group => group.Key)
            .Select(group => new JourneyDayDto(group.Key.ToString("yyyy-MM-dd"), group
                .GroupBy(point => (point.Kind, point.Detail))
                .OrderBy(item => item.Key.Kind).ThenBy(item => item.Key.Detail, StringComparer.Ordinal)
                .Select(item => new JourneyEventDto(item.Key.Kind, item.Count(), item.Key.Detail))
                .ToList()))
            .ToList();

    /// <summary>Null when there is no such account.</summary>
    public async Task<JourneyDto?> GetAsync(Guid userId, int days, CancellationToken ct)
    {
        days = ClampDays(days);
        var now = clock.UtcNow;
        var to = DateOnly.FromDateTime(now);
        var from = to.AddDays(-(days - 1));
        var fromUtc = from.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);
        var user = await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => new { item.CreatedAtUtc }).SingleOrDefaultAsync(ct);
        if (user is null) return null;

        var points = new List<JourneyPoint> { new(user.CreatedAtUtc, JourneyKind.SignedUp, null) };
        var couples = await db.Couples.AsNoTracking().Where(couple => couple.UserAId == userId || couple.UserBId == userId)
            .Select(couple => new { couple.Id, couple.LinkedAtUtc, couple.DissolvedAtUtc }).ToListAsync(ct);
        var coupleIds = couples.Select(couple => couple.Id).ToList();
        foreach (var couple in couples.Where(couple => couple.LinkedAtUtc != null))
        {
            points.Add(new(couple.LinkedAtUtc!.Value, JourneyKind.Linked, null));
            if (couple.DissolvedAtUtc is { } ended) points.Add(new(ended, JourneyKind.Unlinked, null));
        }

        points.AddRange((await db.UserActivityDays.AsNoTracking().Where(item => item.UserId == userId && item.Day >= from).Select(item => item.Day).ToListAsync(ct))
            .Select(day => new JourneyPoint(day.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc), JourneyKind.Active, null)));
        points.AddRange((await db.ExerciseGoals.AsNoTracking().Where(item => item.CreatedByUserId == userId && item.CreatedAtUtc >= fromUtc)
                .Select(item => new { item.CreatedAtUtc, item.ExerciseType }).ToListAsync(ct))
            .Select(row => new JourneyPoint(row.CreatedAtUtc, JourneyKind.DareSet, AdminLiveFeed.Camel(row.ExerciseType.ToString()))));
        var dares = await db.ExerciseOccurrences.AsNoTracking()
            .Where(item => item.UserId == userId
                && ((item.Status == ExerciseOccurrenceStatus.Completed && item.CompletedAtUtc >= fromUtc) || (item.Status == ExerciseOccurrenceStatus.Missed && item.UpdatedAtUtc >= fromUtc)))
            .Select(item => new { item.Status, item.CompletedAtUtc, item.UpdatedAtUtc, item.ExerciseType }).ToListAsync(ct);
        points.AddRange(dares.Select(row => row.Status == ExerciseOccurrenceStatus.Completed
            ? new JourneyPoint(row.CompletedAtUtc!.Value, JourneyKind.DareCompleted, AdminLiveFeed.Camel(row.ExerciseType.ToString()))
            : new JourneyPoint(row.UpdatedAtUtc, JourneyKind.DareMissed, AdminLiveFeed.Camel(row.ExerciseType.ToString()))));
        points.AddRange((await db.FoodEntries.AsNoTracking().Where(item => item.UserId == userId && item.LoggedAtUtc >= fromUtc).Select(item => item.LoggedAtUtc).ToListAsync(ct))
            .Select(at => new JourneyPoint(at, JourneyKind.MealLogged, null)));
        points.AddRange((await db.Memories.AsNoTracking().Where(item => item.UploadedByUserId == userId && item.Status == MemoryStatus.Ready && item.ReadyAtUtc >= fromUtc).Select(item => item.ReadyAtUtc!.Value).ToListAsync(ct))
            .Select(at => new JourneyPoint(at, JourneyKind.PhotoSynced, null)));
        points.AddRange((await db.DailyAnswers.AsNoTracking().Where(item => item.UserId == userId && item.CreatedAtUtc >= fromUtc).Select(item => item.CreatedAtUtc).ToListAsync(ct))
            .Select(at => new JourneyPoint(at, JourneyKind.QuestionAnswered, null)));
        points.AddRange((await db.GameSessions.AsNoTracking().Where(item => coupleIds.Contains(item.CoupleId) && item.Status == GameSessionStatus.Completed && item.EndedAtUtc >= fromUtc)
                .Select(item => new { At = item.EndedAtUtc!.Value, item.GameType }).ToListAsync(ct))
            .Select(row => new JourneyPoint(row.At, JourneyKind.GameFinished, AdminLiveFeed.Camel(row.GameType.ToString()))));

        var subscriptions = await db.StoreSubscriptions.AsNoTracking().Where(item => item.UserId == userId)
            .Select(item => new { item.Id, item.CreatedAtUtc, item.ExpiresAtUtc, item.Status, item.ProductId }).ToListAsync(ct);
        foreach (var subscription in subscriptions.Where(item => item.Status is not (StoreSubscriptionStatus.Pending or StoreSubscriptionStatus.PendingPurchaseCanceled)))
        {
            points.Add(new(subscription.CreatedAtUtc, JourneyKind.PlusStarted, subscription.ProductId));
            if (subscription.Status == StoreSubscriptionStatus.Expired && subscription.ExpiresAtUtc <= now) points.Add(new(subscription.ExpiresAtUtc, JourneyKind.PlusEnded, subscription.ProductId));
        }
        var grants = await db.PlusGrants.AsNoTracking().Where(item => coupleIds.Contains(item.CoupleId))
            .Select(item => new { item.CreatedAtUtc, item.ExpiresAtUtc, item.RevokedAtUtc }).ToListAsync(ct);
        foreach (var grant in grants)
        {
            points.Add(new(grant.CreatedAtUtc, JourneyKind.PlusStarted, AdminLiveFeed.ComplimentaryDetail));
            var ended = grant.RevokedAtUtc ?? grant.ExpiresAtUtc;
            if (ended <= now) points.Add(new(ended, JourneyKind.PlusEnded, AdminLiveFeed.ComplimentaryDetail));
        }

        // What admins did to the account, its couples or its subscriptions. Looking at it isn't doing something to it.
        var targets = coupleIds.Concat(subscriptions.Select(item => item.Id)).Select(id => id.ToString()).Append(userId.ToString()).ToList();
        points.AddRange((await db.AdminAuditEvents.AsNoTracking()
                .Where(item => (item.TargetType == "user" || item.TargetType == "couple" || item.TargetType == "subscription") && item.TargetId != null && targets.Contains(item.TargetId)
                    && item.CreatedAtUtc >= fromUtc && !item.Action.EndsWith(".viewed"))
                .Select(item => new { item.CreatedAtUtc, item.Action }).ToListAsync(ct))
            .Select(row => new JourneyPoint(row.CreatedAtUtc, JourneyKind.AdminAction, row.Action)));

        return new JourneyDto(days, Group(points, from, to));
    }
}
