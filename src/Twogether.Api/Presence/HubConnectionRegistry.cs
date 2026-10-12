using System.Collections.Concurrent;
using Microsoft.AspNetCore.SignalR;

namespace Twogether.Api.Presence;

/// <summary>
/// Open hub connections per user, so they can be closed from outside a hub call: a password reset
/// ends every session, including a connection that only listens and never invokes anything.
/// In memory, per instance, like the presence tracker.
/// </summary>
public sealed class HubConnectionRegistry
{
    private readonly ConcurrentDictionary<Guid, ConcurrentDictionary<string, HubCallerContext>> _byUser = new();

    public void Add(Guid userId, HubCallerContext context) => _byUser.GetOrAdd(userId, _ => new()).TryAdd(context.ConnectionId, context);

    public void Remove(Guid userId, string connectionId)
    {
        if (_byUser.TryGetValue(userId, out var connections) && connections.TryRemove(connectionId, out _) && connections.IsEmpty)
            _byUser.TryRemove(new KeyValuePair<Guid, ConcurrentDictionary<string, HubCallerContext>>(userId, connections));
    }

    /// <returns>How many connections were closed.</returns>
    public int AbortAll(Guid userId)
    {
        if (!_byUser.TryRemove(userId, out var connections)) return 0;
        foreach (var context in connections.Values) context.Abort();
        return connections.Count;
    }
}
