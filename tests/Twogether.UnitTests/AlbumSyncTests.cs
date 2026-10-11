using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Twogether.Application.Features.Memories;
using Twogether.Application.Features.Push;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Memories;
using Twogether.Core.Notifications;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests;

public sealed class AlbumSyncStateTests
{
    private static readonly DateTime Now = new(2026, 10, 11, 9, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void Pending_since_starts_when_the_queue_fills_survives_changes_and_clears_when_empty()
    {
        var state = AlbumSyncState.Create(Guid.NewGuid(), Now);
        Assert.Null(state.PendingSinceUtc);

        state.Report(3, 9_000, Now.AddMinutes(1));
        Assert.Equal(Now.AddMinutes(1), state.PendingSinceUtc);

        // More photos join the queue: they have not been waiting as long, but the queue has.
        state.Report(7, 21_000, Now.AddHours(2));
        Assert.Equal(Now.AddMinutes(1), state.PendingSinceUtc);
        Assert.Equal((7, 21_000L, Now.AddHours(2)), (state.PendingCount, state.PendingBytes, state.ReportedAtUtc));

        state.Report(0, 0, Now.AddHours(3));
        Assert.Null(state.PendingSinceUtc);

        state.Report(1, 100, Now.AddHours(4));
        Assert.Equal(Now.AddHours(4), state.PendingSinceUtc);
    }

    [Theory]
    [InlineData(-1, 0)]
    [InlineData(AlbumSyncRules.MaxPendingCount + 1, 0)]
    [InlineData(0, 1)]
    [InlineData(1, -1)]
    [InlineData(1, MemoryRules.MaxVideoBytes + 1)]
    public void Impossible_reports_are_refused(int count, long bytes)
    {
        var state = AlbumSyncState.Create(Guid.NewGuid(), Now);
        Assert.Throws<ArgumentOutOfRangeException>(() => state.Report(count, bytes, Now));
        Assert.Equal(0, state.PendingCount);
    }

    [Fact]
    public void A_sync_keeps_its_start_for_a_day_then_restarts_and_finishing_clears_it()
    {
        var state = AlbumSyncState.Create(Guid.NewGuid(), Now);
        state.StartSync(Now);
        state.StartSync(Now.AddHours(23).AddMinutes(59));
        Assert.Equal(Now, state.SyncStartedAtUtc);

        state.StartSync(Now.AddHours(24));
        Assert.Equal(Now.AddHours(24), state.SyncStartedAtUtc);

        state.FinishSync(Now.AddHours(24).AddMinutes(5));
        Assert.Null(state.SyncStartedAtUtc);
        Assert.Equal(Now.AddHours(24).AddMinutes(5), state.LastSyncedAtUtc);
    }
}

public sealed class AlbumSyncReminderRuleTests
{
    private static readonly DateTime Now = new(2026, 10, 11, 18, 0, 0, DateTimeKind.Utc);

    /// <summary>Pending since <paramref name="since"/>, last reported at <paramref name="reported"/>.</summary>
    private static AlbumSyncState State(DateTime since, DateTime? reported = null, DateTime? prompted = null, int count = 4)
    {
        var state = AlbumSyncState.Create(Guid.NewGuid(), since);
        state.Report(count, count * 1000L, since);
        if (reported is { } at) state.Report(count, count * 1000L, at);
        if (prompted is { } promptedAt) state.MarkPrompted(promptedAt);
        return state;
    }

    [Fact]
    public void Photos_must_have_waited_six_hours()
    {
        Assert.True(AlbumSyncRules.IsDueForReminder(State(Now.AddHours(-6)), Now));
        Assert.False(AlbumSyncRules.IsDueForReminder(State(Now.AddHours(-6).AddSeconds(1)), Now));
    }

    [Fact]
    public void At_most_one_reminder_a_day()
    {
        var since = Now.AddDays(-2);
        Assert.True(AlbumSyncRules.IsDueForReminder(State(since, Now.AddHours(-1), prompted: Now.AddHours(-24)), Now));
        Assert.False(AlbumSyncRules.IsDueForReminder(State(since, Now.AddHours(-1), prompted: Now.AddHours(-24).AddSeconds(1)), Now));
    }

    [Fact]
    public void A_phone_silent_for_more_than_a_week_is_left_alone()
    {
        var since = Now.AddDays(-10);
        Assert.True(AlbumSyncRules.IsDueForReminder(State(since, reported: Now.AddDays(-7)), Now));
        Assert.False(AlbumSyncRules.IsDueForReminder(State(since, reported: Now.AddDays(-7).AddSeconds(-1)), Now));
    }

    [Fact]
    public void Nothing_waiting_means_no_reminder()
    {
        var state = State(Now.AddDays(-1));
        state.Report(0, 0, Now.AddMinutes(-1));
        Assert.False(AlbumSyncRules.IsDueForReminder(state, Now));
        Assert.False(AlbumSyncRules.IsDueForReminder(AlbumSyncState.Create(Guid.NewGuid(), Now.AddDays(-1)), Now));
    }
}

public sealed class MemorySyncRequestTests
{
    private static SyncMemoryItem Item(Guid? clientId = null, long size = 1000)
        => new(clientId ?? Guid.NewGuid(), "image/jpeg", size, null, null, MemorySource.Camera);

