using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Memories;
using Twogether.Core.Enums;
using Twogether.Core.Questions;
using Twogether.Core.Users;

namespace Twogether.Application.Features.Account;

/// <summary>
/// Everything stored about one person, for the right of access and data portability. Only the
/// caller's own rows: what a partner wrote stays theirs. Secrets are left out (password hash,
/// push tokens, purchase tokens). Records are exported as stored, so new fields show up without
/// this file changing; the format is a snapshot, not a stable API.
/// </summary>
public sealed class AccountExportService(IApplicationDbContext db, IMediaStorage storage, MediaSettings media, IDateTimeProvider clock)
{
    public const int FormatVersion = 1;

    public async Task<AccountExport?> ExportAsync(Guid userId, CancellationToken cancellationToken)
    {
        var user = await db.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == userId, cancellationToken);
        if (user is null) return null;

        var profile = new
        {
            user.Id, user.Email, user.DisplayName, user.AvatarUrl, user.MapColor, user.Gender, user.DateOfBirth,
            user.TimeZoneId, user.CycleOwner, user.EmailVerified, SignsInWithGoogle = user.GoogleId != null,
            user.CoupleId, user.CreatedAtUtc, user.UpdatedAtUtc,
            // The profile picture itself (base64), so the export holds everything the profile does.
            ProfilePicture = user.AvatarBytes is { } picture && AvatarImage.Detect(picture) is { } pictureType
                ? new { ContentType = pictureType, SizeBytes = picture.Length, Version = user.AvatarVersion, Base64 = Convert.ToBase64String(picture) }
                : null
        };

        var answers = await db.DailyAnswers.AsNoTracking()
            .Where(item => item.UserId == userId)
            .Join(db.DailyQuestions.AsNoTracking(), answer => answer.DailyQuestionId, question => question.Id,
                (answer, question) => new { question.LocalDate, question.QuestionKey, answer.Body, answer.CreatedAtUtc, answer.UpdatedAtUtc })
            .OrderBy(item => item.LocalDate)
            .ToListAsync(cancellationToken);

