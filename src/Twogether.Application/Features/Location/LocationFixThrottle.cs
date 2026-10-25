using System.Collections.Concurrent;

namespace Twogether.Application.Features.Location;

/// <summary>
/// Drops a fix that arrives sooner than <see cref="MinGap"/> after the person's last accepted one,
/// before it costs any database work. The phone sends one about every 30 seconds; anything much
/// faster is a misbehaving client. Kept in memory, one entry per person sharing.
/// </summary>
public sealed class LocationFixThrottle
{
    public static readonly TimeSpan MinGap = TimeSpan.FromSeconds(5);
    private const int PruneAbove = 10_000;
    private readonly ConcurrentDictionary<Guid, DateTime> lastAccepted = new();

    public bool TryAccept(Guid userId, DateTime utcNow)
    {
        if (lastAccepted.Count > PruneAbove) Prune(utcNow);
        while (true)
        {
            if (!lastAccepted.TryGetValue(userId, out var previous))
            {
                if (lastAccepted.TryAdd(userId, utcNow)) return true;
                continue;
            }
            if (utcNow - previous < MinGap) return false;
            if (lastAccepted.TryUpdate(userId, utcNow, previous)) return true;
        }
    }

    private void Prune(DateTime utcNow)
    {
        foreach (var (userId, at) in lastAccepted)
            if (utcNow - at >= MinGap) lastAccepted.TryRemove(new KeyValuePair<Guid, DateTime>(userId, at));
    }
}