    private static SyncMemoriesRequest Batch(int count) => new(Enumerable.Range(0, count).Select(_ => Item()).ToList());

    [Fact]
    public void A_batch_holds_one_to_fifty_distinct_items()
    {
        Assert.True(MemorySyncRules.ValidateBatch(Batch(1)).IsSuccess);
        Assert.True(MemorySyncRules.ValidateBatch(Batch(AlbumSyncRules.MaxBatchItems)).IsSuccess);
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateBatch(Batch(AlbumSyncRules.MaxBatchItems + 1)).Error.Code);
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateBatch(Batch(0)).Error.Code);
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateBatch(new SyncMemoriesRequest(null)).Error.Code);
    }

    [Fact]
    public void Duplicate_or_empty_client_ids_fail_the_whole_batch()
    {
        var id = Guid.NewGuid();
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateBatch(new SyncMemoriesRequest([Item(id), Item(), Item(id)])).Error.Code);
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateBatch(new SyncMemoriesRequest([Item(), Item(Guid.Empty)])).Error.Code);
    }

    [Fact]
    public void A_bad_item_does_not_fail_the_batch_and_is_checked_by_the_single_upload_rules()
    {
        var now = new DateTime(2026, 10, 11, 9, 0, 0, DateTimeKind.Utc);
        var tooLarge = Item(size: MemoryRules.MaxPhotoBytes + 1);
        Assert.True(MemorySyncRules.ValidateBatch(new SyncMemoriesRequest([Item(), tooLarge])).IsSuccess);
        Assert.Equal("memories.too_large", MemoryRequestRules.ValidateReserve(tooLarge.ToReserveRequest(), now).Error.Code);
        Assert.True(MemoryRequestRules.ValidateReserve(Item().ToReserveRequest(), now).IsSuccess);
    }

    [Fact]
    public void Completion_takes_one_to_fifty_distinct_ids()
    {
        Assert.True(MemorySyncRules.ValidateComplete(new CompleteSyncRequest([Guid.NewGuid()])).IsSuccess);
        Assert.True(new CompleteSyncRequest([Guid.NewGuid()]).Finished);
        var id = Guid.NewGuid();
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateComplete(new CompleteSyncRequest([id, id])).Error.Code);
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateComplete(new CompleteSyncRequest([])).Error.Code);
        Assert.Equal("memories.invalid_batch", MemorySyncRules.ValidateComplete(new CompleteSyncRequest(Enumerable.Range(0, 51).Select(_ => Guid.NewGuid()).ToList())).Error.Code);
    }

    [Theory]
    [InlineData(0, 0, true)]
    [InlineData(12, 40_000_000, true)]
    [InlineData(AlbumSyncRules.MaxPendingCount, 0, true)]
    [InlineData(AlbumSyncRules.MaxPendingCount + 1, 0, false)]
    [InlineData(-1, 0, false)]
    [InlineData(2, -5, false)]
    [InlineData(0, 10, false)]
    [InlineData(1, MemoryRules.MaxVideoBytes + 1, false)]
    public void Pending_reports_are_range_checked(int count, long bytes, bool valid)
    {
        var result = MemorySyncRules.ValidatePending(new ReportPendingRequest(count, bytes));
        Assert.Equal(valid, result.IsSuccess);
        if (!valid) Assert.Equal("memories.invalid_pending", result.Error.Code);
    }

    [Fact]
    public void Sync_reservations_remember_the_phones_id()
    {
        var clientId = Guid.NewGuid();
        var memory = Memory.Reserve(Guid.NewGuid(), Guid.NewGuid(), MemoryRules.Kind("image/jpeg")!, 1000, null, null, MemorySource.Camera, DateTime.UtcNow, clientId);
        Assert.Equal(clientId, memory.ClientId);
        Assert.Null(Memory.Reserve(Guid.NewGuid(), Guid.NewGuid(), MemoryRules.Kind("image/jpeg")!, 1000, null, null, MemorySource.Camera, DateTime.UtcNow).ClientId);
        Assert.Throws<ArgumentException>(() => Memory.Reserve(Guid.NewGuid(), Guid.NewGuid(), MemoryRules.Kind("image/jpeg")!, 1000, null, null, MemorySource.Camera, DateTime.UtcNow, Guid.Empty));
    }

    [Fact]
    public void Notification_text_counts_photos_in_plain_words()
    {
        Assert.Equal("Maya added 1 photo to your album.", MemorySyncText.SyncedBody("Maya", 1));
        Assert.Equal("Maya added 12 photos to your album.", MemorySyncText.SyncedBody("Maya", 12));
        Assert.Equal("12 photos are waiting on this phone. Sync them to share with Theo.", MemorySyncText.ReminderBody(12, "Theo"));
        Assert.Equal("1 photo is waiting on this phone. Sync it to share with Theo.", MemorySyncText.ReminderBody(1, "Theo"));
    }
}

