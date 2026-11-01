namespace Twogether.Application.Features.Ai;

// What may be sent to Claude, and nothing else (docs/ai.md, the privacy policy's section 6): counts, exercise names,
// targets, times, streaks, first names, the language and the account's country, and for Plan something the mood, budget,
// setting and city the person picked. Never photos, captions, messages, answers, drawings, meals, cycle, pregnancy,
// location, or a dare's own title (people can type anything there). Every prompt is one of these records as JSON, so
// adding a field here is the only way to send more.

public sealed record CatalogExerciseFact(string Type, string Name, string Measure, int Min, int Max, int Default);

/// <param name="Outcome">done, missed or called_off.</param>
/// <param name="Time">"HH:mm" when it was due, in the assignee's day.</param>
public sealed record RecentDareFact(string Date, string Exercise, int Target, string Measure, string Outcome, string Time, int Counted);

public sealed record ActiveDareFact(string Exercise, int Target, string Measure, string Time);

public sealed record DareIdeaFacts(
    string Language,
    string LanguageName,
    string? Country,
    string SetterName,
    string ForName,
    bool ForSelf,
    int StreakDays,
    IReadOnlyList<RecentDareFact> RecentDares,
    IReadOnlyList<ActiveDareFact> ActiveDares,
    IReadOnlyList<CatalogExerciseFact> Catalog);

public sealed record CameraChecksFact(int FormFaults, int TooFastReps, int CheckInsPassed, int CheckInsMissed, int RepsWhilePhoneMoved, int SecondsOutOfFrame);

/// <param name="When">early (before it rang) or while_ringing.</param>
/// <param name="SecondsPerUnit">Seconds on camera per rep (or per second held), when the camera session's start is known.</param>
/// <param name="FastestFairSecondsPerUnit">The quickest a rep can honestly take; faster ones aren't counted.</param>
public sealed record CoachFacts(
    string Language,
    string LanguageName,
    string Name,
    string Exercise,
    string Measure,
    int Target,
    int Counted,
    int? SecondsOnCamera,
    double? SecondsPerUnit,
    double FastestFairSecondsPerUnit,
    string When,
    int? MinutesEarly,
    string ScheduledTime,
    int StreakDays,
    int DaresDoneThisWeek,
    CameraChecksFact Camera);

public sealed record RecapPersonFact(int DaresDone, int DaresMissed, int DaresCalledOff, int StreakDays, int GamesWon, int QuestionsAnswered, IReadOnlyList<string> Exercises);

public sealed record RecapFacts(
    string Language,
    string LanguageName,
    string? Country,
    string ReaderName,
    string PartnerName,
    string WeekFrom,
    string WeekTo,
    int HeartsMade,
    int GamesPlayed,
    int PhotosAdded,
    int QuestionsAnswered,
    RecapPersonFact Reader,
    RecapPersonFact Partner);

public sealed record CatalogGameFact(string Type, string Name, string About, int PlayedLast30Days);

public sealed record PlanFacts(
    string Language,
    string LanguageName,
    string? Country,
    string AskerName,
    string PartnerName,
    string Mood,
    string Budget,
    string Setting,
    string? City,
    IReadOnlyList<CatalogGameFact> Games);
