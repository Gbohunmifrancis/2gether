using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Twogether.Application.Features.Food;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests;

public sealed class FoodTests
{
    private const string GoodJson = """
        {"is_food":true,"description":"Jollof rice with grilled chicken","calories":640,"calories_low":540,"calories_high":740,
         "protein_g":38.26,"carbs_g":72,"fat_g":21,"confidence":0.87,
         "items":[{"name":"Jollof rice","portion":"1.5 cups","calories":420},{"name":"Grilled chicken thigh","portion":"1 piece","calories":220}]}
        """;

    [Fact]
    public void Parser_reads_a_well_formed_estimate()
    {
        var result = FoodEstimateParser.Parse(GoodJson, 1500, 200);
        Assert.True(result.IsSuccess);
        var estimate = result.Value;
        Assert.Equal("Jollof rice with grilled chicken", estimate.Description);
        Assert.Equal((640, 540, 740), (estimate.Calories, estimate.CaloriesLow, estimate.CaloriesHigh));
        Assert.Equal(38.3, estimate.ProteinGrams);
        Assert.Equal(0.87, estimate.Confidence);
        Assert.Equal(2, estimate.Items.Count);
        Assert.True(estimate.IsFood);
        Assert.Equal((1500, 200), (estimate.InputTokens, estimate.OutputTokens));
    }

    [Fact]
    public void Parser_strips_code_fences_fixes_ranges_and_clamps_nonsense()
    {
        var fenced = "```json\n{\"is_food\":true,\"description\":\"Soup\",\"calories\":300,\"calories_low\":500,\"calories_high\":200,\"protein_g\":-4,\"carbs_g\":99999,\"fat_g\":\"12\",\"confidence\":7,\"items\":[]}\n```";
        var estimate = FoodEstimateParser.Parse(fenced, 1, 1).Value;
        Assert.Equal((300, 200, 500), (estimate.Calories, estimate.CaloriesLow, estimate.CaloriesHigh));
        Assert.Equal(0, estimate.ProteinGrams);
        Assert.Equal(FoodEntry.MaxGrams, estimate.CarbsGrams);
        Assert.Equal(12, estimate.FatGrams);
        Assert.Equal(1, estimate.Confidence);

        // A point estimate outside its own range widens the range instead of lying.
        var outside = FoodEstimateParser.Parse("{\"is_food\":true,\"description\":\"Cake\",\"calories\":900,\"calories_low\":300,\"calories_high\":400,\"protein_g\":1,\"carbs_g\":1,\"fat_g\":1,\"confidence\":0.4,\"items\":[]}", 1, 1).Value;
        Assert.Equal((900, 300, 900), (outside.Calories, outside.CaloriesLow, outside.CaloriesHigh));
    }

    [Fact]
    public void Parser_rejects_garbage_and_flags_non_food()
    {
        Assert.Equal("food.estimation_unreadable", FoodEstimateParser.Parse("I cannot see any food here.", 1, 1).Error.Code);
        Assert.Equal("food.estimation_unreadable", FoodEstimateParser.Parse("", 1, 1).Error.Code);
        Assert.Equal("food.estimation_unreadable", FoodEstimateParser.Parse("[1,2,3]", 1, 1).Error.Code);
        var notFood = FoodEstimateParser.Parse("{\"is_food\":false,\"description\":\"A laptop\",\"calories\":0,\"calories_low\":0,\"calories_high\":0,\"protein_g\":0,\"carbs_g\":0,\"fat_g\":0,\"confidence\":0.9,\"items\":[]}", 1, 1).Value;
        Assert.False(notFood.IsFood);
    }

