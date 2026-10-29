using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Twogether.Application.Features.Pregnancy;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Pregnancy;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests;

public sealed class PregnancyTests
{
    private static readonly DateOnly Today = new(2026, 10, 10);
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void Progress_counts_weeks_from_the_last_period_and_splits_trimesters()
    {
        var due = Today.AddDays(280 - 18 * 7 - 3); // 18 weeks and 3 days along today
        var progress = PregnancyMath.Progress(due, Today);
        Assert.Equal((18, 3, 2), (progress.Week, progress.Day, progress.Trimester));
        Assert.Equal(due.DayNumber - Today.DayNumber, progress.DaysToGo);

        Assert.Equal(1, PregnancyMath.Progress(Today.AddDays(280 - 13 * 7 - 6), Today).Trimester); // 13w6d
        Assert.Equal(2, PregnancyMath.Progress(Today.AddDays(280 - 14 * 7), Today).Trimester);     // 14w0d
        Assert.Equal(3, PregnancyMath.Progress(Today.AddDays(280 - 28 * 7), Today).Trimester);     // 28w0d

        var overdue = PregnancyMath.Progress(Today.AddDays(-5), Today);
        Assert.Equal((40, 5, 0, 1.0), (overdue.Week, overdue.Day, overdue.DaysToGo, overdue.Fraction));
        Assert.Equal(Today.AddDays(280), PregnancyMath.DueDateFromLastPeriod(Today));
    }

    [Fact]
    public void Due_date_resolves_from_either_input_and_rejects_typos()
    {
        Assert.Equal(new DateOnly(2027, 3, 14), PregnancyRules.ResolveDueDate("2027-03-14", null, Today).Value);
        Assert.Equal(new DateOnly(2026, 6, 8).AddDays(280), PregnancyRules.ResolveDueDate(null, "2026-06-08", Today).Value);
        Assert.Equal("pregnancy.invalid_due_date", PregnancyRules.ResolveDueDate(null, null, Today).Error.Code);
        Assert.Equal("pregnancy.invalid_due_date", PregnancyRules.ResolveDueDate("14/03/2027", null, Today).Error.Code);
        Assert.Equal("pregnancy.invalid_due_date", PregnancyRules.ResolveDueDate("2028-03-14", null, Today).Error.Code);   // too far ahead
        Assert.Equal("pregnancy.invalid_due_date", PregnancyRules.ResolveDueDate("2026-09-01", null, Today).Error.Code);   // too far past
        Assert.Equal("pregnancy.invalid_due_date", PregnancyRules.ResolveDueDate(null, "2026-11-01", Today).Error.Code);   // period in the future
    }

    [Fact]
    public void Ended_pregnancy_is_frozen()
    {
        var pregnancy = Pregnancy.Start(Guid.NewGuid(), Guid.NewGuid(), Today.AddDays(100), true, Now);
        pregnancy.Update(Today.AddDays(101), false, Now);
        Assert.False(pregnancy.ShareWithPartner);
        pregnancy.End(Now);
        Assert.Equal(PregnancyStatus.Ended, pregnancy.Status);
        Assert.Throws<InvalidOperationException>(() => pregnancy.End(Now));
        Assert.Throws<InvalidOperationException>(() => pregnancy.Update(Today, true, Now));
    }

    [Fact]
    public void Ending_stops_sharing_and_only_the_couple_it_started_in_sees_it()
    {
        var coupleId = Guid.NewGuid();
        var pregnancy = Pregnancy.Start(coupleId, Guid.NewGuid(), Today.AddDays(100), true, Now);
        Assert.True(pregnancy.IsSharedWith(coupleId));
        Assert.False(pregnancy.IsSharedWith(Guid.NewGuid())); // a later partner
        pregnancy.End(Now);
        Assert.False(pregnancy.ShareWithPartner);
        Assert.False(pregnancy.IsSharedWith(coupleId));
    }

