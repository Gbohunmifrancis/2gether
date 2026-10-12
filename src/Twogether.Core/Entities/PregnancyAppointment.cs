using Twogether.Core.Seedwork;
using Twogether.Shared.Guards;

namespace Twogether.Core.Entities;

public sealed class PregnancyAppointment : BaseEntity
{
    public const int MaxTitleLength = 120;
    public const int MaxLocationLength = 200;
    public const int MaxNotesLength = 1000;

    private PregnancyAppointment() { }

    public Guid PregnancyId { get; private set; }
    public Guid CreatedByUserId { get; private set; }
    public string Title { get; private set; } = string.Empty;
    public DateTime AtUtc { get; private set; }
    public string? Location { get; private set; }
    public string? Notes { get; private set; }

    public static PregnancyAppointment Create(Guid pregnancyId, Guid createdByUserId, string title, DateTime atUtc, string? location, string? notes, DateTime utcNow)
    {
        var appointment = new PregnancyAppointment { PregnancyId = pregnancyId, CreatedByUserId = createdByUserId, CreatedAtUtc = utcNow };
        appointment.Update(title, atUtc, location, notes, utcNow);
        return appointment;
    }

    public void Update(string title, DateTime atUtc, string? location, string? notes, DateTime utcNow)
    {
        var cleanTitle = Guard.NotNullOrWhiteSpace(title, nameof(title));
        if (cleanTitle.Length > MaxTitleLength) throw new ArgumentException("Title is too long.", nameof(title));
        var cleanLocation = string.IsNullOrWhiteSpace(location) ? null : location.Trim();
        if (cleanLocation?.Length > MaxLocationLength) throw new ArgumentException("Location is too long.", nameof(location));
        var cleanNotes = string.IsNullOrWhiteSpace(notes) ? null : notes.Trim();
        if (cleanNotes?.Length > MaxNotesLength) throw new ArgumentException("Notes are too long.", nameof(notes));
        Title = cleanTitle;
        AtUtc = DateTime.SpecifyKind(atUtc, DateTimeKind.Utc);
        Location = cleanLocation;
        Notes = cleanNotes;
        UpdatedAtUtc = utcNow;
    }
}
