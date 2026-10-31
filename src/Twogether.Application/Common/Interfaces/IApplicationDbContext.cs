using Microsoft.EntityFrameworkCore;
using Twogether.Core.Entities;

namespace Twogether.Application.Common.Interfaces;

public interface IApplicationDbContext
{
    DbSet<User> Users { get; }
    DbSet<PasswordResetCode> PasswordResetCodes { get; }
    DbSet<AdminAccount> AdminAccounts { get; }
    DbSet<AdminAuditEvent> AdminAuditEvents { get; }
    DbSet<PlusGrant> PlusGrants { get; }
    DbSet<UserActivityDay> UserActivityDays { get; }
    DbSet<AccountDeletion> AccountDeletions { get; }
    DbSet<Couple> Couples { get; }
    DbSet<PartnerInvite> PartnerInvites { get; }
    DbSet<CycleProfile> CycleProfiles { get; }
    DbSet<CycleDayLog> CycleDayLogs { get; }
    DbSet<GameSession> GameSessions { get; }
    DbSet<GameResult> GameResults { get; }
    DbSet<Message> Messages { get; }
    DbSet<Notification> Notifications { get; }
    DbSet<CoupleLocation> CoupleLocations { get; }
    DbSet<ExerciseGoal> ExerciseGoals { get; }
    DbSet<ExerciseOccurrence> ExerciseOccurrences { get; }
    DbSet<FoodEntry> FoodEntries { get; }
    DbSet<NutritionTarget> NutritionTargets { get; }
    DbSet<FoodScan> FoodScans { get; }
    DbSet<Pregnancy> Pregnancies { get; }
    DbSet<PregnancyAppointment> PregnancyAppointments { get; }
    DbSet<KickSession> KickSessions { get; }
    DbSet<DailyQuestion> DailyQuestions { get; }
    DbSet<DailyAnswer> DailyAnswers { get; }
    DbSet<Memory> Memories { get; }
    DbSet<DeviceRegistration> DeviceRegistrations { get; }
    DbSet<StoreSubscription> StoreSubscriptions { get; }
    DbSet<NotificationPreference> NotificationPreferences { get; }
    DbSet<AlbumSyncState> AlbumSyncStates { get; }
    DbSet<AppSetting> AppSettings { get; }
    Task<int> SaveChangesAsync(CancellationToken cancellationToken = default);
}
