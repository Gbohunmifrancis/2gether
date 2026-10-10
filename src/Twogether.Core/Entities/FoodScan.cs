using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// Audit row for one photo estimate call. The photo itself is never stored; the row exists
/// for the daily quota, for cost tracking (tokens), and so an entry can point back at the
/// estimate it came from.
/// </summary>
public sealed class FoodScan : BaseEntity
{
    private FoodScan() { }

    public Guid UserId { get; private set; }
    public Guid CoupleId { get; private set; }
    public string Model { get; private set; } = string.Empty;
    public string MediaType { get; private set; } = string.Empty;
    public int ImageBytes { get; private set; }
    public bool Succeeded { get; private set; }
    public string? ErrorCode { get; private set; }
    public int InputTokens { get; private set; }
    public int OutputTokens { get; private set; }
    public int LatencyMs { get; private set; }
    /// <summary>The accepted estimate as JSON, null when the call failed.</summary>
    public string? ResultJson { get; private set; }

    public static FoodScan Succeed(Guid userId, Guid coupleId, string model, string mediaType, int imageBytes, int inputTokens, int outputTokens, int latencyMs, string resultJson, DateTime utcNow) => new()
    {
        UserId = userId,
        CoupleId = coupleId,
        Model = model,
        MediaType = mediaType,
        ImageBytes = imageBytes,
        Succeeded = true,
        InputTokens = inputTokens,
        OutputTokens = outputTokens,
        LatencyMs = latencyMs,
        ResultJson = resultJson,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public static FoodScan Fail(Guid userId, Guid coupleId, string model, string mediaType, int imageBytes, string errorCode, int latencyMs, DateTime utcNow) => new()
    {
        UserId = userId,
        CoupleId = coupleId,
        Model = model,
        MediaType = mediaType,
        ImageBytes = imageBytes,
        Succeeded = false,
        ErrorCode = errorCode,
        LatencyMs = latencyMs,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };
}
