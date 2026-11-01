using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Ai;

/// <summary>The real catalogs Claude may choose from, by their wire names, and the way back from a name to the thing.</summary>
public static class AiCatalogs
{
    /// <summary>The camelCase name the API uses on the wire ("pushUp", "iCallOn").</summary>
    public static string Wire<T>(T value) where T : struct, Enum => JsonNamingPolicy.CamelCase.ConvertName(value.ToString());

    public static IReadOnlyList<CatalogExerciseFact> Exercises { get; } = ExerciseCatalog.All
        .Select(item => new CatalogExerciseFact(Wire(item.Type), item.DisplayName, Wire(item.Measure), item.MinTarget, item.MaxTarget, item.DefaultTarget))
        .ToList();

    private static readonly Dictionary<string, ExerciseType> ExerciseByName = ExerciseCatalog.All.ToDictionary(item => Wire(item.Type), item => item.Type, StringComparer.OrdinalIgnoreCase);

    /// <summary>Only a name from the catalog; numbers and anything else are refused.</summary>
    public static bool TryExercise(string? name, out ExerciseType type)
    {
        type = default;
        return name is not null && ExerciseByName.TryGetValue(name.Trim(), out type);
    }

    /// <summary>The games a couple can play in the app, with a line about each for the model (English; it writes the why itself).</summary>
    public static IReadOnlyList<(GameType Type, string Name, string About)> Games { get; } =
    [
        (GameType.CoupleQuiz, "Same answer", "Both answer the same question about the two of you; you score when your answers match."),
        (GameType.GuessMyAnswer, "Guess my answer", "One answers about themselves, the other guesses the answer."),
        (GameType.MemoryMatch, "Memory match", "Turn cards over in turns to find the pairs."),
        (GameType.WordDash, "Word dash", "Race each other to make words from the same letters."),
        (GameType.Handwriting, "Draw and guess", "One draws a word, the other guesses it."),
        (GameType.TruthOrDare, "Truth or dare", "Gentle couple truths and dares, taking turns."),
        (GameType.ICallOn, "I call on", "Name, animal, place and thing for one letter, against the clock."),
        (GameType.WouldYouRather, "Would you rather", "Both pick between two options and see if you agree."),
        (GameType.TicTacToe, "Tic-tac-toe", "Noughts and crosses, live on both phones."),
        (GameType.ConnectFour, "Connect four", "Drop discs in turns; four in a row wins."),
        (GameType.Trivia, "Trivia", "General knowledge questions, both answering at once."),
        (GameType.SkillDuel, "Heart to heart", "Five tiny skill rounds each, scores side by side."),
        (GameType.SpotTheDifference, "Spot the difference", "Both hunt the same seven differences at once."),
        (GameType.SnakesAndLadders, "Snakes and ladders", "Roll the die, climb ladders, slide down snakes, first to 100."),
    ];

    private static readonly Dictionary<string, GameType> GameByName = Games.ToDictionary(item => Wire(item.Type), item => item.Type, StringComparer.OrdinalIgnoreCase);

    public static bool TryGame(string? name, out GameType type)
    {
        type = default;
        return name is not null && GameByName.TryGetValue(name.Trim(), out type);
    }

    /// <summary>The English name of a reply language, for the instructions; the code itself goes in the facts too.</summary>
    public static string LanguageName(string? code) => code switch
    {
        "fr" => "French",
        "pt" => "Brazilian Portuguese",
        "es" => "Latin American Spanish",
        "ar" => "Arabic",
        "hi" => "Hindi",
        _ => "English"
    };

    /// <summary>The first word of a display name: Claude gets first names only.</summary>
    public static string FirstName(string? displayName, string fallback)
    {
        var first = displayName?.Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
        return string.IsNullOrWhiteSpace(first) ? fallback : first.Length > 40 ? first[..40] : first;
    }
}
