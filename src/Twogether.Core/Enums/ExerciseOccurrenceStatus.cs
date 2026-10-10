namespace Twogether.Core.Enums;

public enum ExerciseOccurrenceStatus
{
    /// <summary>The alarm is ringing on the assigned partner's device; the camera has not been opened yet.</summary>
    Alarming,
    /// <summary>The assigned partner is performing the exercise in front of the camera.</summary>
    InProgress,
    Completed,
    Missed,
    /// <summary>The other partner let them off, or the goal was changed while it was ringing.</summary>
    Cancelled
}
