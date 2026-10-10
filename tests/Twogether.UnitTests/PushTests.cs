using MessagingErrorCode = FirebaseAdmin.Messaging.MessagingErrorCode;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Twogether.Application.Features.Push;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.Infrastructure.Push;

namespace Twogether.UnitTests;

public sealed class PushTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);
    private static Notification Note(NotificationType type, DateTime createdAt, string? data = null)
        => Notification.Create(Guid.NewGuid(), Guid.NewGuid(), type, "Time to move", "Squats: 15 reps.", data, createdAt);

    [Fact]
    public void Alarm_pushes_are_high_priority_data_messages_on_the_alarm_channel()
    {
        var alarm = PushPolicy.Build(Note(NotificationType.ExerciseDue, Now.AddMinutes(-5), "{\"occurrenceId\":\"x\"}"), "tok", PushTokenKind.InstallationId, Now);
        Assert.Equal((PushPriority.High, PushPolicy.AlarmChannel, true), (alarm.Priority, alarm.ChannelId, alarm.DataOnly));
        Assert.Equal(TimeSpan.FromMinutes(25), alarm.TimeToLive); // only what is left of the 30-minute window
        Assert.Equal("exerciseDue", alarm.Data["type"]);
        Assert.Equal("{\"occurrenceId\":\"x\"}", alarm.Data["data"]);
        Assert.Equal(PushTokenKind.InstallationId, alarm.TokenKind);

        var note = Note(NotificationType.DailyQuestionAnswered, Now);
        var normal = PushPolicy.Build(note, "tok", PushTokenKind.RegistrationToken, Now);
        Assert.Equal((PushPriority.Normal, PushPolicy.GeneralChannel, false), (normal.Priority, normal.ChannelId, normal.DataOnly));
        Assert.Equal(note.Id.ToString(), normal.Data["notificationId"]);
        Assert.False(normal.Data.ContainsKey("data"));
    }

    [Fact]
    public void Stale_pushes_are_not_worth_sending()
    {
        Assert.True(PushPolicy.IsStale(Note(NotificationType.ExerciseDue, Now.AddMinutes(-31)), Now));
        Assert.False(PushPolicy.IsStale(Note(NotificationType.ExerciseDue, Now.AddMinutes(-29)), Now));
        Assert.False(PushPolicy.IsStale(Note(NotificationType.ExerciseMissed, Now.AddHours(-23)), Now));
        Assert.True(PushPolicy.IsStale(Note(NotificationType.Message, Now.AddHours(-2)), Now));
    }

    [Theory]
    [InlineData(new PushOutcome[0], PushDecision.Skip)]
    [InlineData(new[] { PushOutcome.Delivered, PushOutcome.RetryLater }, PushDecision.Sent)]
    [InlineData(new[] { PushOutcome.InvalidToken, PushOutcome.RetryLater }, PushDecision.Retry)]
    [InlineData(new[] { PushOutcome.InvalidToken, PushOutcome.InvalidToken }, PushDecision.Skip)]
    [InlineData(new[] { PushOutcome.InvalidToken, PushOutcome.Rejected }, PushDecision.Fail)]
    public void One_decision_per_notification_from_its_device_results(PushOutcome[] outcomes, PushDecision expected)
        => Assert.Equal(expected, PushPolicy.Decide(outcomes));

    [Fact]
    public void Retries_back_off_and_give_up_after_the_budget()
    {
        Assert.Equal(TimeSpan.FromSeconds(15), PushPolicy.RetryDelay(0));
        Assert.Equal(TimeSpan.FromSeconds(60), PushPolicy.RetryDelay(2));
        Assert.Equal(TimeSpan.FromMinutes(10), PushPolicy.RetryDelay(20));

        var note = Note(NotificationType.ExerciseMissed, Now);
        for (var attempt = 1; attempt < PushPolicy.MaxAttempts; attempt++)
        {
            note.RetryPush(Now.AddMinutes(attempt), PushPolicy.MaxAttempts, Now);
            Assert.Equal(PushState.Pending, note.PushState);
        }
        note.RetryPush(Now.AddHours(1), PushPolicy.MaxAttempts, Now);
        Assert.Equal((PushState.Failed, PushPolicy.MaxAttempts), (note.PushState, note.PushAttempts));
    }

    [Fact]
    public void Only_errors_that_prove_a_dead_token_disable_a_device()
    {
        Assert.Equal(PushOutcome.InvalidToken, FcmErrorMapping.Map(MessagingErrorCode.Unregistered));
        Assert.Equal(PushOutcome.InvalidToken, FcmErrorMapping.Map(MessagingErrorCode.SenderIdMismatch));
        Assert.Equal(PushOutcome.RetryLater, FcmErrorMapping.Map(MessagingErrorCode.QuotaExceeded));
        Assert.Equal(PushOutcome.RetryLater, FcmErrorMapping.Map(MessagingErrorCode.Unavailable));
        Assert.Equal(PushOutcome.RetryLater, FcmErrorMapping.Map(null));
        Assert.Equal(PushOutcome.Rejected, FcmErrorMapping.Map(MessagingErrorCode.InvalidArgument));
    }

    [Fact]
    public void A_token_moves_to_whoever_registers_it_last_and_comes_back_to_life()
    {
        var ada = Guid.NewGuid();
        var femi = Guid.NewGuid();
        var device = DeviceRegistration.Register(ada, DevicePlatform.Android, " tok-123 ", "1.0.0", Now);
        Assert.Equal("tok-123", device.Token);
        device.Disable("signed_out", Now);
        Assert.False(device.IsActive);
        device.Refresh(femi, DevicePlatform.Android, "1.1.0", Now.AddDays(1), PushTokenKind.InstallationId);
        Assert.True(device.IsActive);
        Assert.Equal((femi, "1.1.0", PushTokenKind.InstallationId), (device.UserId, device.AppVersion, device.TokenKind));
        Assert.Throws<ArgumentException>(() => DeviceRegistration.Register(ada, DevicePlatform.Android, new string('t', 1025), null, Now));
        Assert.Equal("...abcdef", PushPolicy.TokenSuffix("0123456789abcdef"));
    }

    [Fact]
    public void Pending_pushes_have_a_partial_index_and_tokens_are_unique()
    {
        using var context = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>().UseNpgsql("Host=localhost;Database=model-only").Options);
        var model = context.GetService<IDesignTimeModel>().Model;
        var pending = model.FindEntityType(typeof(Notification))!.GetIndexes().Single(index => index.GetDatabaseName() == "IX_notifications_pending_push");
        Assert.Equal("\"PushState\" = 'Pending'", pending.GetFilter());
        Assert.True(model.FindEntityType(typeof(DeviceRegistration))!.GetIndexes().Single(index => index.IsUnique).Properties.Single().Name == nameof(DeviceRegistration.Token));
    }
}
