using System.Globalization;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Pregnancy;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Pregnancy;

public sealed record PregnancyAppointmentDto(Guid Id, Guid PregnancyId, string Title, DateTime AtUtc, string? Location, string? Notes, Guid CreatedByUserId);

public sealed record KickSessionDto(Guid Id, Guid PregnancyId, DateTime StartedAtUtc, DateTime? EndedAtUtc, int Count, int? DurationSeconds);

public sealed record PregnancyDto(
    Guid Id,
    Guid UserId,
    string UserName,
    bool IsMine,
    string DueDate,
    PregnancyStatus Status,
    bool ShareWithPartner,
    int Week,
    int Day,
    int Trimester,
    int DaysToGo,
    double Progress,
    PregnancyAppointmentDto? NextAppointment,
    DateTime CreatedAtUtc,
    DateTime? EndedAtUtc);

/// <summary>Give either the due date or the first day of the last period (yyyy-MM-dd).</summary>
public sealed record StartPregnancyRequest(string? DueDate, string? LastPeriodStart, bool ShareWithPartner = true);

public sealed record UpdatePregnancyRequest(string DueDate, bool ShareWithPartner);

public sealed record PregnancyAppointmentRequest(string Title, DateTime AtUtc, string? Location, string? Notes);

public sealed record UpdateKickSessionRequest(int Count, bool End);

/// <summary>Sent to whoever may see the pregnancy. Carries no health data: clients refetch.</summary>
public sealed record PregnancyChangedDto(Guid PregnancyId, string Change);

public static class PregnancyRules
{
    /// <summary>A due date more than ~43 weeks out, or more than 3 weeks past, is a typo.</summary>
    public const int MaxDaysAhead = PregnancyMath.MaxTrackedDays;
    public const int MaxDaysOverdue = 21;

    public static Result<DateOnly> ResolveDueDate(string? dueDate, string? lastPeriodStart, DateOnly today)
    {
        DateOnly due;
        if (!string.IsNullOrWhiteSpace(dueDate))
        {
            if (!TryParse(dueDate, out due)) return Invalid("Due date must look like yyyy-MM-dd.");
        }
        else if (!string.IsNullOrWhiteSpace(lastPeriodStart))
        {
            if (!TryParse(lastPeriodStart, out var lmp)) return Invalid("Last period start must look like yyyy-MM-dd.");
            if (lmp > today) return Invalid("The last period cannot start in the future.");
            due = PregnancyMath.DueDateFromLastPeriod(lmp);
        }
        else return Invalid("Give a due date or the first day of the last period.");

        if (due.DayNumber - today.DayNumber > MaxDaysAhead) return Invalid("That due date is too far away.");
        if (today.DayNumber - due.DayNumber > MaxDaysOverdue) return Invalid("That due date is too far in the past.");
        return due;
    }

    public static Result ValidateAppointment(string? title, DateTime atUtc, string? location, string? notes, DateTime utcNow)
    {
        if (string.IsNullOrWhiteSpace(title) || title.Trim().Length > PregnancyAppointment.MaxTitleLength)
            return Error.Validation("pregnancy.invalid_appointment", Loc.T("Title is required, at most {max} characters.", ("max", PregnancyAppointment.MaxTitleLength)));
        if (location?.Trim().Length > PregnancyAppointment.MaxLocationLength)
            return Error.Validation("pregnancy.invalid_appointment", Loc.T("Location must be at most {max} characters.", ("max", PregnancyAppointment.MaxLocationLength)));
        if (notes?.Trim().Length > PregnancyAppointment.MaxNotesLength)
            return Error.Validation("pregnancy.invalid_appointment", Loc.T("Notes must be at most {max} characters.", ("max", PregnancyAppointment.MaxNotesLength)));
        if (atUtc < utcNow.AddYears(-1) || atUtc > utcNow.AddYears(2))
            return Error.Validation("pregnancy.invalid_appointment", Loc.T("Appointment time is out of range."));
        return Result.Success();
    }

    private static bool TryParse(string value, out DateOnly date)
        => DateOnly.TryParseExact(value.Trim(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out date);

    private static Error Invalid(string message) => Error.Validation("pregnancy.invalid_due_date", message);
}

public static class PregnancyMapper
{
    public static PregnancyDto ToDto(Core.Entities.Pregnancy pregnancy, string userName, bool isMine, DateOnly today, PregnancyAppointment? nextAppointment)
    {
        var progress = PregnancyMath.Progress(pregnancy.DueDate, today);
        return new PregnancyDto(
            pregnancy.Id,
            pregnancy.UserId,
            userName,
            isMine,
            pregnancy.DueDate.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            pregnancy.Status,
            pregnancy.ShareWithPartner,
            progress.Week,
            progress.Day,
            progress.Trimester,
            progress.DaysToGo,
            progress.Fraction,
            nextAppointment is null ? null : ToDto(nextAppointment),
            pregnancy.CreatedAtUtc,
            pregnancy.EndedAtUtc);
    }

    public static PregnancyAppointmentDto ToDto(PregnancyAppointment item)
        => new(item.Id, item.PregnancyId, item.Title, item.AtUtc, item.Location, item.Notes, item.CreatedByUserId);

    public static KickSessionDto ToDto(KickSession item)
        => new(item.Id, item.PregnancyId, item.StartedAtUtc, item.EndedAtUtc, item.Count,
            item.EndedAtUtc is { } ended ? (int)(ended - item.StartedAtUtc).TotalSeconds : null);
}
