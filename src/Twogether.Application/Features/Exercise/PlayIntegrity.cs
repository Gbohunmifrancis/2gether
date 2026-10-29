using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Exercise;

/// <summary>Asks Google whether a completion came from our genuine app on a genuine phone.</summary>
public interface IPlayIntegrityVerifier
{
    bool IsConfigured { get; }

    /// <summary>
    /// The verdict for <paramref name="token"/>, which must have been requested for <paramref name="requestHash"/>.
    /// Never throws for Google's answers: an unreachable Google is <see cref="ExerciseIntegrity.NotChecked"/>.
    /// </summary>
    Task<ExerciseIntegrity> VerifyAsync(string? token, string requestHash, CancellationToken cancellationToken);
}

public sealed class PlayIntegritySettings
{
    /// <summary>"playIntegrity", "fake" (development only; refused in Production) or empty (not checked).</summary>
    public string? Provider { get; init; }
    public string? PackageName { get; init; }
    /// <summary>A service account in the Google Cloud project linked to the app in Play Console.</summary>
    public string? CredentialsJson { get; init; }
}

/// <summary>The decoded token's fields that decide the verdict.</summary>
public sealed record IntegrityPayload(
    string? RequestPackageName,
    string? RequestHash,
    long? TimestampMillis,
    string? AppRecognitionVerdict,
    IReadOnlyList<string> DeviceRecognitionVerdict);

public static class PlayIntegrityRules
{
    /// <summary>How old a token may be when it reaches the server.</summary>
    public static readonly TimeSpan MaxAge = TimeSpan.FromMinutes(10);

    /// <summary>
    /// Binds a token to one completion of one occurrence, with its count and what the camera noticed, so a
    /// genuine token can't be reused for another dare, a different count, or made-up checks. The app
    /// computes the same string: "heartpact:exercise:&lt;id&gt;:&lt;count&gt;:&lt;the six check numbers in order&gt;".
    /// </summary>
    public static string RequestHash(Guid occurrenceId, int achievedCount, ExerciseCheckCounts checks = default)
    {
        var text = string.Create(CultureInfo.InvariantCulture,
            $"heartpact:exercise:{occurrenceId:D}:{achievedCount}:{checks.FormFaults}:{checks.FastReps}:{checks.ChallengesPassed}:{checks.ChallengesMissed}:{checks.PhoneMovedReps}:{checks.OutOfFrameSeconds}");
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(text));
        return Convert.ToHexStringLower(bytes);
    }

    /// <summary>
    /// Passed only when the token is for this request and this app, recent, from an app Play recognises,
    /// on a device that meets Play's device integrity bar. Anything else is <see cref="ExerciseIntegrity.Failed"/>.
    /// </summary>
    public static ExerciseIntegrity Evaluate(IntegrityPayload payload, string packageName, string requestHash, DateTime utcNow)
    {
        if (!string.Equals(payload.RequestPackageName, packageName, StringComparison.Ordinal)) return ExerciseIntegrity.Failed;
        if (!string.Equals(payload.RequestHash, requestHash, StringComparison.Ordinal)) return ExerciseIntegrity.Failed;
        if (payload.TimestampMillis is not { } millis) return ExerciseIntegrity.Failed;
        var issued = DateTimeOffset.FromUnixTimeMilliseconds(millis).UtcDateTime;
        // A little slack for clocks: Google's and ours.
        if (issued > utcNow.AddMinutes(1) || utcNow - issued > MaxAge) return ExerciseIntegrity.Failed;
        if (payload.AppRecognitionVerdict != "PLAY_RECOGNIZED") return ExerciseIntegrity.Failed;
        if (!payload.DeviceRecognitionVerdict.Contains("MEETS_DEVICE_INTEGRITY") && !payload.DeviceRecognitionVerdict.Contains("MEETS_STRONG_INTEGRITY"))
            return ExerciseIntegrity.Failed;
        return ExerciseIntegrity.Passed;
    }
}
