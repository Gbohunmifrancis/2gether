using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Core.Exercise;

/// <summary>
/// What the camera noticed during a session, reported by the device with the completion. Advisory, like
/// the live count: the server can't see the camera, so it stores the numbers, flags the suspicious ones
/// for the partner, and never rejects a completion over them.
/// </summary>
public readonly record struct ExerciseCheckCounts(
    int FormFaults,
    int FastReps,
    int ChallengesPassed,
    int ChallengesMissed,
    int PhoneMovedReps,
    int OutOfFrameSeconds)
{
    /// <summary>A session is a few minutes long: anything beyond these is a broken or hostile client.</summary>
    public const int MaxCount = 10_000;
    public const int MaxSeconds = 24 * 60 * 60;

    /// <summary>Reps counted with the phone moving before the partner is told: one could be a nudge.</summary>
    public const int PhoneMovedThreshold = 2;
    /// <summary>Seconds out of view before the partner is told.</summary>
    public const int LeftFrameThreshold = 20;

    public ExerciseCheckCounts Clamped() => new(
        Math.Clamp(FormFaults, 0, MaxCount),
        Math.Clamp(FastReps, 0, MaxCount),
        Math.Clamp(ChallengesPassed, 0, MaxCount),
        Math.Clamp(ChallengesMissed, 0, MaxCount),
        Math.Clamp(PhoneMovedReps, 0, MaxCount),
        Math.Clamp(OutOfFrameSeconds, 0, MaxSeconds));

    public IReadOnlyList<ExerciseFlag> Flags(ExerciseIntegrity integrity)
    {
        var flags = new List<ExerciseFlag>();
        if (ChallengesMissed > 0) flags.Add(ExerciseFlag.MissedCheckIn);
        if (PhoneMovedReps >= PhoneMovedThreshold) flags.Add(ExerciseFlag.PhoneMoved);
        if (OutOfFrameSeconds >= LeftFrameThreshold) flags.Add(ExerciseFlag.LeftFrame);
        if (integrity is ExerciseIntegrity.Failed or ExerciseIntegrity.Missing) flags.Add(ExerciseFlag.UnverifiedApp);
        return flags;
    }

    /// <summary>The partner's one-line summary of the flags, or null when there are none.</summary>
    public string? Summary(ExerciseIntegrity integrity)
    {
        var parts = new List<string>();
        foreach (var flag in Flags(integrity))
        {
            parts.Add(flag switch
            {
                ExerciseFlag.MissedCheckIn => Loc.Plural(ChallengesMissed, "missed a camera check-in", "missed {count} camera check-ins"),
                ExerciseFlag.PhoneMoved => Loc.Plural(PhoneMovedReps, "the phone was moving during {count} rep", "the phone was moving during {count} reps"),
                ExerciseFlag.LeftFrame => Loc.Plural(OutOfFrameSeconds, "out of view for {count} second", "out of view for {count} seconds"),
                _ => Loc.T("the app couldn't be verified")
            });
        }
        if (parts.Count == 0) return null;
        var text = string.Join(", ", parts);
        return char.ToUpperInvariant(text[0]) + text[1..] + ".";
    }
}
