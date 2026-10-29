using Microsoft.EntityFrameworkCore;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Core.Memories;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests.Support;

// Shared with tests/Twogether.IntegrationTests (linked there), so nothing here may depend on SQLite.

/// <summary>
/// Builds rows for the admin insights tests at chosen times, through the entities' own methods where they have them
/// (timestamps only otherwise). Call <see cref="SaveAsync"/> when done.
/// </summary>
public sealed class InsightsData(ApplicationDbContext db)
{
    public ApplicationDbContext Db => db;

    public void Set<T>(T entity, string property, object? value) where T : class => db.Entry(entity).Property(property).CurrentValue = value;

    public User Person(string name, DateTime createdAtUtc, string? country = null, bool internalAccount = false, string? email = null)
    {
        var user = User.Create(email ?? $"{name.ToLowerInvariant()}-{Guid.NewGuid():N}@example.com", name, new DateOnly(1995, 1, 1), createdAtUtc);
        if (country is not null) user.SetCountry(country, createdAtUtc);
        if (internalAccount) user.MarkInternal(true, createdAtUtc);
        db.Users.Add(user);
        return user;
    }

    public Couple Link(User a, User b, DateTime linkedAtUtc)
    {
        var couple = Couple.Create(a.Id, linkedAtUtc);
        couple.LinkPartner(b.Id, linkedAtUtc);
        a.SetCouple(couple.Id, linkedAtUtc);
        b.SetCouple(couple.Id, linkedAtUtc);
        db.Couples.Add(couple);
        return couple;
    }

    public PartnerInvite Invite(Couple couple, User inviter, DateTime atUtc)
    {
        var invite = PartnerInvite.Create(couple.Id, inviter.Id, $"code-{Guid.NewGuid():N}", $"link-{Guid.NewGuid():N}", atUtc.AddDays(7), atUtc);
        db.PartnerInvites.Add(invite);
        return invite;
    }

    public ExerciseGoal Goal(Couple couple, User assigned, User setBy, DateTime createdAtUtc, ExerciseType type = ExerciseType.Squat, int target = 15, string timeZone = "UTC")
    {
        var goal = ExerciseGoal.Create(couple.Id, assigned.Id, setBy.Id, type, null, target, new TimeOnly(8, 0), timeZone, 30, createdAtUtc);
        db.ExerciseGoals.Add(goal);
        return goal;
    }

    /// <summary>
    /// One day's dare. It rings at <paramref name="scheduledAtUtc"/> (or is opened an hour early), the camera opens
    /// <paramref name="startAfter"/> later, and it ends as <paramref name="status"/> after <paramref name="onCamera"/>.
    /// </summary>
    public ExerciseOccurrence Dare(
        ExerciseGoal goal, DateTime scheduledAtUtc, ExerciseOccurrenceStatus status,
        TimeSpan? startAfter = null, TimeSpan? onCamera = null, double confidence = 0.9,
        ExerciseCheckCounts checks = default, ExerciseIntegrity integrity = ExerciseIntegrity.Passed, bool early = false)
    {
        var alarmAt = early ? scheduledAtUtc.AddHours(-1) : scheduledAtUtc;
        var occurrence = ExerciseOccurrence.Open(goal, DateOnly.FromDateTime(scheduledAtUtc), scheduledAtUtc, alarmAt);
        var startedAt = alarmAt + (early ? TimeSpan.Zero : startAfter ?? TimeSpan.FromMinutes(2));
        if (early || startAfter is not null || status == ExerciseOccurrenceStatus.Completed) occurrence.Start(startedAt);
        switch (status)
        {
            case ExerciseOccurrenceStatus.Completed:
                occurrence.Complete(goal.Target, confidence, startedAt + (onCamera ?? TimeSpan.FromSeconds(60)), checks, integrity);
                break;
            case ExerciseOccurrenceStatus.Missed:
                occurrence.Miss(scheduledAtUtc.AddMinutes(30));
                break;
            case ExerciseOccurrenceStatus.Cancelled:
                occurrence.Cancel(scheduledAtUtc.AddMinutes(5));
                break;
        }
        db.ExerciseOccurrences.Add(occurrence);
        return occurrence;
    }

