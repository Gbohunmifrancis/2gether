using System.Globalization;
using Twogether.Core.Entities;
using Twogether.Core.Questions;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Questions;

public sealed record DailyAnswerDto(Guid UserId, string UserName, string Body, DateTime AnsweredAtUtc, DateTime UpdatedAtUtc);

public sealed record DailyQuestionDto(
    Guid Id,
    string LocalDate,
    string Key,
    string Category,
    string Text,
    DailyAnswerDto? MyAnswer,
    bool PartnerAnswered,
    /// <summary>Null until I have answered, whatever my partner wrote. The server enforces this.</summary>
    DailyAnswerDto? PartnerAnswer,
    int StreakDays);

public sealed record DailyQuestionHistoryDto(int Days, int StreakDays, IReadOnlyList<DailyQuestionDto> Items);

public sealed record AnswerDailyQuestionRequest(string Body);

/// <summary>Couple-group event. No answer text: the partner's app refetches and the reveal rule applies.</summary>
public sealed record DailyQuestionAnsweredDto(Guid DailyQuestionId, string LocalDate, Guid UserId, bool BothAnswered);

public static class DailyQuestionMapper
{
    public static string RetiredText => Loc.T("This question is no longer in the bank.");

    /// <summary>Answer to unlock: the partner's answer is included only when the viewer has answered.</summary>
    public static DailyQuestionDto ToDto(DailyQuestion question, Guid viewerId, IReadOnlyList<DailyAnswer> answers, IReadOnlyDictionary<Guid, string> names, int streakDays)
    {
        var definition = DailyQuestionCatalog.Find(question.QuestionKey);
        var mine = answers.FirstOrDefault(answer => answer.UserId == viewerId);
        var theirs = answers.FirstOrDefault(answer => answer.UserId != viewerId);
        return new DailyQuestionDto(
            question.Id,
            question.LocalDate.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            question.QuestionKey,
            definition?.Category ?? "retired",
            definition is null ? RetiredText : Loc.T(definition.Text),
            mine is null ? null : ToDto(mine, names),
            theirs is not null,
            mine is not null && theirs is not null ? ToDto(theirs, names) : null,
            streakDays);
    }

    private static DailyAnswerDto ToDto(DailyAnswer answer, IReadOnlyDictionary<Guid, string> names)
        => new(answer.UserId, (names.GetValueOrDefault(answer.UserId) ?? Loc.T("Your partner")), answer.Body, answer.CreatedAtUtc, answer.UpdatedAtUtc);
}
