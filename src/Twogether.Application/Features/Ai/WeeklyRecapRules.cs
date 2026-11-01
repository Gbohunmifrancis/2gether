using Twogether.Application.Features.Exercise;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Ai;

/// <param name="WeekEnding">The local Sunday the week ends on.</param>
/// <param name="PeriodStartUtc">The Sunday before, at 18:00 local.</param>
/// <param name="PeriodEndUtc">This Sunday, at 18:00 local.</param>
public sealed record RecapWeek(DateOnly WeekEnding, DateTime PeriodStartUtc, DateTime PeriodEndUtc);

/// <summary>One partner's week.</summary>
public sealed record RecapPersonStats(Guid UserId, int DaresDone, int DaresMissed, int DaresCalledOff, int StreakDays, int GamesWon, int QuestionsAnswered, IReadOnlyList<string> Exercises);

/// <summary>The couple's week, as counted (and stored with the recap).</summary>
public sealed record WeeklyRecapStats(int HeartsMade, int GamesPlayed, int PhotosAdded, int QuestionsAnswered, IReadOnlyList<RecapPersonStats> People)
{
    /// <summary>Anything at all happened: a dare done, missed or called off, a game, a photo or an answer. A streak alone isn't news.</summary>
    public bool HasActivity => HeartsMade + GamesPlayed + PhotosAdded + QuestionsAnswered > 0
        || People.Any(person => person.DaresDone + person.DaresMissed + person.DaresCalledOff > 0);
}

/// <summary>The recap one partner reads, in their language.</summary>
public sealed record WeeklyRecapNote(Guid UserId, string Language, string Title, IReadOnlyList<string> Lines, string Suggestion);

/// <summary>
/// When a couple's week ends: Sunday at 18:00 in the couple's time zone, the seven days before it counted. The recap is
/// written then, or within a day after if the server was down; a later week never back-fills an older one.
/// </summary>
public static class WeeklyRecapRules
{
    public static readonly TimeOnly SendAt = new(18, 0);
    public static readonly TimeSpan CatchUp = TimeSpan.FromHours(24);

    /// <summary>The couple's zone: the first partner's saved zone, else the second's, else UTC.</summary>
    public static string CoupleZone(string? first, string? second)
        => ExerciseGoalRules.IsSavedZone(first) ? first!.Trim() : ExerciseGoalRules.IsSavedZone(second) ? second!.Trim() : "UTC";

    /// <summary>The latest week whose Sunday 18:00 has passed at <paramref name="nowUtc"/>.</summary>
    public static RecapWeek LatestEnded(DateTime nowUtc, TimeZoneInfo zone)
    {
        var today = ExerciseSchedule.LocalDate(nowUtc, zone);
        var sunday = today.AddDays(-(int)today.DayOfWeek);
        var end = ExerciseSchedule.ToUtc(sunday, SendAt, zone);
        if (end > nowUtc)
        {
            sunday = sunday.AddDays(-7);
            end = ExerciseSchedule.ToUtc(sunday, SendAt, zone);
        }
        return new RecapWeek(sunday, ExerciseSchedule.ToUtc(sunday.AddDays(-7), SendAt, zone), end);
    }

    /// <summary>Due from the week's end until <see cref="CatchUp"/> after it.</summary>
    public static bool IsDue(RecapWeek week, DateTime nowUtc) => nowUtc >= week.PeriodEndUtc && nowUtc - week.PeriodEndUtc < CatchUp;

    /// <summary>
    /// A cheap first check for the worker: Sunday 18:00 happens somewhere (UTC+14 to UTC−12) between Sunday 04:00 and
    /// Monday 06:00 UTC, and the catch-up day runs to Tuesday 06:00 UTC. The rest of the week there is nothing to do.
    /// </summary>
    public static bool MaybeDueAnywhere(DateTime nowUtc)
    {
        var minutes = (int)nowUtc.DayOfWeek * 24 * 60 + nowUtc.Hour * 60 + nowUtc.Minute;
        return minutes >= 4 * 60 && minutes < (2 * 24 + 6) * 60;
    }
}

/// <summary>A weekly recap in someone's data export: only the one written for them.</summary>
public static class WeeklyRecapExport
{
    public static object? For(Twogether.Core.Entities.WeeklyRecap recap, Guid userId)
    {
        var note = System.Text.Json.JsonSerializer.Deserialize<List<WeeklyRecapNote>>(recap.NotesJson, new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web))
            ?.FirstOrDefault(item => item.UserId == userId);
        return note is null ? null : new { recap.WeekEnding, recap.PeriodStartUtc, recap.PeriodEndUtc, note.Language, note.Title, note.Lines, note.Suggestion };
    }
}
