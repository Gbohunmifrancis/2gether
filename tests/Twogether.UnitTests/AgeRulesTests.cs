using Twogether.Core.Users;

namespace Twogether.UnitTests;

public sealed class AgeRulesTests
{
    private static readonly DateTime Noon = new(2026, 10, 25, 12, 0, 0, DateTimeKind.Utc);

    [Theory]
    [InlineData("2008-10-25", true)]   // 18 today
    [InlineData("2008-10-26", true)]   // 18 tomorrow in UTC, today already at UTC+14
    [InlineData("2008-10-27", false)]  // 18 the day after tomorrow: still 17 everywhere
    [InlineData("1990-01-01", true)]
    [InlineData("2012-05-14", false)]
    public void Adults_are_18_or_over_on_the_latest_day_on_earth(string birth, bool adult)
        => Assert.Equal(adult, AgeRules.IsAdult(DateOnly.Parse(birth), Noon));

    [Fact]
    public void Turning_18_counts_from_midnight_at_UTC_plus_14()
    {
        var birth = new DateOnly(2008, 10, 26);
        // 09:59 UTC on the 25th is 23:59 on the 25th at UTC+14; ten o'clock is midnight there, the 26th.
        Assert.False(AgeRules.IsAdult(birth, new DateTime(2026, 10, 25, 9, 59, 0, DateTimeKind.Utc)));
        Assert.True(AgeRules.IsAdult(birth, new DateTime(2026, 10, 25, 10, 0, 0, DateTimeKind.Utc)));
    }

    [Fact]
    public void Born_on_29_February_turns_a_year_older_on_1_March()
    {
        var birth = new DateOnly(2008, 2, 29);
        Assert.Equal(17, AgeRules.AgeOn(birth, new DateOnly(2026, 2, 28)));
        Assert.Equal(18, AgeRules.AgeOn(birth, new DateOnly(2026, 3, 1)));
        Assert.Equal(20, AgeRules.AgeOn(birth, new DateOnly(2028, 2, 29)));
    }

    [Theory]
    [InlineData("1899-12-31", false)]
    [InlineData("1900-01-01", true)]
    [InlineData("2026-10-25", true)]
    [InlineData("2026-10-27", false)]  // the future, even at UTC+14
    public void A_birthday_is_a_real_date(string birth, bool plausible)
        => Assert.Equal(plausible, AgeRules.IsPlausible(DateOnly.Parse(birth), Noon));
}
