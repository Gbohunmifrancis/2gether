using System.Collections.Concurrent;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Application.Common.Security;

/// <summary>A budget of failures per key: at most <see cref="MaxFailures"/> in any <see cref="Window"/>.</summary>
public sealed record AttemptRule(string Name, int MaxFailures, TimeSpan Window);

public static class AttemptRules
{
    /// <summary>Wrong passwords for one email, from anywhere: stops guessing one account.</summary>
    public static readonly AttemptRule LoginPerEmail = new("login-email", 10, TimeSpan.FromMinutes(15));
    /// <summary>Wrong passwords from one address, any account: stops credential stuffing.</summary>
    public static readonly AttemptRule LoginPerIp = new("login-ip", 30, TimeSpan.FromMinutes(15));
    /// <summary>Every sign-up attempt counts, successful or not.</summary>
    public static readonly AttemptRule RegisterPerIp = new("register-ip", 10, TimeSpan.FromHours(1));
    /// <summary>Wrong invite codes. Codes are short enough to guess, so all three budgets apply.</summary>
    public static readonly AttemptRule InviteCodePerUser = new("invite-user", 5, TimeSpan.FromMinutes(15));
    public static readonly AttemptRule InviteCodePerIp = new("invite-ip", 20, TimeSpan.FromHours(1));
    /// <summary>
    /// Across everyone. Spread over many accounts and addresses, guessing still hits this, and
    /// then codes stop working for an hour while invite links keep working.
    /// </summary>
    public static readonly AttemptRule InviteCodeGlobal = new("invite-global", 200, TimeSpan.FromHours(1));
    public static readonly AttemptRule AccountDeletePerUser = new("delete-user", 5, TimeSpan.FromMinutes(15));
    /// <summary>Reset emails per address asked about, existing or not: no inbox gets flooded.</summary>
    public static readonly AttemptRule ResetRequestPerEmail = new("reset-email", 3, TimeSpan.FromHours(1));
    public static readonly AttemptRule ResetRequestPerIp = new("reset-ip", 10, TimeSpan.FromHours(1));
    /// <summary>Wrong reset codes. Each code also allows only five tries of its own.</summary>
    public static readonly AttemptRule ResetCodePerEmail = new("reset-code-email", 10, TimeSpan.FromMinutes(15));
    public static readonly AttemptRule ResetCodePerIp = new("reset-code-ip", 30, TimeSpan.FromMinutes(15));
    /// <summary>Google sign-ins that fail verification, per address.</summary>
    public static readonly AttemptRule GooglePerIp = new("google-ip", 30, TimeSpan.FromMinutes(15));
    /// <summary>Admin sign-in failures (password or authenticator code): tight, since an admin account opens everything.</summary>
    public static readonly AttemptRule AdminLoginPerEmail = new("admin-email", 5, TimeSpan.FromMinutes(15));
    public static readonly AttemptRule AdminLoginPerIp = new("admin-ip", 20, TimeSpan.FromMinutes(15));
}

/// <summary>
/// Counts failures in memory, per rule and key, over a sliding window. Successes cost nothing,
/// so a person who types their password right is never slowed down by someone else's guessing
/// of a different account. One instance per process: with several API instances each enforces
/// its own budget, which multiplies the limits by the instance count.
/// </summary>
public sealed class AttemptLimiter(IDateTimeProvider clock, bool enabled = true)
{
    private const int SweepEvery = 1024;
    private readonly ConcurrentDictionary<(string Rule, string Key), Bucket> buckets = new();
    private int calls;

    private sealed class Bucket(TimeSpan window)
    {
        public TimeSpan Window { get; } = window;
        public Queue<DateTime> Failures { get; } = new();

        public void Trim(DateTime now)
        {
            while (Failures.Count > 0 && Failures.Peek() <= now - Window) Failures.Dequeue();
        }
    }

    /// <summary>Null when allowed; when blocked, how long until the oldest failure in the window expires.</summary>
    public TimeSpan? RetryAfter(AttemptRule rule, string key)
    {
        if (!enabled) return null;
        SweepOccasionally();
        if (!buckets.TryGetValue((rule.Name, key), out var bucket)) return null;
        var now = clock.UtcNow;
        lock (bucket)
        {
            bucket.Trim(now);
            if (bucket.Failures.Count < rule.MaxFailures) return null;
            var wait = bucket.Failures.Peek() + rule.Window - now;
            return wait > TimeSpan.Zero ? wait : TimeSpan.FromSeconds(1);
        }
    }

    public void RecordFailure(AttemptRule rule, string key)
    {
        if (!enabled) return;
        var bucket = buckets.GetOrAdd((rule.Name, key), _ => new Bucket(rule.Window));
        var now = clock.UtcNow;
        lock (bucket)
        {
            bucket.Trim(now);
            bucket.Failures.Enqueue(now);
            // Never hold more than the budget: memory per key stays bounded under attack.
            while (bucket.Failures.Count > rule.MaxFailures) bucket.Failures.Dequeue();
        }
    }

    public void Reset(AttemptRule rule, string key) => buckets.TryRemove((rule.Name, key), out _);

    /// <summary>Drops keys whose failures have all expired, so many one-off keys do not pile up.</summary>
    private void SweepOccasionally()
    {
        if (Interlocked.Increment(ref calls) % SweepEvery != 0) return;
        var now = clock.UtcNow;
        foreach (var entry in buckets)
        {
            lock (entry.Value)
            {
                entry.Value.Trim(now);
                if (entry.Value.Failures.Count == 0) buckets.TryRemove(entry.Key, out _);
            }
        }
    }
}
