using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Live;

/// <summary>
/// What just happened across the app, built from the timestamps the tables already keep: sign-ups,
/// couples linked and ended, dares done and missed, games finished, Plus started and ended, accounts
/// deleted, and alerts. Measured people only (<see cref="AdminScope"/>). The pulse's recent list and the
/// live stream both read from here.
/// </summary>
public sealed class AdminLiveFeed(IApplicationDbContext db, IDateTimeProvider clock, AdminSettings settings, AdminAlertMonitor alerts)
{
    public const int RecentLimit = 30;
    public static readonly TimeSpan RecentWindow = TimeSpan.FromHours(24);
    public const string ComplimentaryDetail = "complimentary";

    private sealed record Raw(string Id, LiveKind Kind, DateTime AtUtc, Guid? UserId, Guid? PartnerId, string? Detail, LiveSeverity Severity);

    /// <summary>Events after <paramref name="sinceUtc"/> up to now, newest first, at most <paramref name="limit"/>.</summary>
    public async Task<IReadOnlyList<LiveEvent>> SinceAsync(DateTime sinceUtc, int limit, CancellationToken ct)
    {
        var now = clock.UtcNow;
        var scope = new MeasuredScope(db, settings);
        var users = scope.Users.AsNoTracking();
        var couples = scope.Couples.AsNoTracking();
        var raw = new List<Raw>();

        raw.AddRange((await users.Where(user => user.CreatedAtUtc > sinceUtc && user.CreatedAtUtc <= now)
                .OrderByDescending(user => user.CreatedAtUtc).Take(limit)
                .Select(user => new { user.Id, user.CreatedAtUtc, user.CountryCode }).ToListAsync(ct))
            .Select(row => new Raw($"signedUp:{row.Id}", LiveKind.SignedUp, row.CreatedAtUtc, row.Id, null, row.CountryCode, LiveSeverity.Good)));

        // The one who joined is the actor; the one who invited is the partner.
        raw.AddRange((await couples.Where(couple => couple.LinkedAtUtc > sinceUtc && couple.LinkedAtUtc <= now)
                .OrderByDescending(couple => couple.LinkedAtUtc).Take(limit)
                .Select(couple => new { couple.Id, At = couple.LinkedAtUtc!.Value, couple.UserAId, couple.UserBId }).ToListAsync(ct))
            .Select(row => new Raw($"coupleLinked:{row.Id}", LiveKind.CoupleLinked, row.At, row.UserBId, row.UserAId, null, LiveSeverity.Good)));

        // A pending invite that was withdrawn never was a couple.
        raw.AddRange((await couples.Where(couple => couple.LinkedAtUtc != null && couple.DissolvedAtUtc > sinceUtc && couple.DissolvedAtUtc <= now)
                .OrderByDescending(couple => couple.DissolvedAtUtc).Take(limit)
                .Select(couple => new { couple.Id, At = couple.DissolvedAtUtc!.Value, couple.UserAId, couple.UserBId }).ToListAsync(ct))
            .Select(row => new Raw($"coupleEnded:{row.Id}", LiveKind.CoupleEnded, row.At, row.UserAId, row.UserBId, null, LiveSeverity.Warn)));

        var measuredOccurrences = db.ExerciseOccurrences.AsNoTracking().Where(item => couples.Any(couple => couple.Id == item.CoupleId));
        raw.AddRange((await measuredOccurrences
                .Where(item => item.Status == ExerciseOccurrenceStatus.Completed && item.CompletedAtUtc > sinceUtc && item.CompletedAtUtc <= now)
                .OrderByDescending(item => item.CompletedAtUtc).Take(limit)
                .Select(item => new { item.Id, At = item.CompletedAtUtc!.Value, item.UserId, item.ExerciseType }).ToListAsync(ct))
            .Select(row => new Raw($"dareCompleted:{row.Id}", LiveKind.DareCompleted, row.At, row.UserId, null, Camel(row.ExerciseType.ToString()), LiveSeverity.Good)));
        // Missed is the last change an occurrence gets, so its update time is when it was missed.
        raw.AddRange((await measuredOccurrences
                .Where(item => item.Status == ExerciseOccurrenceStatus.Missed && item.UpdatedAtUtc > sinceUtc && item.UpdatedAtUtc <= now)
                .OrderByDescending(item => item.UpdatedAtUtc).Take(limit)
                .Select(item => new { item.Id, At = item.UpdatedAtUtc, item.UserId, item.ExerciseType }).ToListAsync(ct))
            .Select(row => new Raw($"dareMissed:{row.Id}", LiveKind.DareMissed, row.At, row.UserId, null, Camel(row.ExerciseType.ToString()), LiveSeverity.Warn)));

        raw.AddRange((await db.GameSessions.AsNoTracking()
                .Where(item => item.Status == GameSessionStatus.Completed && item.EndedAtUtc > sinceUtc && item.EndedAtUtc <= now)
                .Join(couples, game => game.CoupleId, couple => couple.Id, (game, couple) => new { game.Id, At = game.EndedAtUtc!.Value, game.GameType, game.InvitedByUserId, couple.UserAId, couple.UserBId })
                .OrderByDescending(row => row.At).Take(limit).ToListAsync(ct))
            .Select(row =>
            {
                var actor = row.InvitedByUserId ?? row.UserAId;
                return new Raw($"gameFinished:{row.Id}", LiveKind.GameFinished, row.At, actor, actor == row.UserAId ? row.UserBId : row.UserAId, Camel(row.GameType.ToString()), LiveSeverity.Info);
            }));

        var subscriptions = db.StoreSubscriptions.AsNoTracking().Where(item => users.Any(user => user.Id == item.UserId));
        raw.AddRange((await subscriptions
                .Where(item => item.CreatedAtUtc > sinceUtc && item.CreatedAtUtc <= now
                    && item.Status != StoreSubscriptionStatus.Pending && item.Status != StoreSubscriptionStatus.PendingPurchaseCanceled)
                .OrderByDescending(item => item.CreatedAtUtc).Take(limit)
                .Select(item => new { item.Id, item.CreatedAtUtc, item.UserId, item.ProductId }).ToListAsync(ct))
            .Select(row => new Raw($"plusStarted:{row.Id}", LiveKind.PlusStarted, row.CreatedAtUtc, row.UserId, null, row.ProductId, LiveSeverity.Good)));
        raw.AddRange((await subscriptions
                .Where(item => item.Status == StoreSubscriptionStatus.Expired && item.ExpiresAtUtc > sinceUtc && item.ExpiresAtUtc <= now)
                .OrderByDescending(item => item.ExpiresAtUtc).Take(limit)
                .Select(item => new { item.Id, item.ExpiresAtUtc, item.UserId, item.ProductId }).ToListAsync(ct))
            .Select(row => new Raw($"plusEnded:{row.Id}", LiveKind.PlusEnded, row.ExpiresAtUtc, row.UserId, null, row.ProductId, LiveSeverity.Warn)));

        var grants = db.PlusGrants.AsNoTracking().Join(couples, grant => grant.CoupleId, couple => couple.Id,
            (grant, couple) => new { grant.Id, grant.CreatedAtUtc, grant.ExpiresAtUtc, grant.RevokedAtUtc, couple.UserAId, couple.UserBId });
        raw.AddRange((await grants.Where(row => row.CreatedAtUtc > sinceUtc && row.CreatedAtUtc <= now).OrderByDescending(row => row.CreatedAtUtc).Take(limit).ToListAsync(ct))
            .Select(row => new Raw($"plusStarted:grant:{row.Id}", LiveKind.PlusStarted, row.CreatedAtUtc, row.UserAId, row.UserBId, ComplimentaryDetail, LiveSeverity.Good)));
        raw.AddRange((await grants
                .Where(row => (row.RevokedAtUtc > sinceUtc && row.RevokedAtUtc <= now) || (row.RevokedAtUtc == null && row.ExpiresAtUtc > sinceUtc && row.ExpiresAtUtc <= now))
                .Take(limit).ToListAsync(ct))
            .Select(row => new Raw($"plusEnded:grant:{row.Id}", LiveKind.PlusEnded, row.RevokedAtUtc ?? row.ExpiresAtUtc, row.UserAId, row.UserBId, ComplimentaryDetail, LiveSeverity.Warn)));

        raw.AddRange((await db.AccountDeletions.AsNoTracking()
                .Where(item => !item.WasInternal && item.CreatedAtUtc > sinceUtc && item.CreatedAtUtc <= now)
                .OrderByDescending(item => item.CreatedAtUtc).Take(limit)
                .Select(item => new { item.Id, item.CreatedAtUtc }).ToListAsync(ct))
            .Select(row => new Raw($"accountDeleted:{row.Id}", LiveKind.AccountDeleted, row.CreatedAtUtc, null, null, null, LiveSeverity.Bad)));

        var chosen = raw.OrderByDescending(item => item.AtUtc).ThenBy(item => item.Id, StringComparer.Ordinal).Take(limit).ToList();
        await FillPartnersAsync(chosen, ct);
        var ids = chosen.SelectMany(item => new[] { item.UserId, item.PartnerId }).OfType<Guid>().Distinct().ToList();
        var names = ids.Count == 0 ? [] : await db.Users.AsNoTracking().Where(user => ids.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.DisplayName, ct);
        var events = chosen.Select(item => new LiveEvent(item.Id, item.Kind, Utc(item.AtUtc), item.UserId,
                item.UserId is { } user ? names.GetValueOrDefault(user) : null,
                item.PartnerId is { } partner ? names.GetValueOrDefault(partner) : null,
                item.Detail, item.Severity))
            .Concat(alerts.RaisedSince(sinceUtc).Where(item => item.AtUtc <= now))
            .OrderByDescending(item => item.AtUtc).ThenBy(item => item.Id, StringComparer.Ordinal)
            .Take(limit)
            .ToList();
        return events;
    }

