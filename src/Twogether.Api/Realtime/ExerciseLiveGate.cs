using System.Collections.Concurrent;
using Twogether.Core.Enums;

namespace Twogether.Api.Realtime;

/// <summary>
/// Watching live (docs/couple-goals.md): who may send frames for which dare, remembered for a few seconds so eight frames a
/// second don't each look the dare up; caps on how fast frames, "watching" and cheers are relayed for one person; who
/// is watching which dare right now; and which dares already told the partner that counting started. All in memory:
/// nothing about a frame is ever stored.
/// </summary>
public sealed class ExerciseLiveGate
{
    /// <summary>How often the app sends frames: about eight a second.</summary>
    public const int AppRate = 8;
    /// <summary>Frames a second relayed for one person; more are dropped.</summary>
    public const int MaxFramesPerSecond = 10;
    /// <summary>"Watching" signals in a window of <see cref="SignalWindow"/>; the app repeats one every 10 seconds.</summary>
    public const int MaxWatchSignals = 6;
    /// <summary>Cheers in a window of <see cref="SignalWindow"/>; the app allows one a second.</summary>
    public const int MaxCheers = 5;
    public static readonly TimeSpan SignalWindow = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan Remember = TimeSpan.FromSeconds(3);
    /// <summary>A "watching" lasts this long unless repeated.</summary>
    public static readonly TimeSpan WatchingFor = TimeSpan.FromSeconds(25);

    private readonly ConcurrentDictionary<(Guid UserId, string Kind), Window> windows = new();
    private readonly ConcurrentDictionary<(Guid UserId, Guid OccurrenceId), Pass> passes = new();
    private readonly ConcurrentDictionary<Guid, DateTime> watched = new();
    private readonly ConcurrentDictionary<Guid, DateTime> announced = new();

    /// <summary>Counts a frame against the person's rate; false when they're over it.</summary>
    public bool TryFrame(Guid userId, DateTime utcNow) => Take(userId, "frame", utcNow, TimeSpan.FromSeconds(1), MaxFramesPerSecond);

    public bool TryWatchSignal(Guid userId, DateTime utcNow) => Take(userId, "watch", utcNow, SignalWindow, MaxWatchSignals);

    public bool TryCheer(Guid userId, DateTime utcNow) => Take(userId, "cheer", utcNow, SignalWindow, MaxCheers);

    private bool Take(Guid userId, string kind, DateTime utcNow, TimeSpan window, int max)
    {
        var slot = utcNow.Ticks / window.Ticks;
        var ends = new DateTime((slot + 1) * window.Ticks, DateTimeKind.Utc);
        var current = windows.AddOrUpdate((userId, kind), _ => new Window(slot, 1, ends), (_, w) => w.Slot == slot ? w with { Count = w.Count + 1 } : new Window(slot, 1, ends));
        if (windows.Count > 2000)
            foreach (var stale in windows.Where(item => item.Value.EndsUtc <= utcNow).Select(item => item.Key).ToList()) windows.TryRemove(stale, out _);
        return current.Count <= max;
    }

    /// <summary>The partner to relay to and the dare's exercise and target, when this person was cleared to send frames for it in the last few seconds.</summary>
    public LivePass? Known(Guid userId, Guid occurrenceId, DateTime utcNow)
        => passes.TryGetValue((userId, occurrenceId), out var pass) && pass.UntilUtc > utcNow ? new LivePass(pass.PartnerId, pass.CoupleId, pass.Type, pass.Target) : null;

    public void Allow(Guid userId, Guid occurrenceId, LivePass pass, DateTime utcNow)
    {
        if (passes.Count > 1000)
            foreach (var stale in passes.Where(item => item.Value.UntilUtc <= utcNow).Select(item => item.Key).ToList()) passes.TryRemove(stale, out _);
        passes[(userId, occurrenceId)] = new Pass(pass.PartnerId, pass.CoupleId, pass.Type, pass.Target, utcNow + Remember);
    }

    /// <summary>The partner opened (or keeps open) the live view of a dare, or closed it.</summary>
    public void Watching(Guid occurrenceId, bool watching, DateTime utcNow)
    {
        if (watching) watched[occurrenceId] = utcNow + WatchingFor;
        else watched.TryRemove(occurrenceId, out _);
        if (watched.Count > 1000)
            foreach (var stale in watched.Where(item => item.Value <= utcNow).Select(item => item.Key).ToList()) watched.TryRemove(stale, out _);
    }

    public bool IsWatched(Guid occurrenceId, DateTime utcNow) => watched.TryGetValue(occurrenceId, out var until) && until > utcNow;

    /// <summary>Whether this process already told the partner counting started on this dare (the database is checked once besides).</summary>
    public bool WasAnnounced(Guid occurrenceId) => announced.ContainsKey(occurrenceId);

    public void Announced(Guid occurrenceId, DateTime utcNow)
    {
        announced[occurrenceId] = utcNow;
        if (announced.Count > 5000)
            foreach (var old in announced.Where(item => item.Value < utcNow.AddHours(-2)).Select(item => item.Key).ToList()) announced.TryRemove(old, out _);
    }

    private sealed record Window(long Slot, int Count, DateTime EndsUtc);
    private sealed record Pass(Guid PartnerId, Guid CoupleId, ExerciseType Type, int Target, DateTime UntilUtc);
}

/// <summary>Who a dare's frames go to, and what the dare is.</summary>
public sealed record LivePass(Guid PartnerId, Guid CoupleId, ExerciseType Type, int Target);
