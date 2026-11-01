using Anthropic;
using Twogether.Application.Features.Food;

namespace Twogether.Infrastructure.Ai;

/// <summary>
/// How every Claude client on the server is made: the one key (<c>Food:AnthropicApiKey</c>, else <c>ANTHROPIC_API_KEY</c>),
/// a timeout per attempt, and the SDK's retries with backoff on 429, 5xx and dropped connections. The food estimator and
/// the AI features (<see cref="AnthropicClaudeClient"/>) both build theirs here.
/// </summary>
public static class AnthropicClients
{
    public const int DefaultMaxRetries = 2;

    public static string? ApiKey(FoodSettings settings)
        => string.IsNullOrWhiteSpace(settings.AnthropicApiKey) ? Environment.GetEnvironmentVariable("ANTHROPIC_API_KEY") : settings.AnthropicApiKey;

    /// <summary>Null when no key is configured: the features that need it say so, or hide.</summary>
    public static AnthropicClient? Create(FoodSettings settings, int timeoutSeconds, int maxRetries = DefaultMaxRetries)
    {
        var apiKey = ApiKey(settings);
        if (string.IsNullOrWhiteSpace(apiKey)) return null;
        return new AnthropicClient
        {
            ApiKey = apiKey,
            Timeout = TimeSpan.FromSeconds(Math.Clamp(timeoutSeconds, 5, 300)),
            MaxRetries = Math.Clamp(maxRetries, 0, 5)
        };
    }
}
