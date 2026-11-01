using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// One call to Claude for an AI feature: who it was for, which feature, and what it cost in tokens. Nothing that was
/// sent or written is kept here. Recorded before the call, so it counts against the person's daily cap from that
/// moment however it ends (like <see cref="FoodScan"/>), and read by the admin's cost view.
/// </summary>
public sealed class AiRequest : BaseEntity
{
    private AiRequest() { }

    public const string PendingCode = "ai.pending";

    public Guid UserId { get; private set; }
    public Guid CoupleId { get; private set; }
    public AiFeature Feature { get; private set; }
    public string Model { get; private set; } = string.Empty;
    public bool Succeeded { get; private set; }
    public string? ErrorCode { get; private set; }
    public int InputTokens { get; private set; }
    public int OutputTokens { get; private set; }
    public int LatencyMs { get; private set; }

    public bool IsPending => !Succeeded && ErrorCode == PendingCode;

    public static AiRequest Start(Guid userId, Guid coupleId, AiFeature feature, string model, DateTime utcNow) => new()
    {
        UserId = userId,
        CoupleId = coupleId,
        Feature = feature,
        Model = model.Length > 80 ? model[..80] : model,
        ErrorCode = PendingCode,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Succeed(int inputTokens, int outputTokens, int latencyMs, DateTime utcNow)
    {
        if (!IsPending) throw new InvalidOperationException("Only a pending request can get its result.");
        Succeeded = true;
        ErrorCode = null;
        InputTokens = Math.Max(0, inputTokens);
        OutputTokens = Math.Max(0, outputTokens);
        LatencyMs = Math.Max(0, latencyMs);
        UpdatedAtUtc = utcNow;
    }

    /// <summary>A failed call still cost what came back (an unreadable answer is billed like any other).</summary>
    public void Fail(string errorCode, int latencyMs, DateTime utcNow, int inputTokens = 0, int outputTokens = 0)
    {
        if (!IsPending) throw new InvalidOperationException("Only a pending request can fail.");
        if (string.IsNullOrWhiteSpace(errorCode) || errorCode == PendingCode) throw new ArgumentException("A failed request needs its own error code.", nameof(errorCode));
        ErrorCode = errorCode.Length > 60 ? errorCode[..60] : errorCode;
        InputTokens = Math.Max(0, inputTokens);
        OutputTokens = Math.Max(0, outputTokens);
        LatencyMs = Math.Max(0, latencyMs);
        UpdatedAtUtc = utcNow;
    }
}
