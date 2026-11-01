using System.Globalization;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Ai;
using Twogether.Application.Features.Config;
using Twogether.Application.Features.Exercise;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Localization;

namespace Twogether.Infrastructure.Ai;

/// <summary>
/// Sunday evening's recap (docs/ai.md): for each active couple whose week has just ended in their time zone, counts the
/// week, skips couples who did nothing, and has Claude write a short recap for each partner in their language. The row
/// for the couple and week is claimed first (a unique index), so the recap is written and pushed once, whichever
/// instance gets there; a failure is tried again later the same evening, at most <see cref="WeeklyRecap.MaxAttempts"/> times.
/// </summary>
public sealed class WeeklyRecapWriter(
    ApplicationDbContext db,
    IClaudeClient claude,
    FeatureFlagService flags,
    AiUsage usage,
    IDateTimeProvider clock,
    ILogger<WeeklyRecapWriter> logger)
{
    public const int BatchSize = 200;

    public sealed record RunSummary(int Due, int Written, int Skipped, int Failed, int ClaimedElsewhere);

    private sealed record Candidate(Guid CoupleId, Guid UserAId, Guid UserBId, string? ZoneA, string? ZoneB);

    /// <summary>One pass over the couples. Returns the notifications saved (already in the push outbox) so the caller can show them live.</summary>
    public async Task<(RunSummary Summary, List<Notification> Notices)> RunAsync(CancellationToken cancellationToken)
    {
        var notices = new List<Notification>();
        var now = clock.UtcNow;
        if (!claude.IsConfigured || !WeeklyRecapRules.MaybeDueAnywhere(now) || !(await flags.GetAiAsync(cancellationToken)).WeeklyRecap)
            return (new RunSummary(0, 0, 0, 0, 0), notices);

        int due = 0, written = 0, skipped = 0, failed = 0, elsewhere = 0;
        Guid? after = null;
        while (true)
        {
            var page = await CandidatesAsync(after, cancellationToken);
            if (page.Count == 0) break;
            after = page[^1].CoupleId;
            var ids = page.Select(item => item.CoupleId).ToList();
            var existing = await db.WeeklyRecaps.AsNoTracking().Where(item => ids.Contains(item.CoupleId) && item.PeriodEndUtc >= now.AddDays(-2))
                .ToListAsync(cancellationToken);
            foreach (var candidate in page)
            {
                var zoneId = WeeklyRecapRules.CoupleZone(candidate.ZoneA, candidate.ZoneB);
                var week = WeeklyRecapRules.LatestEnded(now, ExerciseSchedule.ResolveTimeZone(zoneId));
                if (!WeeklyRecapRules.IsDue(week, now)) continue;
                var row = existing.FirstOrDefault(item => item.CoupleId == candidate.CoupleId && item.WeekEnding == week.WeekEnding);
                if (row is not null && !row.CanRetryAt(now)) continue;
                due++;
                try
                {
                    var recap = row is null ? await ClaimAsync(candidate, week, zoneId, cancellationToken) : await ReclaimAsync(row, cancellationToken);
                    if (recap is null) { elsewhere++; continue; }
                    switch (await WriteAsync(recap, candidate, notices, cancellationToken))
                    {
                        case AiNoteState.Ready: written++; break;
                        case AiNoteState.Skipped: skipped++; break;
                        default: failed++; break;
                    }
                }
                catch (Exception exception) when (exception is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
                {
                    failed++;
                    db.ChangeTracker.Clear();
                    logger.LogWarning(exception, "Weekly recap for couple {CoupleId} failed; it is tried again later.", candidate.CoupleId);
                }
            }
            if (page.Count < BatchSize) break;
        }
        var summary = new RunSummary(due, written, skipped, failed, elsewhere);
        if (due > 0)
            logger.LogInformation("Weekly recaps: {Due} due, {Written} written, {Skipped} quiet weeks skipped, {Failed} failed, {Elsewhere} claimed by another instance.",
                summary.Due, summary.Written, summary.Skipped, summary.Failed, summary.ClaimedElsewhere);
        return (summary, notices);
    }

    private Task<List<Candidate>> CandidatesAsync(Guid? after, CancellationToken cancellationToken)
    {
        var couples = db.Couples.AsNoTracking().Where(couple => couple.Status == CoupleStatus.Active && couple.UserBId != null);
        if (after is { } last) couples = couples.Where(couple => couple.Id.CompareTo(last) > 0);
        return (from couple in couples
                join a in db.Users.AsNoTracking() on couple.UserAId equals a.Id
                join b in db.Users.AsNoTracking() on couple.UserBId equals (Guid?)b.Id
                orderby couple.Id
                select new Candidate(couple.Id, couple.UserAId, b.Id, a.TimeZoneId, b.TimeZoneId))
            .Take(BatchSize)
            .ToListAsync(cancellationToken);
    }

    private async Task<WeeklyRecap?> ClaimAsync(Candidate candidate, RecapWeek week, string zoneId, CancellationToken cancellationToken)
    {
        var recap = WeeklyRecap.Claim(candidate.CoupleId, week.WeekEnding, zoneId, week.PeriodStartUtc, week.PeriodEndUtc, clock.UtcNow);
        db.WeeklyRecaps.Add(recap);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return recap;
        }
        catch (DbUpdateException)
        {
            // Another instance claimed this couple's week first.
            db.ChangeTracker.Clear();
            return null;
        }
    }

    private async Task<WeeklyRecap?> ReclaimAsync(WeeklyRecap row, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var claimed = await db.WeeklyRecaps.Where(item => item.Id == row.Id && item.Attempts == row.Attempts && item.State == row.State)
            .ExecuteUpdateAsync(set => set.SetProperty(item => item.State, AiNoteState.Pending).SetProperty(item => item.Attempts, row.Attempts + 1)
                .SetProperty(item => item.ClaimedAtUtc, now).SetProperty(item => item.UpdatedAtUtc, now), cancellationToken);
        if (claimed != 1) return null;
        return await db.WeeklyRecaps.SingleAsync(item => item.Id == row.Id, cancellationToken);
    }

    /// <summary>Counts the week and, unless nothing happened, writes both recaps and their notifications in one save.</summary>
    private async Task<AiNoteState> WriteAsync(WeeklyRecap recap, Candidate candidate, List<Notification> notices, CancellationToken cancellationToken)
    {
        var stats = await StatsAsync(recap.CoupleId, [candidate.UserAId, candidate.UserBId], recap.PeriodStartUtc, recap.PeriodEndUtc, cancellationToken);
        var statsJson = JsonSerializer.Serialize(stats, AiAssistant.StoredJson);
        if (!stats.HasActivity)
        {
            recap.Skip(statsJson, clock.UtcNow);
            await db.SaveChangesAsync(cancellationToken);
            return AiNoteState.Skipped;
        }

        var people = await db.Users.AsNoTracking().Where(user => user.Id == candidate.UserAId || user.Id == candidate.UserBId)
            .Select(user => new { user.Id, user.DisplayName, user.Language, user.CountryCode }).ToListAsync(cancellationToken);
        var zone = ExerciseSchedule.ResolveTimeZone(recap.TimeZoneId);
        var notes = new List<WeeklyRecapNote>();
        foreach (var reader in people)
        {
            var partner = people.FirstOrDefault(person => person.Id != reader.Id);
            var facts = Facts(stats, reader.Id, reader.Language, reader.CountryCode, reader.DisplayName, partner?.DisplayName, recap, zone);
            var request = usage.Record(reader.Id, recap.CoupleId, AiFeature.WeeklyRecap, claude.ModelName);
            await db.SaveChangesAsync(cancellationToken);
            var watch = System.Diagnostics.Stopwatch.StartNew();
            var reply = await claude.CompleteJsonAsync(AiPrompts.Recap(facts), cancellationToken);
            var latency = (int)Math.Min(watch.ElapsedMilliseconds, int.MaxValue);
            var text = reply.IsSuccess ? AiParsers.Recap(reply.Value.Json) : null;
            if (reply.IsFailure || text is null || text.IsFailure)
            {
                request.Fail(reply.IsFailure ? reply.Error.Code : text!.Error.Code, latency, clock.UtcNow,
                    reply.IsSuccess ? reply.Value.InputTokens : 0, reply.IsSuccess ? reply.Value.OutputTokens : 0);
                recap.Fail(clock.UtcNow);
                await db.SaveChangesAsync(cancellationToken);
                logger.LogWarning("Weekly recap for couple {CoupleId} could not be written (attempt {Attempt}).", recap.CoupleId, recap.Attempts);
                return AiNoteState.Failed;
            }
            request.Succeed(reply.Value.InputTokens, reply.Value.OutputTokens, latency, clock.UtcNow);
            notes.Add(new WeeklyRecapNote(reader.Id, Loc.Normalize(reader.Language) ?? "en", text.Value.Title, text.Value.Lines, text.Value.Suggestion));
        }

        var now = clock.UtcNow;
        recap.Complete(statsJson, JsonSerializer.Serialize(notes, AiAssistant.StoredJson), now);
        foreach (var note in notes)
        {
            using (Loc.Use(note.Language))
            {
                var notice = Notification.Create(note.UserId, recap.CoupleId, NotificationType.WeeklyRecap, Loc.T("Your week together"), note.Title,
                    JsonSerializer.Serialize(new { recapId = recap.Id, weekEnding = recap.WeekEnding.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) }), now);
                db.Notifications.Add(notice);
                notices.Add(notice);
            }
        }
        await db.SaveChangesAsync(cancellationToken);
        return AiNoteState.Ready;
    }

    /// <summary>The week's counts: dares by when they were due, games by when they ended, photos and answers by when they were added.</summary>
    public async Task<WeeklyRecapStats> StatsAsync(Guid coupleId, IReadOnlyList<Guid> members, DateTime fromUtc, DateTime toUtc, CancellationToken cancellationToken)
    {
        var streakFrom = toUtc.AddDays(-60);
        var occurrences = await db.ExerciseOccurrences.AsNoTracking()
            .Where(item => item.CoupleId == coupleId && item.ScheduledAtUtc >= streakFrom && item.ScheduledAtUtc < toUtc)
            .OrderByDescending(item => item.ScheduledAtUtc).ToListAsync(cancellationToken);
        var goals = await db.ExerciseGoals.AsNoTracking().Where(goal => goal.CoupleId == coupleId).OrderByDescending(goal => goal.UpdatedAtUtc).ToListAsync(cancellationToken);
        var week = occurrences.Where(item => item.ScheduledAtUtc >= fromUtc).ToList();
        var games = await db.GameResults.AsNoTracking().Where(item => item.CoupleId == coupleId && item.EndedAtUtc >= fromUtc && item.EndedAtUtc < toUtc)
            .Select(item => item.WinnerUserId).ToListAsync(cancellationToken);
        var photos = await db.Memories.AsNoTracking().CountAsync(item => item.CoupleId == coupleId && item.Status == MemoryStatus.Ready
            && item.CreatedAtUtc >= fromUtc && item.CreatedAtUtc < toUtc, cancellationToken);
        var answers = await db.DailyAnswers.AsNoTracking().Where(item => item.CoupleId == coupleId && item.CreatedAtUtc >= fromUtc && item.CreatedAtUtc < toUtc)
            .Select(item => item.UserId).ToListAsync(cancellationToken);

        var people = members.Select(userId =>
        {
            var mine = week.Where(item => item.UserId == userId).ToList();
            var streak = ExerciseStats.Compute(userId, string.Empty, occurrences, ExerciseStats.TodayFor(userId, goals, occurrences, toUtc), goals).CurrentStreakDays;
            return new RecapPersonStats(userId,
                mine.Count(item => item.Status == ExerciseOccurrenceStatus.Completed),
                mine.Count(item => item.Status == ExerciseOccurrenceStatus.Missed),
                mine.Count(item => item.Status == ExerciseOccurrenceStatus.Cancelled),
                streak,
                games.Count(winner => winner == userId),
                answers.Count(id => id == userId),
                mine.Where(item => item.Status == ExerciseOccurrenceStatus.Completed).Select(item => ExerciseCatalog.Get(item.ExerciseType).DisplayName).Distinct().ToList());
        }).ToList();
        return new WeeklyRecapStats(week.Count(item => item.Status == ExerciseOccurrenceStatus.Completed), games.Count, photos, answers.Count, people);
    }

    public static RecapFacts Facts(WeeklyRecapStats stats, Guid readerId, string? language, string? country, string? readerName, string? partnerName, WeeklyRecap recap, TimeZoneInfo zone)
    {
        var code = Loc.Normalize(language) ?? "en";
        var empty = new RecapPersonStats(Guid.Empty, 0, 0, 0, 0, 0, 0, []);
        var me = stats.People.FirstOrDefault(person => person.UserId == readerId) ?? empty;
        var them = stats.People.FirstOrDefault(person => person.UserId != readerId) ?? empty;
        static RecapPersonFact Person(RecapPersonStats person) => new(person.DaresDone, person.DaresMissed, person.DaresCalledOff, person.StreakDays, person.GamesWon, person.QuestionsAnswered, person.Exercises);
        return new RecapFacts(code, AiCatalogs.LanguageName(code), country,
            AiCatalogs.FirstName(readerName, "You"), AiCatalogs.FirstName(partnerName, "your partner"),
            ExerciseSchedule.LocalDate(recap.PeriodStartUtc, zone).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            recap.WeekEnding.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            stats.HeartsMade, stats.GamesPlayed, stats.PhotosAdded, stats.QuestionsAnswered, Person(me), Person(them));
    }
}
