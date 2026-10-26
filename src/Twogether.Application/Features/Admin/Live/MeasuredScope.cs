using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Application.Features.Admin.Live;

/// <summary>
/// <see cref="AdminScope.Measured"/> for couples too: a couple counts when at least one of its members is
/// measured and no member still on file is internal or an admin. A partner who deleted their account
/// doesn't hide the couple.
/// </summary>
public sealed class MeasuredScope(IApplicationDbContext db, AdminSettings settings)
{
    // Stored emails are lower case; the allowlist may not be.
    private readonly List<string> adminEmails = settings.Emails.Select(email => email.Trim().ToLowerInvariant()).ToList();

    public IQueryable<User> Users => AdminScope.Measured(db.Users.AsQueryable(), adminEmails);

    private IQueryable<User> Excluded
    {
        get
        {
            var measured = Users;
            return db.Users.Where(user => !measured.Any(item => item.Id == user.Id));
        }
    }

    public IQueryable<Twogether.Core.Entities.Couple> Couples
    {
        get
        {
            var measured = Users;
            var excluded = Excluded;
            return db.Couples.Where(couple =>
                measured.Any(user => user.Id == couple.UserAId || user.Id == couple.UserBId)
                && !excluded.Any(user => user.Id == couple.UserAId || user.Id == couple.UserBId));
        }
    }
}
