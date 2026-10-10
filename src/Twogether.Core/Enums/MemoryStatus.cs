namespace Twogether.Core.Enums;

public enum MemoryStatus
{
    /// <summary>Upload slot handed out; the bytes may or may not have arrived yet.</summary>
    Pending,
    Ready,
    /// <summary>Hidden everywhere; the stored object is being removed. The cleanup worker retries.</summary>
    Deleting
}