    [Fact]
    public void Image_validation_bounds_size_and_type_and_accepts_data_uris()
    {
        var bytes = new byte[2048];
        Random.Shared.NextBytes(bytes);
        var base64 = Convert.ToBase64String(bytes);
        Assert.Equal("food.image_type", FoodRules.ValidateImage(base64, "application/pdf", 1 << 20).Error.Code);
        Assert.Equal("food.image_invalid", FoodRules.ValidateImage("not base64!!", "image/jpeg", 1 << 20).Error.Code);
        Assert.Equal("food.image_too_large", FoodRules.ValidateImage(base64, "image/jpeg", 1024).Error.Code);
        var photo = FoodRules.ValidateImage("data:image/jpeg;base64," + base64, "IMAGE/JPEG", 1 << 20).Value;
        Assert.Equal("image/jpeg", photo.MediaType);
        Assert.Equal(bytes, photo.Bytes);
        Assert.Null(FoodRules.CleanHint(" ", null));
        Assert.Equal("large. no sauce", FoodRules.CleanHint("large", "no sauce"));
    }

    [Fact]
    public void Entry_and_target_rules_reject_bad_input_before_entities_do()
    {
        Assert.Equal("food.invalid_date", FoodRules.ParseLocalDate("10/10/2026").Error.Code);
        Assert.Equal(new DateOnly(2026, 10, 10), FoodRules.ParseLocalDate("2026-10-10").Value);
        Assert.Equal("food.invalid_description", FoodRules.ValidateEntry(MealType.Lunch, " ", 100, null, null, null).Error.Code);
        Assert.Equal("food.invalid_calories", FoodRules.ValidateEntry(MealType.Lunch, "Rice", 5001, null, null, null).Error.Code);
        Assert.Equal("food.invalid_macros", FoodRules.ValidateEntry(MealType.Lunch, "Rice", 100, -1, null, null).Error.Code);
        Assert.Equal("food.invalid_target", FoodRules.ValidateTarget(500, null, null, null, FoodShareLevel.TotalsOnly).Error.Code);
        Assert.True(FoodRules.ValidateTarget(1900, 120, 210, 60, FoodShareLevel.FullDetail).IsSuccess);
    }

    [Fact]
    public void Entry_keeps_estimate_range_until_a_hand_edit_leaves_it()
    {
        var now = DateTime.UtcNow;
        var entry = FoodEntry.Create(Guid.NewGuid(), Guid.NewGuid(), new DateOnly(2026, 10, 10), MealType.Lunch, "Jollof", 640, 38.26, 72, 21, now,
            FoodEntrySource.PhotoEstimate, 540, 740, 0.87, Guid.NewGuid());
        Assert.Equal(38.3, entry.ProteinGrams);
        Assert.Equal(FoodEntrySource.PhotoEstimate, entry.Source);

        entry.Update(entry.LocalDate, MealType.Dinner, "Jollof, big plate", 700, 40, 80, 25, now);
        Assert.Equal((540, 740), (entry.CaloriesLow, entry.CaloriesHigh)); // still inside the range

        entry.Update(entry.LocalDate, MealType.Dinner, "Jollof, huge plate", 1200, 40, 80, 25, now);
        Assert.Null(entry.CaloriesLow);
        Assert.Null(entry.Confidence);
        Assert.Equal(FoodEntrySource.PhotoEstimate, entry.Source);

        Assert.Throws<ArgumentOutOfRangeException>(() => entry.Update(entry.LocalDate, MealType.Snack, "x", -1, null, null, null, now));
        Assert.Throws<ArgumentException>(() => FoodEntry.Create(Guid.NewGuid(), Guid.NewGuid(), entry.LocalDate, MealType.Snack, "x", 100, null, null, null, now, caloriesLow: 50, caloriesHigh: null));
    }

    [Fact]
    public void Totals_sum_entries_and_treat_missing_macros_as_zero()
    {
        var now = DateTime.UtcNow;
        var a = FoodEntry.Create(Guid.NewGuid(), Guid.NewGuid(), new DateOnly(2026, 1, 1), MealType.Breakfast, "Oats", 450, 18, 61, 14, now);
        var b = FoodEntry.Create(a.CoupleId, a.UserId, a.LocalDate, MealType.Snack, "Latte", 250, null, null, null, now);
        var totals = FoodMapper.Totals([a, b]);
        Assert.Equal((700, 18d, 61d, 14d, 2), (totals.Calories, totals.ProteinGrams, totals.CarbsGrams, totals.FatGrams, totals.EntryCount));
        Assert.Equal((700, 700), (totals.CaloriesLow, totals.CaloriesHigh));
    }

