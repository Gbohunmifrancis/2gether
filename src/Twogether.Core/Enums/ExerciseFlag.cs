namespace Twogether.Core.Enums;

/// <summary>Something in a finished dare the partner may want to look at. Informational: the dare still counts.</summary>
public enum ExerciseFlag
{
    /// <summary>A surprise check-in on camera wasn't done in time.</summary>
    MissedCheckIn,
    /// <summary>Reps were counted while the phone itself kept moving.</summary>
    PhoneMoved,
    /// <summary>Out of the camera's view for a long stretch.</summary>
    LeftFrame,
    /// <summary>The app couldn't prove it's the genuine app on a genuine phone.</summary>
    UnverifiedApp
}
