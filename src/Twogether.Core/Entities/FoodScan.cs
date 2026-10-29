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

    /// <summary>
    /// The code of a scan whose estimate has not come back. Recorded before the photo is sent, so
    /// the scan counts against the daily limit from that moment, whatever happens next: parallel
    /// requests cannot all pass the limit, and a scan the model was already asked for still counts
    /// if the call fails or the phone gives up waiting. A scan left with this code was abandoned.
    /// </summary>
    public const string PendingCode = "food.scan_pending";

    public bool IsPending => !Succeeded && ErrorCode == PendingCode;

    public static FoodScan Start(Guid userId, Guid coupleId, string model, string mediaType, int imageBytes, DateTime utcNow) => new()
    {
        UserId = userId,
        CoupleId = coupleId,
        Model = model,
        MediaType = mediaType,
        ImageBytes = imageBytes,
        Succeeded = false,
        ErrorCode = PendingCode,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Succeed(int inputTokens, int outputTokens, int latencyMs, string resultJson, DateTime utcNow)
    {
        if (!IsPending) throw new InvalidOperationException("Only a pending scan can get its result.");
        Succeeded = true;
        ErrorCode = null;
        InputTokens = inputTokens;
        OutputTokens = outputTokens;
        LatencyMs = latencyMs;
        ResultJson = resultJson;
        UpdatedAtUtc = utcNow;
    }

    public void Fail(string errorCode, int latencyMs, DateTime utcNow)
    {
        if (!IsPending) throw new InvalidOperationException("Only a pending scan can fail.");
        if (string.IsNullOrWhiteSpace(errorCode) || errorCode == PendingCode) throw new ArgumentException("A failed scan needs its own error code.", nameof(errorCode));
        ErrorCode = errorCode;
        LatencyMs = latencyMs;
        UpdatedAtUtc = utcNow;
    }
}
