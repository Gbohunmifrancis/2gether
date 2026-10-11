using System.Globalization;
using Twogether.Core.Entities;
using Twogether.Core.Exercise;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Notifications;

/// <summary>
/// Times are local "HH:mm" in <see cref="TimeZoneId"/>, the person's own zone, which is also
/// saved on their profile. The exercise alarm and couple changes ignore every setting here.
/// </summary>
public sealed record NotificationPreferencesDto(
    bool Messages,
    bool Games,
    bool Exercise,
    bool Cycle,
    bool Pregnancy,
    bool DailyQuestions,
    bool Album,
    bool QuietHoursEnabled,
    string QuietStart,
    string QuietEnd,
    string TimeZoneId,
    bool QuietNow);

public sealed record UpdateNotificationPreferencesRequest(
    bool Messages,
    bool Games,
    bool Exercise,
    bool Cycle,
    bool Pregnancy,
    bool DailyQuestions,
    bool QuietHoursEnabled,
    string? QuietStart,
    string? QuietEnd,
    string? TimeZoneId,
    // Optional so apps built before the album category keep it as it was instead of switching it off.
    bool? Album = null);

public sealed record ValidNotificationPreferences(TimeOnly QuietStart, TimeOnly QuietEnd, string? TimeZoneId);

public static class NotificationPreferenceRules
{
    public static Result<ValidNotificationPreferences> Validate(UpdateNotificationPreferencesRequest request)
    {
        var start = ParseTime(request.QuietStart) ?? (request.QuietStart is null ? NotificationPreference.DefaultQuietStart : (TimeOnly?)null);
        var end = ParseTime(request.QuietEnd) ?? (request.QuietEnd is null ? NotificationPreference.DefaultQuietEnd : (TimeOnly?)null);
        if (start is null || end is null)
            return Error.Validation("notifications.invalid_time", "Quiet hours use 24-hour times like 22:00.");
        if (start == end)
            return Error.Validation("notifications.empty_window", "Quiet hours must start and end at different times.");
        string? zone = null;
        if (!string.IsNullOrWhiteSpace(request.TimeZoneId))
        {
            if (!ExerciseSchedule.IsKnownTimeZone(request.TimeZoneId))
                return Error.Validation("notifications.invalid_time_zone", "That time zone is not recognised.");
            zone = request.TimeZoneId.Trim();
        }
        return new ValidNotificationPreferences(start.Value, end.Value, zone);
    }

    public static NotificationPreferencesDto ToDto(NotificationPreference? preference, string timeZoneId, DateTime utcNow)
    {
        var effective = preference ?? NotificationPreference.Create(Guid.Empty, utcNow);
        return new NotificationPreferencesDto(
            effective.MessagesEnabled,
            effective.GamesEnabled,
            effective.ExerciseEnabled,
            effective.CycleEnabled,
            effective.PregnancyEnabled,
            effective.DailyQuestionsEnabled,
            effective.AlbumEnabled,
            effective.QuietHoursEnabled,
            Format(effective.QuietStartLocal),
            Format(effective.QuietEndLocal),
            timeZoneId,
            effective.IsQuietAt(Core.Notifications.NotificationRouting.LocalTime(utcNow, timeZoneId)));
    }

    private static TimeOnly? ParseTime(string? value)
        => TimeOnly.TryParseExact(value?.Trim(), ["HH:mm", "H:mm"], CultureInfo.InvariantCulture, DateTimeStyles.None, out var time) ? time : null;

    private static string Format(TimeOnly time) => time.ToString("HH:mm", CultureInfo.InvariantCulture);
}
