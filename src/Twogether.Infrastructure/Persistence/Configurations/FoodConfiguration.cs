using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class FoodEntryConfiguration : IEntityTypeConfiguration<FoodEntry>
{
    public void Configure(EntityTypeBuilder<FoodEntry> builder)
    {
        builder.ToTable("food_entries", table =>
        {
            table.HasCheckConstraint("CK_food_entries_Calories", "\"Calories\" >= 0");
            table.HasCheckConstraint("CK_food_entries_Range", "\"CaloriesLow\" IS NULL OR (\"CaloriesHigh\" IS NOT NULL AND \"CaloriesLow\" <= \"Calories\" AND \"Calories\" <= \"CaloriesHigh\")");
        });
        builder.HasKey(entry => entry.Id);
        builder.Property(entry => entry.MealType).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(entry => entry.Source).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(entry => entry.Description).HasMaxLength(FoodEntry.MaxDescriptionLength).IsRequired();
        builder.HasIndex(entry => new { entry.UserId, entry.LocalDate });
        builder.HasIndex(entry => new { entry.CoupleId, entry.LocalDate });
    }
}

public sealed class NutritionTargetConfiguration : IEntityTypeConfiguration<NutritionTarget>
{
    public void Configure(EntityTypeBuilder<NutritionTarget> builder)
    {
        builder.ToTable("nutrition_targets", table => table.HasCheckConstraint("CK_nutrition_targets_DailyCalories", "\"DailyCalories\" BETWEEN 800 AND 6000"));
        builder.HasKey(target => target.Id);
        builder.Property(target => target.ShareLevel).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.HasIndex(target => target.UserId).IsUnique();
    }
}

public sealed class FoodScanConfiguration : IEntityTypeConfiguration<FoodScan>
{
    public void Configure(EntityTypeBuilder<FoodScan> builder)
    {
        builder.ToTable("food_scans");
        builder.HasKey(scan => scan.Id);
        builder.Property(scan => scan.Model).HasMaxLength(80).IsRequired();
        builder.Property(scan => scan.MediaType).HasMaxLength(40).IsRequired();
        builder.Property(scan => scan.ErrorCode).HasMaxLength(60);
        builder.Property(scan => scan.ResultJson).HasColumnType("jsonb");
        // The daily quota query: scans by this user since midnight.
        builder.HasIndex(scan => new { scan.UserId, scan.CreatedAtUtc });
    }
}
