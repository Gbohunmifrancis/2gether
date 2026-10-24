using System.Net;
using System.Text.Json;
using Twogether.Application.Features.Games;

namespace Twogether.Infrastructure.Games;

public sealed class OpenTriviaQuestionProvider(HttpClient client) : IGameQuestionProvider
{
    public async Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await client.GetAsync("https://opentdb.com/api.php?amount=20&type=multiple", cancellationToken);
            if (!response.IsSuccessStatusCode) return [];
            using var payload = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
            if (!payload.RootElement.TryGetProperty("results", out var results)) return [];
            return results.EnumerateArray()
                .Select(item => item.TryGetProperty("question", out var question) ? WebUtility.HtmlDecode(question.GetString()) : null)
                .Where(question => !string.IsNullOrWhiteSpace(question))
                .Cast<string>()
                .ToList();
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return []; }
        catch (HttpRequestException) { return []; }
    }

    /// <summary>
    /// Open Trivia DB's multiple-choice questions, HTML entities decoded and options shuffled. Any failure (network,
    /// rate limit, odd payload) gives an empty list: the setup then falls back to the built-in bank.
    /// </summary>
    public async Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await client.GetAsync($"https://opentdb.com/api.php?amount={amount}&type=multiple", cancellationToken);
            if (!response.IsSuccessStatusCode) return [];
            using var payload = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
            var root = payload.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return [];
            // response_code 0 is success; 1 to 5 mean too few questions, a bad token, rate limited and so on.
            if (root.TryGetProperty("response_code", out var code) && code.ValueKind == JsonValueKind.Number && code.GetInt32() != 0) return [];
            if (!root.TryGetProperty("results", out var results) || results.ValueKind != JsonValueKind.Array) return [];
            return results.EnumerateArray().Select(Read).OfType<TriviaQuestion>().Where(question => question.IsPlayable).Take(amount).ToList();
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return []; }
        catch (HttpRequestException) { return []; }
        catch (JsonException) { return []; }
    }

    private static TriviaQuestion? Read(JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object) return null;
        var text = Decode(item, "question");
        var correct = Decode(item, "correct_answer");
        if (text is null || correct is null) return null;
        if (!item.TryGetProperty("incorrect_answers", out var wrong) || wrong.ValueKind != JsonValueKind.Array) return null;
        var incorrect = wrong.EnumerateArray()
            .Select(option => option.ValueKind == JsonValueKind.String ? WebUtility.HtmlDecode(option.GetString())?.Trim() : null)
            .Where(option => !string.IsNullOrWhiteSpace(option))
            .Cast<string>()
            .ToList();
        return incorrect.Count == TriviaQuestion.OptionCount - 1 ? TriviaQuestion.Shuffled(text, correct, incorrect) : null;
    }

    private static string? Decode(JsonElement item, string name)
        => item.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String && WebUtility.HtmlDecode(value.GetString())?.Trim() is { Length: > 0 } text ? text : null;
}
