using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Cycle;
using Twogether.Application.Features.Notifications;
using Twogether.Application.Features.Pregnancy;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Localization;

namespace Twogether.Infrastructure.Account;

/// <summary>
/// Hard delete, all or nothing. Rows the person wrote or that describe them go everywhere they
/// appear. Rows a couple shares (games, the daily question list, the couple itself) go only when no
/// other member is left to own them; when one is, those rows stay with that partner and mention the
/// deleted person by an id that no longer resolves to anyone.
/// Memory photos cannot be removed inside a database transaction, so the rows are marked for the
/// cleanup worker with their captions already wiped; the worker deletes the objects within minutes.
/// </summary>
public sealed class AccountEraser(ApplicationDbContext db, IDateTimeProvider clock) : IAccountEraser
{
    public async Task<AccountErasure?> EraseAsync(Guid userId, CancellationToken cancellationToken)
    {
        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == userId, cancellationToken);
        if (user is null) return null;
        var now = clock.UtcNow;

        var (partnerId, notice, cancelled) = await EndActiveCoupleAsync(user, now, cancellationToken);
        await db.SaveChangesAsync(cancellationToken);

        var couples = await db.Couples.AsNoTracking()
            .Where(item => item.UserAId == userId || item.UserBId == userId)
            .Select(item => new { item.Id, Other = item.UserAId == userId ? item.UserBId : item.UserAId })
            .ToListAsync(cancellationToken);
        var coupleIds = couples.Select(item => item.Id).ToList();
        var others = couples.Where(item => item.Other.HasValue).Select(item => item.Other!.Value).Distinct().ToList();
        var remaining = await db.Users.AsNoTracking().Where(item => others.Contains(item.Id)).Select(item => item.Id).ToListAsync(cancellationToken);
        var orphaned = couples.Where(item => item.Other is not { } other || !remaining.Contains(other)).Select(item => item.Id).ToList();

        await db.Memories
            .Where(item => item.UploadedByUserId == userId || orphaned.Contains(item.CoupleId))
            .ExecuteUpdateAsync(set => set
                .SetProperty(item => item.Status, MemoryStatus.Deleting)
                .SetProperty(item => item.Caption, (string?)null)
                .SetProperty(item => item.TakenAtUtc, (DateTime?)null)
                .SetProperty(item => item.UpdatedAtUtc, now), cancellationToken);

        var goalIds = await db.ExerciseGoals
            .Where(item => item.AssignedUserId == userId || item.CreatedByUserId == userId || orphaned.Contains(item.CoupleId))
            .Select(item => item.Id)
            .ToListAsync(cancellationToken);
        await db.ExerciseOccurrences.Where(item => item.UserId == userId || goalIds.Contains(item.GoalId)).ExecuteDeleteAsync(cancellationToken);
        await db.ExerciseGoals.Where(item => goalIds.Contains(item.Id)).ExecuteDeleteAsync(cancellationToken);

