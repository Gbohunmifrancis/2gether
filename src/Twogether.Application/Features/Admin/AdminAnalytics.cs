using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin;

public sealed record DayValue(string Date, double Value);
public sealed record NamedCount(string Name, int Count);

public sealed record OverviewDto(
    int Days,
    int TotalUsers,
    int NewUsers,
    int DisabledUsers,
    int ActiveCouples,
    int PendingCouples,
    int DissolvedCouples,
    int ActiveToday,
    int ActiveWeek,
    int ActiveMonth,
    int PlusCouples,
    IReadOnlyList<DayValue> Signups,
    IReadOnlyList<DayValue> ActiveUsers,
    IReadOnlyList<DayValue> CouplesLinked,
    IReadOnlyList<NamedCount> Platforms,
    IReadOnlyList<NamedCount> AppVersions);

public sealed record EngagementDto(
    int Days,
    int DaresCompleted,
    int DaresMissed,
    int DaresCalledOff,
    double CompletionRate,
    IReadOnlyList<DayValue> DaresCompletedByDay,
    IReadOnlyList<DayValue> DaresMissedByDay,
    IReadOnlyList<NamedCount> DaresByExercise,
    int FoodEntries,
    int FoodScans,
    int FoodScansFailed,
    long ScanInputTokens,
    long ScanOutputTokens,
    IReadOnlyList<DayValue> FoodEntriesByDay,
    int PhotosSynced,
    long PhotoBytesSynced,
    IReadOnlyList<DayValue> PhotosByDay,
    int QuestionsAnswered,
    IReadOnlyList<DayValue> AnswersByDay,
    int GamesCompleted,
    IReadOnlyList<NamedCount> GamesByType,
    int LocationSharers,
    int ActivePregnancies,
    int CycleTrackers);

public sealed record RevenueDto(
    int Days,
    string Currency,
    decimal EstimatedMonthlyRevenue,
    int PayingCouples,
    int ComplimentaryCouples,
    IReadOnlyList<NamedCount> ActiveByProduct,
    IReadOnlyList<NamedCount> ByStatus,
    IReadOnlyList<DayValue> NewSubscriptions,
    IReadOnlyList<DayValue> Ended,
    int RenewingOff);

/// <summary>
/// Aggregates for the admin dashboard. Counts and sums only: nothing here reads what couples wrote,
/// photographed, logged about their bodies, or where they are.
/// </summary>
public sealed class AdminAnalytics(IApplicationDbContext db, IDateTimeProvider clock, AdminSettings settings)
{
    public static int ClampDays(int days) => Math.Clamp(days, 7, 365);

    /// <summary>Every day in the window, with zero where nothing happened.</summary>
    public static IReadOnlyList<DayValue> Fill(DateOnly from, DateOnly to, IEnumerable<(DateOnly Day, double Value)> values)
    {
        var lookup = values.GroupBy(item => item.Day).ToDictionary(group => group.Key, group => group.Sum(item => item.Value));
        var result = new List<DayValue>();
        for (var day = from; day <= to; day = day.AddDays(1)) result.Add(new DayValue(day.ToString("yyyy-MM-dd"), lookup.GetValueOrDefault(day)));
        return result;
    }

