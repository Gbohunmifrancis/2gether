using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Admin;

/// <summary>Last-active time and one row per active day, written outside the request's change tracking.</summary>
public static class UserActivity
{
    public static async Task RecordAsync(IApplicationDbContext db, Guid userId, DateTime? previous, DateTime utcNow, CancellationToken cancellationToken)
    {
        try
        {
            await db.Users.Where(user => user.Id == userId).ExecuteUpdateAsync(set => set.SetProperty(user => user.LastActiveAtUtc, utcNow), cancellationToken);
            var today = DateOnly.FromDateTime(utcNow);
            if (previous is null || DateOnly.FromDateTime(previous.Value) != today)
            {
                var context = (DbContext)db;
                await context.Database.ExecuteSqlInterpolatedAsync(
                    $"INSERT INTO user_activity_days (\"UserId\", \"Day\") VALUES ({userId}, {today}) ON CONFLICT DO NOTHING", cancellationToken);
            }
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            // Analytics must never fail a request.
        }
    }

    public static async Task SetLanguageAsync(IApplicationDbContext db, Guid userId, string language, CancellationToken cancellationToken)
    {
        try
        {
            await db.Users.Where(user => user.Id == userId).ExecuteUpdateAsync(set => set.SetProperty(user => user.Language, language), cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            // Remembering the language must never fail a request; the next one tries again.
        }
    }
}
