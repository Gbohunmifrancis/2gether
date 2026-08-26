using System.Collections.Concurrent;
using System.Net;
using System.Text.Json;
using System.Net.Http;
using Twogether.Application.Features.Games;

namespace Twogether.Infrastructure.Games;

public sealed class PublicGameWordValidator(HttpClient client) : IGameWordValidator
{
    private static readonly ConcurrentDictionary<string, bool?> Cache = new(StringComparer.OrdinalIgnoreCase);

    public async Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(string stateJson, string answersJson, CancellationToken cancellationToken = default)
    {
        using var state = JsonDocument.Parse(stateJson);
        var root = state.RootElement;
        var letter = ReadString(root, "Letter", "letter") ?? string.Empty;
        var categories = ReadArray(root, "Categories", "categories");
        var answers = JsonSerializer.Deserialize<Dictionary<string, string>>(answersJson) ?? [];
        var checks = categories.Select(async category =>
        {
            var answer = answers.FirstOrDefault(item => string.Equals(item.Key, category, StringComparison.OrdinalIgnoreCase)).Value?.Trim() ?? string.Empty;
            if (string.IsNullOrWhiteSpace(answer) || !answer.StartsWith(letter, StringComparison.OrdinalIgnoreCase))
                return (Category: category, Valid: (bool?)false);
            var key = $"{category}:{answer}";
            if (!Cache.TryGetValue(key, out var valid))
            {
                valid = await CheckAsync(category, answer, cancellationToken);
                if (valid.HasValue) Cache[key] = valid;
            }
            return (Category: category, Valid: valid);
        });
        return (await Task.WhenAll(checks)).ToDictionary(item => item.Category, item => item.Valid, StringComparer.OrdinalIgnoreCase);
    }

    private async Task<bool?> CheckAsync(string category, string answer, CancellationToken cancellationToken)
    {
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(4));
            if (category.Equals("Name", StringComparison.OrdinalIgnoreCase))
            {
                using var response = await client.GetAsync($"https://api.genderize.io/?name={Uri.EscapeDataString(answer.Split(' ', StringSplitOptions.RemoveEmptyEntries)[0])}", timeout.Token);
                if (!response.IsSuccessStatusCode) return null;
                using var payload = JsonDocument.Parse(await response.Content.ReadAsStringAsync(timeout.Token));
                return payload.RootElement.TryGetProperty("count", out var count) && count.GetInt32() > 0;
            }
            if (category.Equals("Place", StringComparison.OrdinalIgnoreCase))
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, $"https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q={Uri.EscapeDataString(answer)}");
                request.Headers.UserAgent.ParseAdd("Twogether/1.0 (private couple app)");
                using var response = await client.SendAsync(request, timeout.Token);
                if (!response.IsSuccessStatusCode) return null;
                using var payload = JsonDocument.Parse(await response.Content.ReadAsStringAsync(timeout.Token));
                return payload.RootElement.ValueKind == JsonValueKind.Array && payload.RootElement.GetArrayLength() > 0;
            }

            using var dictionaryResponse = await client.GetAsync($"https://api.dictionaryapi.dev/api/v2/entries/en/{Uri.EscapeDataString(answer)}", timeout.Token);
            if (dictionaryResponse.StatusCode == HttpStatusCode.NotFound) return false;
            return dictionaryResponse.IsSuccessStatusCode ? true : null;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return null;
        }
        catch (HttpRequestException)
        {
            return null;
        }
    }

    private static string? ReadString(JsonElement root, string primary, string secondary)
        => root.TryGetProperty(primary, out var value) || root.TryGetProperty(secondary, out value) ? value.GetString() : null;

    private static IReadOnlyList<string> ReadArray(JsonElement root, string primary, string secondary)
    {
        if (!root.TryGetProperty(primary, out var value) && !root.TryGetProperty(secondary, out value)) return [];
        return value.EnumerateArray().Select(item => item.GetString()).Where(item => !string.IsNullOrWhiteSpace(item)).Cast<string>().ToList();
    }
}
