namespace Twogether.Core.Exercise;

/// <summary>
/// Converts a goal's local wall-clock time into UTC instants. All DST edge cases live here
/// so the worker and the entities never call ConvertTimeToUtc on a raw wall clock.
/// </summary>
public static class ExerciseSchedule
{
    /// <summary>Resolves an IANA or Windows id; unknown ids fall back to UTC instead of throwing.</summary>
    public static TimeZoneInfo ResolveTimeZone(string? timeZoneId)
    {
        if (string.IsNullOrWhiteSpace(timeZoneId)) return TimeZoneInfo.Utc;
        return TimeZoneInfo.TryFindSystemTimeZoneById(timeZoneId.Trim(), out var zone) ? zone : TimeZoneInfo.Utc;
    }

    public static bool IsKnownTimeZone(string? timeZoneId)
        => !string.IsNullOrWhiteSpace(timeZoneId) && TimeZoneInfo.TryFindSystemTimeZoneById(timeZoneId.Trim(), out _);

    public static DateOnly LocalDate(DateTime utc, TimeZoneInfo zone)
        => DateOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(AsUtc(utc), zone));

    /// <summary>Earliest UTC instant at or after <paramref name="fromUtc"/> whose local wall clock in <paramref name="zone"/> is <paramref name="scheduledTime"/>.</summary>
    public static DateTime NextDueUtc(TimeOnly scheduledTime, TimeZoneInfo zone, DateTime fromUtc)
    {
        var from = AsUtc(fromUtc);
        // Start one day early: around a DST change the local date of `from` can map to a UTC
        // instant later than `from` for the previous local date, so always check it as well.
        var localDate = LocalDate(from, zone).AddDays(-1);
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var candidate = ToUtc(localDate, scheduledTime, zone);
            if (candidate >= from) return candidate;
            localDate = localDate.AddDays(1);
        }
        throw new InvalidOperationException("Could not find the next due time; the time zone data is inconsistent.");
    }

    /// <summary>
    /// True when <paramref name="dueUtc"/> is still ahead of <paramref name="utcNow"/> and falls on
    /// the same local date in <paramref name="zone"/>: the day's dare has not rung yet, so it may be
    /// done early.
    /// </summary>
    public static bool IsLaterToday(DateTime dueUtc, TimeZoneInfo zone, DateTime utcNow)
    {
        var due = AsUtc(dueUtc);
        var now = AsUtc(utcNow);
        return now < due && LocalDate(due, zone) == LocalDate(now, zone);
    }

    /// <summary>The UTC instant for <paramref name="scheduledTime"/> on <paramref name="localDate"/> in <paramref name="zone"/>.</summary>
    public static DateTime ToUtc(DateOnly localDate, TimeOnly scheduledTime, TimeZoneInfo zone)
    {
        var wallClock = DateTime.SpecifyKind(localDate.ToDateTime(scheduledTime), DateTimeKind.Unspecified);
        // Spring-forward gap: the wall clock never happens; ring at the first minute that does.
        while (zone.IsInvalidTime(wallClock)) wallClock = wallClock.AddMinutes(1);
        TimeSpan offset;
        if (zone.IsAmbiguousTime(wallClock))
        {
            // Fall-back overlap: the wall clock happens twice; take the larger offset, which is
            // the earlier instant, so the alarm rings the first time the clock shows the time.
            offset = zone.GetAmbiguousTimeOffsets(wallClock).Max();
        }
        else offset = zone.GetUtcOffset(wallClock);
        return DateTime.SpecifyKind(wallClock - offset, DateTimeKind.Utc);
    }

    private static DateTime AsUtc(DateTime value) => value.Kind switch
    {
        DateTimeKind.Utc => value,
        DateTimeKind.Local => value.ToUniversalTime(),
        _ => DateTime.SpecifyKind(value, DateTimeKind.Utc)
    };
}
