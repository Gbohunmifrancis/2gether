using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;

namespace Twogether.Infrastructure.Ai;

/// <summary>
/// The daily caps and the record of every call. A request is counted and recorded as pending in one transaction, under a
/// lock per person, before anything goes to Claude: parallel requests queue there instead of all passing the cap on the
/// same count, and a request counts for the day however it ends (as food scans do). Days are UTC days.
/// </summary>
public sealed class AiUsage(ApplicationDbContext db, IDateTimeProvider clock)
{
    public Task<int> UsedTodayAsync(Guid userId, AiFeature feature, CancellationToken cancellationToken)
    {
        var midnight = clock.UtcNow.Date;
        return db.AiRequests.AsNoTracking().CountAsync(item => item.UserId == userId && item.Feature == feature && item.CreatedAtUtc >= midnight, cancellationToken);
    }

    /// <summary>
    /// Counts today's requests and records this one, unless <paramref name="cap"/> is reached (then the request is null).
    /// <paramref name="alsoAdd"/> is saved in the same transaction (the coach note's claim), and only when there is room.
    /// </summary>
    public async Task<(AiRequest? Request, int Used)> StartAsync(Guid userId, Guid coupleId, AiFeature feature, int cap, string model, CancellationToken cancellationToken, object? alsoAdd = null)
    {
        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        await db.Database.LockAsync(LockScope.AiRequests, userId, cancellationToken);
        var used = await UsedTodayAsync(userId, feature, cancellationToken);
        if (used >= cap) return (null, used);
        var request = AiRequest.Start(userId, coupleId, feature, model, clock.UtcNow);
        db.AiRequests.Add(request);
        if (alsoAdd is not null) db.Add(alsoAdd);
        await db.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return (request, used);
    }

    /// <summary>Records a call that no cap applies to (the weekly recap, once per couple per week).</summary>
    public AiRequest Record(Guid userId, Guid coupleId, AiFeature feature, string model)
    {
        var request = AiRequest.Start(userId, coupleId, feature, model, clock.UtcNow);
        db.AiRequests.Add(request);
        return request;
    }
}