        await db.CycleDayLogs.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await db.CycleProfiles.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        // Appointments and kick sessions cascade with the pregnancy, and the partner's "shared pregnancy mode with
        // you" notice goes with it.
        foreach (var pregnancyId in await db.Pregnancies.Where(item => item.UserId == userId).Select(item => item.Id).ToListAsync(cancellationToken))
        {
            var sharedNotice = PregnancyNotices.SharedData(pregnancyId);
            await db.Notifications.Where(item => item.Type == NotificationType.PregnancyUpdated && item.DataJson == sharedNotice).ExecuteDeleteAsync(cancellationToken);
        }
        await db.Pregnancies.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        // Appointments the person added to someone else's pregnancy stay in that health record, but what they wrote
        // goes: a plain title in the owner's language, no place, no notes.
        var owners = await db.PregnancyAppointments.Where(item => item.CreatedByUserId == userId)
            .Join(db.Pregnancies, item => item.PregnancyId, pregnancy => pregnancy.Id, (item, pregnancy) => pregnancy.UserId)
            .Distinct().ToListAsync(cancellationToken);
        foreach (var ownerId in owners)
        {
            string title;
            using (Loc.Use(await db.LanguageOfAsync(ownerId, cancellationToken))) title = Loc.T("Appointment");
            await db.PregnancyAppointments
                .Where(item => item.CreatedByUserId == userId && db.Pregnancies.Any(pregnancy => pregnancy.Id == item.PregnancyId && pregnancy.UserId == ownerId))
                .ExecuteUpdateAsync(set => set
                    .SetProperty(item => item.Title, title)
                    .SetProperty(item => item.Location, (string?)null)
                    .SetProperty(item => item.Notes, (string?)null)
                    .SetProperty(item => item.UpdatedAtUtc, now), cancellationToken);
        }
        await db.FoodEntries.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await db.FoodScans.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await db.NutritionTargets.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await db.CoupleLocations.Where(item => item.UserId == userId || orphaned.Contains(item.CoupleId)).ExecuteDeleteAsync(cancellationToken);
        await db.DeviceRegistrations.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await db.NotificationPreferences.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        // Also cascades from users; removed here so the list above stays the whole story.
        await db.AlbumSyncStates.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await db.StoreSubscriptions.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await db.Notifications.Where(item => item.UserId == userId || orphaned.Contains(item.CoupleId)).ExecuteDeleteAsync(cancellationToken);
        await db.Messages.Where(item => item.SenderUserId == userId || orphaned.Contains(item.CoupleId)).ExecuteDeleteAsync(cancellationToken);
        await db.PartnerInvites.Where(item => item.InviterUserId == userId || orphaned.Contains(item.CoupleId)).ExecuteDeleteAsync(cancellationToken);
        await db.DailyAnswers.Where(item => item.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        // Answers to these questions cascade.
        await db.DailyQuestions.Where(item => orphaned.Contains(item.CoupleId)).ExecuteDeleteAsync(cancellationToken);
        // Game state embeds both players' answers, so every game the person played goes.
        await db.GameResults.Where(item => coupleIds.Contains(item.CoupleId)).ExecuteDeleteAsync(cancellationToken);
        await db.GameSessions.Where(item => coupleIds.Contains(item.CoupleId)).ExecuteDeleteAsync(cancellationToken);
        await db.Couples.Where(item => orphaned.Contains(item.Id)).ExecuteDeleteAsync(cancellationToken);
        await db.Users.Where(item => item.Id == userId).ExecuteDeleteAsync(cancellationToken);

        await transaction.CommitAsync(cancellationToken);
        return new AccountErasure(userId, user.DisplayName, partnerId, notice, cancelled);
    }

    private async Task<(Guid? PartnerId, Notification? Notice, List<ExerciseOccurrence> Cancelled)> EndActiveCoupleAsync(User user, DateTime now, CancellationToken cancellationToken)
    {
        if (user.CoupleId is not { } coupleId) return (null, null, []);
        var couple = await db.Couples.SingleOrDefaultAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, cancellationToken);
        if (couple is null) return (null, null, []);

        var goals = await db.ExerciseGoals.Where(goal => goal.CoupleId == coupleId && goal.IsActive).ToListAsync(cancellationToken);
        foreach (var goal in goals) goal.Deactivate(now);
        var ringing = await db.ExerciseOccurrences
            .Where(item => item.CoupleId == coupleId && (item.Status == ExerciseOccurrenceStatus.Alarming || item.Status == ExerciseOccurrenceStatus.InProgress))
            .ToListAsync(cancellationToken);
        foreach (var occurrence in ringing) occurrence.Cancel(now);

        couple.Dissolve(now);
        user.SetCouple(null, now);
        var partnerId = couple.UserAId == user.Id ? couple.UserBId : couple.UserAId;
        if (partnerId is not { } linkedPartnerId) return (null, null, ringing);

        var partner = await db.Users.SingleAsync(item => item.Id == linkedPartnerId, cancellationToken);
        partner.SetCouple(null, now);
        await CycleSharing.StopSharingAsync(db, [linkedPartnerId], now, cancellationToken);
        using var _ = Loc.Use(partner.Language);
        var notice = Notification.Create(linkedPartnerId, coupleId, NotificationType.Breakup, Loc.T("Your couple space was ended"),
            Loc.T("{name} deleted their account.", ("name", user.DisplayName)), null, now);
        db.Notifications.Add(notice);
        return (linkedPartnerId, notice, ringing);
    }
}