    [Fact]
    public void Totals_add_up_photo_ranges_and_count_exact_entries_at_both_ends()
    {
        var now = DateTime.UtcNow;
        var toast = FoodEntry.Create(Guid.NewGuid(), Guid.NewGuid(), new DateOnly(2026, 1, 1), MealType.Breakfast, "French toast", 640, null, null, null, now, FoodEntrySource.PhotoEstimate, 540, 760, 0.7);
        var bowl = FoodEntry.Create(toast.CoupleId, toast.UserId, toast.LocalDate, MealType.Lunch, "Salad bowl", 600, null, null, null, now, FoodEntrySource.PhotoEstimate, 480, 700, 0.7);
        var latte = FoodEntry.Create(toast.CoupleId, toast.UserId, toast.LocalDate, MealType.Snack, "Latte", 120, null, null, null, now);
        var totals = FoodMapper.Totals([toast, bowl, latte]);
        Assert.Equal((1360, 1140, 1580), (totals.Calories, totals.CaloriesLow, totals.CaloriesHigh));
    }

    [Fact]
    public void Portion_adjusts_only_photo_estimates_within_reason_and_scales_the_range_outward()
    {
        Assert.True(FoodRules.ValidatePortion(null, fromScan: false).IsSuccess);
        Assert.True(FoodRules.ValidatePortion(0.75, fromScan: true).IsSuccess);
        Assert.Equal("food.invalid_portion", FoodRules.ValidatePortion(0.75, fromScan: false).Error.Code);
        Assert.Equal("food.invalid_portion", FoodRules.ValidatePortion(0.1, fromScan: true).Error.Code);
        Assert.Equal("food.invalid_portion", FoodRules.ValidatePortion(double.NaN, fromScan: true).Error.Code);
        Assert.Equal((390, 525), FoodRules.ScaleRange(520, 700, 0.75));
        Assert.Equal((676, 910), FoodRules.ScaleRange(520, 700, 1.3));
    }

    [Fact]
    public void Food_tables_map_with_check_constraints_and_quota_index()
    {
        using var context = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>().UseNpgsql("Host=localhost;Database=model-only").Options);
        // Check constraints live only on the design-time model.
        var model = context.GetService<IDesignTimeModel>().Model;
        var entries = model.FindEntityType(typeof(FoodEntry))!;
        Assert.Equal("food_entries", entries.GetTableName());
        Assert.Contains(entries.GetCheckConstraints(), check => check.Name == "CK_food_entries_Range");
        Assert.Equal(typeof(string), entries.FindProperty(nameof(FoodEntry.MealType))!.GetProviderClrType());

        var targets = model.FindEntityType(typeof(NutritionTarget))!;
        Assert.Single(targets.GetIndexes().Where(index => index.IsUnique));
        Assert.Contains(targets.GetCheckConstraints(), check => check.Name == "CK_nutrition_targets_DailyCalories");

        var scans = model.FindEntityType(typeof(FoodScan))!;
        Assert.Equal("food_scans", scans.GetTableName());
        Assert.Contains(scans.GetIndexes(), index => index.GetDatabaseName() == "IX_food_scans_UserId_CreatedAtUtc");
    }
}

public sealed class FoodScanRequestSizeTests
{
    [Theory]
    [InlineData(5 * 1024 * 1024, true)]
    [InlineData(6 * 1024 * 1024 - 64 * 1024, true)]
    [InlineData(6 * 1024 * 1024, false)]
    public void The_largest_photo_still_fits_in_the_scan_request_once_encoded(int imageBytes, bool fits)
        => Assert.Equal(fits, Twogether.Application.Features.Food.FoodSettings.FitsInRequest(imageBytes));
}
