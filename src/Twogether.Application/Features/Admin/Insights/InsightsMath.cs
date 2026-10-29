namespace Twogether.Application.Features.Admin.Insights;

/// <summary>The small pure pieces of the insights: windows, medians, weeks and buckets.</summary>
public static class InsightsMath
{
    public static int ClampDays(int days) => Math.Clamp(days, 1, 365);
    public static int ClampWeeks(int weeks) => Math.Clamp(weeks, 1, 26);
    public static int ClampLimit(int limit) => Math.Clamp(limit, 1, 50);

    /// <summary>The window's first instant: midnight UTC <paramref name="days"/> - 1 days before today, so "1" is today.</summary>
    public static DateTime WindowStart(DateTime utcNow, int days) => DateOnly.FromDateTime(utcNow).AddDays(-(days - 1)).ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);

    public static DateTime Utc(DateOnly day) => day.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);

    /// <summary>Monday of the day's week (ISO weeks, UTC).</summary>
    public static DateOnly WeekStart(DateOnly day) => day.AddDays(-Weekday(day.DayOfWeek));

    /// <summary>0 = Monday … 6 = Sunday.</summary>
    public static int Weekday(DayOfWeek day) => ((int)day + 6) % 7;

    public static double? Median(IEnumerable<double> values)
    {
        var sorted = values.OrderBy(value => value).ToList();
        if (sorted.Count == 0) return null;
        var middle = sorted.Count / 2;
        return sorted.Count % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    /// <summary>The median of values given as (value, how many times), without expanding them.</summary>
    public static double? WeightedMedian(IEnumerable<(double Value, int Count)> values)
    {
        var sorted = values.Where(item => item.Count > 0).OrderBy(item => item.Value).ToList();
        var total = sorted.Sum(item => (long)item.Count);
        if (total == 0) return null;
        double At(long index)
        {
            long seen = 0;
            foreach (var (value, count) in sorted)
            {
                seen += count;
                if (index < seen) return value;
            }
            return sorted[^1].Value;
        }
        return total % 2 == 1 ? At(total / 2) : (At(total / 2 - 1) + At(total / 2)) / 2;
    }

    public static double? Rate(int part, int whole) => whole == 0 ? null : Math.Round((double)part / whole, 3);

    public static double? Round(double? value, int digits = 1) => value is { } v ? Math.Round(v, digits) : null;

    /// <summary>Current-streak buckets, as the dare insights show them.</summary>
    public static readonly IReadOnlyList<string> StreakBuckets = ["0", "1-2", "3-6", "7-13", "14-29", "30+"];

    public static string StreakBucket(int days) => days switch
    {
        <= 0 => "0",
        <= 2 => "1-2",
        <= 6 => "3-6",
        <= 13 => "7-13",
        <= 29 => "14-29",
        _ => "30+",
    };

    /// <summary>Ten buckets of ten points: 0-9 … 90-100 (a perfect 100 goes in the last).</summary>
    public static int ScoreBucket(int score) => Math.Clamp(score / 10, 0, 9);

    public static string Camel(string value) => string.IsNullOrEmpty(value) ? value : char.ToLowerInvariant(value[0]) + value[1..];
}
