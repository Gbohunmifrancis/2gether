using Microsoft.EntityFrameworkCore;
using Twogether.Application.Features.Location;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests;

public sealed class LocationSharingTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);

    private static CoupleLocation Sharing(Guid coupleId, DateTime? at = null)
    {
        var when = at ?? Now.AddMinutes(-5);
        var row = CoupleLocation.Start(coupleId, Guid.NewGuid(), when);
        Assert.True(row.TryRecordFix(6.45, 3.39, 12, when, when));
        return row;
    }

    [Fact]
    public void Partners_see_each_other_only_while_both_share()
    {
        var coupleId = Guid.NewGuid();
        var ada = Sharing(coupleId);
        var femi = Sharing(coupleId);
        Assert.True(LocationSharingRules.CanSee(ada, femi, Now));

        femi.StopSharing(Now);
        Assert.False(LocationSharingRules.CanSee(ada, femi, Now));
        Assert.False(LocationSharingRules.CanSee(femi, ada, Now)); // stopping hides the partner from you too
        Assert.False(LocationSharingRules.CanSee(ada, null, Now));
        Assert.False(LocationSharingRules.CanSee(null, ada, Now));
    }

    [Fact]
    public void Pause_hides_both_ways_and_expires_on_its_own()
    {
        var coupleId = Guid.NewGuid();
        var ada = Sharing(coupleId, Now);
        var femi = Sharing(coupleId, Now);
        ada.Pause(Now.AddHours(1), Now);

        Assert.Equal(LocationSharingStatus.Paused, LocationSharingRules.StatusOf(ada, Now));
        Assert.False(LocationSharingRules.CanSee(femi, ada, Now));
        Assert.False(LocationSharingRules.CanSee(ada, femi, Now));
        ada.Heartbeat(Now.AddMinutes(5));
        Assert.False(ada.TryRecordFix(1, 1, 1, Now.AddMinutes(10), Now.AddMinutes(10))); // fixes during a pause are dropped
        Assert.Equal(6.45, ada.Latitude);

        // The phone keeps its heartbeat going through the pause; after it, both see each other again.
        var later = Now.AddHours(1).AddSeconds(1);
        ada.Heartbeat(later);
        femi.Heartbeat(later);
        Assert.Equal(LocationSharingStatus.Sharing, LocationSharingRules.StatusOf(ada, later));
        Assert.True(LocationSharingRules.CanSee(femi, ada, later));
        Assert.True(ada.TryRecordFix(1, 1, 1, later, later));
        Assert.Null(ada.PausedUntilUtc);
    }

    [Fact]
    public void Pause_needs_an_active_share_and_a_bounded_length()
    {
        var row = Sharing(Guid.NewGuid());
        Assert.Throws<ArgumentException>(() => row.Pause(Now, Now));
        row.StopSharing(Now);
        Assert.Throws<InvalidOperationException>(() => row.Pause(Now.AddHours(1), Now));
        Assert.Equal("location.invalid_pause", LocationSharingRules.PauseUntil(5, Now).Error.Code);
        Assert.Equal("location.invalid_pause", LocationSharingRules.PauseUntil(25 * 60, Now).Error.Code);
        Assert.Equal(Now.AddMinutes(60), LocationSharingRules.PauseUntil(60, Now).Value);
    }

    [Fact]
    public void State_exposes_pause_end_but_never_coordinates_and_nothing_when_off()
    {
        var row = Sharing(Guid.NewGuid());
        row.Pause(Now.AddMinutes(30), Now);
        var paused = LocationSharingRules.State(row.UserId, "Ada", row, Now, withLastFix: true);
        Assert.Equal((LocationSharingStatus.Paused, Now.AddMinutes(30)), (paused.Status, paused.PausedUntilUtc));

        row.StopSharing(Now);
        var off = LocationSharingRules.State(row.UserId, "Ada", row, Now, withLastFix: true);
        Assert.Equal(LocationSharingStatus.Off, off.Status);
        Assert.Null(off.PausedUntilUtc);
        Assert.Null(off.LastUpdatedAtUtc);
        Assert.Equal(LocationSharingStatus.Off, LocationSharingRules.StatusOf(null, Now));
    }

    [Fact]
    public void A_fix_never_starts_a_stopped_share()
    {
        var row = Sharing(Guid.NewGuid());
        row.StopSharing(Now);
        Assert.False(row.TryRecordFix(1, 1, 1, Now.AddMinutes(1), Now.AddMinutes(1)));
        Assert.False(row.SharingEnabled);
        Assert.False(row.HasFix);
        Assert.False(row.Heartbeat(Now.AddMinutes(1)));
        Assert.Equal(LocationSharingStatus.Off, LocationSharingRules.StatusOf(row, Now.AddMinutes(1)));

        row.StartSharing(Now.AddMinutes(2));
        Assert.Equal(LocationSharingStatus.Sharing, LocationSharingRules.StatusOf(row, Now.AddMinutes(2)));
        Assert.False(row.IsVisibleAt(Now.AddMinutes(2))); // nothing to show until the first fix
        Assert.True(row.TryRecordFix(1, 1, 1, Now.AddMinutes(2), Now.AddMinutes(2)));
        Assert.True(row.IsVisibleAt(Now.AddMinutes(2)));
    }

    [Fact]
    public void Stopping_wipes_the_point()
    {
        var row = Sharing(Guid.NewGuid());
        row.StopSharing(Now);
        Assert.Null(row.Latitude);
        Assert.Null(row.Longitude);
        Assert.Null(row.AccuracyMeters);
        Assert.Null(row.FixAtUtc);
    }

    [Fact]
    public void A_share_whose_phone_goes_quiet_lapses_for_both_partners()
    {
        var coupleId = Guid.NewGuid();
        var ada = Sharing(coupleId, Now);
        var femi = Sharing(coupleId, Now);
        var almost = Now + CoupleLocation.Lease - TimeSpan.FromSeconds(1);
        femi.Heartbeat(almost);
        Assert.True(LocationSharingRules.CanSee(femi, ada, almost));

        var lapsed = Now + CoupleLocation.Lease;
        femi.Heartbeat(lapsed);
        Assert.Equal(LocationSharingStatus.Off, LocationSharingRules.StatusOf(ada, lapsed));
        Assert.False(LocationSharingRules.CanSee(femi, ada, lapsed));
        Assert.False(LocationSharingRules.CanSee(ada, femi, lapsed));
        Assert.Null(LocationSharingRules.State(ada.UserId, "Ada", ada, lapsed, withLastFix: true).LastUpdatedAtUtc);

        // A heartbeat keeps a still phone alive; starting again after a lapse drops the frozen point.
        var heard = CoupleLocation.Start(coupleId, Guid.NewGuid(), Now);
        heard.TryRecordFix(1, 1, 1, Now, Now);
        heard.Heartbeat(Now.AddMinutes(4));
        heard.Heartbeat(Now.AddMinutes(8));
        Assert.Equal(LocationSharingStatus.Sharing, LocationSharingRules.StatusOf(heard, Now.AddMinutes(12)));
        ada.StartSharing(lapsed);
        Assert.False(ada.HasFix);
    }

    [Fact]
    public void Starting_while_still_live_keeps_the_point_and_ends_a_pause()
    {
        var row = Sharing(Guid.NewGuid(), Now);
        row.Pause(Now.AddHours(1), Now);
        row.StartSharing(Now.AddMinutes(1));
        Assert.Null(row.PausedUntilUtc);
        Assert.True(row.HasFix);
        Assert.True(row.IsVisibleAt(Now.AddMinutes(1)));
    }

    [Fact]
    public void The_point_carries_the_time_of_the_fix_capped_at_the_server_clock_and_late_fixes_are_dropped()
    {
        var row = Sharing(Guid.NewGuid(), Now);
        row.Pause(Now.AddMinutes(20), Now.AddMinutes(1));
        row.Resume(Now.AddMinutes(2));
        Assert.Equal(Now, row.FixAtUtc); // pause and resume do not make the point look new

        Assert.True(row.TryRecordFix(2, 2, 5, Now.AddHours(3), Now.AddMinutes(3))); // phone clock ahead
        Assert.Equal(Now.AddMinutes(3), row.FixAtUtc);
        Assert.False(row.TryRecordFix(3, 3, 5, Now.AddMinutes(2), Now.AddMinutes(4))); // delivered late
        Assert.Equal(2, row.Latitude);
        Assert.Equal(Now.AddMinutes(4), row.LastSeenAtUtc); // but the phone is still there

        Assert.Null(LocationSharingRules.FixTime(0));
        Assert.Null(LocationSharingRules.FixTime(-5));
        Assert.Equal(Now, LocationSharingRules.FixTime(new DateTimeOffset(Now).ToUnixTimeMilliseconds()));
    }

    [Fact]
    public void Status_shows_the_partner_last_fix_only_while_mutual()
    {
        var coupleId = Guid.NewGuid();
        var ada = Sharing(coupleId, Now);
        var femi = Sharing(coupleId, Now);
        var both = LocationSharingRules.Status(ada.UserId, "Ada", ada, femi.UserId, "Femi", femi, Now);
        Assert.True(both.Mutual);
        Assert.Equal(Now, both.Partner!.LastUpdatedAtUtc);

        ada.StopSharing(Now);
        var onlyFemi = LocationSharingRules.Status(ada.UserId, "Ada", ada, femi.UserId, "Femi", femi, Now);
        Assert.False(onlyFemi.Mutual);
        Assert.Equal(LocationSharingStatus.Sharing, onlyFemi.Partner!.Status); // the state is never hidden
        Assert.Null(onlyFemi.Partner.LastUpdatedAtUtc); // but not when Femi last moved

        var femiView = LocationSharingRules.Status(femi.UserId, "Femi", femi, ada.UserId, "Ada", ada, Now);
        Assert.Equal(Now, femiView.Me.LastUpdatedAtUtc); // your own is always yours to see
    }

    [Fact]
    public void Fixes_must_be_finite_and_in_range()
    {
        Assert.True(LocationSharingRules.IsValidFix(6.45, 3.39, 12));
        Assert.False(LocationSharingRules.IsValidFix(double.NaN, 3.39, 12));
        Assert.False(LocationSharingRules.IsValidFix(6.45, double.PositiveInfinity, 12));
        Assert.False(LocationSharingRules.IsValidFix(6.45, 3.39, double.NaN));
        Assert.False(LocationSharingRules.IsValidFix(91, 3.39, 12));
        Assert.False(LocationSharingRules.IsValidFix(6.45, -181, 12));
        Assert.False(LocationSharingRules.IsValidFix(6.45, 3.39, -1));
    }

    [Fact]
    public void Fixes_closer_than_the_minimum_gap_are_dropped_per_person()
    {
        var throttle = new LocationFixThrottle();
        var ada = Guid.NewGuid();
        var femi = Guid.NewGuid();
        Assert.True(throttle.TryAccept(ada, Now));
        Assert.False(throttle.TryAccept(ada, Now.AddSeconds(2)));
        Assert.True(throttle.TryAccept(femi, Now.AddSeconds(2)));
        Assert.True(throttle.TryAccept(ada, Now + LocationFixThrottle.MinGap));
    }

    [Fact]
    public void Location_rows_map_nullable_coordinates_and_the_sweep_index()
    {
        using var context = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>().UseNpgsql("Host=localhost;Database=model-only").Options);
        var entity = context.Model.FindEntityType(typeof(CoupleLocation))!;
        Assert.True(entity.FindProperty(nameof(CoupleLocation.Latitude))!.IsNullable);
        Assert.True(entity.FindProperty(nameof(CoupleLocation.FixAtUtc))!.IsNullable);
        Assert.False(entity.FindProperty(nameof(CoupleLocation.LastSeenAtUtc))!.IsNullable);
        Assert.Null(entity.FindProperty(nameof(CoupleLocation.HasFix)));
        Assert.Contains("IX_couple_locations_UpdatedAtUtc", entity.GetIndexes().Select(index => index.GetDatabaseName()));
    }
}