    public void ActiveOn(User user, DateOnly day) => db.UserActivityDays.Add(new UserActivityDay { UserId = user.Id, Day = day });

    public GameSession Game(Couple couple, GameType type, DateTime createdAtUtc, GameSessionStatus status, TimeSpan? length = null, string? stateJson = null)
    {
        var game = GameSession.Create(couple.Id, type, couple.UserAId, createdAtUtc);
        if (status == GameSessionStatus.Abandoned)
        {
            game.Abandon(createdAtUtc.AddMinutes(1));
        }
        else if (status != GameSessionStatus.WaitingForPartner)
        {
            game.Start(couple.UserAId, null, stateJson ?? "{}", createdAtUtc);
            if (status == GameSessionStatus.Completed) game.Complete(stateJson ?? "{}", createdAtUtc + (length ?? TimeSpan.FromMinutes(10)));
        }
        db.GameSessions.Add(game);
        return game;
    }

    /// <summary>A Heart to heart state with these scores: per player, one per round (null: not played yet).</summary>
    public static string Duel(Guid first, Guid second, int?[] firstScores, int?[] secondScores, int seed = 7)
    {
        var rules = new SkillDuelRules();
        var state = rules.Start([first, second], TestCouple.Now, SkillDuelRules.CreateSetup("us", seed)).StateJson;
        foreach (var (player, scores) in new[] { (first, firstScores), (second, secondScores) })
            for (var round = 0; round < scores.Length; round++)
                if (scores[round] is { } score)
                    state = rules.Apply(new GameMove(state, player, "score", $"{{\"score\":{score}}}", round, TestCouple.Now)).StateJson;
        return state;
    }

    public StoreSubscription Subscription(User buyer, DateTime createdAtUtc, StoreSubscriptionStatus status, DateTime expiresAtUtc, bool autoRenewing = true, string product = "heartpact_plus_monthly")
    {
        var subscription = StoreSubscription.Create($"token-{Guid.NewGuid():N}", buyer.Id, createdAtUtc);
        subscription.ApplyStoreState(product, status, expiresAtUtc, autoRenewing, true, null, createdAtUtc);
        db.StoreSubscriptions.Add(subscription);
        return subscription;
    }

    public PlusGrant Grant(Couple couple, DateTime createdAtUtc, DateTime expiresAtUtc)
    {
        var grant = PlusGrant.Create(couple.Id, expiresAtUtc, "test", Guid.NewGuid(), createdAtUtc);
        db.PlusGrants.Add(grant);
        return grant;
    }

    public void Scans(User user, Couple couple, DateTime atUtc, int count)
    {
        for (var index = 0; index < count; index++) db.FoodScans.Add(FoodScan.Start(user.Id, couple.Id, "test-model", "image/jpeg", 10, atUtc.AddMinutes(index)));
    }

    public Memory Photo(Couple couple, User uploader, DateTime readyAtUtc, long bytes = 100)
    {
        var memory = Memory.Reserve(couple.Id, uploader.Id, MemoryRules.Kind("image/jpeg")!, bytes, null, null, MemorySource.Camera, readyAtUtc);
        memory.MarkReady(bytes, readyAtUtc);
        db.Memories.Add(memory);
        return memory;
    }

    public FoodEntry Meal(Couple couple, User user, DateTime atUtc)
    {
        var entry = FoodEntry.Create(couple.Id, user.Id, DateOnly.FromDateTime(atUtc), MealType.Lunch, "lunch", 500, null, null, null, atUtc);
        db.FoodEntries.Add(entry);
        return entry;
    }

    public Task SaveAsync() => db.SaveChangesAsync();
}
