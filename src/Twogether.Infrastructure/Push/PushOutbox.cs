using Microsoft.EntityFrameworkCore;
using Twogether.Application.Features.Push;
using Twogether.Infrastructure.Persistence;

namespace Twogether.Infrastructure.Push;

/// <summary>
/// Claims due pushes with a lease. FOR UPDATE SKIP LOCKED means two dispatcher instances
/// claim disjoint rows; a crashed instance's lease simply expires and the rows come back.
/// </summary>
public sealed class PushOutbox(ApplicationDbContext database) : IPushOutbox
{
    public async Task<IReadOnlyList<Guid>> ClaimAsync(DateTime utcNow, TimeSpan lease, int batchSize, CancellationToken cancellationToken)
    {
        var leaseUntil = utcNow.Add(lease);
        return await database.Database.SqlQuery<Guid>($"""
            UPDATE notifications SET "PushLockedUntilUtc" = {leaseUntil}
            WHERE "Id" IN (
                SELECT "Id" FROM notifications
                WHERE "PushState" = 'Pending'
                  AND ("PushNextAttemptAtUtc" IS NULL OR "PushNextAttemptAtUtc" <= {utcNow})
                  AND ("PushLockedUntilUtc" IS NULL OR "PushLockedUntilUtc" < {utcNow})
                ORDER BY "CreatedAtUtc"
                LIMIT {batchSize}
                FOR UPDATE SKIP LOCKED)
            RETURNING "Id" AS "Value"
            """).ToListAsync(cancellationToken);
    }
}
