using System.Globalization;
using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Questions;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Questions;

namespace Twogether.Api.Controllers;

/// <summary>
/// One question a day for the couple. Each partner answers privately; the partner's answer
/// unlocks only after you answer. Dates are the caller's local day, bounded to UTC +/- 1 so no
/// one can open tomorrow's question early or backfill a streak.
/// </summary>
[Authorize]
public sealed class QuestionsController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    IHubContext<CoupleHub> hub,
    Twogether.Application.Features.Billing.IEntitlementService entitlements,
    ILogger<QuestionsController> logger) : ApiControllerBase
{
    public const string AnsweredEvent = "DailyQuestionAnswered";
    private static readonly ApiError NoCouple = new("questions.no_couple", "Link your partner to get daily questions.");

    [HttpGet("today")]
    public async Task<ActionResult<DailyQuestionDto>> Today([FromQuery] string? date, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var localDate = ResolveDate(date);
        if (localDate is null) return UnprocessableEntity(new ApiError("questions.invalid_date", "Date must be yesterday, today or tomorrow in yyyy-MM-dd."));

        var question = await EnsureQuestionAsync(couple.Id, localDate.Value, cancellationToken);
        var answers = await db.DailyAnswers.AsNoTracking().Where(item => item.DailyQuestionId == question.Id).ToListAsync(cancellationToken);
        var streak = await StreakAsync(couple.Id, localDate.Value, cancellationToken);
        return Ok(DailyQuestionMapper.ToDto(question, currentUser.UserId.Value, answers, await NamesAsync(couple, cancellationToken), streak));
    }

    [HttpPut("{questionId:guid}/answer")]
    public async Task<ActionResult<DailyQuestionDto>> Answer(Guid questionId, AnswerDailyQuestionRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return BadRequest(NoCouple);
        var question = await db.DailyQuestions.AsNoTracking().SingleOrDefaultAsync(item => item.Id == questionId && item.CoupleId == couple.Id, cancellationToken);
        if (question is null) return NotFound();
        if (question.LocalDate < DateOnly.FromDateTime(clock.UtcNow).AddDays(-1))
            return Conflict(new ApiError("questions.closed", "This question has closed. Answer today's instead."));
        if (string.IsNullOrWhiteSpace(request.Body) || request.Body.Trim().Length > DailyAnswer.MaxBodyLength)
            return UnprocessableEntity(new ApiError("questions.invalid_answer", $"An answer is 1 to {DailyAnswer.MaxBodyLength} characters."));

        var now = clock.UtcNow;
        var existing = await db.DailyAnswers.SingleOrDefaultAsync(item => item.DailyQuestionId == question.Id && item.UserId == userId, cancellationToken);
        var firstAnswer = existing is null;
        if (existing is null) db.DailyAnswers.Add(DailyAnswer.Create(question.Id, couple.Id, userId, request.Body.Trim(), now));
        else existing.Edit(request.Body.Trim(), now);

        var partnerId = couple.UserAId == userId ? couple.UserBId : couple.UserAId;
        var partnerAnswered = partnerId is { } other && await db.DailyAnswers.AsNoTracking().AnyAsync(item => item.DailyQuestionId == question.Id && item.UserId == other, cancellationToken);
        var names = await NamesAsync(couple, cancellationToken);
        Notification? notification = null;
        if (firstAnswer && partnerId is { } recipient)
        {
            var me = names.GetValueOrDefault(userId, "Your partner");
            notification = Notification.Create(recipient, couple.Id, NotificationType.DailyQuestionAnswered,
                partnerAnswered ? "You can both see your answers" : "Your turn",
                partnerAnswered ? $"{me} answered today's question too." : $"{me} answered today's question. Answer yours to see theirs.",
                JsonSerializer.Serialize(new { dailyQuestionId = question.Id }), now);
            db.Notifications.Add(notification);
        }

        try { await db.SaveChangesAsync(cancellationToken); }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            // A double-submitted first answer: the other request already created it. Report the stored state.
            logger.LogInformation("Duplicate first answer for question {QuestionId} by user {UserId}; returning stored state.", question.Id, userId);
            return await CurrentStateAsync(couple, question, cancellationToken);
        }

        if (notification is not null) await ExerciseEvents.NotificationAsync(hub, notification, cancellationToken);
        if (firstAnswer)
            await hub.Clients.Group(CoupleHub.GroupName(couple.Id)).SendAsync(AnsweredEvent,
                new DailyQuestionAnsweredDto(question.Id, question.LocalDate.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture), userId, partnerAnswered), cancellationToken);
        return await CurrentStateAsync(couple, question, cancellationToken);
    }

    /// <summary>Past questions, newest first. My partner's answers show only where I answered too.</summary>
    [HttpGet("history")]
    public async Task<ActionResult<DailyQuestionHistoryDto>> History([FromQuery] int days = 30, [FromQuery] string? date = null, CancellationToken cancellationToken = default)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        // Free couples look back a week; Plus couples get the full archive window.
        var maxDays = (await entitlements.ForUserAsync(currentUser.UserId.Value, cancellationToken)).Limits.QuestionHistoryDays;
        days = Math.Clamp(days, 1, Math.Min(90, maxDays));
        var couple = await ActiveCoupleAsync(cancellationToken);
        if (couple is null) return Ok(new DailyQuestionHistoryDto(days, 0, []));
        var today = ResolveDate(date) ?? DateOnly.FromDateTime(clock.UtcNow);
        var since = today.AddDays(-days);
        var questions = await db.DailyQuestions.AsNoTracking()
            .Where(item => item.CoupleId == couple.Id && item.LocalDate > since && item.LocalDate <= today)
            .OrderByDescending(item => item.LocalDate)
            .ToListAsync(cancellationToken);
        var ids = questions.Select(item => item.Id).ToList();
        var answers = await db.DailyAnswers.AsNoTracking().Where(item => ids.Contains(item.DailyQuestionId)).ToListAsync(cancellationToken);
        var names = await NamesAsync(couple, cancellationToken);
        var streak = await StreakAsync(couple.Id, today, cancellationToken);
        var items = questions
            .Select(question => DailyQuestionMapper.ToDto(question, currentUser.UserId.Value, answers.Where(item => item.DailyQuestionId == question.Id).ToList(), names, streak))
            .ToList();
        return Ok(new DailyQuestionHistoryDto(days, streak, items));
    }

    private async Task<ActionResult<DailyQuestionDto>> CurrentStateAsync(Couple couple, DailyQuestion question, CancellationToken cancellationToken)
    {
        var answers = await db.DailyAnswers.AsNoTracking().Where(item => item.DailyQuestionId == question.Id).ToListAsync(cancellationToken);
        var streak = await StreakAsync(couple.Id, question.LocalDate, cancellationToken);
        return Ok(DailyQuestionMapper.ToDto(question, currentUser.UserId!.Value, answers, await NamesAsync(couple, cancellationToken), streak));
    }

    /// <summary>Reads the day's question, creating it on first read. Two first reads racing both end up on the same row.</summary>
    private async Task<DailyQuestion> EnsureQuestionAsync(Guid coupleId, DateOnly localDate, CancellationToken cancellationToken)
    {
        var existing = await db.DailyQuestions.AsNoTracking().SingleOrDefaultAsync(item => item.CoupleId == coupleId && item.LocalDate == localDate, cancellationToken);
        if (existing is not null) return existing;

        var lastUsed = await db.DailyQuestions.AsNoTracking()
            .Where(item => item.CoupleId == coupleId)
            .GroupBy(item => item.QuestionKey)
            .Select(group => new { Key = group.Key, Last = group.Max(item => item.LocalDate) })
            .ToDictionaryAsync(item => item.Key, item => item.Last, cancellationToken);
        var key = DailyQuestionPicker.Pick(coupleId, DailyQuestionCatalog.All.Select(item => item.Key), lastUsed);
        var question = DailyQuestion.Create(coupleId, localDate, key, clock.UtcNow);
        db.DailyQuestions.Add(question);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return question;
        }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            db.DailyQuestions.Entry(question).State = EntityState.Detached;
            return await db.DailyQuestions.AsNoTracking().SingleAsync(item => item.CoupleId == coupleId && item.LocalDate == localDate, cancellationToken);
        }
    }

    private async Task<int> StreakAsync(Guid coupleId, DateOnly today, CancellationToken cancellationToken)
    {
        // Days where two answers exist. The unique (question, user) index means two rows are two people.
        var bothAnswered = await (
            from question in db.DailyQuestions.AsNoTracking()
            where question.CoupleId == coupleId && question.LocalDate <= today
            join answer in db.DailyAnswers.AsNoTracking() on question.Id equals answer.DailyQuestionId
            group answer by question.LocalDate into day
            where day.Count() >= 2
            select day.Key).ToListAsync(cancellationToken);
        return DailyQuestionStreak.CurrentStreakDays(bothAnswered, today);
    }

    private DateOnly? ResolveDate(string? date)
    {
        var utcToday = DateOnly.FromDateTime(clock.UtcNow);
        if (string.IsNullOrWhiteSpace(date)) return utcToday;
        if (!DateOnly.TryParseExact(date.Trim(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var day)) return null;
        return Math.Abs(day.DayNumber - utcToday.DayNumber) <= 1 ? day : null;
    }

    private async Task<Dictionary<Guid, string>> NamesAsync(Couple couple, CancellationToken cancellationToken)
    {
        var ids = couple.UserBId is { } b ? new[] { couple.UserAId, b } : [couple.UserAId];
        return await db.Users.AsNoTracking().Where(user => ids.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.DisplayName, cancellationToken);
    }

    private async Task<Couple?> ActiveCoupleAsync(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return null;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        return couple is not null && (couple.UserAId == currentUser.UserId.Value || couple.UserBId == currentUser.UserId.Value) ? couple : null;
    }
}
