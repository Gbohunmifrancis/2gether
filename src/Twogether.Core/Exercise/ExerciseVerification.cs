using Twogether.Core.Enums;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

namespace Twogether.Core.Exercise;

/// <summary>
/// The server cannot see the camera, so it checks what it can: the count reached the target,
/// the pose detector was confident enough, and the session lasted at least as long as the
/// exercise physically takes. A replayed or fabricated completion fails the last check.
/// </summary>
public static class ExerciseVerification
{
    /// <summary>
    /// The phone ignores frames whose joints are under 0.5 visibility, and its confidence is the mean
    /// over the frames that counted, so an honest count never sends less. A hair of slack absorbs float rounding.
    /// </summary>
    public const double MinimumConfidence = 0.5;
    private const double ConfidenceSlack = 1e-6;

    /// <summary>How long an in-progress session may run past the alarm deadline before it is missed.</summary>
    public static readonly TimeSpan CompletionAllowance = TimeSpan.FromMinutes(10);

    public static Result Validate(ExerciseType type, int target, int achievedCount, double confidence, DateTime? startedAtUtc, DateTime completedAtUtc)
    {
        if (startedAtUtc is null)
            return Error.Validation("exercise.not_started", Loc.T("Open the camera and start the exercise first."));
        if (achievedCount < target)
            return Error.Validation("exercise.target_not_met", Loc.T("Keep going: {achieved} of {target} so far.", ("achieved", achievedCount), ("target", target)));
        if (confidence + ConfidenceSlack < MinimumConfidence)
            return Error.Validation("exercise.low_confidence", Loc.T("The camera could not see you clearly. Step into better light and keep your whole body in frame."));
        var minimumSeconds = ExerciseCatalog.Get(type).MinSecondsPerUnit * target;
        var elapsed = completedAtUtc - startedAtUtc.Value;
        if (elapsed.TotalSeconds < minimumSeconds)
            return Error.Validation("exercise.too_fast", Loc.T("That was faster than the exercise can be done. Keep going until the time is up."));
        return Result.Success();
    }
}
