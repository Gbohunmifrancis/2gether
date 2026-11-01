using System.Globalization;
using System.Text;
using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Ai;

/// <summary>A recap as written for one reader.</summary>
public sealed record RecapText(string Title, IReadOnlyList<string> Lines, string Suggestion);

/// <summary>
/// Turns the model's JSON into something safe to show. Strict and pure: a suggestion outside the catalogs, a target out
/// of range, a bad time or an empty line is dropped; text is trimmed to one line and a sane length. Nothing the model
/// says goes anywhere unchecked.
/// </summary>
public static class AiParsers
{
    public const int MaxIdeas = 3;
    public const int MaxReasonLength = 120;
    public const int MaxDateTitleLength = 60;
    public const int MaxRecapTitleLength = 60;
    public const int MaxRecapLineLength = 120;
    public const int MaxSuggestionLength = 160;

    public static Result<IReadOnlyList<DareIdeaDto>> DareIdeas(string json)
    {
        var root = Root(json);
        if (root is not { } value || !value.TryGetProperty("ideas", out var ideas) || ideas.ValueKind != JsonValueKind.Array) return Unreadable<IReadOnlyList<DareIdeaDto>>();
        var kept = new List<DareIdeaDto>();
        foreach (var idea in ideas.EnumerateArray())
        {
            if (kept.Count == MaxIdeas) break;
            if (idea.ValueKind != JsonValueKind.Object) continue;
            if (!AiCatalogs.TryExercise(String(idea, "exercise_type"), out var type)) continue;
            var definition = ExerciseCatalog.Get(type);
            if (!idea.TryGetProperty("target", out var targetElement) || targetElement.ValueKind != JsonValueKind.Number || !targetElement.TryGetInt32(out var target)) continue;
            if (target < definition.MinTarget || target > definition.MaxTarget) continue;
            if (!TimeOnly.TryParseExact(String(idea, "time")?.Trim(), "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out var time)) continue;
            var reason = Line(String(idea, "reason"), MaxReasonLength);
            if (reason is null) continue;
            var scheduled = time.ToString("HH:mm", CultureInfo.InvariantCulture);
            if (kept.Any(item => item.ExerciseType == type && item.Target == target && item.ScheduledTime == scheduled)) continue;
            kept.Add(new DareIdeaDto(type, target, definition.Measure, scheduled, reason));
        }
        return kept.Count == 0 ? Unreadable<IReadOnlyList<DareIdeaDto>>() : kept;
    }

    public static Result<(IReadOnlyList<DateIdeaDto> Dates, IReadOnlyList<GameIdeaDto> Games)> Plan(string json)
    {
        var root = Root(json);
        if (root is not { } value) return Unreadable<(IReadOnlyList<DateIdeaDto>, IReadOnlyList<GameIdeaDto>)>();
        var dates = new List<DateIdeaDto>();
        if (value.TryGetProperty("dates", out var dateList) && dateList.ValueKind == JsonValueKind.Array)
            foreach (var date in dateList.EnumerateArray())
            {
                if (dates.Count == MaxIdeas) break;
                if (date.ValueKind != JsonValueKind.Object) continue;
                var title = Line(String(date, "title"), MaxDateTitleLength);
                var why = Line(String(date, "why"), MaxReasonLength);
                if (title is null || why is null || dates.Any(item => string.Equals(item.Title, title, StringComparison.OrdinalIgnoreCase))) continue;
                dates.Add(new DateIdeaDto(title, why));
            }
        var games = new List<GameIdeaDto>();
        if (value.TryGetProperty("games", out var gameList) && gameList.ValueKind == JsonValueKind.Array)
            foreach (var game in gameList.EnumerateArray())
            {
                if (games.Count == 2) break;
                if (game.ValueKind != JsonValueKind.Object || !AiCatalogs.TryGame(String(game, "game_type"), out var type)) continue;
                var why = Line(String(game, "why"), MaxReasonLength);
                if (why is null || games.Any(item => item.GameType == type)) continue;
                games.Add(new GameIdeaDto(type, why));
            }
        return dates.Count == 0 ? Unreadable<(IReadOnlyList<DateIdeaDto>, IReadOnlyList<GameIdeaDto>)>() : (dates, games);
    }

    public static Result<string> Coach(string json)
    {
        var root = Root(json);
        var note = root is { } value ? Line(String(value, "note"), 400) : null;
        // A note shorter than a few words isn't a note.
        return note is null || note.Length < 12 ? Unreadable<string>() : note;
    }

    public static Result<RecapText> Recap(string json)
    {
        var root = Root(json);
        if (root is not { } value) return Unreadable<RecapText>();
        var title = Line(String(value, "title"), MaxRecapTitleLength);
        var suggestion = Line(String(value, "suggestion"), MaxSuggestionLength);
        var lines = new List<string>();
        if (value.TryGetProperty("lines", out var lineList) && lineList.ValueKind == JsonValueKind.Array)
            foreach (var line in lineList.EnumerateArray())
            {
                if (lines.Count == 4) break;
                if (line.ValueKind == JsonValueKind.String && Line(line.GetString(), MaxRecapLineLength) is { } clean) lines.Add(clean);
            }
        return title is null || suggestion is null || lines.Count == 0 ? Unreadable<RecapText>() : new RecapText(title, lines, suggestion);
    }

    public const int MaxCityLength = 60;

    /// <summary>
    /// The city typed for Plan something: optional, at most 60 characters of letters (any script), spaces and the
    /// punctuation place names use. Anything else is refused rather than sent.
    /// </summary>
    public static Result<string?> City(string? city)
    {
        var clean = Line(city, int.MaxValue);
        if (clean is null) return Result.Success<string?>(null);
        var fits = clean.Length <= MaxCityLength && clean.All(c => char.IsLetter(c) || char.GetUnicodeCategory(c) is UnicodeCategory.NonSpacingMark or UnicodeCategory.SpacingCombiningMark
            || c is ' ' or '-' or '\'' or '’' or '.' or ',' or '(' or ')');
        return fits ? clean : Result.Failure<string?>(Error.Validation("ai.city_invalid", Loc.T("Type just the name of a city or town.")));
    }

    /// <summary>
    /// One line of text: control characters and line breaks become spaces, runs of spaces collapse, and anything longer than
    /// <paramref name="max"/> is cut at a word and ends with an ellipsis. Empty is null.
    /// </summary>
    public static string? Line(string? text, int max)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        var builder = new StringBuilder(text.Length);
        var space = false;
        foreach (var c in text)
        {
            var blank = char.IsWhiteSpace(c) || char.IsControl(c);
            if (blank) { space = builder.Length > 0; continue; }
            if (space) builder.Append(' ');
            space = false;
            builder.Append(c);
        }
        var clean = builder.ToString();
        if (clean.Length == 0) return null;
        if (clean.Length <= max) return clean;
        var cut = clean[..(max - 1)];
        var lastSpace = cut.LastIndexOf(' ');
        if (lastSpace > max / 2) cut = cut[..lastSpace];
        return cut.TrimEnd(' ', ',', ';', ':', '-') + "…";
    }

    private static JsonElement? Root(string json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try
        {
            using var document = JsonDocument.Parse(json.Trim());
            return document.RootElement.ValueKind == JsonValueKind.Object ? document.RootElement.Clone() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string? String(JsonElement element, string name)
        => element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static Result<T> Unreadable<T>()
        => Result.Failure<T>(Error.Failure("ai.unreadable", Loc.T("That didn't come out right. Try again in a moment.")));
}
