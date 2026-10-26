using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Application.Features.Admin.Insights;

/// <summary>
/// <see cref="AdminScope.Measured"/> in the shapes the queries need: the people, their ids (for IN subqueries), and the
/// couples none of whose members is left out, so a tester's couple disappears with the tester.
/// </summary>
public sealed class MeasuredScope
{
    public MeasuredScope(IApplicationDbContext db, AdminSettings settings)
    {
        // Emails are stored lower-case; the setting may not be.
        var adminEmails = settings.Emails.Select(email => email.Trim().ToLowerInvariant()).ToList();
        Users = AdminScope.Measured(db.Users, adminEmails);
        UserIds = Users.Select(user => user.Id);
        var userIds = UserIds;
        Couples = db.Couples.Where(couple => userIds.Contains(couple.UserAId) && (couple.UserBId == null || userIds.Contains(couple.UserBId.Value)));
        CoupleIds = Couples.Select(couple => couple.Id);
    }

    public IQueryable<User> Users { get; }
    public IQueryable<Guid> UserIds { get; }
    public IQueryable<Twogether.Core.Entities.Couple> Couples { get; }
    public IQueryable<Guid> CoupleIds { get; }
}
