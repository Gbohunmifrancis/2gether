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
            if (string.IsNullOrWhiteSpace(answer) || !GameEngine.Fold(answer).StartsWith(GameEngine.Fold(letter), StringComparison.Ordinal))
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

    public async Task<bool?> IsWordAsync(string word, CancellationToken cancellationToken = default)
    {
        var normalized = word.Trim().ToLowerInvariant();
        if (normalized.Length < 2 || !normalized.All(char.IsAsciiLetter)) return false;
        var key = "word:" + normalized;
        if (Cache.TryGetValue(key, out var cached)) return cached;
        bool? valid;
        try
        {
            // A turn lasts 20 seconds: a slow dictionary must not eat it. No answer in time means the word stands.
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(2.5));
            using var response = await client.GetAsync("https://api.dictionaryapi.dev/api/v2/entries/en/" + Uri.EscapeDataString(normalized), timeout.Token);
            valid = response.StatusCode == HttpStatusCode.NotFound ? false : response.IsSuccessStatusCode ? true : null;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            valid = null;
        }
        catch (HttpRequestException)
        {
            valid = null;
        }
        if (valid.HasValue) Cache[key] = valid;
        return valid;
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
            if (!dictionaryResponse.IsSuccessStatusCode) return null;
            return Fits(category, await dictionaryResponse.Content.ReadAsStringAsync(timeout.Token));
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

    /// <summary>
    /// What a dictionary entry says about an answer in <paramref name="category"/>: any real word is a thing, but a food
    /// or an animal has to be described as one ("a tropical fruit", "a large mammal"). A word that is real but not
    /// described that way gets no verdict (null), so the partner judges it instead of it scoring on its own.
    /// </summary>
    public static bool? Fits(string category, string entryJson)
    {
        var hints = category.ToLowerInvariant() switch
        {
            "food" => FoodHints,
            "animal" => AnimalHints,
            _ => null,
        };
        if (hints is null) return true;
        try
        {
            using var entry = JsonDocument.Parse(entryJson);
            var definitions = new List<string>();
            Collect(entry.RootElement, definitions);
            var words = string.Join(' ', definitions).ToLowerInvariant()
                .Split([' ', ',', '.', ';', ':', '(', ')', '"', '\'', '-'], StringSplitOptions.RemoveEmptyEntries);
            return words.Any(word => hints.Contains(word) || hints.Contains(word.TrimEnd('s'))) ? true : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static void Collect(JsonElement element, List<string> definitions)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Array:
                foreach (var item in element.EnumerateArray()) Collect(item, definitions);
                break;
            case JsonValueKind.Object:
                foreach (var property in element.EnumerateObject())
                {
                    if (property.Name == "definition" && property.Value.ValueKind == JsonValueKind.String) definitions.Add(property.Value.GetString()!);
                    else if (property.Name is "meanings" or "definitions") Collect(property.Value, definitions);
                }
                break;
        }
    }

    private static readonly HashSet<string> FoodHints =
    [
        "food", "fruit", "vegetable", "dish", "eaten", "edible", "eat", "cooked", "cook", "meal", "meat", "bread", "cake", "pastry",
        "dessert", "sweet", "cheese", "sauce", "soup", "stew", "snack", "drink", "beverage", "grain", "cereal", "nut", "bean", "legume",
        "herb", "spice", "seed", "berry", "pasta", "rice", "flour", "dough", "baked", "fried", "roasted", "biscuit", "confection", "salad",
        "sandwich", "dumpling", "porridge", "juice", "tuber", "flavour", "flavor", "culinary", "cuisine",
    ];

    private static readonly HashSet<string> AnimalHints =
    [
        "animal", "mammal", "bird", "fish", "reptile", "amphibian", "insect", "creature", "species", "beast", "rodent", "primate",
        "carnivore", "herbivore", "predator", "mollusc", "mollusk", "crustacean", "serpent", "snake", "lizard", "frog", "ape", "monkey",
        "antelope", "deer", "cattle", "horse", "dog", "cat", "feline", "canine", "bovine", "equine", "whale", "spider", "worm", "beetle",
        "fowl", "poultry", "livestock", "marsupial", "arachnid", "invertebrate", "vertebrate", "pet",
    ];

    private static string? ReadString(JsonElement root, string primary, string secondary)
        => root.TryGetProperty(primary, out var value) || root.TryGetProperty(secondary, out value) ? value.GetString() : null;

    private static IReadOnlyList<string> ReadArray(JsonElement root, string primary, string secondary)
    {
        if (!root.TryGetProperty(primary, out var value) && !root.TryGetProperty(secondary, out value)) return [];
        return value.EnumerateArray().Select(item => item.GetString()).Where(item => !string.IsNullOrWhiteSpace(item)).Cast<string>().ToList();
    }
}
