using Twogether.Application.Features.Location;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class LocationSharingTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);
    private static CoupleLocation Sharing(Guid coupleId) => CoupleLocation.Create(coupleId, Guid.NewGuid(), 6.45, 3.39, 12, Now.AddMinutes(-5));

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
        var ada = Sharing(coupleId);
        var femi = Sharing(coupleId);
        ada.Pause(Now.AddHours(1), Now);

        Assert.Equal(LocationSharingStatus.Paused, LocationSharingRules.StatusOf(ada, Now));
        Assert.False(LocationSharingRules.CanSee(femi, ada, Now));
        Assert.False(LocationSharingRules.CanSee(ada, femi, Now));
        Assert.Throws<InvalidOperationException>(() => ada.Update(1, 1, 1, Now.AddMinutes(10))); // fixes during a pause are dropped

        var later = Now.AddHours(1).AddSeconds(1);
        Assert.Equal(LocationSharingStatus.Sharing, LocationSharingRules.StatusOf(ada, later));
        Assert.True(LocationSharingRules.CanSee(femi, ada, later));
        ada.Update(1, 1, 1, later);
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
        var paused = LocationSharingRules.State(row.UserId, "Ada", row, Now);
        Assert.Equal((LocationSharingStatus.Paused, Now.AddMinutes(30)), (paused.Status, paused.PausedUntilUtc));

        row.StopSharing(Now);
        var off = LocationSharingRules.State(row.UserId, "Ada", row, Now);
        Assert.Equal(LocationSharingStatus.Off, off.Status);
        Assert.Null(off.PausedUntilUtc);
        Assert.Null(off.LastUpdatedAtUtc);
        Assert.Equal(LocationSharingStatus.Off, LocationSharingRules.StatusOf(null, Now));
    }
}