public sealed class AlbumNotificationTests
{
    private static readonly DateTime Noon = new(2026, 10, 10, 11, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void Album_notifications_have_their_own_category_that_can_be_switched_off()
    {
        Assert.Equal(NotificationCategory.Album, NotificationRouting.CategoryOf(NotificationType.MemoriesSynced));
        Assert.Equal(NotificationCategory.Album, NotificationRouting.CategoryOf(NotificationType.AlbumSyncReminder));
        Assert.True(NotificationRouting.CanMute(NotificationCategory.Album));

        var preference = NotificationPreference.Create(Guid.NewGuid(), Noon);
        Assert.True(preference.AlbumEnabled);
        preference.Update(true, true, true, true, true, true, false, false, new TimeOnly(22, 0), new TimeOnly(7, 0), Noon);
        Assert.Equal(PushRoute.Muted, NotificationRouting.Route(NotificationType.AlbumSyncReminder, preference, "UTC", Noon));
        Assert.Equal(PushRoute.Muted, NotificationRouting.Route(NotificationType.MemoriesSynced, preference, "UTC", Noon));
        Assert.Equal(PushRoute.Normal, NotificationRouting.Route(NotificationType.Message, preference, "UTC", Noon));
    }

    [Fact]
    public void Preferences_from_older_apps_leave_the_album_setting_alone_and_report_it()
    {
        var request = new Twogether.Application.Features.Notifications.UpdateNotificationPreferencesRequest(true, true, true, true, true, true, false, null, null, null);
        Assert.Null(request.Album);
        var preference = NotificationPreference.Create(Guid.NewGuid(), Noon);
        preference.Update(true, true, true, true, true, true, false, false, new TimeOnly(22, 0), new TimeOnly(7, 0), Noon);
        Assert.False(Twogether.Application.Features.Notifications.NotificationPreferenceRules.ToDto(preference, "UTC", Noon).Album);
    }

    [Fact]
    public void The_sync_reminder_is_held_by_the_app_until_wifi_so_it_is_data_only()
    {
        var reminder = Notification.Create(Guid.NewGuid(), Guid.NewGuid(), NotificationType.AlbumSyncReminder, MemorySyncText.ReminderTitle,
            MemorySyncText.ReminderBody(3, "Theo"), "{\"pendingCount\":3,\"preferWifi\":true}", Noon);
        var push = PushPolicy.Build(reminder, "t", PushTokenKind.RegistrationToken, Noon);
        Assert.True(push.DataOnly);
        Assert.Equal(PushPriority.Normal, push.Priority);
        Assert.Equal("albumSyncReminder", push.Data["type"]);

        var synced = Notification.Create(Guid.NewGuid(), Guid.NewGuid(), NotificationType.MemoriesSynced, MemorySyncText.SyncedTitle,
            MemorySyncText.SyncedBody("Maya", 3), "{\"count\":3}", Noon);
        Assert.False(PushPolicy.Build(synced, "t", PushTokenKind.RegistrationToken, Noon).DataOnly);
    }
}

public sealed class AlbumSyncPersistenceModelTests
{
    private static ApplicationDbContext CreateContext()
        => new(new DbContextOptionsBuilder<ApplicationDbContext>().UseNpgsql("Host=localhost;Database=model-only").Options);

    [Fact]
    public void Sync_state_is_one_row_per_user_that_goes_with_the_user()
    {
        using var context = CreateContext();
        var entity = context.Model.FindEntityType(typeof(AlbumSyncState))!;

        Assert.Equal("album_sync_states", entity.GetTableName());
        Assert.Equal(nameof(AlbumSyncState.UserId), Assert.Single(entity.FindPrimaryKey()!.Properties).Name);
        var foreignKey = Assert.Single(entity.GetForeignKeys());
        Assert.Equal(typeof(User), foreignKey.PrincipalEntityType.ClrType);
        Assert.Equal(DeleteBehavior.Cascade, foreignKey.DeleteBehavior);
        // Check constraints live only in the design-time model.
        var checks = context.GetService<IDesignTimeModel>().Model.FindEntityType(typeof(AlbumSyncState))!.GetCheckConstraints().ToDictionary(check => check.Name!, check => check.Sql);
        Assert.Equal($"\"PendingCount\" BETWEEN 0 AND {AlbumSyncRules.MaxPendingCount}", checks["CK_album_sync_states_PendingCount"]);
        Assert.Equal("\"PendingBytes\" >= 0", checks["CK_album_sync_states_PendingBytes"]);
    }

    [Fact]
    public void A_phone_photo_maps_to_at_most_one_memory_per_uploader()
    {
        using var context = CreateContext();
        var entity = context.Model.FindEntityType(typeof(Memory))!;
        var index = entity.GetIndexes().Single(item => item.GetDatabaseName() == "IX_memories_UploadedByUserId_ClientId");

        Assert.True(index.IsUnique);
        Assert.Equal([nameof(Memory.UploadedByUserId), nameof(Memory.ClientId)], index.Properties.Select(property => property.Name));
        Assert.Equal("\"ClientId\" IS NOT NULL", index.GetFilter());
    }
}
