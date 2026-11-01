using System.Text.Json;
using Anthropic;
using Anthropic.Exceptions;
using Anthropic.Models.Messages;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Food;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

namespace Twogether.Infrastructure.Food;

/// <summary>
/// Sends one meal photo to Claude and asks for a schema-constrained JSON estimate. The photo
/// goes out with the request and is never written anywhere; only token counts come back
/// into our logs.
/// </summary>
public sealed class AnthropicFoodPhotoEstimator : IFoodPhotoEstimator
{
    private const string SystemPrompt =
        "You estimate the energy and macronutrients in one photo of food or drink for a couple's shared food log. " +
        "Identify each item, estimate its portion from visual cues (plate size, cutlery, hands, packaging), and sum the kilocalories. " +
        "Be realistic: home-cooked and restaurant portions are usually larger than textbook servings. " +
        "Express uncertainty in the low/high range rather than in false precision; widen the range when portion size or cooking method is unclear. " +
        "Set is_food to false for anything that is not food or drink. Keep the description short and name the dish the way a person would.";

    private static readonly Dictionary<string, JsonElement> Schema =
        JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(FoodEstimateParser.JsonSchema)!;

    private readonly AnthropicClient? _client;
    private readonly FoodSettings _settings;
    private readonly ILogger<AnthropicFoodPhotoEstimator> _logger;

    public AnthropicFoodPhotoEstimator(FoodSettings settings, ILogger<AnthropicFoodPhotoEstimator> logger)
    {
        _settings = settings;
        _logger = logger;
        _client = Twogether.Infrastructure.Ai.AnthropicClients.Create(settings, settings.TimeoutSeconds);
    }

    public bool IsConfigured => _client is not null;
    public string ModelName => _settings.Model;

    public async Task<Result<FoodEstimate>> EstimateAsync(FoodPhoto photo, string? hint, string? countryCode, CancellationToken cancellationToken)
    {
        if (_client is null) return Error.Failure("food.estimation_unavailable", Loc.T("Photo estimates are not configured."));
        var mediaType = ToMediaType(photo.MediaType);
        if (mediaType is null) return Error.Validation("food.image_type", Loc.T("Unsupported image type."));

        var userText = FoodPrompt.UserText(hint, countryCode);
        var request = new MessageCreateParams
        {
            Model = _settings.Model,
            MaxTokens = 1024,
            System = SystemPrompt,
            OutputConfig = new OutputConfig
            {
                Effort = Effort.Low,
                Format = new JsonOutputFormat { Schema = Schema }
            },
            Messages =
            [
                new()
                {
                    Role = Role.User,
                    Content = new List<ContentBlockParam>
                    {
                        new ImageBlockParam { Source = new Base64ImageSource { Data = Convert.ToBase64String(photo.Bytes), MediaType = mediaType.Value } },
                        new TextBlockParam { Text = userText }
                    }
                }
            ]
        };

        try
        {
            var response = await _client.Messages.Create(request, cancellationToken);
            var stopReason = response.StopReason?.ToString() ?? string.Empty;
            if (stopReason.Equals("refusal", StringComparison.OrdinalIgnoreCase))
            {
                _logger.LogWarning("Food photo estimate refused by the model.");
                return Error.Failure("food.estimation_refused", Loc.T("The estimate could not be produced for this photo. Try a clearer photo of the meal."));
            }
            var text = string.Concat(response.Content.Select(block => block.Value).OfType<TextBlock>().Select(block => block.Text));
            var inputTokens = (int)Math.Min(response.Usage.InputTokens, int.MaxValue);
            var outputTokens = (int)Math.Min(response.Usage.OutputTokens, int.MaxValue);
            if (stopReason.Equals("max_tokens", StringComparison.OrdinalIgnoreCase))
                _logger.LogWarning("Food photo estimate hit max_tokens after {OutputTokens} tokens.", outputTokens);
            return FoodEstimateParser.Parse(text, inputTokens, outputTokens);
        }
        catch (AnthropicRateLimitException exception)
        {
            _logger.LogWarning(exception, "Food photo estimate rate limited.");
            return new Error("food.estimation_busy", Loc.T("The estimator is busy. Try again in a moment."), ErrorType.Network);
        }
        catch (Anthropic5xxException exception)
        {
            _logger.LogWarning(exception, "Food photo estimate upstream error.");
            return new Error("food.estimation_failed", Loc.T("The estimator is unavailable right now. Try again later."), ErrorType.Network);
        }
        catch (Anthropic4xxException exception)
        {
            // Our request was wrong (bad key, bad model, oversize image): not retryable, and worth a loud log.
            _logger.LogError(exception, "Food photo estimate rejected by the API.");
            return Error.Failure("food.estimation_failed", Loc.T("The estimate could not be produced. Log the meal by hand."));
        }
        catch (AnthropicIOException exception)
        {
            _logger.LogWarning(exception, "Food photo estimate network failure.");
            return new Error("food.estimation_failed", Loc.T("The estimator could not be reached. Try again later."), ErrorType.Network);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            _logger.LogWarning("Food photo estimate timed out after {Seconds}s.", _settings.TimeoutSeconds);
            return new Error("food.estimation_timeout", Loc.T("The estimate took too long. Try again."), ErrorType.Network);
        }
    }

    private static MediaType? ToMediaType(string mediaType) => mediaType.ToLowerInvariant() switch
    {
        "image/jpeg" => MediaType.ImageJpeg,
        "image/png" => MediaType.ImagePng,
        "image/webp" => MediaType.ImageWebP,
        "image/gif" => MediaType.ImageGif,
        _ => null
    };
}