    private (DateTime FromUtc, DateOnly From, DateOnly To) Window(int days)
    {
        var to = DateOnly.FromDateTime(clock.UtcNow);
        var from = to.AddDays(-(days - 1));
        return (from.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc), from, to);
    }

    private static IEnumerable<(DateOnly, double)> ByDay(IEnumerable<(DateTime Day, int Count)> rows) => rows.Select(row => (DateOnly.FromDateTime(row.Day), (double)row.Count));

    public async Task<OverviewDto> OverviewAsync(int days, CancellationToken ct)
    {
        days = ClampDays(days);
        var (fromUtc, from, to) = Window(days);
        var today = to;
        var totalUsers = await db.Users.CountAsync(ct);
        var newUsers = await db.Users.CountAsync(user => user.CreatedAtUtc >= fromUtc, ct);
        var disabled = await db.Users.CountAsync(user => user.DisabledAtUtc != null, ct);
        var couples = await db.Couples.GroupBy(couple => couple.Status).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);
        int CouplesIn(CoupleStatus status) => couples.FirstOrDefault(item => item.Key == status)?.Count ?? 0;

        var activeToday = await db.UserActivityDays.CountAsync(item => item.Day == today, ct);
        var weekFrom = today.AddDays(-6);
        var monthFrom = today.AddDays(-29);
        var activeWeek = await db.UserActivityDays.Where(item => item.Day >= weekFrom).Select(item => item.UserId).Distinct().CountAsync(ct);
        var activeMonth = await db.UserActivityDays.Where(item => item.Day >= monthFrom).Select(item => item.UserId).Distinct().CountAsync(ct);

        var signups = (await db.Users.Where(user => user.CreatedAtUtc >= fromUtc).GroupBy(user => user.CreatedAtUtc.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct))
            .Select(row => (row.Key, row.Count));
        var active = (await db.UserActivityDays.Where(item => item.Day >= from).GroupBy(item => item.Day).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct))
            .Select(row => (row.Key, (double)row.Count));
        var linked = (await db.Couples.Where(couple => couple.LinkedAtUtc != null && couple.LinkedAtUtc >= fromUtc).GroupBy(couple => couple.LinkedAtUtc!.Value.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct))
            .Select(row => (row.Key, row.Count));

        var activeDevices = db.DeviceRegistrations.Where(device => device.DisabledAtUtc == null && device.LastSeenAtUtc >= clock.UtcNow.AddDays(-30));
        var platforms = await activeDevices.GroupBy(device => device.Platform).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);
        var versions = await activeDevices.GroupBy(device => device.AppVersion ?? "unknown").Select(group => new { group.Key, Count = group.Count() }).OrderByDescending(item => item.Count).Take(8).ToListAsync(ct);

        return new OverviewDto(
            days, totalUsers, newUsers, disabled,
            CouplesIn(CoupleStatus.Active), CouplesIn(CoupleStatus.PendingInvite), CouplesIn(CoupleStatus.Dissolved),
            activeToday, activeWeek, activeMonth,
            await PlusCouplesAsync(ct),
            Fill(from, to, ByDay(signups)),
            Fill(from, to, active),
            Fill(from, to, ByDay(linked)),
            platforms.Select(item => new NamedCount(item.Key.ToString().ToLowerInvariant(), item.Count)).ToList(),
            versions.Select(item => new NamedCount(item.Key, item.Count)).ToList());
    }

    public async Task<EngagementDto> EngagementAsync(int days, CancellationToken ct)
    {
        days = ClampDays(days);
        var (fromUtc, from, to) = Window(days);
        var occurrences = db.ExerciseOccurrences.Where(item => item.ScheduledAtUtc >= fromUtc);
        var byStatus = await occurrences.GroupBy(item => item.Status).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);
        int Dares(ExerciseOccurrenceStatus status) => byStatus.FirstOrDefault(item => item.Key == status)?.Count ?? 0;
        var completed = Dares(ExerciseOccurrenceStatus.Completed);
        var missed = Dares(ExerciseOccurrenceStatus.Missed);
        var calledOff = Dares(ExerciseOccurrenceStatus.Cancelled);
        var completedByDay = (await occurrences.Where(item => item.Status == ExerciseOccurrenceStatus.Completed).GroupBy(item => item.ScheduledAtUtc.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct)).Select(row => (row.Key, row.Count));
        var missedByDay = (await occurrences.Where(item => item.Status == ExerciseOccurrenceStatus.Missed).GroupBy(item => item.ScheduledAtUtc.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct)).Select(row => (row.Key, row.Count));
        var byExercise = await occurrences.GroupBy(item => item.ExerciseType).Select(group => new { group.Key, Count = group.Count() }).OrderByDescending(item => item.Count).ToListAsync(ct);

        var entries = db.FoodEntries.Where(item => item.LoggedAtUtc >= fromUtc);
        var foodByDay = (await entries.GroupBy(item => item.LoggedAtUtc.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct)).Select(row => (row.Key, row.Count));
        var scans = db.FoodScans.Where(item => item.CreatedAtUtc >= fromUtc);
        var scanStats = await scans.GroupBy(_ => 1).Select(group => new
        {
            Total = group.Count(),
            Failed = group.Count(item => !item.Succeeded),
            Input = group.Sum(item => (long)item.InputTokens),
            Output = group.Sum(item => (long)item.OutputTokens),
        }).SingleOrDefaultAsync(ct);

        var photos = db.Memories.Where(item => item.Status == MemoryStatus.Ready && item.ReadyAtUtc >= fromUtc);
        var photoStats = await photos.GroupBy(_ => 1).Select(group => new { Count = group.Count(), Bytes = group.Sum(item => item.SizeBytes) }).SingleOrDefaultAsync(ct);
        var photosByDay = (await photos.GroupBy(item => item.ReadyAtUtc!.Value.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct)).Select(row => (row.Key, row.Count));

        var answers = db.DailyAnswers.Where(item => item.CreatedAtUtc >= fromUtc);
        var answersByDay = (await answers.GroupBy(item => item.CreatedAtUtc.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct)).Select(row => (row.Key, row.Count));

        var games = db.GameSessions.Where(item => item.Status == GameSessionStatus.Completed && item.EndedAtUtc >= fromUtc);
        var gamesByType = await games.GroupBy(item => item.GameType).Select(group => new { group.Key, Count = group.Count() }).OrderByDescending(item => item.Count).ToListAsync(ct);

        var now = clock.UtcNow;
        return new EngagementDto(
            days, completed, missed, calledOff,
            completed + missed == 0 ? 0 : Math.Round((double)completed / (completed + missed), 3),
            Fill(from, to, ByDay(completedByDay)),
            Fill(from, to, ByDay(missedByDay)),
            byExercise.Select(item => new NamedCount(Camel(item.Key.ToString()), item.Count)).ToList(),
            await entries.CountAsync(ct),
            scanStats?.Total ?? 0, scanStats?.Failed ?? 0, scanStats?.Input ?? 0, scanStats?.Output ?? 0,
            Fill(from, to, ByDay(foodByDay)),
            photoStats?.Count ?? 0, photoStats?.Bytes ?? 0,
            Fill(from, to, ByDay(photosByDay)),
            await answers.CountAsync(ct),
            Fill(from, to, ByDay(answersByDay)),
            gamesByType.Sum(item => item.Count),
            gamesByType.Select(item => new NamedCount(Camel(item.Key.ToString()), item.Count)).ToList(),
            // Live shares only: a phone that went quiet past the lease no longer counts.
            await db.CoupleLocations.CountAsync(item => item.SharingEnabled && item.LastSeenAtUtc > now - Twogether.Core.Entities.CoupleLocation.Lease && (item.PausedUntilUtc == null || item.PausedUntilUtc < now), ct),
            await db.Pregnancies.CountAsync(item => item.Status == PregnancyStatus.Active, ct),
            await db.Users.CountAsync(user => user.CycleOwner, ct));
    }

    public async Task<RevenueDto> RevenueAsync(int days, CancellationToken ct)
    {
        days = ClampDays(days);
        var (fromUtc, from, to) = Window(days);
        var now = clock.UtcNow;
        var granting = await db.StoreSubscriptions
            .Where(item => (item.Status == StoreSubscriptionStatus.Active || item.Status == StoreSubscriptionStatus.Canceled || item.Status == StoreSubscriptionStatus.InGracePeriod) && item.ExpiresAtUtc > now)
            .Select(item => new { item.ProductId, item.UserId, item.AutoRenewing })
            .ToListAsync(ct);
        var byProduct = granting.GroupBy(item => item.ProductId).Select(group => new NamedCount(group.Key, group.Count())).OrderByDescending(item => item.Count).ToList();
        // Only renewing subscriptions bring money next month.
        var monthly = granting.Where(item => item.AutoRenewing).Sum(item => settings.MonthlyPrices.GetValueOrDefault(item.ProductId));
        var byStatus = await db.StoreSubscriptions.GroupBy(item => item.Status).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);
        var created = (await db.StoreSubscriptions.Where(item => item.CreatedAtUtc >= fromUtc).GroupBy(item => item.CreatedAtUtc.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct)).Select(row => (row.Key, row.Count));
        var ended = (await db.StoreSubscriptions.Where(item => item.Status == StoreSubscriptionStatus.Expired && item.ExpiresAtUtc >= fromUtc && item.ExpiresAtUtc <= now).GroupBy(item => item.ExpiresAtUtc.Date).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct)).Select(row => (row.Key, row.Count));
        var complimentary = await db.PlusGrants.Where(item => item.RevokedAtUtc == null && item.ExpiresAtUtc > now).Select(item => item.CoupleId).Distinct().CountAsync(ct);
        return new RevenueDto(
            days, settings.Currency, Math.Round(monthly, 2),
            await PayingCouplesAsync(ct), complimentary,
            byProduct,
            byStatus.Select(item => new NamedCount(Camel(item.Key.ToString()), item.Count)).OrderByDescending(item => item.Count).ToList(),
            Fill(from, to, ByDay(created)),
            Fill(from, to, ByDay(ended)),
            granting.Count(item => !item.AutoRenewing));
    }

    /// <summary>Couples with paid Plus: a buyer's current couple, counted once even if both pay.</summary>
    private async Task<int> PayingCouplesAsync(CancellationToken ct)
    {
        var now = clock.UtcNow;
        return await db.StoreSubscriptions
            .Where(item => (item.Status == StoreSubscriptionStatus.Active || item.Status == StoreSubscriptionStatus.Canceled || item.Status == StoreSubscriptionStatus.InGracePeriod) && item.ExpiresAtUtc > now)
            .Join(db.Users, subscription => subscription.UserId, user => user.Id, (subscription, user) => user.CoupleId)
            .Where(coupleId => coupleId != null)
            .Distinct()
            .CountAsync(ct);
    }

    private async Task<int> PlusCouplesAsync(CancellationToken ct)
    {
        var now = clock.UtcNow;
        var paying = await db.StoreSubscriptions
            .Where(item => (item.Status == StoreSubscriptionStatus.Active || item.Status == StoreSubscriptionStatus.Canceled || item.Status == StoreSubscriptionStatus.InGracePeriod) && item.ExpiresAtUtc > now)
            .Join(db.Users, subscription => subscription.UserId, user => user.Id, (subscription, user) => user.CoupleId)
            .Where(coupleId => coupleId != null).Select(coupleId => coupleId!.Value)
            .Distinct().ToListAsync(ct);
        var granted = await db.PlusGrants.Where(item => item.RevokedAtUtc == null && item.ExpiresAtUtc > now).Select(item => item.CoupleId).Distinct().ToListAsync(ct);
        return paying.Union(granted).Count();
    }

    private static string Camel(string value) => string.IsNullOrEmpty(value) ? value : char.ToLowerInvariant(value[0]) + value[1..];
}
