using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;

namespace Twogether.Infrastructure.Persistence;

/// <summary>What a <see cref="DatabaseLocks"/> lock guards. The numbers are part of the lock key: never reuse one.</summary>
public enum LockScope
{
    /// <summary>A couple's album quota: check what is used, then reserve.</summary>
    AlbumQuota = 1,
    /// <summary>A person's daily photo scans: count, then record the scan.</summary>
    FoodScans = 2,
    /// <summary>A couple's one open game: check there is none, then invite.</summary>
    GameInvite = 3,
    /// <summary>One Bachs subscription: its events (and an admin's sync or cancel) apply one at a time, newest wins.</summary>
    BachsSubscription = 4
}

/// <summary>
/// Locks for a check-then-insert limit that no unique index can express, such as "this much
/// storage" or "this many scans a day". A second request for the same key waits until the first
/// one's transaction ends, so both cannot pass the check on the same count. Other keys never wait.
/// </summary>
public static class DatabaseLocks
{
    /// <summary>Held until the current transaction commits or rolls back. Call it first thing inside that transaction.</summary>
    public static async Task LockAsync(this DatabaseFacade database, LockScope scope, Guid key, CancellationToken cancellationToken)
    {
        if (database.CurrentTransaction is null) throw new InvalidOperationException("A lock is released with its transaction, so it needs one.");
        // Only PostgreSQL has advisory locks. SQLite, used by the tests, runs one write transaction at a time anyway.
        if (!database.IsNpgsql()) return;
        await database.ExecuteSqlAsync($"SELECT pg_advisory_xact_lock({(int)scope}, {Fold(key)})", cancellationToken);
    }

    /// <summary>A lock key for something named by text (a Bachs subscription id).</summary>
    public static Guid KeyFor(string text) => new(System.Security.Cryptography.MD5.HashData(System.Text.Encoding.UTF8.GetBytes(text)));

    /// <summary>The lock takes two 32-bit keys. Two ids that fold to the same number only make each other wait a moment.</summary>
    public static int Fold(Guid key)
    {
        Span<byte> bytes = stackalloc byte[16];
        key.TryWriteBytes(bytes);
        return BitConverter.ToInt32(bytes) ^ BitConverter.ToInt32(bytes[4..]) ^ BitConverter.ToInt32(bytes[8..]) ^ BitConverter.ToInt32(bytes[12..]);
    }
}
