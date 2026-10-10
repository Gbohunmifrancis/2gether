using Twogether.Core.Enums;

namespace Twogether.Core.Exercise;

/// <summary>
/// One exercise the on-device pose counter knows how to count. MinSecondsPerUnit is the
/// server-side plausibility floor: a rep (or, for holds, a fraction of the target seconds)
/// cannot physically happen faster than this, so a completion that arrives sooner is rejected.
/// The frontend mirrors these values exactly.
/// </summary>
public sealed record ExerciseDefinition(
    ExerciseType Type,
    string DisplayName,
    ExerciseMeasure Measure,
    int DefaultTarget,
    int MinTarget,
    int MaxTarget,
    double MinSecondsPerUnit,
    string Cue);

public static class ExerciseCatalog
{
    public static IReadOnlyList<ExerciseDefinition> All { get; } =
    [
        new(ExerciseType.Squat, "Squats", ExerciseMeasure.Reps, 15, 1, 200, 0.8, "Turn sideways. Lower until your thighs are parallel, then stand tall."),
        new(ExerciseType.PushUp, "Push-ups", ExerciseMeasure.Reps, 10, 1, 200, 0.7, "Turn sideways. Chest to the floor, elbows locked at the top."),
        new(ExerciseType.JumpingJack, "Jumping jacks", ExerciseMeasure.Reps, 20, 1, 300, 0.4, "Face the camera. Hands above your head, feet wide, then back together."),
        new(ExerciseType.Plank, "Plank hold", ExerciseMeasure.Seconds, 30, 5, 600, 0.9, "Turn sideways. Keep a straight line from shoulders to ankles."),
        new(ExerciseType.ArmRaise, "Overhead arm raises", ExerciseMeasure.Reps, 15, 1, 300, 0.5, "Face the camera. Both hands above your head, then down to your sides."),
        new(ExerciseType.HighKnees, "High knees", ExerciseMeasure.Reps, 30, 1, 400, 0.25, "Face the camera. Drive each knee up to hip height."),
        new(ExerciseType.SitUp, "Sit-ups", ExerciseMeasure.Reps, 10, 1, 200, 0.8, "Turn sideways. Sit all the way up, then lower back down.")
    ];

    private static readonly Dictionary<ExerciseType, ExerciseDefinition> ByType = All.ToDictionary(item => item.Type);

    public static ExerciseDefinition Get(ExerciseType type)
        => ByType.TryGetValue(type, out var definition)
            ? definition
            : throw new ArgumentOutOfRangeException(nameof(type), type, "Unknown exercise type.");

    public static ExerciseMeasure MeasureOf(ExerciseType type) => Get(type).Measure;
}
