using Twogether.Core.Enums;

namespace Twogether.Application.Features.Ai;

/// <summary>
/// <c>GET api/ai/status</c>: which AI features this person may use right now. A feature is on only when the server has
/// a key and the admin switch is on; the app hides whatever is off. The counts are what is left of today's caps.
/// </summary>
public sealed record AiStatusDto(bool DareIdeas, bool Coach, bool WeeklyRecap, bool Planner, int DareIdeasLeft, int PlansLeft);

/// <summary><c>POST api/ai/dare-ideas</c>: ideas for a dare for this member of the couple (usually the partner).</summary>
public sealed record DareIdeasRequest(Guid AssignedUserId);

/// <param name="ScheduledTime">"HH:mm", 24-hour, in the assignee's day.</param>
/// <param name="Reason">One playful line, in the asker's language.</param>
public sealed record DareIdeaDto(ExerciseType ExerciseType, int Target, ExerciseMeasure Measure, string ScheduledTime, string Reason);

public sealed record DareIdeasDto(IReadOnlyList<DareIdeaDto> Ideas, int Left);

public enum PlanMood { Cosy, Active, Adventurous, Romantic }
public enum PlanBudget { Free, Low, Treat }
public enum PlanSetting { In, Out }

/// <summary><c>POST api/ai/plan</c>. The city is optional, typed by the person, and only used for going out.</summary>
public sealed record PlanRequest(PlanMood Mood, PlanBudget Budget, PlanSetting Setting, string? City = null);

public sealed record DateIdeaDto(string Title, string Why);
public sealed record GameIdeaDto(GameType GameType, string Why);
public sealed record PlanDto(IReadOnlyList<DateIdeaDto> Dates, IReadOnlyList<GameIdeaDto> Games, int Left);

/// <summary>
/// <c>GET api/ai/coach/{occurrenceId}</c>. <c>state</c> is <c>ready</c> (with the text), <c>pending</c> (being written:
/// ask again in a couple of seconds) or <c>unavailable</c> (it couldn't be written; show nothing).
/// </summary>
public sealed record CoachNoteDto(Guid OccurrenceId, string State, string? Text, Guid UserId);

/// <summary>The week's counts, for the card and the full view. Each partner's own numbers come as me and partner.</summary>
public sealed record WeeklyRecapStatsDto(
    int HeartsMade,
    int MyDaresDone,
    int PartnerDaresDone,
    int DaresMissed,
    int MyStreakDays,
    int PartnerStreakDays,
    int GamesPlayed,
    int MyGamesWon,
    int PartnerGamesWon,
    int PhotosAdded,
    int QuestionsAnswered);

/// <summary><c>GET api/ai/recap</c>: the couple's latest weekly recap, written for the reader in their language.</summary>
public sealed record WeeklyRecapDto(
    Guid Id,
    /// <summary>"yyyy-MM-dd": the Sunday the week ended on.</summary>
    string WeekEnding,
    DateTime PeriodStartUtc,
    DateTime PeriodEndUtc,
    string Title,
    IReadOnlyList<string> Lines,
    string Suggestion,
    WeeklyRecapStatsDto Stats);
