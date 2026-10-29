using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Insights;

/// <summary>One timed action: whose (a person's, or a couple's for games, which both play) and when. No content.</summary>
public sealed class ActivityStamp
{
    public Guid ActorId { get; init; }
    public DateTime AtUtc { get; init; }
}

/// <summary>
/// What "active" means for the insights, in one place so retention, rhythm and the rest agree: a person did something
/// that day. Started or completed a dare, logged a meal, synced a photo, answered a question, finished a game or made
/// a move in one (games belong to the couple), or signed in. It is read from the timestamps the tables already keep;
/// nothing a couple wrote is touched. Sign-ins have a day but no time of day (user_activity_days, LastActiveAtUtc), so
/// they count for active days and not for the hour-by-hour rhythm.
/// </summary>
public sealed class ActivityLog(IApplicationDbContext db, MeasuredScope measured)
{
    /// <summary>Timed actions of people, one query per kind, in [from, to). <paramref name="users"/> narrows who (default: everyone measured).</summary>
    public IReadOnlyList<IQueryable<ActivityStamp>> PersonStamps(DateTime fromUtc, DateTime toUtc, IQueryable<Guid>? users = null)
    {
        var ids = users ?? measured.UserIds;
        return
        [
            db.ExerciseOccurrences.Where(item => item.StartedAtUtc >= fromUtc && item.StartedAtUtc < toUtc && ids.Contains(item.UserId))
                .Select(item => new ActivityStamp { ActorId = item.UserId, AtUtc = item.StartedAtUtc!.Value }),
            db.ExerciseOccurrences.Where(item => item.CompletedAtUtc >= fromUtc && item.CompletedAtUtc < toUtc && ids.Contains(item.UserId))
                .Select(item => new ActivityStamp { ActorId = item.UserId, AtUtc = item.CompletedAtUtc!.Value }),
            db.FoodEntries.Where(item => item.CreatedAtUtc >= fromUtc && item.CreatedAtUtc < toUtc && ids.Contains(item.UserId))
                .Select(item => new ActivityStamp { ActorId = item.UserId, AtUtc = item.CreatedAtUtc }),
            db.Memories.Where(item => item.ReadyAtUtc >= fromUtc && item.ReadyAtUtc < toUtc && ids.Contains(item.UploadedByUserId))
                .Select(item => new ActivityStamp { ActorId = item.UploadedByUserId, AtUtc = item.ReadyAtUtc!.Value }),
            db.DailyAnswers.Where(item => item.CreatedAtUtc >= fromUtc && item.CreatedAtUtc < toUtc && ids.Contains(item.UserId))
                .Select(item => new ActivityStamp { ActorId = item.UserId, AtUtc = item.CreatedAtUtc }),
        ];
    }

    /// <summary>
    /// Timed game actions of couples, in [from, to): a finished game, and the latest move of a game still being played
    /// (sessions keep their last change, not every move).
    /// </summary>
    public IReadOnlyList<IQueryable<ActivityStamp>> CoupleStamps(DateTime fromUtc, DateTime toUtc, IQueryable<Guid>? couples = null)
    {
        var ids = couples ?? measured.CoupleIds;
        return
        [
            db.GameSessions.Where(item => item.Status == GameSessionStatus.Completed && item.EndedAtUtc >= fromUtc && item.EndedAtUtc < toUtc && ids.Contains(item.CoupleId))
                .Select(item => new ActivityStamp { ActorId = item.CoupleId, AtUtc = item.EndedAtUtc!.Value }),
            db.GameSessions.Where(item => (item.Status == GameSessionStatus.InProgress || item.Status == GameSessionStatus.Paused) && item.UpdatedAtUtc >= fromUtc && item.UpdatedAtUtc < toUtc && ids.Contains(item.CoupleId))
                .Select(item => new ActivityStamp { ActorId = item.CoupleId, AtUtc = item.UpdatedAtUtc }),
        ];
    }

    /// <summary>
    /// Every (person, UTC day) with any activity from <paramref name="fromDay"/> to <paramref name="toDay"/>, inclusive.
    /// A couple's game counts for both partners. Each source is reduced to distinct days in the database.
    /// </summary>
    public async Task<HashSet<(Guid UserId, DateOnly Day)>> ActiveDaysAsync(DateOnly fromDay, DateOnly toDay, CancellationToken ct, IQueryable<Guid>? users = null)
    {
        var ids = users ?? measured.UserIds;
        var couples = users is null ? measured.CoupleIds : measured.Couples.Where(couple => ids.Contains(couple.UserAId) || (couple.UserBId != null && ids.Contains(couple.UserBId.Value))).Select(couple => couple.Id);
        var fromUtc = InsightsMath.Utc(fromDay);
        var toUtc = InsightsMath.Utc(toDay.AddDays(1));
        var days = new HashSet<(Guid, DateOnly)>();

        foreach (var row in await db.UserActivityDays.Where(item => item.Day >= fromDay && item.Day <= toDay && ids.Contains(item.UserId)).Select(item => new { item.UserId, item.Day }).ToListAsync(ct))
            days.Add((row.UserId, row.Day));
        foreach (var row in await db.Users.Where(user => user.LastActiveAtUtc >= fromUtc && user.LastActiveAtUtc < toUtc && ids.Contains(user.Id)).Select(user => new { user.Id, user.LastActiveAtUtc!.Value.Date }).ToListAsync(ct))
            days.Add((row.Id, DateOnly.FromDateTime(row.Date)));
        foreach (var source in PersonStamps(fromUtc, toUtc, ids))
            foreach (var row in await source.Select(stamp => new { stamp.ActorId, stamp.AtUtc.Date }).Distinct().ToListAsync(ct))
                days.Add((row.ActorId, DateOnly.FromDateTime(row.Date)));

        var coupleDays = new List<(Guid CoupleId, DateOnly Day)>();
        foreach (var source in CoupleStamps(fromUtc, toUtc, couples))
            coupleDays.AddRange((await source.Select(stamp => new { stamp.ActorId, stamp.AtUtc.Date }).Distinct().ToListAsync(ct)).Select(row => (row.ActorId, DateOnly.FromDateTime(row.Date))));
        if (coupleDays.Count > 0)
        {
            var coupleIds = coupleDays.Select(item => item.CoupleId).Distinct().ToList();
            var members = await db.Couples.Where(couple => coupleIds.Contains(couple.Id)).Select(couple => new { couple.Id, couple.UserAId, couple.UserBId }).ToDictionaryAsync(couple => couple.Id, ct);
            foreach (var (coupleId, day) in coupleDays)
            {
                if (!members.TryGetValue(coupleId, out var couple)) continue;
                days.Add((couple.UserAId, day));
                if (couple.UserBId is { } partner) days.Add((partner, day));
            }
        }
        return days;
    }

    /// <summary>Counts of the stamps by [weekday 0 = Monday][hour], UTC, grouped in the database.</summary>
    public static async Task AddByHourAsync(int[][] grid, IQueryable<ActivityStamp> stamps, CancellationToken ct)
    {
        var rows = await stamps.GroupBy(stamp => new { stamp.AtUtc.DayOfWeek, stamp.AtUtc.Hour })
            .Select(group => new { group.Key.DayOfWeek, group.Key.Hour, Count = group.Count() })
            .ToListAsync(ct);
        foreach (var row in rows) grid[InsightsMath.Weekday(row.DayOfWeek)][row.Hour] += row.Count;
    }

    public static int[][] EmptyWeek() => Enumerable.Range(0, 7).Select(_ => new int[24]).ToArray();
}
