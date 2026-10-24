using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Application.Features.Notifications;

/// <summary>
/// A notification is read by someone other than the person whose request created it, so it is written in the
/// recipient's language: <c>using (Loc.Use(await db.LanguageOfAsync(partnerId, ct))) { ... }</c>.
/// </summary>
public static class NotificationLanguage
{
    public static async Task<string> LanguageOfAsync(this IApplicationDbContext db, Guid userId, CancellationToken cancellationToken)
        => await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => user.Language).SingleOrDefaultAsync(cancellationToken) ?? "en";

    public static async Task<Dictionary<Guid, string>> LanguagesOfAsync(this IApplicationDbContext db, IEnumerable<Guid> userIds, CancellationToken cancellationToken)
    {
        var ids = userIds.Distinct().ToList();
        return await db.Users.AsNoTracking().Where(user => ids.Contains(user.Id)).ToDictionaryAsync(user => user.Id, user => user.Language, cancellationToken);
    }
}
