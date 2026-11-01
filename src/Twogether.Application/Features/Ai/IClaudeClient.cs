using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Ai;

/// <param name="Feature">Which feature asks, for the logs.</param>
/// <param name="System">The instructions: fixed text per feature.</param>
/// <param name="User">The facts, as JSON built by <see cref="AiPrompts"/>: only what docs/ai.md says may be sent.</param>
/// <param name="JsonSchema">The answer's shape; the API holds the model to it, and the parsers check it again.</param>
public sealed record ClaudeRequest(AiFeature Feature, string System, string User, string JsonSchema, int MaxTokens = 1024);

/// <summary>The model's JSON, unchecked, and what the call cost.</summary>
public sealed record ClaudeReply(string Json, int InputTokens, int OutputTokens);

/// <summary>
/// The one way the AI features reach Claude: the server's key, a timeout, retries with backoff on 429 and 5xx, and a
/// JSON answer constrained to a schema. Failures are <see cref="Result"/>s with an <c>ai.*</c> code, never exceptions.
/// </summary>
public interface IClaudeClient
{
    bool IsConfigured { get; }
    string ModelName { get; }
    Task<Result<ClaudeReply>> CompleteJsonAsync(ClaudeRequest request, CancellationToken cancellationToken);
}