        Guid? sharedCoupleId = user.CoupleId is { } coupleId
            && await db.Couples.AsNoTracking().AnyAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, cancellationToken)
            ? coupleId : null;

        var memories = await db.Memories.AsNoTracking()
            .Where(item => item.UploadedByUserId == userId && item.Status == MemoryStatus.Ready)
            .OrderBy(item => item.SortAtUtc)
            .ToListAsync(cancellationToken);

        return new AccountExport(
            FormatVersion,
            clock.UtcNow,
            profile,
            await db.CycleProfiles.AsNoTracking().Where(item => item.UserId == userId).ToListAsync(cancellationToken),
            await db.CycleDayLogs.AsNoTracking().Where(item => item.UserId == userId).OrderBy(item => item.LogDate).ToListAsync(cancellationToken),
            await db.Pregnancies.AsNoTracking().Where(item => item.UserId == userId).ToListAsync(cancellationToken),
            // Appointments of my own pregnancies; ones I added to my partner's only while I may still see it, because
            // the owner can move them and add notes, and after a breakup or a hidden pregnancy that's no longer mine to read.
            await db.PregnancyAppointments.AsNoTracking().Where(item => db.Pregnancies.Any(pregnancy => pregnancy.Id == item.PregnancyId
                && (pregnancy.UserId == userId
                    || (item.CreatedByUserId == userId && sharedCoupleId != null && pregnancy.CoupleId == sharedCoupleId
                        && pregnancy.Status == PregnancyStatus.Active && pregnancy.ShareWithPartner)))).ToListAsync(cancellationToken),
            await db.KickSessions.AsNoTracking().Where(item => db.Pregnancies.Any(pregnancy => pregnancy.Id == item.PregnancyId && pregnancy.UserId == userId)).ToListAsync(cancellationToken),
            await db.FoodEntries.AsNoTracking().Where(item => item.UserId == userId).OrderBy(item => item.LoggedAtUtc).ToListAsync(cancellationToken),
            await db.NutritionTargets.AsNoTracking().Where(item => item.UserId == userId).ToListAsync(cancellationToken),
            await db.FoodScans.AsNoTracking().Where(item => item.UserId == userId).OrderBy(item => item.CreatedAtUtc).ToListAsync(cancellationToken),
            await db.ExerciseGoals.AsNoTracking().Where(item => item.AssignedUserId == userId || item.CreatedByUserId == userId).ToListAsync(cancellationToken),
            await db.ExerciseOccurrences.AsNoTracking().Where(item => item.UserId == userId).OrderBy(item => item.CreatedAtUtc).ToListAsync(cancellationToken),
            await db.CoupleLocations.AsNoTracking().Where(item => item.UserId == userId).ToListAsync(cancellationToken),
            await db.Messages.AsNoTracking().Where(item => item.SenderUserId == userId && item.DeletedAtUtc == null).OrderBy(item => item.CreatedAtUtc).ToListAsync(cancellationToken),
            answers.Select(item => new ExportedAnswer(item.LocalDate, item.QuestionKey, DailyQuestionCatalog.Find(item.QuestionKey)?.Text, item.Body, item.CreatedAtUtc, item.UpdatedAtUtc)).ToList(),
            memories.Select(item => new ExportedMemory(
                item.Id, item.CoupleId, item.ContentType, item.IsVideo, item.SizeBytes, item.Caption, item.TakenAtUtc, item.Source, item.CreatedAtUtc,
                storage.IsConfigured ? storage.PresignDownload(item.ObjectKey, media.DownloadUrlLifetime) : null)).ToList(),
            await db.Notifications.AsNoTracking().Where(item => item.UserId == userId).OrderBy(item => item.CreatedAtUtc)
                .Select(item => new { item.Id, item.Type, item.Title, item.Body, item.CreatedAtUtc, item.ReadAtUtc }).ToListAsync<object>(cancellationToken),
            await db.DeviceRegistrations.AsNoTracking().Where(item => item.UserId == userId)
                .Select(item => new { item.Id, item.Platform, item.AppVersion, item.CreatedAtUtc, item.LastSeenAtUtc, item.DisabledAtUtc }).ToListAsync<object>(cancellationToken),
            await db.NotificationPreferences.AsNoTracking().Where(item => item.UserId == userId).ToListAsync(cancellationToken),
            await db.StoreSubscriptions.AsNoTracking().Where(item => item.UserId == userId)
                .Select(item => new { item.Store, item.ProductId, item.Status, item.ExpiresAtUtc, item.AutoRenewing, item.CreatedAtUtc }).ToListAsync<object>(cancellationToken),
            media.DownloadUrlLifetime,
            // What Claude wrote for this person (docs/ai.md): the coach's notes on their dares, and their weekly recaps.
            await db.AiCoachNotes.AsNoTracking().Where(item => item.UserId == userId && item.State == AiNoteState.Ready).OrderBy(item => item.CreatedAtUtc)
                .Select(item => new { item.OccurrenceId, item.Language, item.Text, item.CreatedAtUtc }).ToListAsync<object>(cancellationToken),
            (await db.WeeklyRecaps.AsNoTracking().Where(item => db.Couples.Any(couple => couple.Id == item.CoupleId && (couple.UserAId == userId || couple.UserBId == userId))
                    && item.State == AiNoteState.Ready).OrderBy(item => item.PeriodEndUtc).ToListAsync(cancellationToken))
                .Select(item => (object?)Twogether.Application.Features.Ai.WeeklyRecapExport.For(item, userId)).OfType<object>().ToList());
    }
}

public sealed record ExportedAnswer(DateOnly Date, string QuestionKey, string? Question, string Answer, DateTime AnsweredAtUtc, DateTime UpdatedAtUtc);
public sealed record ExportedMemory(Guid Id, Guid CoupleId, string ContentType, bool IsVideo, long SizeBytes, string? Caption, DateTime? TakenAtUtc, MemorySource Source, DateTime UploadedAtUtc, Uri? DownloadUrl);

public sealed record AccountExport(
    int FormatVersion,
    DateTime ExportedAtUtc,
    object Profile,
    IReadOnlyList<object> CycleProfiles,
    IReadOnlyList<object> CycleDays,
    IReadOnlyList<object> Pregnancies,
    IReadOnlyList<object> PregnancyAppointments,
    IReadOnlyList<object> KickSessions,
    IReadOnlyList<object> FoodEntries,
    IReadOnlyList<object> NutritionTargets,
    IReadOnlyList<object> FoodScans,
    IReadOnlyList<object> ExerciseGoals,
    IReadOnlyList<object> ExerciseOccurrences,
    IReadOnlyList<object> Locations,
    IReadOnlyList<object> MessagesSent,
    IReadOnlyList<ExportedAnswer> DailyAnswers,
    IReadOnlyList<ExportedMemory> Memories,
    IReadOnlyList<object> Notifications,
    IReadOnlyList<object> Devices,
    IReadOnlyList<object> NotificationPreferences,
    IReadOnlyList<object> Subscriptions,
    TimeSpan MemoryLinksValidFor,
    IReadOnlyList<object>? CoachNotes = null,
    IReadOnlyList<object>? WeeklyRecaps = null);
