using Twogether.Core.Cycle;

namespace Twogether.UnitTests;

public sealed class CyclePredictionTests
{
    [Fact]
    public void Fertile_window_ends_at_ovulation_fourteen_days_before_the_next_period()
    {
        var forecast = CyclePrediction.Predict(new DateOnly(2026, 9, 30), 28, 5, new DateOnly(2026, 10, 11));
        Assert.Equal(12, forecast.CycleDay);
        Assert.Equal(new DateOnly(2026, 10, 28), forecast.NextPeriodStart);
        Assert.Equal(new DateOnly(2026, 11, 1), forecast.NextPeriodEnd);
        Assert.Equal(new DateOnly(2026, 10, 9), forecast.FertileWindowStart);
        Assert.Equal(new DateOnly(2026, 10, 14), forecast.FertileWindowEnd);
    }

    [Fact]
    public void An_old_last_period_rolls_forward_to_the_cycle_containing_today()
    {
        var forecast = CyclePrediction.Predict(new DateOnly(2026, 7, 1), 30, 4, new DateOnly(2026, 10, 11));
        // 1 Jul + 3 x 30 days = 29 Sep starts the current cycle.
        Assert.Equal(new DateOnly(2026, 9, 29), forecast.CycleStart);
        Assert.Equal(13, forecast.CycleDay);
        Assert.Equal(new DateOnly(2026, 10, 29), forecast.NextPeriodStart);
        Assert.True(forecast.NextPeriodStart > new DateOnly(2026, 10, 11));
    }

    [Fact]
    public void The_first_day_of_a_period_is_day_one_and_next_is_a_full_cycle_away()
    {
        var forecast = CyclePrediction.Predict(new DateOnly(2026, 10, 11), 28, 5, new DateOnly(2026, 10, 11));
        Assert.Equal((1, new DateOnly(2026, 11, 8)), (forecast.CycleDay, forecast.NextPeriodStart));
        var cycleEnd = CyclePrediction.Predict(new DateOnly(2026, 9, 13), 28, 5, new DateOnly(2026, 10, 11));
        Assert.Equal((1, new DateOnly(2026, 10, 11)), (cycleEnd.CycleDay, cycleEnd.CycleStart));
    }
}
