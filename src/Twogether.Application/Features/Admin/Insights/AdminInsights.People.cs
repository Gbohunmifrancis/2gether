using Microsoft.EntityFrameworkCore;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Insights;

public sealed partial class AdminInsights
{
    /// <summary>
    /// People by the country they chose at sign-up (never where they are). A couple together now counts in each
    /// country either partner is in, once. Active: in the last 7 days (<see cref="ActivityLog"/>). Unknown: people who
    /// signed up before the country was asked.
    /// </summary>
    public async Task<WorldDto> WorldAsync(CancellationToken ct)
    {
        var now = clock.UtcNow;
        var today = DateOnly.FromDateTime(now);
        var people = await measured.Users.GroupBy(user => user.CountryCode).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);

        var activeCoupleIds = measured.Couples.Where(couple => couple.Status == CoupleStatus.Active && couple.UserBId != null).Select(couple => couple.Id);
        var partnered = measured.Users.Where(user => user.CountryCode != null && user.CoupleId != null && activeCoupleIds.Contains(user.CoupleId.Value));
        var couples = await partnered.GroupBy(user => user.CountryCode!).Select(group => new { group.Key, Count = group.Select(user => user.CoupleId).Distinct().Count() }).ToListAsync(ct);
        var plusCouples = (await PlusCoupleIdsAsync(now, ct)).ToList();
        var plus = await partnered.Where(user => plusCouples.Contains(user.CoupleId!.Value))
            .GroupBy(user => user.CountryCode!).Select(group => new { group.Key, Count = group.Select(user => user.CoupleId).Distinct().Count() }).ToListAsync(ct);
        var activeIds = (await Activity.ActiveDaysAsync(today.AddDays(-6), today, ct)).Select(item => item.UserId).Distinct().ToList();
        var active = await measured.Users.Where(user => user.CountryCode != null && activeIds.Contains(user.Id))
            .GroupBy(user => user.CountryCode!).Select(group => new { group.Key, Count = group.Count() }).ToListAsync(ct);

        int Of<T>(IEnumerable<T> rows, Func<T, string?> key, Func<T, int> count, string code) => rows.Where(row => string.Equals(key(row), code, StringComparison.OrdinalIgnoreCase)).Sum(count);
        var countries = people.Where(item => item.Key != null).Select(item => item.Key!.ToUpperInvariant()).Distinct()
            .Select(code => new CountryDto(
                code,
                Of(people, row => row.Key, row => row.Count, code),
                Of(couples, row => row.Key, row => row.Count, code),
                Of(plus, row => row.Key, row => row.Count, code),
                Of(active, row => row.Key, row => row.Count, code)))
            .OrderByDescending(item => item.People).ThenBy(item => item.Code)
            .ToList();
        return new WorldDto(countries, people.Where(item => item.Key == null).Sum(item => item.Count));
    }

    /// <summary>How far back a top couple's streak is read: a year.</summary>
    private const int TopStreakHistoryDays = 366;

    /// <summary>
    /// The couples together now who did the most in the window: hearts (dares completed, by either), then games
    /// finished, then dares set (goals made). Names are account metadata; the streak is the longer of the two partners'.
    /// </summary>
    public async Task<IReadOnlyList<TopCoupleDto>> TopCouplesAsync(int days, int limit, CancellationToken ct)
    {
        days = InsightsMath.ClampDays(days);
        limit = InsightsMath.ClampLimit(limit);
        var now = clock.UtcNow;
        var fromUtc = InsightsMath.WindowStart(now, days);
        var activeCouples = measured.Couples.Where(couple => couple.Status == CoupleStatus.Active && couple.UserBId != null);
        var activeIds = activeCouples.Select(couple => couple.Id);

        var hearts = await db.ExerciseOccurrences.Where(item => item.Status == ExerciseOccurrenceStatus.Completed && item.CompletedAtUtc >= fromUtc && activeIds.Contains(item.CoupleId))
            .GroupBy(item => item.CoupleId).Select(group => new { group.Key, Count = group.Count() }).ToDictionaryAsync(item => item.Key, item => item.Count, ct);
        var games = await db.GameSessions.Where(item => item.Status == GameSessionStatus.Completed && item.EndedAtUtc >= fromUtc && activeIds.Contains(item.CoupleId))
            .GroupBy(item => item.CoupleId).Select(group => new { group.Key, Count = group.Count() }).ToDictionaryAsync(item => item.Key, item => item.Count, ct);
        var daresSet = await db.ExerciseGoals.Where(item => item.CreatedAtUtc >= fromUtc && activeIds.Contains(item.CoupleId))
            .GroupBy(item => item.CoupleId).Select(group => new { group.Key, Count = group.Count() }).ToDictionaryAsync(item => item.Key, item => item.Count, ct);

        var top = hearts.Keys.Concat(games.Keys).Concat(daresSet.Keys).Distinct()
            .Select(id => (Id: id, Hearts: hearts.GetValueOrDefault(id), Games: games.GetValueOrDefault(id), DaresSet: daresSet.GetValueOrDefault(id)))
            .OrderByDescending(item => item.Hearts).ThenByDescending(item => item.Games).ThenByDescending(item => item.DaresSet).ThenBy(item => item.Id)
            .Take(limit).ToList();
        if (top.Count == 0) return [];

        var topIds = top.Select(item => item.Id).ToList();
        var couples = await db.Couples.Where(couple => topIds.Contains(couple.Id))
            .Select(couple => new { couple.Id, couple.UserAId, UserBId = couple.UserBId!.Value, LinkedAtUtc = couple.LinkedAtUtc!.Value })
            .ToDictionaryAsync(couple => couple.Id, ct);
        var memberIds = couples.Values.SelectMany(couple => new[] { couple.UserAId, couple.UserBId }).ToList();
        var names = await db.Users.Where(user => memberIds.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.DisplayName, ct);
        var streaks = await StreaksAsync(db.Users.Where(user => memberIds.Contains(user.Id)).Select(user => user.Id), DateOnly.FromDateTime(now).AddDays(-TopStreakHistoryDays), now, ct);
        var plus = await PlusCoupleIdsAsync(now, ct);

        return top.Where(item => couples.ContainsKey(item.Id)).Select(item =>
        {
            var couple = couples[item.Id];
            return new TopCoupleDto(
                item.Id,
                [names.GetValueOrDefault(couple.UserAId) ?? string.Empty, names.GetValueOrDefault(couple.UserBId) ?? string.Empty],
                item.Hearts, item.DaresSet, item.Games,
                Math.Max(streaks.GetValueOrDefault(couple.UserAId), streaks.GetValueOrDefault(couple.UserBId)),
                plus.Contains(item.Id),
                DateTime.SpecifyKind(couple.LinkedAtUtc, DateTimeKind.Utc));
        }).ToList();
    }
}
