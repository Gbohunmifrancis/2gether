using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Application.Features.Admin;

/// <summary>
/// How long the admin audit log is kept: two years, as the privacy policy says (section 9). Older entries, including
/// the email address kept when an admin deletes an account, are removed.
/// </summary>
public static class AdminAuditRetention
{
    public static readonly TimeSpan Kept = TimeSpan.FromDays(730);

    public static Task<int> DeleteExpiredAsync(IApplicationDbContext db, DateTime utcNow, CancellationToken cancellationToken)
    {
        var before = utcNow - Kept;
        return db.AdminAuditEvents.Where(item => item.CreatedAtUtc < before).ExecuteDeleteAsync(cancellationToken);
    }
}
