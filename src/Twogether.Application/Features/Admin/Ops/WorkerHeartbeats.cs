using System.Collections.Concurrent;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Application.Features.Admin.Ops;

public enum WorkerStatus { Ok, Late, Failing, Unknown }

public sealed record WorkerHealthDto(string Name, DateTime? LastRunUtc, DateTime? LastErrorUtc, string? LastError, int EverySeconds, WorkerStatus Status);

/// <summary>
/// What each background worker last did, reported by the worker itself at the end of every loop. In
/// memory and per instance: it shows the workers of the instance that answers, and starts empty after
/// a restart.
/// </summary>
public sealed class WorkerHeartbeats(IDateTimeProvider clock)
{
    /// <summary>A worker that ticks every second is not late after three seconds of a slow loop.</summary>
    public static readonly TimeSpan MinimumLateAfter = TimeSpan.FromSeconds(30);
    public const int MaxErrorLength = 300;

    private sealed class Beat(TimeSpan interval, DateTime registeredUtc)
    {
        public TimeSpan Interval { get; } = interval;
        public DateTime RegisteredUtc { get; } = registeredUtc;
        public DateTime? LastRunUtc;
        public DateTime? LastErrorUtc;
        public string? LastError;
        public bool LastFailed;
    }

    private readonly ConcurrentDictionary<string, Beat> beats = new();

    /// <summary>Called once when the worker starts, so a worker that never gets to run still shows up (and turns late).</summary>
    public void Register(string name, TimeSpan interval) => beats.TryAdd(name, new Beat(interval, clock.UtcNow));

    public void Ok(string name, TimeSpan interval)
    {
        var beat = beats.GetOrAdd(name, _ => new Beat(interval, clock.UtcNow));
        lock (beat)
        {
            beat.LastRunUtc = clock.UtcNow;
            beat.LastFailed = false;
        }
    }

    public void Failed(string name, TimeSpan interval, Exception exception)
    {
        var beat = beats.GetOrAdd(name, _ => new Beat(interval, clock.UtcNow));
        var now = clock.UtcNow;
        // The type and message only: enough to recognise the failure, never a stack trace.
        var text = $"{exception.GetType().Name}: {exception.Message}";
        lock (beat)
        {
            beat.LastRunUtc = now;
            beat.LastErrorUtc = now;
            beat.LastError = text.Length > MaxErrorLength ? text[..MaxErrorLength] : text;
            beat.LastFailed = true;
        }
    }

    public static TimeSpan LateAfter(TimeSpan interval) => interval * 3 > MinimumLateAfter ? interval * 3 : MinimumLateAfter;

    public IReadOnlyList<WorkerHealthDto> Snapshot()
    {
        var now = clock.UtcNow;
        return beats.OrderBy(pair => pair.Key, StringComparer.Ordinal).Select(pair =>
        {
            var beat = pair.Value;
            lock (beat)
            {
                var lateAfter = LateAfter(beat.Interval);
                var status = beat.LastFailed ? WorkerStatus.Failing
                    : beat.LastRunUtc is { } last ? (now - last > lateAfter ? WorkerStatus.Late : WorkerStatus.Ok)
                    // Never ran: unknown while it is still starting, late once it has had three intervals.
                    : now - beat.RegisteredUtc > lateAfter ? WorkerStatus.Late : WorkerStatus.Unknown;
                return new WorkerHealthDto(pair.Key, beat.LastRunUtc, beat.LastErrorUtc, beat.LastError, (int)Math.Round(beat.Interval.TotalSeconds), status);
            }
        }).ToList();
    }
}
