using Twogether.Application.Common.Interfaces;

namespace Twogether.Application.Features.Admin.Ops;

/// <summary>
/// Things that went wrong and are only logged, counted for the admin alerts: purchase verifications
/// that failed for a reason that isn't the buyer's (Google unreachable, an unknown purchase). In memory
/// and per instance, for the last day.
/// </summary>
public sealed class OpsSignals(IDateTimeProvider clock)
{
    private static readonly TimeSpan Keep = TimeSpan.FromDays(1);
    private readonly Queue<DateTime> verifyFailures = new();

    public void VerifyFailed()
    {
        var now = clock.UtcNow;
        lock (verifyFailures)
        {
            verifyFailures.Enqueue(now);
            while (verifyFailures.Count > 0 && now - verifyFailures.Peek() > Keep) verifyFailures.Dequeue();
            // Bounded whatever happens: an outage must not grow this without end.
            while (verifyFailures.Count > 10_000) verifyFailures.Dequeue();
        }
    }

    public int VerifyFailuresSince(DateTime sinceUtc)
    {
        lock (verifyFailures) return verifyFailures.Count(at => at >= sinceUtc);
    }
}
