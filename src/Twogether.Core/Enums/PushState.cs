namespace Twogether.Core.Enums;

public enum PushState
{
    /// <summary>Waiting for the dispatcher (or for its next retry).</summary>
    Pending,
    /// <summary>Delivered to at least one device.</summary>
    Sent,
    /// <summary>Nothing to do: no devices, stale by the time it was due, or push is not configured.</summary>
    Skipped,
    /// <summary>Gave up after the retry budget, or the provider rejected the message itself.</summary>
    Failed
}
