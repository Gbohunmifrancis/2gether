using Twogether.Core.Users;

namespace Twogether.Application.Features.Food;

/// <summary>The text sent with a meal photo.</summary>
public static class FoodPrompt
{
    public static string UserText(string? hint, string? countryCode)
    {
        var text = "Estimate this meal. Respond with the JSON object only.";
        // A plate of jollof, biryani or feijoada is easier to name, and to portion, when the model knows where it was served.
        if (ContentRegions.Normalize(countryCode) is { } country)
            text += $" The person lives in the country with ISO 3166-1 code {country}: consider dishes, ingredients and portion sizes common there first.";
        if (!string.IsNullOrWhiteSpace(hint)) text += $" Notes from the person who ate it: {hint}";
        return text;
    }
}
