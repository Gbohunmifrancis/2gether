using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Core.Exercise;

/// <summary>
/// The server cannot see the camera, so it checks what it can: the count reached the target,
/// the pose detector was confident enough, and the session lasted at least as long as the
/// exercise physically takes. A replayed or fabricated completion fails the last check.
/// </summary>
public static class ExerciseVerification
{
    public const double MinimumConfidence = 0.35;

    /// <summary>How long an in-progress session may run past the alarm deadline before it is missed.</summary>
    public static readonly TimeSpan CompletionAllowance = TimeSpan.FromMinutes(10);

    public static Result Validate(ExerciseType type, int target, int achievedCount, double confidence, DateTime? startedAtUtc, DateTime completedAtUtc)
    {
        if (startedAtUtc is null)
            return Error.Validation("exercise.not_started", "Open the camera and start the exercise first.");
        if (achievedCount < target)
            return Error.Validation("exercise.target_not_met", $"Keep going: {achievedCount} of {target} so far.");
        if (confidence < MinimumConfidence)
            return Error.Validation("exercise.low_confidence", "The camera could not see you clearly. Step into better light and keep your whole body in frame.");
        var minimumSeconds = ExerciseCatalog.Get(type).MinSecondsPerUnit * target;
        var elapsed = completedAtUtc - startedAtUtc.Value;
        if (elapsed.TotalSeconds < minimumSeconds)
            return Error.Validation("exercise.too_fast", "That was faster than the exercise can be done. Keep going until the time is up.");
        return Result.Success();
    }
}
