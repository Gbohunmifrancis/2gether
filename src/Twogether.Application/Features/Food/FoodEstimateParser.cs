using System.Text.Json;
using Twogether.Core.Entities;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Food;

/// <summary>
/// Turns the model's JSON into a FoodEstimate. Pure and strict: anything the model gets
/// wrong (missing fields, a range out of order, grams for a glass of water) is clamped or
/// rejected here so the rest of the app never sees a nonsensical number.
/// </summary>
public static class FoodEstimateParser
{
    public const string JsonSchema = """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["is_food", "description", "calories", "calories_low", "calories_high", "protein_g", "carbs_g", "fat_g", "confidence", "items"],
          "properties": {
            "is_food": { "type": "boolean", "description": "False when the photo does not show food or drink." },
            "description": { "type": "string", "description": "Short name of the meal, at most 80 characters, e.g. 'Jollof rice with grilled chicken'." },
            "calories": { "type": "integer", "description": "Best single estimate of total kilocalories in the photo." },
            "calories_low": { "type": "integer", "description": "Plausible lower bound in kilocalories." },
            "calories_high": { "type": "integer", "description": "Plausible upper bound in kilocalories." },
            "protein_g": { "type": "number" },
            "carbs_g": { "type": "number" },
            "fat_g": { "type": "number" },
            "confidence": { "type": "number", "description": "0 to 1: how sure the identification and portion size are." },
            "items": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["name", "portion", "calories"],
                "properties": {
                  "name": { "type": "string" },
                  "portion": { "type": "string", "description": "Estimated portion, e.g. '1 cup', '150 g', 'half plate'." },
                  "calories": { "type": "integer" }
                }
              }
            }
          }
        }
        """;

    public static Result<FoodEstimate> Parse(string json, int inputTokens, int outputTokens)
    {
        if (string.IsNullOrWhiteSpace(json)) return Unreadable();
        try
        {
            using var document = JsonDocument.Parse(Trim(json));
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return Unreadable();

            var isFood = root.TryGetProperty("is_food", out var isFoodElement) && isFoodElement.ValueKind != JsonValueKind.False;
            var description = Text(root, "description", "Meal");
            if (description.Length > FoodEntry.MaxDescriptionLength) description = description[..FoodEntry.MaxDescriptionLength].TrimEnd();
            var calories = Clamp(Number(root, "calories"), 0, FoodEntry.MaxCalories);
            var low = Clamp(Number(root, "calories_low", calories), 0, FoodEntry.MaxCalories * 2);
            var high = Clamp(Number(root, "calories_high", calories), 0, FoodEntry.MaxCalories * 2);
            if (low > high) (low, high) = (high, low);
            low = Math.Min(low, calories);
            high = Math.Max(high, calories);
            var confidence = Math.Clamp(Number(root, "confidence", 0.5), 0, 1);
            var items = new List<FoodEstimateItem>();
            if (root.TryGetProperty("items", out var itemsElement) && itemsElement.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in itemsElement.EnumerateArray().Take(12))
                {
                    if (item.ValueKind != JsonValueKind.Object) continue;
                    var name = Text(item, "name", "");
                    if (name.Length == 0) continue;
                    items.Add(new FoodEstimateItem(Truncate(name, 60), Truncate(Text(item, "portion", ""), 40), (int)Clamp(Number(item, "calories"), 0, FoodEntry.MaxCalories)));
                }
            }
            return new FoodEstimate(
                description,
                (int)calories,
                (int)low,
                (int)high,
                Math.Round(Math.Clamp(Number(root, "protein_g"), 0, FoodEntry.MaxGrams), 1),
                Math.Round(Math.Clamp(Number(root, "carbs_g"), 0, FoodEntry.MaxGrams), 1),
                Math.Round(Math.Clamp(Number(root, "fat_g"), 0, FoodEntry.MaxGrams), 1),
                Math.Round(confidence, 2),
                items,
                isFood,
                inputTokens,
                outputTokens);
        }
        catch (JsonException)
        {
            return Unreadable();
        }
    }

    private static Result<FoodEstimate> Unreadable() => Error.Failure("food.estimation_unreadable", "The estimate could not be read. Try another photo.");

    /// <summary>Models occasionally wrap JSON in a code fence; strip it rather than fail.</summary>
    private static string Trim(string json)
    {
        var text = json.Trim();
        if (text.StartsWith("```", StringComparison.Ordinal))
        {
            var firstLineEnd = text.IndexOf('\n');
            text = firstLineEnd < 0 ? string.Empty : text[(firstLineEnd + 1)..];
            var fence = text.LastIndexOf("```", StringComparison.Ordinal);
            if (fence >= 0) text = text[..fence];
        }
        return text.Trim();
    }

    private static string Text(JsonElement element, string name, string fallback)
        => element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(value.GetString())
            ? value.GetString()!.Trim()
            : fallback;

    private static double Number(JsonElement element, string name, double fallback = 0)
    {
        if (!element.TryGetProperty(name, out var value)) return fallback;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number) && !double.IsNaN(number) && !double.IsInfinity(number)) return number;
        if (value.ValueKind == JsonValueKind.String && double.TryParse(value.GetString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var parsed)) return parsed;
        return fallback;
    }

    private static double Clamp(double value, double min, double max) => Math.Round(Math.Clamp(value, min, max));
    private static string Truncate(string value, int max) => value.Length <= max ? value : value[..max].TrimEnd();
}
