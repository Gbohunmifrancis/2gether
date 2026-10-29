namespace Twogether.Core.Users;

/// <summary>
/// Heartpact is for adults: it shares location and health data between partners. Signing up needs a date of birth
/// that makes the person 18 or over.
/// </summary>
public static class AgeRules
{
    public const int MinimumAge = 18;

    /// <summary>Whole years from <paramref name="birth"/> to <paramref name="today"/>. Born on 29 February: a year older on 1 March in other years.</summary>
    public static int AgeOn(DateOnly birth, DateOnly today)
    {
        var age = today.Year - birth.Year;
        if (birth > today.AddYears(-age)) age--;
        return age;
    }

    /// <summary>A date that can be someone's birthday: not in the future, not before 1900.</summary>
    public static bool IsPlausible(DateOnly birth, DateTime utcNow) => birth.Year >= 1900 && birth <= Latest(utcNow);

    /// <summary>
    /// 18 or over. The day is the latest it is anywhere on Earth (UTC+14), so nobody is turned away on the morning of
    /// their 18th birthday, wherever they are.
    /// </summary>
    public static bool IsAdult(DateOnly birth, DateTime utcNow) => AgeOn(birth, Latest(utcNow)) >= MinimumAge;

    private static DateOnly Latest(DateTime utcNow) => DateOnly.FromDateTime(utcNow.AddHours(14));
}