    [Fact]
    public void An_overdue_pregnancy_can_still_be_edited_when_its_due_date_stays()
    {
        var due = Today.AddDays(-30); // past the three weeks a new due date may be
        Assert.Equal(due, PregnancyRules.ResolveUpdatedDueDate("2026-09-10", due, Today).Value);
        Assert.Equal("pregnancy.invalid_due_date", PregnancyRules.ResolveUpdatedDueDate("2026-09-11", due, Today).Error.Code);
        Assert.Equal(Today.AddDays(5), PregnancyRules.ResolveUpdatedDueDate("2026-10-15", due, Today).Value);
        Assert.Equal("pregnancy.invalid_due_date", PregnancyRules.ResolveUpdatedDueDate("not a date", due, Today).Error.Code);
    }

    [Fact]
    public void The_shared_notice_is_found_by_the_data_it_was_created_with()
    {
        var id = Guid.Parse("6f9619ff-8b86-d011-b42d-00c04fc964ff");
        Assert.Equal("{\"pregnancyId\":\"6f9619ff-8b86-d011-b42d-00c04fc964ff\"}", PregnancyNotices.SharedData(id));
    }

    [Fact]
    public void Kick_session_count_only_goes_up_and_closes_itself_after_three_hours()
    {
        var session = KickSession.Start(Guid.NewGuid(), Now);
        session.Record(4, end: false, Now.AddMinutes(2));
        session.Record(3, end: false, Now.AddMinutes(3)); // late retry with an older count
        Assert.Equal(4, session.Count);
        Assert.True(session.IsOpen);
        Assert.Throws<ArgumentOutOfRangeException>(() => session.Record(201, false, Now));

        session.Record(10, end: false, Now.AddHours(5)); // app was abandoned: capped at three hours
        Assert.False(session.IsOpen);
        Assert.Equal(Now + KickSession.MaxDuration, session.EndedAtUtc);
        Assert.Throws<InvalidOperationException>(() => session.Record(11, true, Now.AddHours(6)));
        Assert.Equal(3 * 60 * 60, PregnancyMapper.ToDto(session).DurationSeconds);
    }

    [Fact]
    public void Appointment_rules_bound_text_and_time()
    {
        Assert.True(PregnancyRules.ValidateAppointment("Anatomy scan", Now.AddDays(11), "St Nicholas", null, Now).IsSuccess);
        Assert.Equal("pregnancy.invalid_appointment", PregnancyRules.ValidateAppointment(" ", Now, null, null, Now).Error.Code);
        Assert.Equal("pregnancy.invalid_appointment", PregnancyRules.ValidateAppointment("Scan", Now.AddYears(3), null, null, Now).Error.Code);
        Assert.Equal("pregnancy.invalid_appointment", PregnancyRules.ValidateAppointment("Scan", Now, null, new string('x', 1001), Now).Error.Code);
    }

    [Fact]
    public void Children_cascade_with_the_pregnancy_and_only_one_can_be_active_per_person()
    {
        using var context = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>().UseNpgsql("Host=localhost;Database=model-only").Options);
        var model = context.GetService<IDesignTimeModel>().Model;

        var pregnancies = model.FindEntityType(typeof(Pregnancy))!;
        var active = pregnancies.GetIndexes().Single(index => index.IsUnique);
        Assert.Equal("IX_pregnancies_UserId", active.GetDatabaseName());
        Assert.Equal("\"Status\" = 'Active'", active.GetFilter());

        foreach (var child in new[] { typeof(PregnancyAppointment), typeof(KickSession) })
        {
            var foreignKey = model.FindEntityType(child)!.GetForeignKeys().Single();
            Assert.Equal(typeof(Pregnancy), foreignKey.PrincipalEntityType.ClrType);
            Assert.Equal(DeleteBehavior.Cascade, foreignKey.DeleteBehavior);
        }
        Assert.Equal("FK_kick_sessions_pregnancies_PregnancyId", model.FindEntityType(typeof(KickSession))!.GetForeignKeys().Single().GetConstraintName());
    }
}
