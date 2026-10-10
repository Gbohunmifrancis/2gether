using Twogether.Application.Features.Memories;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Memories;

namespace Twogether.UnitTests;

public sealed class MemoryTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void Upload_rules_accept_photos_and_videos_within_their_caps()
    {
        Assert.True(MemoryRequestRules.ValidateReserve(new ReserveMemoryRequest("image/jpeg", 2_000_000, "Beach", Now.AddYears(-1)), Now).IsSuccess);
        Assert.True(MemoryRequestRules.ValidateReserve(new ReserveMemoryRequest("VIDEO/MP4", MemoryRules.MaxVideoBytes, null, null), Now).IsSuccess);
        Assert.Equal("memories.unsupported_type", MemoryRequestRules.ValidateReserve(new ReserveMemoryRequest("application/pdf", 10, null, null), Now).Error.Code);
        Assert.Equal("memories.too_large", MemoryRequestRules.ValidateReserve(new ReserveMemoryRequest("image/png", MemoryRules.MaxPhotoBytes + 1, null, null), Now).Error.Code);
        Assert.Equal("memories.invalid_size", MemoryRequestRules.ValidateReserve(new ReserveMemoryRequest("image/png", 0, null, null), Now).Error.Code);
        Assert.Equal("memories.invalid_taken_at", MemoryRequestRules.ValidateReserve(new ReserveMemoryRequest("image/png", 10, null, Now.AddDays(3)), Now).Error.Code);
        Assert.Equal("memories.invalid_caption", MemoryRequestRules.ValidateReserve(new ReserveMemoryRequest("image/png", 10, new string('x', 501), null), Now).Error.Code);
    }

    [Fact]
    public void Object_keys_are_built_by_the_server_under_the_couple()
    {
        var coupleId = Guid.NewGuid();
        var memory = Memory.Reserve(coupleId, Guid.NewGuid(), MemoryRules.Kind("image/heic")!, 1000, "  First hike ", null, MemorySource.GooglePhotos, Now);
        Assert.Equal($"couples/{coupleId:N}/{memory.Id:N}.heic", memory.ObjectKey);
        Assert.Equal("First hike", memory.Caption);
        Assert.Equal(Now, memory.SortAtUtc); // no date taken: sorted by upload time
        Assert.Equal(MemoryStatus.Pending, memory.Status);

        memory.Describe(null, new DateTime(2025, 10, 10, 8, 0, 0), Now);
        Assert.Equal(DateTimeKind.Utc, memory.TakenAtUtc!.Value.Kind);
        Assert.Equal(memory.TakenAtUtc, memory.SortAtUtc);

        memory.MarkReady(950, Now);
        Assert.Equal((MemoryStatus.Ready, 950L), (memory.Status, memory.SizeBytes));
        Assert.Throws<InvalidOperationException>(() => memory.MarkReady(950, Now));
        Assert.Throws<ArgumentOutOfRangeException>(() => Memory.Reserve(coupleId, Guid.NewGuid(), MemoryRules.Kind("image/png")!, MemoryRules.MaxPhotoBytes + 1, null, null, MemorySource.Camera, Now));
    }

    [Fact]
    public void On_this_day_matches_earlier_years_and_keeps_leap_day_memories_visible()
    {
        var today = new DateOnly(2026, 10, 10);
        Assert.True(MemoryRules.IsOnThisDay(new DateOnly(2024, 10, 10), today));
        Assert.False(MemoryRules.IsOnThisDay(new DateOnly(2026, 10, 10), today)); // today is not a memory yet
        Assert.False(MemoryRules.IsOnThisDay(new DateOnly(2024, 10, 11), today));
        Assert.True(MemoryRules.IsOnThisDay(new DateOnly(2024, 2, 29), new DateOnly(2026, 2, 28)));
        Assert.False(MemoryRules.IsOnThisDay(new DateOnly(2024, 2, 29), new DateOnly(2028, 2, 28))); // 2028 has its own 29th
        Assert.True(MemoryRules.IsOnThisDay(new DateOnly(2024, 2, 29), new DateOnly(2028, 2, 29)));
    }

    [Fact]
    public void Cursor_round_trips_and_rejects_tampering()
    {
        var cursor = new MemoryCursor(new DateTime(2025, 6, 1, 18, 30, 15, DateTimeKind.Utc), Guid.NewGuid());
        var encoded = cursor.Encode();
        Assert.DoesNotContain('=', encoded);
        Assert.Equal(cursor, MemoryCursor.Decode(encoded));
        Assert.Null(MemoryCursor.Decode("not-a-cursor"));
        Assert.Null(MemoryCursor.Decode(Convert.ToBase64String("-5_abc"u8.ToArray())));
        Assert.Null(MemoryCursor.Decode(new string('A', 200)));
        Assert.Null(MemoryCursor.Decode(null));
    }
}
