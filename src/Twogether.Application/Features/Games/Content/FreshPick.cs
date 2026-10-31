using System.Security.Cryptography;
using System.Text.Json;

namespace Twogether.Application.Features.Games.Content;

/// <summary>
/// Drawing a game's content from a bank: never the same item twice in a game, and, where the bank allows, nothing the
/// couple met in their last few games of the same kind (<see cref="GameSetupContext.RecentSetups"/>).
/// </summary>
public static class FreshPick
{
    /// <summary>
    /// <paramref name="count"/> different items in random order, those whose <paramref name="key"/> is in
    /// <paramref name="seen"/> only once the fresh ones run out.
    /// </summary>
    public static IReadOnlyList<T> Pick<T>(IReadOnlyList<T> bank, int count, Func<T, string> key, IReadOnlySet<string>? seen = null)
    {
        var copy = bank.ToArray();
        RandomNumberGenerator.Shuffle(copy.AsSpan());
        if (seen is null || seen.Count == 0) return copy.Take(count).ToList();
        return copy.Where(item => !seen.Contains(key(item))).Concat(copy.Where(item => seen.Contains(key(item)))).Take(count).ToList();
    }

    /// <summary>
    /// The texts found in earlier setups under <paramref name="property"/>: an array of strings, or of objects whose
    /// <paramref name="field"/> is the text. A setup that doesn't read is skipped.
    /// </summary>
    public static IReadOnlySet<string> Seen(IEnumerable<string>? setups, string property, string? field = null)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var setup in setups ?? [])
        {
            if (string.IsNullOrWhiteSpace(setup)) continue;
            try
            {
                using var document = JsonDocument.Parse(setup);
                if (document.RootElement.ValueKind != JsonValueKind.Object || !document.RootElement.TryGetProperty(property, out var items) || items.ValueKind != JsonValueKind.Array) continue;
                foreach (var item in items.EnumerateArray())
                {
                    var text = field is null
                        ? item.ValueKind == JsonValueKind.String ? item.GetString() : null
                        : item.ValueKind == JsonValueKind.Object && item.TryGetProperty(field, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
                    if (!string.IsNullOrWhiteSpace(text)) seen.Add(text);
                }
            }
            catch (JsonException) { }
        }
        return seen;
    }
}