    /// <summary>For dares: the partner is the other member of the couple the dare belongs to.</summary>
    private async Task FillPartnersAsync(List<Raw> chosen, CancellationToken ct)
    {
        var dareIds = chosen.Where(item => item.Kind is LiveKind.DareCompleted or LiveKind.DareMissed).Select(item => Guid.Parse(item.Id[(item.Id.IndexOf(':') + 1)..])).ToList();
        if (dareIds.Count == 0) return;
        var partners = await db.ExerciseOccurrences.AsNoTracking().Where(item => dareIds.Contains(item.Id))
            .Join(db.Couples.AsNoTracking(), item => item.CoupleId, couple => couple.Id,
                (item, couple) => new { item.Id, Partner = couple.UserAId == item.UserId ? couple.UserBId : (Guid?)couple.UserAId })
            .ToDictionaryAsync(row => row.Id, row => row.Partner, ct);
        for (var i = 0; i < chosen.Count; i++)
        {
            var item = chosen[i];
            if (item.Kind is not (LiveKind.DareCompleted or LiveKind.DareMissed)) continue;
            chosen[i] = item with { PartnerId = partners.GetValueOrDefault(Guid.Parse(item.Id[(item.Id.IndexOf(':') + 1)..])) };
        }
    }

    public async Task<PulseDto> PulseAsync(IReadOnlyCollection<Guid> connectedUserIds, CancellationToken ct)
    {
        var now = clock.UtcNow;
        var today = DateOnly.FromDateTime(now);
        var todayStart = today.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);
        var scope = new MeasuredScope(db, settings);
        var users = scope.Users.AsNoTracking();
        var couples = scope.Couples.AsNoTracking();
        var onlineSince = now.AddMinutes(-5);
        // Last-active is written at most every 15 minutes, so someone with the app open (a live hub
        // connection on this instance) counts as online too.
        var connected = connectedUserIds.ToList();
        var online = await users.CountAsync(user => user.LastActiveAtUtc >= onlineSince || connected.Contains(user.Id), ct);
        var activeToday = await db.UserActivityDays.AsNoTracking().CountAsync(item => item.Day == today && users.Any(user => user.Id == item.UserId), ct);
        var occurrences = db.ExerciseOccurrences.AsNoTracking().Where(item => couples.Any(couple => couple.Id == item.CoupleId));
        var ringing = await occurrences.CountAsync(item => item.Status == ExerciseOccurrenceStatus.Alarming && item.DeadlineUtc > now, ct);
        var onCamera = await occurrences.CountAsync(item => item.Status == ExerciseOccurrenceStatus.InProgress, ct);
        var hearts = await occurrences.CountAsync(item => item.Status == ExerciseOccurrenceStatus.Completed && item.CompletedAtUtc >= todayStart, ct);
        var openGames = await db.GameSessions.AsNoTracking().CountAsync(item =>
            (item.Status == GameSessionStatus.WaitingForPartner || item.Status == GameSessionStatus.InProgress || item.Status == GameSessionStatus.Paused)
            && couples.Any(couple => couple.Id == item.CoupleId), ct);
        var signups = await users.CountAsync(user => user.CreatedAtUtc >= todayStart, ct);
        var linked = await couples.CountAsync(couple => couple.LinkedAtUtc >= todayStart, ct);
        var plusSubscriptions = await db.StoreSubscriptions.AsNoTracking().CountAsync(item => item.CreatedAtUtc >= todayStart
            && item.Status != StoreSubscriptionStatus.Pending && item.Status != StoreSubscriptionStatus.PendingPurchaseCanceled
            && users.Any(user => user.Id == item.UserId), ct);
        var plusGrants = await db.PlusGrants.AsNoTracking().CountAsync(item => item.CreatedAtUtc >= todayStart && couples.Any(couple => couple.Id == item.CoupleId), ct);
        var recent = await SinceAsync(now - RecentWindow, RecentLimit, ct);
        return new PulseDto(now, online, activeToday, ringing, onCamera, openGames, hearts, signups, linked, plusSubscriptions + plusGrants, recent);
    }

    /// <summary>Times from the database come back unspecified on some providers; the wire always says UTC.</summary>
    private static DateTime Utc(DateTime value) => value.Kind == DateTimeKind.Utc ? value : DateTime.SpecifyKind(value, DateTimeKind.Utc);

    internal static string Camel(string value) => string.IsNullOrEmpty(value) ? value : char.ToLowerInvariant(value[0]) + value[1..];
}
