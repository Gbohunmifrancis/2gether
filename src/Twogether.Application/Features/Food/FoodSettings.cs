namespace Twogether.Application.Features.Food;

/// <summary>Bound from the "Food" configuration section; the API key may also come from ANTHROPIC_API_KEY.</summary>
public sealed class FoodSettings
{
    public string Model { get; init; } = "claude-opus-5-5";
    public string? AnthropicApiKey { get; init; }
    public int MaxImageBytes { get; init; } = 5 * 1024 * 1024;

    /// <summary>
    /// The scan request body: the image base64-encoded (4 bytes per 3) inside a small JSON
    /// envelope. Every other endpoint takes a few KB; this one alone may take this much.
    /// </summary>
    public const long MaxScanRequestBytes = 8 * 1024 * 1024;

    /// <summary>Whether an image of this size still fits in a scan request once encoded.</summary>
    public static bool FitsInRequest(int imageBytes) => (imageBytes + 2L) / 3 * 4 + 64 * 1024 <= MaxScanRequestBytes;
    public int TimeoutSeconds { get; init; } = 45;
}
