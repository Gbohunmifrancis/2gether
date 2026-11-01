using System.Diagnostics;
using System.Globalization;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Ai;
using Twogether.Application.Features.Config;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Infrastructure.Ai;

/// <summary>
/// The AI features a person asks for (docs/ai.md): dare ideas, the coach's note on a finished dare, the weekly recap as
/// written for them, and Plan something. Each checks its admin switch and the key first, builds its facts from the
/// database (only what <see cref="AiFacts"/> allows), counts against the daily cap, calls Claude once, and checks the
/// answer before anyone sees it.
/// </summary>
public sealed class AiAssistant(
    ApplicationDbContext db,
    IClaudeClient claude,
    AiSettings settings,
    FeatureFlagService flags,
    AiUsage usage,
    IDateTimeProvider clock,
    ILogger<AiAssistant> logger)
{
    public static readonly JsonSerializerOptions StoredJson = new(JsonSerializerDefaults.Web);

    private static Error Off => Error.NotFound("ai.off", Loc.T("Suggestions are switched off right now."));
    private static Error NoCouple => Error.Validation("ai.no_couple", Loc.T("Link your partner first."));

    public async Task<AiStatusDto> StatusAsync(Guid userId, CancellationToken cancellationToken)
    {
        if (!claude.IsConfigured) return new AiStatusDto(false, false, false, false, 0, 0);
        var on = await flags.GetAiAsync(cancellationToken);
        var ideasLeft = on.DareIdeas ? Math.Max(0, settings.DareIdeasPerDay - await usage.UsedTodayAsync(userId, AiFeature.DareIdeas, cancellationToken)) : 0;
        var plansLeft = on.Planner ? Math.Max(0, settings.PlansPerDay - await usage.UsedTodayAsync(userId, AiFeature.Plan, cancellationToken)) : 0;
        return new AiStatusDto(on.DareIdeas, on.Coach, on.WeeklyRecap, on.Planner, ideasLeft, plansLeft);
    }

    // ----- Dare ideas -----

    public async Task<Result<DareIdeasDto>> DareIdeasAsync(Guid userId, Guid? coupleId, Guid assigneeId, CancellationToken cancellationToken)
    {
        if (!await IsOnAsync(AiFeature.DareIdeas, cancellationToken)) return Off;
        var couple = await ActiveCoupleAsync(userId, coupleId, cancellationToken);
        if (couple is null) return NoCouple;
        if (assigneeId != couple.UserAId && assigneeId != couple.UserBId)
            return Error.Validation("ai.invalid_assignee", Loc.T("Dares can only be for you or your partner."));

        var facts = await DareFactsAsync(userId, couple, assigneeId, cancellationToken);
        var (request, used) = await usage.StartAsync(userId, couple.Id, AiFeature.DareIdeas, settings.DareIdeasPerDay, claude.ModelName, cancellationToken);
        if (request is null) return Limit(AiFeature.DareIdeas, settings.DareIdeasPerDay);
        var ideas = await CallAsync(request, AiPrompts.DareIdeas(facts), AiParsers.DareIdeas, cancellationToken);
        if (ideas.IsFailure) return ideas.Error;
        return new DareIdeasDto(ideas.Value, Math.Max(0, settings.DareIdeasPerDay - used - 1));
    }

    /// <summary>The facts for dare ideas: the catalog, and the assignee's last three weeks of dares, active dares and streak.</summary>
    public async Task<DareIdeaFacts> DareFactsAsync(Guid userId, Couple couple, Guid assigneeId, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var people = await db.Users.AsNoTracking().Where(user => user.Id == userId || user.Id == assigneeId)
            .Select(user => new { user.Id, user.DisplayName, user.CountryCode }).ToListAsync(cancellationToken);
        var setter = people.FirstOrDefault(user => user.Id == userId);
        var assignee = people.FirstOrDefault(user => user.Id == assigneeId);
        var goals = await db.ExerciseGoals.AsNoTracking().Where(goal => goal.CoupleId == couple.Id && goal.AssignedUserId == assigneeId)
            .OrderByDescending(goal => goal.UpdatedAtUtc).ToListAsync(cancellationToken);
        var since = now.AddDays(-60);
        var occurrences = await db.ExerciseOccurrences.AsNoTracking()
            .Where(item => item.CoupleId == couple.Id && item.UserId == assigneeId && item.ScheduledAtUtc >= since)
            .OrderByDescending(item => item.ScheduledAtUtc).ToListAsync(cancellationToken);
        var streak = ExerciseStats.Compute(assigneeId, string.Empty, occurrences, ExerciseStats.TodayFor(assigneeId, goals, occurrences, now), goals).CurrentStreakDays;
        var zones = goals.ToDictionary(goal => goal.Id, goal => ExerciseSchedule.ResolveTimeZone(goal.TimeZoneId));
        var recentFrom = now.AddDays(-21);
        var recent = occurrences
            .Where(item => !item.IsAlarmActive && item.ScheduledAtUtc >= recentFrom)
            .Take(21)
            .Select(item => new RecentDareFact(
                item.LocalDate.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                AiCatalogs.Wire(item.ExerciseType),
                item.Target,
                AiCatalogs.Wire(ExerciseCatalog.MeasureOf(item.ExerciseType)),
                Outcome(item.Status),
                LocalClock(item.ScheduledAtUtc, zones.GetValueOrDefault(item.GoalId) ?? TimeZoneInfo.Utc),
                item.Status == ExerciseOccurrenceStatus.Completed ? item.AchievedCount : 0))
            .ToList();
        var active = goals.Where(goal => goal.IsActive)
            .Select(goal => new ActiveDareFact(AiCatalogs.Wire(goal.ExerciseType), goal.Target, AiCatalogs.Wire(ExerciseCatalog.MeasureOf(goal.ExerciseType)),
                goal.ScheduledTime.ToString("HH:mm", CultureInfo.InvariantCulture)))
            .ToList();
        var language = Loc.Language;
        return new DareIdeaFacts(language, AiCatalogs.LanguageName(language), assignee?.CountryCode,
            AiCatalogs.FirstName(setter?.DisplayName, "You"), AiCatalogs.FirstName(assignee?.DisplayName, "Your partner"),
            userId == assigneeId, streak, recent, active, AiCatalogs.Exercises);
    }

    // ----- Plan something -----

    public async Task<Result<PlanDto>> PlanAsync(Guid userId, Guid? coupleId, PlanRequest request, CancellationToken cancellationToken)
    {
        if (!await IsOnAsync(AiFeature.Plan, cancellationToken)) return Off;
        var couple = await ActiveCoupleAsync(userId, coupleId, cancellationToken);
        if (couple is null) return NoCouple;
        if (!Enum.IsDefined(request.Mood) || !Enum.IsDefined(request.Budget) || !Enum.IsDefined(request.Setting))
            return Error.Validation("ai.plan_invalid", Loc.T("Pick a mood, a budget, and in or out."));
        var city = AiParsers.City(request.City);
        if (city.IsFailure) return city.Error;

        var facts = await PlanFactsAsync(userId, couple, request, city.Value, cancellationToken);
        var (started, used) = await usage.StartAsync(userId, couple.Id, AiFeature.Plan, settings.PlansPerDay, claude.ModelName, cancellationToken);
        if (started is null) return Limit(AiFeature.Plan, settings.PlansPerDay);
        var plan = await CallAsync(started, AiPrompts.Plan(facts), AiParsers.Plan, cancellationToken);
        if (plan.IsFailure) return plan.Error;
        return new PlanDto(plan.Value.Dates, plan.Value.Games, Math.Max(0, settings.PlansPerDay - used - 1));
    }

    public async Task<PlanFacts> PlanFactsAsync(Guid userId, Couple couple, PlanRequest request, string? city, CancellationToken cancellationToken)
    {
        var partnerId = couple.UserAId == userId ? couple.UserBId : couple.UserAId;
        var people = await db.Users.AsNoTracking().Where(user => user.Id == userId || user.Id == partnerId)
            .Select(user => new { user.Id, user.DisplayName, user.CountryCode }).ToListAsync(cancellationToken);
        var me = people.FirstOrDefault(user => user.Id == userId);
        var partner = people.FirstOrDefault(user => user.Id == partnerId);
        var since = clock.UtcNow.AddDays(-30);
        var played = await db.GameResults.AsNoTracking().Where(item => item.CoupleId == couple.Id && item.EndedAtUtc >= since)
            .GroupBy(item => item.GameType).Select(group => new { group.Key, Count = group.Count() })
            .ToDictionaryAsync(item => item.Key, item => item.Count, cancellationToken);
        var language = Loc.Language;
        return new PlanFacts(language, AiCatalogs.LanguageName(language), me?.CountryCode,
            AiCatalogs.FirstName(me?.DisplayName, "You"), AiCatalogs.FirstName(partner?.DisplayName, "Your partner"),
            AiCatalogs.Wire(request.Mood), AiCatalogs.Wire(request.Budget), AiCatalogs.Wire(request.Setting),
            // A city only matters for going out; staying in, it isn't sent at all.
            request.Setting == PlanSetting.Out ? city : null,
            AiCatalogs.Games.Select(game => new CatalogGameFact(AiCatalogs.Wire(game.Type), game.Name, game.About, played.GetValueOrDefault(game.Type))).ToList());
    }

    // ----- Coach -----

    /// <summary>
    /// The note on a finished dare, for the person who did it or their partner. The first reader to ask writes it (its row
    /// is the claim); anyone asking meanwhile is told it is pending. A failed note is tried again a little later, at most
    /// <see cref="AiCoachNote.MaxAttempts"/> times.
    /// </summary>
    public async Task<Result<CoachNoteDto>> CoachAsync(Guid userId, Guid? coupleId, Guid occurrenceId, CancellationToken cancellationToken)
    {
        if (!await IsOnAsync(AiFeature.Coach, cancellationToken)) return Off;
        var couple = await ActiveCoupleAsync(userId, coupleId, cancellationToken);
        if (couple is null) return NoCouple;
        var occurrence = await db.ExerciseOccurrences.AsNoTracking().SingleOrDefaultAsync(item => item.Id == occurrenceId && item.CoupleId == couple.Id, cancellationToken);
        if (occurrence is null || occurrence.Status != ExerciseOccurrenceStatus.Completed)
            return Error.NotFound("ai.coach_none", Loc.T("There's no note for this dare."));

        var now = clock.UtcNow;
        var note = await db.AiCoachNotes.SingleOrDefaultAsync(item => item.OccurrenceId == occurrenceId, cancellationToken);
        if (note is not null)
        {
            if (note.State == AiNoteState.Ready) return Dto(note);
            if (!note.CanRetryAt(now)) return note.IsExhausted ? Unavailable(occurrence) : Pending(occurrence);
        }
        // Old dares don't get a note after the fact.
        if (occurrence.CompletedAtUtc is not { } completedAt || now - completedAt > TimeSpan.FromDays(Math.Max(1, settings.CoachMaxAgeDays))) return Unavailable(occurrence);

        AiRequest? request;
        if (note is null)
        {
            var language = await db.Users.AsNoTracking().Where(user => user.Id == occurrence.UserId).Select(user => user.Language).SingleOrDefaultAsync(cancellationToken) ?? "en";
            note = AiCoachNote.Claim(occurrence.Id, occurrence.CoupleId, occurrence.UserId, language, now);
            try
            {
                // Counted against the person who did the dare: it is their note.
                (request, _) = await usage.StartAsync(occurrence.UserId, couple.Id, AiFeature.Coach, settings.CoachNotesPerDay, claude.ModelName, cancellationToken, note);
            }
            catch (DbUpdateException)
            {
                // The partner's phone asked at the same moment and its claim went in first.
                db.ChangeTracker.Clear();
                return Pending(occurrence);
            }
            if (request is null) return Unavailable(occurrence);
        }
        else
        {
            // Take over a failed or abandoned note only if nobody else just did.
            var attempts = note.Attempts;
            var state = note.State;
            var claimed = await db.AiCoachNotes
                .Where(item => item.Id == note.Id && item.Attempts == attempts && item.State == state)
                .ExecuteUpdateAsync(set => set.SetProperty(item => item.State, AiNoteState.Pending).SetProperty(item => item.Attempts, attempts + 1)
                    .SetProperty(item => item.ClaimedAtUtc, now).SetProperty(item => item.UpdatedAtUtc, now), cancellationToken);
            if (claimed != 1) return Pending(occurrence);
            db.ChangeTracker.Clear();
            note = await db.AiCoachNotes.SingleAsync(item => item.Id == note.Id, cancellationToken);
            (request, _) = await usage.StartAsync(occurrence.UserId, couple.Id, AiFeature.Coach, settings.CoachNotesPerDay, claude.ModelName, cancellationToken);
            if (request is null)
            {
                note.Fail(clock.UtcNow);
                await db.SaveChangesAsync(cancellationToken);
                return Unavailable(occurrence);
            }
        }

        var facts = await CoachFactsAsync(occurrence, note.Language, cancellationToken);
        // An exception leaves the note pending: its lease runs out and the next reader tries again.
        var text = await CallAsync(request, AiPrompts.Coach(facts), AiParsers.Coach, cancellationToken);
        if (text.IsFailure)
        {
            note.Fail(clock.UtcNow);
            await db.SaveChangesAsync(cancellationToken);
            return Unavailable(occurrence);
        }
        note.Complete(text.Value, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return Dto(note);
    }

    public async Task<CoachFacts> CoachFactsAsync(ExerciseOccurrence occurrence, string language, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var name = await db.Users.AsNoTracking().Where(user => user.Id == occurrence.UserId).Select(user => user.DisplayName).SingleOrDefaultAsync(cancellationToken);
        var goals = await db.ExerciseGoals.AsNoTracking().Where(goal => goal.CoupleId == occurrence.CoupleId && goal.AssignedUserId == occurrence.UserId)
            .OrderByDescending(goal => goal.UpdatedAtUtc).ToListAsync(cancellationToken);
        var since = now.AddDays(-60);
        var occurrences = await db.ExerciseOccurrences.AsNoTracking()
            .Where(item => item.CoupleId == occurrence.CoupleId && item.UserId == occurrence.UserId && item.ScheduledAtUtc >= since)
            .OrderByDescending(item => item.ScheduledAtUtc).ToListAsync(cancellationToken);
        var streak = ExerciseStats.Compute(occurrence.UserId, string.Empty, occurrences, ExerciseStats.TodayFor(occurrence.UserId, goals, occurrences, now), goals).CurrentStreakDays;
        var weekAgo = now.AddDays(-7);
        var doneThisWeek = occurrences.Count(item => item.Status == ExerciseOccurrenceStatus.Completed && item.CompletedAtUtc >= weekAgo);
        var zone = ExerciseSchedule.ResolveTimeZone(goals.FirstOrDefault(goal => goal.Id == occurrence.GoalId)?.TimeZoneId);
        var definition = ExerciseCatalog.Get(occurrence.ExerciseType);
        int? seconds = occurrence.StartedAtUtc is { } started && occurrence.CompletedAtUtc is { } completed && completed > started
            ? (int)Math.Round((completed - started).TotalSeconds) : null;
        double? pace = seconds is { } total && occurrence.AchievedCount > 0 ? Math.Round((double)total / occurrence.AchievedCount, 1) : null;
        var early = occurrence.CompletedAtUtc is { } done && done < occurrence.ScheduledAtUtc;
        int? minutesEarly = early ? (int)Math.Max(1, Math.Round((occurrence.ScheduledAtUtc - occurrence.CompletedAtUtc!.Value).TotalMinutes)) : null;
        var checks = occurrence.Checks;
        return new CoachFacts(language, AiCatalogs.LanguageName(language), AiCatalogs.FirstName(name, "you"),
            definition.DisplayName, AiCatalogs.Wire(definition.Measure), occurrence.Target, occurrence.AchievedCount, seconds, pace, definition.MinSecondsPerUnit,
            early ? "early" : "while_ringing", minutesEarly, LocalClock(occurrence.ScheduledAtUtc, zone), streak, doneThisWeek,
            new CameraChecksFact(checks.FormFaults, checks.FastReps, checks.ChallengesPassed, checks.ChallengesMissed, checks.PhoneMovedReps, checks.OutOfFrameSeconds));
    }

    // ----- Weekly recap, as one partner reads it -----

    /// <summary>The couple's latest recap from the last eight days, written for this reader; null when there is none (or it is off).</summary>
    public async Task<WeeklyRecapDto?> LatestRecapAsync(Guid userId, Guid? coupleId, CancellationToken cancellationToken)
    {
        if (!await IsOnAsync(AiFeature.WeeklyRecap, cancellationToken)) return null;
        var couple = await ActiveCoupleAsync(userId, coupleId, cancellationToken);
        if (couple is null) return null;
        var since = clock.UtcNow.AddDays(-8);
        var recap = await db.WeeklyRecaps.AsNoTracking()
            .Where(item => item.CoupleId == couple.Id && item.State == AiNoteState.Ready && item.PeriodEndUtc >= since)
            .OrderByDescending(item => item.PeriodEndUtc).FirstOrDefaultAsync(cancellationToken);
        return recap is null ? null : ToDto(recap, userId);
    }

    public static WeeklyRecapDto? ToDto(WeeklyRecap recap, Guid userId)
    {
        var note = JsonSerializer.Deserialize<List<WeeklyRecapNote>>(recap.NotesJson, StoredJson)?.FirstOrDefault(item => item.UserId == userId);
        var stats = JsonSerializer.Deserialize<WeeklyRecapStats>(recap.StatsJson, StoredJson);
        if (note is null || stats is null) return null;
        var me = stats.People.FirstOrDefault(person => person.UserId == userId);
        var partner = stats.People.FirstOrDefault(person => person.UserId != userId);
        return new WeeklyRecapDto(recap.Id, recap.WeekEnding.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture), recap.PeriodStartUtc, recap.PeriodEndUtc,
            note.Title, note.Lines, note.Suggestion,
            new WeeklyRecapStatsDto(stats.HeartsMade, me?.DaresDone ?? 0, partner?.DaresDone ?? 0, (me?.DaresMissed ?? 0) + (partner?.DaresMissed ?? 0),
                me?.StreakDays ?? 0, partner?.StreakDays ?? 0, stats.GamesPlayed, me?.GamesWon ?? 0, partner?.GamesWon ?? 0, stats.PhotosAdded, stats.QuestionsAnswered));
    }

    // ----- Shared -----

    private async Task<bool> IsOnAsync(AiFeature feature, CancellationToken cancellationToken)
        => claude.IsConfigured && (await flags.GetAiAsync(cancellationToken)).IsOn(feature);

    private async Task<Couple?> ActiveCoupleAsync(Guid userId, Guid? coupleId, CancellationToken cancellationToken)
    {
        if (coupleId is not { } id) return null;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == id && item.Status == CoupleStatus.Active, cancellationToken);
        return couple is not null && (couple.UserAId == userId || couple.UserBId == userId) ? couple : null;
    }

    /// <summary>Sends the prompt, checks the answer, and records how the call ended and what it cost.</summary>
    private async Task<Result<T>> CallAsync<T>(AiRequest request, ClaudeRequest prompt, Func<string, Result<T>> parse, CancellationToken cancellationToken)
    {
        var watch = Stopwatch.StartNew();
        Result<ClaudeReply> reply;
        try
        {
            reply = await claude.CompleteJsonAsync(prompt, cancellationToken);
        }
        catch (Exception exception)
        {
            // Still counted (Claude may have been asked already); the row only says how it ended.
            await TryFailAsync(request, exception is OperationCanceledException ? "ai.cancelled" : "ai.error", watch);
            throw;
        }
        var latency = (int)Math.Min(watch.ElapsedMilliseconds, int.MaxValue);
        if (reply.IsFailure)
        {
            request.Fail(reply.Error.Code, latency, clock.UtcNow);
            await db.SaveChangesAsync(cancellationToken);
            return reply.Error;
        }
        var parsed = parse(reply.Value.Json);
        if (parsed.IsFailure)
        {
            request.Fail(parsed.Error.Code, latency, clock.UtcNow, reply.Value.InputTokens, reply.Value.OutputTokens);
            await db.SaveChangesAsync(cancellationToken);
            logger.LogWarning("AI {Feature} answer for user {UserId} didn't pass the checks.", prompt.Feature, request.UserId);
            return parsed.Error;
        }
        request.Succeed(reply.Value.InputTokens, reply.Value.OutputTokens, latency, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);
        return parsed;
    }

    private async Task TryFailAsync(AiRequest request, string code, Stopwatch watch)
    {
        try
        {
            if (!request.IsPending) return;
            request.Fail(code, (int)Math.Min(watch.ElapsedMilliseconds, int.MaxValue), clock.UtcNow);
            await db.SaveChangesAsync(CancellationToken.None);
        }
        catch (Exception exception)
        {
            logger.LogWarning(exception, "Could not record how AI request {RequestId} ended; it stays pending and still counts.", request.Id);
        }
    }

    private static Error Limit(AiFeature feature, int cap) => new("ai.limit", feature == AiFeature.Plan
        ? Loc.Plural(cap, "You've had today's {count} plan. More tomorrow!", "You've had today's {count} plans. More tomorrow!")
        : Loc.Plural(cap, "You've had today's {count} suggestion. More tomorrow!", "You've had today's {count} suggestions. More tomorrow!"), ErrorType.Conflict);

    private static CoachNoteDto Dto(AiCoachNote note) => new(note.OccurrenceId, "ready", note.Text, note.UserId);
    private static CoachNoteDto Pending(ExerciseOccurrence occurrence) => new(occurrence.Id, "pending", null, occurrence.UserId);
    private static CoachNoteDto Unavailable(ExerciseOccurrence occurrence) => new(occurrence.Id, "unavailable", null, occurrence.UserId);

    private static string Outcome(ExerciseOccurrenceStatus status) => status switch
    {
        ExerciseOccurrenceStatus.Completed => "done",
        ExerciseOccurrenceStatus.Missed => "missed",
        _ => "called_off"
    };

    private static string LocalClock(DateTime utc, TimeZoneInfo zone)
        => TimeOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(utc, DateTimeKind.Utc), zone)).ToString("HH:mm", CultureInfo.InvariantCulture);
}
