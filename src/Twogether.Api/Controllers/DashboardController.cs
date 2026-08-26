using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Presence;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Dashboard;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

public sealed class DashboardController(
    IConfiguration configuration,
    IApplicationDbContext db,
    ICurrentUser currentUser,
    CouplePresenceTracker presence,
    IDateTimeProvider clock) : ApiControllerBase
{
    [Authorize]
    [HttpGet]
    [ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
    public async Task<ActionResult<DashboardSnapshotDto>> Get(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();

        var user = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.UserId.Value, cancellationToken);
        if (user is null) return Unauthorized();

        var currentUserName = user.DisplayName;
        var partnerName = "Waiting for partner";
        var partnerOnline = false;
        var notificationCount = 0;
        var cycleDaysUntilPeriod = configuration.GetValue("Dashboard:CycleDaysUntilPeriod", 5);
        var cycleSummary = configuration["Dashboard:CycleSummary"] ?? $"{cycleDaysUntilPeriod} days until period";
        var latestNote = configuration["Dashboard:LatestNote"] ?? "Share a thought with your favorite person.";
        var latestNoteAuthor = "Twogether";

        if (user.CoupleId is { } coupleId)
        {
            var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, cancellationToken);
            if (couple is not null)
            {
                var partnerId = couple.UserAId == user.Id ? couple.UserBId : couple.UserAId;
                if (partnerId is { } linkedPartnerId)
                {
                    partnerName = await db.Users.AsNoTracking().Where(item => item.Id == linkedPartnerId).Select(item => item.DisplayName).SingleOrDefaultAsync(cancellationToken) ?? partnerName;
                    partnerOnline = presence.IsOnline(coupleId, linkedPartnerId);
                }

                var latestMessage = await db.Messages.AsNoTracking()
                    .Where(item => item.CoupleId == coupleId && item.DeletedAtUtc == null)
                    .OrderByDescending(item => item.CreatedAtUtc)
                    .FirstOrDefaultAsync(cancellationToken);
                if (latestMessage is not null)
                {
                    latestNote = latestMessage.Body;
                    latestNoteAuthor = latestMessage.SenderUserId == user.Id ? currentUserName : partnerName;
                }

                notificationCount = await db.Notifications.AsNoTracking().CountAsync(item => item.UserId == user.Id && item.ReadAtUtc == null, cancellationToken);
            }
        }

        var profile = await db.CycleProfiles.AsNoTracking().SingleOrDefaultAsync(item => item.UserId == user.Id, cancellationToken);
        if (profile?.LastPeriodStartDate is { } lastPeriodStart)
        {
            var nextPeriod = lastPeriodStart.AddDays(profile.AverageCycleLengthDays);
            cycleDaysUntilPeriod = Math.Max(0, nextPeriod.DayNumber - DateOnly.FromDateTime(clock.UtcNow).DayNumber);
            cycleSummary = cycleDaysUntilPeriod == 0 ? "Period expected today" : $"{cycleDaysUntilPeriod} days until period";
        }

        var activeGameCount = user.CoupleId is { } activeCoupleId
            ? await db.GameSessions.AsNoTracking().CountAsync(item => item.CoupleId == activeCoupleId && (item.Status == GameSessionStatus.WaitingForPartner || item.Status == GameSessionStatus.InProgress), cancellationToken)
            : 0;

        var connectionStreakDays = 0;
        if (user.CoupleId is { } streakCoupleId)
        {
            var activityDates = await db.Messages.AsNoTracking()
                .Where(item => item.CoupleId == streakCoupleId && item.DeletedAtUtc == null)
                .Select(item => item.CreatedAtUtc.Date)
                .Distinct()
                .ToListAsync(cancellationToken);
            activityDates.AddRange(await db.GameSessions.AsNoTracking()
                .Where(item => item.CoupleId == streakCoupleId)
                .Select(item => item.UpdatedAtUtc.Date)
                .Distinct()
                .ToListAsync(cancellationToken));
            var activeDays = activityDates.Distinct().ToHashSet();
            var cursor = clock.UtcNow.Date;
            if (!activeDays.Contains(cursor)) cursor = cursor.AddDays(-1);
            while (activeDays.Contains(cursor))
            {
                connectionStreakDays++;
                cursor = cursor.AddDays(-1);
            }
        }

        var snapshot = new DashboardSnapshotDto(
            clock.UtcNow,
            currentUserName,
            partnerName,
            partnerOnline,
            notificationCount,
            connectionStreakDays,
            cycleSummary,
            cycleDaysUntilPeriod,
            configuration["Dashboard:HeroDescription"]
                ?? "Keep close, even on the busy days. Share a thought, play a game, or simply check in.",
            latestNote,
            latestNoteAuthor,
            [
                new DashboardGameDto("guess-my-answer", "Guess my answer", "How well do you know me?", "question", "rose"),
                new DashboardGameDto("couple-quiz", "Couple quiz", "Discover something new", "sparkles", "violet"),
                new DashboardGameDto("memory-match", "Memory match", activeGameCount > 0 ? $"{activeGameCount} game{(activeGameCount == 1 ? "" : "s")} in progress" : "Find your little moments", "heart", "cyan")
            ]);

        return Ok(snapshot);
    }
}
