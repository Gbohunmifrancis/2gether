using System.Text.Json;
using Anthropic;
using Anthropic.Exceptions;
using Anthropic.Models.Messages;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Ai;
using Twogether.Application.Features.Food;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Infrastructure.Ai;

/// <summary>
/// The AI features' one Claude client (docs/ai.md): the food estimator's key and model, a time limit for the whole call,
/// the SDK's retries with backoff on 429 and 5xx, and a JSON answer held to the request's schema. Only token counts are
/// logged, never what was sent or written.
/// </summary>
public sealed class AnthropicClaudeClient : IClaudeClient
{
    private readonly AnthropicClient? _client;
    private readonly FoodSettings _food;
    private readonly AiSettings _settings;
    private readonly ILogger<AnthropicClaudeClient> _logger;

    public AnthropicClaudeClient(FoodSettings food, AiSettings settings, ILogger<AnthropicClaudeClient> logger)
    {
        _food = food;
        _settings = settings;
        _logger = logger;
        _client = AnthropicClients.Create(food, settings.TimeoutSeconds, settings.MaxRetries);
    }

    public bool IsConfigured => _client is not null;
    public string ModelName => _food.Model;

    public async Task<Result<ClaudeReply>> CompleteJsonAsync(ClaudeRequest request, CancellationToken cancellationToken)
    {
        if (_client is null) return Error.Failure("ai.unavailable", Loc.T("Suggestions aren't available right now."));
        Dictionary<string, JsonElement> schema;
        try
        {
            schema = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(request.JsonSchema)!;
        }
        catch (JsonException exception)
        {
            throw new ArgumentException("The request's schema is not JSON.", nameof(request), exception);
        }

        var parameters = new MessageCreateParams
        {
            Model = _food.Model,
            MaxTokens = Math.Clamp(request.MaxTokens, 256, 4096),
            System = request.System,
            OutputConfig = new OutputConfig
            {
                Effort = Effort.Low,
                Format = new JsonOutputFormat { Schema = schema }
            },
            Messages = [new() { Role = Role.User, Content = request.User }]
        };

        // One limit for the whole call, retries included, so a person waiting on a suggestion never waits for three timeouts.
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        limit.CancelAfter(TimeSpan.FromSeconds(Math.Clamp(_settings.TimeoutSeconds, 5, 300)));
        try
        {
            var response = await _client.Messages.Create(parameters, limit.Token);
            var stopReason = response.StopReason?.ToString() ?? string.Empty;
            var inputTokens = (int)Math.Min(response.Usage.InputTokens, int.MaxValue);
            var outputTokens = (int)Math.Min(response.Usage.OutputTokens, int.MaxValue);
            if (stopReason.Equals("refusal", StringComparison.OrdinalIgnoreCase))
            {
                _logger.LogWarning("AI {Feature} refused by the model.", request.Feature);
                return Error.Failure("ai.refused", Loc.T("That didn't come out right. Try again in a moment."));
            }
            if (stopReason.Equals("max_tokens", StringComparison.OrdinalIgnoreCase))
            {
                // Cut off mid-answer: the JSON can't be whole, so don't pretend it is.
                _logger.LogWarning("AI {Feature} hit max_tokens after {OutputTokens} tokens.", request.Feature, outputTokens);
                return Error.Failure("ai.truncated", Loc.T("That didn't come out right. Try again in a moment."));
            }
            var text = string.Concat(response.Content.Select(block => block.Value).OfType<TextBlock>().Select(block => block.Text));
            _logger.LogInformation("AI {Feature}: {InputTokens} in / {OutputTokens} out.", request.Feature, inputTokens, outputTokens);
            return new ClaudeReply(text, inputTokens, outputTokens);
        }
        catch (AnthropicRateLimitException exception)
        {
            _logger.LogWarning(exception, "AI {Feature} rate limited after retries.", request.Feature);
            return new Error("ai.busy", Loc.T("Lots of people are asking right now. Try again in a minute."), ErrorType.Network);
        }
        catch (Anthropic5xxException exception)
        {
            _logger.LogWarning(exception, "AI {Feature} upstream error after retries.", request.Feature);
            return new Error("ai.failed", Loc.T("Suggestions aren't available right now. Try again later."), ErrorType.Network);
        }
        catch (Anthropic4xxException exception)
        {
            // Our request was wrong (bad key, bad model, a schema the API won't take): not retryable, and worth a loud log.
            _logger.LogError(exception, "AI {Feature} rejected by the API.", request.Feature);
            return Error.Failure("ai.failed", Loc.T("Suggestions aren't available right now. Try again later."));
        }
        catch (AnthropicIOException exception)
        {
            _logger.LogWarning(exception, "AI {Feature} network failure.", request.Feature);
            return new Error("ai.failed", Loc.T("Suggestions aren't available right now. Try again later."), ErrorType.Network);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            _logger.LogWarning("AI {Feature} timed out after {Seconds}s.", request.Feature, _settings.TimeoutSeconds);
            return new Error("ai.timeout", Loc.T("That took too long. Try again."), ErrorType.Network);
        }
    }
}
