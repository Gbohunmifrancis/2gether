using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Exercise;

/// <summary>What the alarm worker does when a goal's pointer comes due.</summary>
public enum DueAction
{
    /// <summary>Open the day's occurrence and send the <c>exerciseDue</c> push.</summary>
    Open,
    /// <summary>Started early and not finished: nothing new opens, but the phone still gets its on-time push.</summary>
    RingStartedEarly,
    /// <summary>The window closed while the server was down: no occurrence, nobody is blamed.</summary>
    SkipDowntime,
    /// <summary>The day is already settled (or opened elsewhere): only move the pointer.</summary>
    None,
}

public static class ExerciseAlarmRules
{
    /// <summary>
    /// Decides one due goal. <paramref name="existing"/> is the occurrence already there for the
    /// due time's local date, if any. Every action also moves the pointer to the next day.
    /// </summary>
    public static DueAction Decide(ExerciseOccurrence? existing, DateTime scheduledAtUtc, int graceMinutes, DateTime utcNow)
    {
        var windowClosed = utcNow > scheduledAtUtc.AddMinutes(graceMinutes);
        if (existing is null) return windowClosed ? DueAction.SkipDowntime : DueAction.Open;
        // A phone without exact alarms may wake late, so a dare left half done early needs the push to ring on time.
        if (existing.Status == ExerciseOccurrenceStatus.InProgress && existing.ScheduledAtUtc == scheduledAtUtc && existing.IsOpenAt(utcNow) && !windowClosed)
            return DueAction.RingStartedEarly;
        return DueAction.None;
    }
}
