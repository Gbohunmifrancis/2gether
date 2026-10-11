using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin;

namespace Twogether.Infrastructure.Admin;

/// <summary>
/// At start-up: authenticator secrets stored as plain text (from before encryption) or under an older
/// key are stored again under the current one. Ones no key opens are left for an admin to reset.
/// </summary>
public static class AdminSecretMigration
{
    public static async Task<int> RunAsync(IApplicationDbContext db, AdminSecretProtector secrets, CancellationToken cancellationToken = default)
    {
        var rewritten = 0;
        foreach (var account in await db.AdminAccounts.ToListAsync(cancellationToken))
        {
            var (secret, rewrite) = secrets.Unprotect(account.TotpSecret);
            if (secret is null || !rewrite) continue;
            account.ReplaceStoredSecret(secrets.Protect(secret), DateTime.UtcNow);
            rewritten++;
        }
        if (rewritten > 0) await db.SaveChangesAsync(cancellationToken);
        return rewritten;
    }
}
