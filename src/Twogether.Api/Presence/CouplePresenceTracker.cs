using System.Collections.Concurrent;

namespace Twogether.Api.Presence;

public sealed class CouplePresenceTracker
{
    private readonly ConcurrentDictionary<(Guid CoupleId, Guid UserId), int> _connections = new();

    public void Connected(Guid coupleId, Guid userId) => _connections.AddOrUpdate((coupleId, userId), 1, (_, count) => count + 1);
    public void Disconnected(Guid coupleId, Guid userId) => _connections.AddOrUpdate((coupleId, userId), 0, (_, count) => Math.Max(0, count - 1));
    public bool IsOnline(Guid coupleId, Guid userId) => _connections.TryGetValue((coupleId, userId), out var count) && count > 0;
    public bool AreBothOnline(Guid coupleId) => _connections.Count(item => item.Key.CoupleId == coupleId && item.Value > 0) >= 2;
}
