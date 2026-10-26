using Twogether.Core.Entities;

namespace Twogether.Application.Features.Admin;

/// <summary>
/// Who analytics count: everyone but internal (test and team) accounts and the admins themselves. Every admin metric
/// starts from these, so a test couple never shows up in a chart.
/// </summary>
public static class AdminScope
{
    public static IQueryable<User> Measured(IQueryable<User> users, IReadOnlyCollection<string> adminEmails) =>
        users.Where(user => !user.IsInternal && !adminEmails.Contains(user.Email));
}
