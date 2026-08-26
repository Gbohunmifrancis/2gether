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
}
