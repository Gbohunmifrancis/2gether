using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace Twogether.Application.Features.Admin.Live;

/// <summary>
/// Where the live stream has got to: asks for events a little before the last poll (a row can be
/// committed a moment after its timestamp) and lets each id through once.
/// </summary>
public sealed class LiveCursor(DateTime startUtc)
{
    public static readonly TimeSpan Overlap = TimeSpan.FromSeconds(30);
    private readonly Dictionary<string, DateTime> seen = new(StringComparer.Ordinal);

    public DateTime SinceUtc { get; private set; } = startUtc;

    /// <summary>The events not let through before, oldest first; then moves on to <paramref name="polledAtUtc"/>.</summary>
    public IReadOnlyList<LiveEvent> Accept(IEnumerable<LiveEvent> batch, DateTime polledAtUtc)
    {
        // Older than what was asked for can only be a copy of something already let through or forgotten on purpose.
        var asked = SinceUtc;
        var fresh = batch.Where(item => item.AtUtc > asked && seen.TryAdd(item.Id, item.AtUtc))
            .OrderBy(item => item.AtUtc).ThenBy(item => item.Id, StringComparer.Ordinal).ToList();
        var next = polledAtUtc - Overlap;
        if (next > SinceUtc) SinceUtc = next;
        // Anything older than what is asked for can't come back, so it needn't be remembered.
        foreach (var old in seen.Where(pair => pair.Value <= SinceUtc).Select(pair => pair.Key).ToList()) seen.Remove(old);
        return fresh;
    }
}

public sealed record LiveStreamTiming(TimeSpan Poll, TimeSpan Ping, TimeSpan Recheck)
{
    public static readonly LiveStreamTiming Default = new(TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(20), TimeSpan.FromSeconds(60));
}

/// <summary>
/// Server-Sent Events over an ordinary response body: <c>event: live</c> for each new event, <c>event: ping</c>
/// at the start and every so often. Runs until cancelled (client gone, token expired) or until
/// <c>stillAllowed</c> says the admin's session has ended.
/// </summary>
public static class LiveStream
{
    public const string LiveEventName = "live";
    public const string PingEventName = "ping";
    public const int BatchLimit = 100;

    public static async Task RunAsync(
        Stream output,
        LiveCursor cursor,
        Func<DateTime, CancellationToken, Task<IReadOnlyList<LiveEvent>>> fetchSince,
        Func<CancellationToken, Task<bool>> stillAllowed,
        Func<DateTime> utcNow,
        JsonSerializerOptions json,
        LiveStreamTiming timing,
        Action<Exception>? onError,
        CancellationToken ct)
    {
        try
        {
            await WriteAsync(output, PingEventName, new { nowUtc = utcNow() }, json, ct);
            var sincePing = Stopwatch.StartNew();
            var sinceCheck = Stopwatch.StartNew();
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var polledAt = utcNow();
                    foreach (var item in cursor.Accept(await fetchSince(cursor.SinceUtc, ct), polledAt))
                        await WriteAsync(output, LiveEventName, item, json, ct);
                }
                catch (Exception exception) when (exception is not OperationCanceledException)
                {
                    // A failed poll (the database blinked) is retried on the next one; the stream stays open.
                    onError?.Invoke(exception);
                }
                if (sincePing.Elapsed >= timing.Ping)
                {
                    await WriteAsync(output, PingEventName, new { nowUtc = utcNow() }, json, ct);
                    sincePing.Restart();
                }
                if (sinceCheck.Elapsed >= timing.Recheck)
                {
                    if (!await stillAllowed(ct)) return;
                    sinceCheck.Restart();
                }
                await Task.Delay(timing.Poll, ct);
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // The client went away or the session ran out: the stream simply ends.
        }
    }

    public static async Task WriteAsync<T>(Stream output, string eventName, T data, JsonSerializerOptions json, CancellationToken ct)
    {
        var frame = $"event: {eventName}\ndata: {JsonSerializer.Serialize(data, json)}\n\n";
        await output.WriteAsync(Encoding.UTF8.GetBytes(frame), ct);
        await output.FlushAsync(ct);
    }
}
