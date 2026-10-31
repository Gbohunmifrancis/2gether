using Microsoft.EntityFrameworkCore;
using Twogether.Application.Features.Admin;
using Twogether.Core.Entities;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

public sealed class AdminAuditRetentionTests : IDisposable
{
    private static readonly DateTime Now = new(2026, 10, 26, 12, 0, 0, DateTimeKind.Utc);
    private readonly TestDatabase database = new();

    public void Dispose() => database.Dispose();

    [Fact]
    public async Task Entries_older_than_two_years_go_and_newer_ones_stay()
    {
        await using (var db = database.CreateContext())
        {
            db.AdminAuditEvents.Add(AdminAuditEvent.Record(null, "ops@heartpact.me", "user.delete", "user", "old", "gone@example.com", null, true, Now.AddDays(-731)));
            db.AdminAuditEvents.Add(AdminAuditEvent.Record(null, "ops@heartpact.me", "user.disable", "user", "kept", null, null, true, Now.AddDays(-729)));
            await db.SaveChangesAsync();
        }

        await using (var db = database.CreateContext())
            Assert.Equal(1, await AdminAuditRetention.DeleteExpiredAsync(db, Now, CancellationToken.None));

        await using var check = database.CreateContext();
        Assert.Equal(["kept"], await check.AdminAuditEvents.Select(item => item.TargetId).ToListAsync());
    }
}
