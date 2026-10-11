using Twogether.Application.Features.Notifications;
using Twogether.Application.Features.Push;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Notifications;

namespace Twogether.UnitTests;

public sealed class NotificationPreferenceTests
{
    // 22:30 in Lagos (UTC+1, no DST).
    private static readonly DateTime LagosNight = new(2026, 10, 10, 21, 30, 0, DateTimeKind.Utc);
    private static readonly DateTime LagosNoon = new(2026, 10, 10, 11, 0, 0, DateTimeKind.Utc);

    private static NotificationPreference Preference(bool messages = true, bool quiet = true, string start = "22:00", string end = "07:00")
    {
        var preference = NotificationPreference.Create(Guid.NewGuid(), LagosNoon);
        preference.Update(messages, true, true, true, true, true, quiet, TimeOnly.Parse(start), TimeOnly.Parse(end), LagosNoon);
        return preference;
    }

    [Fact]
    public void Every_notification_type_has_a_category()
    {
        foreach (var type in Enum.GetValues<NotificationType>())
            Assert.True(Enum.IsDefined(NotificationRouting.CategoryOf(type)));
    }

    [Fact]
    public void No_preferences_means_every_push_is_normal()
    {
        foreach (var type in Enum.GetValues<NotificationType>())
            Assert.Equal(PushRoute.Normal, NotificationRouting.Route(type, null, "Africa/Lagos", LagosNight));
    }

    [Fact]
    public void A_switched_off_category_is_not_pushed()
    {
        var preference = Preference(messages: false, quiet: false);

        Assert.Equal(PushRoute.Muted, NotificationRouting.Route(NotificationType.Message, preference, "Africa/Lagos", LagosNoon));
        Assert.Equal(PushRoute.Normal, NotificationRouting.Route(NotificationType.GameInvite, preference, "Africa/Lagos", LagosNoon));
    }

    [Fact]
    public void Quiet_hours_follow_the_persons_own_time_zone()
    {
        var preference = Preference();

        Assert.Equal(PushRoute.Quiet, NotificationRouting.Route(NotificationType.Message, preference, "Africa/Lagos", LagosNight));
        Assert.Equal(PushRoute.Normal, NotificationRouting.Route(NotificationType.Message, preference, "Africa/Lagos", LagosNoon));
        // London is on summer time in October: 22:30 there too.
        Assert.Equal(PushRoute.Quiet, NotificationRouting.Route(NotificationType.Message, preference, "Europe/London", LagosNight));
        // In New York it is 17:30.
        Assert.Equal(PushRoute.Normal, NotificationRouting.Route(NotificationType.Message, preference, "America/New_York", LagosNight));
    }

    [Fact]
    public void The_exercise_alarm_ignores_every_setting()
    {
        var preference = Preference(messages: false);
        preference.Update(false, false, false, false, false, false, true, new TimeOnly(0, 0), new TimeOnly(23, 59), LagosNoon);

        Assert.Equal(PushRoute.Normal, NotificationRouting.Route(NotificationType.ExerciseDue, preference, "Africa/Lagos", LagosNight));
        Assert.Equal(PushRoute.Muted, NotificationRouting.Route(NotificationType.ExerciseMissed, preference, "Africa/Lagos", LagosNight));
    }

    [Fact]
    public void Couple_changes_cannot_be_muted_but_arrive_quietly_at_night()
    {
        var preference = Preference();
        preference.Update(false, false, false, false, false, false, true, new TimeOnly(22, 0), new TimeOnly(7, 0), LagosNoon);

        Assert.Equal(PushRoute.Quiet, NotificationRouting.Route(NotificationType.Breakup, preference, "Africa/Lagos", LagosNight));
        Assert.Equal(PushRoute.Normal, NotificationRouting.Route(NotificationType.CoupleConnected, preference, "Africa/Lagos", LagosNoon));
    }

    [Theory]
    [InlineData("22:00", "07:00", "22:00", true)]
    [InlineData("22:00", "07:00", "06:59", true)]
    [InlineData("22:00", "07:00", "07:00", false)]
    [InlineData("22:00", "07:00", "12:00", false)]
    [InlineData("13:00", "15:00", "14:00", true)]
    [InlineData("13:00", "15:00", "15:00", false)]
    public void Quiet_window_is_start_inclusive_end_exclusive_and_may_cross_midnight(string start, string end, string at, bool expected)
        => Assert.Equal(expected, NotificationRouting.IsWithin(TimeOnly.Parse(at), TimeOnly.Parse(start), TimeOnly.Parse(end)));

    [Fact]
    public void Quiet_pushes_use_the_quiet_channel_but_the_alarm_never_does()
    {
        var message = Notification.Create(Guid.NewGuid(), Guid.NewGuid(), NotificationType.Message, "Hi", "there", null, LagosNight);
        var alarm = Notification.Create(Guid.NewGuid(), Guid.NewGuid(), NotificationType.ExerciseDue, "Squats", "now", null, LagosNight);

        var quiet = PushPolicy.Build(message, "t", PushTokenKind.RegistrationToken, LagosNight, PushRoute.Quiet);
        Assert.Equal(PushPolicy.QuietChannel, quiet.ChannelId);
        Assert.Equal("true", quiet.Data["quiet"]);
        Assert.Equal(PushPolicy.GeneralChannel, PushPolicy.Build(message, "t", PushTokenKind.RegistrationToken, LagosNight).ChannelId);
        var ringing = PushPolicy.Build(alarm, "t", PushTokenKind.RegistrationToken, LagosNight, PushRoute.Quiet);
        Assert.Equal(PushPolicy.AlarmChannel, ringing.ChannelId);
        Assert.False(ringing.Data.ContainsKey("quiet"));
    }

    [Theory]
    [InlineData("22:00", "07:00", null, true)]
    [InlineData(null, null, null, true)]
    [InlineData("7:30", "9:00", "Africa/Lagos", true)]
    [InlineData("25:00", "07:00", null, false)]
    [InlineData("10pm", "07:00", null, false)]
    [InlineData("08:00", "08:00", null, false)]
    [InlineData("22:00", "07:00", "Mars/Olympus", false)]
    public void Requests_are_validated(string? start, string? end, string? zone, bool valid)
    {
        var request = new UpdateNotificationPreferencesRequest(true, true, true, true, true, true, true, start, end, zone);
        Assert.Equal(valid, NotificationPreferenceRules.Validate(request).IsSuccess);
    }
}
