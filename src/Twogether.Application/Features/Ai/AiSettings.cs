namespace Twogether.Application.Features.Ai;

/// <summary>
/// Bound from the "Ai" configuration section (docs/ai.md). The API key, the model and the request timeout are the food
/// estimator's (<c>Food:AnthropicApiKey</c> or <c>ANTHROPIC_API_KEY</c>, <c>Food:Model</c>): one key, one model.
/// </summary>
public sealed class AiSettings
{
    /// <summary>"Suggest a dare" requests per person per UTC day.</summary>
    public int DareIdeasPerDay { get; init; } = 10;
    /// <summary>"Plan something" requests per person per UTC day.</summary>
    public int PlansPerDay { get; init; } = 5;
    /// <summary>Coach notes written for one person per UTC day (each is once per finished dare anyway).</summary>
    public int CoachNotesPerDay { get; init; } = 20;
    /// <summary>A whole call, the SDK's own retries included; under the app's 30-second read timeout.</summary>
    public int TimeoutSeconds { get; init; } = 25;
    /// <summary>Retries on 429, 5xx and dropped connections, with the SDK's backoff (it honours retry-after).</summary>
    public int MaxRetries { get; init; } = 2;
    /// <summary>A coach note is written only for a dare finished this recently, so old history never runs up a bill.</summary>
    public int CoachMaxAgeDays { get; init; } = 7;
}
