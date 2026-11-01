namespace Twogether.Core.Enums;

/// <summary>Where a generated note stands: being written (claimed by one request or worker), written, or given up on.</summary>
public enum AiNoteState
{
    Pending,
    Ready,
    Failed,
    /// <summary>Weekly recap only: nothing happened that week, so nothing was written or sent.</summary>
    Skipped
}
