using System.Text.Json;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Games;

/// <summary>Whose content a game uses (docs/games.md): Nigerian couples get Naija trivia, words and pictures.</summary>
public static class GameRegion
{
    public const string Nigeria = "ng";
    public const string Global = "global";

    /// <summary>A couple plays the Nigerian versions when either partner lives in Nigeria.</summary>
    public static string For(IEnumerable<string?> countryCodes)
        => countryCodes.Any(code => string.Equals(code, "NG", StringComparison.OrdinalIgnoreCase)) ? Nigeria : Global;

    /// <summary>
    /// What of a setup both partners may see: its region and an I call on letter. The rest (the words to draw, the
    /// questions) can be one partner's secret, so it stays on the server.
    /// </summary>
    public static string PublicSetup(string? setupJson)
    {
        string? letter = null;
        if (!string.IsNullOrWhiteSpace(setupJson))
        {
            try
            {
                using var setup = JsonDocument.Parse(setupJson);
                if (setup.RootElement.TryGetProperty("letter", out var value) && value.ValueKind == JsonValueKind.String) letter = value.GetString();
            }
            catch (JsonException) { }
        }
        return letter is null ? JsonSerializer.Serialize(new { region = Of(setupJson) }) : JsonSerializer.Serialize(new { region = Of(setupJson), letter });
    }

    /// <summary>The region a game was set up with; "global" for games set up before regions existed.</summary>
    public static string Of(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return Global;
        try
        {
            using var setup = JsonDocument.Parse(setupJson);
            return setup.RootElement.TryGetProperty("region", out var region) && region.GetString() == Nigeria ? Nigeria : Global;
        }
        catch (JsonException)
        {
            return Global;
        }
    }
}

public interface IGameSetupFactory
{
    /// <summary>The setup a new game starts from: its region, plus anything fetched or chosen up front.</summary>
    Task<string> CreateAsync(GameType gameType, string region, string? letter, CancellationToken cancellationToken);
}

/// <summary>One case per game that needs more than its region; the rest get just the region.</summary>
public sealed class GameSetupFactory(IGameQuestionProvider questions) : IGameSetupFactory
{
    public async Task<string> CreateAsync(GameType gameType, string region, string? letter, CancellationToken cancellationToken) => gameType switch
    {
        GameType.ICallOn => JsonSerializer.Serialize(new { region, letter = letter?.ToUpperInvariant() }),
        GameType.MemoryMatch => JsonSerializer.Serialize(new { region }),
        GameType.TruthOrDare => JsonSerializer.Serialize(new { region }),
        GameType.WordDash => JsonSerializer.Serialize(new { region }),
        GameType.CoupleQuiz => SameAnswerRules.CreateSetup(region),
        GameType.GuessMyAnswer => GuessMyAnswerRules.CreateSetup(region),
        GameType.WouldYouRather => WouldYouRatherRules.CreateSetup(region),
        GameType.TicTacToe or GameType.ConnectFour => JsonSerializer.Serialize(new { region }),
        // The words to draw are the drawer's secret: they stay in the setup on the server (GameRegion.PublicSetup).
        GameType.Handwriting => JsonSerializer.Serialize(new { region, words = Content.DrawAndGuessWords.Pick(region, DrawAndGuessRules.Rounds) }),
        GameType.SkillDuel => SkillDuelRules.CreateSetup(region),
        GameType.SpotTheDifference => SpotTheDifferenceRules.CreateSetup(region),
        GameType.Trivia => JsonSerializer.Serialize(new { region, trivia = await TriviaRules.QuestionsFor(region, questions, cancellationToken) }),
        _ => JsonSerializer.Serialize(new { region, questions = await questions.GetQuestionsAsync(cancellationToken) }),
    };
}
