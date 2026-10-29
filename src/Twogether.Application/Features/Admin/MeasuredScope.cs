using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Application.Features.Admin;

/// <summary>
/// <see cref="AdminScope.Measured"/> in the shapes the admin queries need: the people, their ids (for IN subqueries),
/// and their couples. A couple counts when at least one member is measured and no member still on file is internal or
/// an admin, so a tester's couple disappears with the tester, but a partner who deleted their account doesn't hide it.
/// </summary>
public sealed class MeasuredScope
{
    public MeasuredScope(IApplicationDbContext db, AdminSettings settings)
    {
        // Stored emails are lower case; the allowlist may not be.
        var adminEmails = settings.Emails.Select(email => email.Trim().ToLowerInvariant()).ToList();
        var measured = AdminScope.Measured(db.Users, adminEmails);
        var excluded = db.Users.Where(user => !measured.Any(item => item.Id == user.Id));
        Users = measured;
        UserIds = measured.Select(user => user.Id);
        Couples = db.Couples.Where(couple =>
            measured.Any(user => user.Id == couple.UserAId || user.Id == couple.UserBId)
            && !excluded.Any(user => user.Id == couple.UserAId || user.Id == couple.UserBId));
        CoupleIds = Couples.Select(couple => couple.Id);
    }

    public IQueryable<User> Users { get; }
    public IQueryable<Guid> UserIds { get; }
    public IQueryable<Twogether.Core.Entities.Couple> Couples { get; }
    public IQueryable<Guid> CoupleIds { get; }
}
