using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence;

public sealed class ApplicationDbContext(DbContextOptions<ApplicationDbContext> options)
    : DbContext(options), IApplicationDbContext
{
    public DbSet<User> Users => Set<User>();
    public DbSet<Couple> Couples => Set<Couple>();
    public DbSet<PartnerInvite> PartnerInvites => Set<PartnerInvite>();
    public DbSet<CycleProfile> CycleProfiles => Set<CycleProfile>();
    public DbSet<CycleDayLog> CycleDayLogs => Set<CycleDayLog>();
    public DbSet<GameSession> GameSessions => Set<GameSession>();
    public DbSet<GameResult> GameResults => Set<GameResult>();
    public DbSet<Message> Messages => Set<Message>();
    public DbSet<Notification> Notifications => Set<Notification>();
    public DbSet<CoupleLocation> CoupleLocations => Set<CoupleLocation>();
    public DbSet<ExerciseGoal> ExerciseGoals => Set<ExerciseGoal>();
    public DbSet<ExerciseOccurrence> ExerciseOccurrences => Set<ExerciseOccurrence>();
    public DbSet<FoodEntry> FoodEntries => Set<FoodEntry>();
    public DbSet<NutritionTarget> NutritionTargets => Set<NutritionTarget>();
    public DbSet<FoodScan> FoodScans => Set<FoodScan>();
    public DbSet<Pregnancy> Pregnancies => Set<Pregnancy>();
    public DbSet<PregnancyAppointment> PregnancyAppointments => Set<PregnancyAppointment>();
    public DbSet<KickSession> KickSessions => Set<KickSession>();
    public DbSet<DailyQuestion> DailyQuestions => Set<DailyQuestion>();
    public DbSet<DailyAnswer> DailyAnswers => Set<DailyAnswer>();
    public DbSet<Memory> Memories => Set<Memory>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(ApplicationDbContext).Assembly);
        base.OnModelCreating(modelBuilder);
    }
}
