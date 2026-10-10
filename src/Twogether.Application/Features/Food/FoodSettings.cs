namespace Twogether.Application.Features.Food;

/// <summary>Bound from the "Food" configuration section; the API key may also come from ANTHROPIC_API_KEY.</summary>
public sealed class FoodSettings
{
    public string Model { get; init; } = "claude-opus-5-5";
    public string? AnthropicApiKey { get; init; }
    public int MaxImageBytes { get; init; } = 5 * 1024 * 1024;
    public int TimeoutSeconds { get; init; } = 45;
}
