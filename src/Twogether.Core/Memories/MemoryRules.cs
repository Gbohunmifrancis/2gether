namespace Twogether.Core.Memories;

public sealed record MemoryMediaKind(string ContentType, string Extension, bool IsVideo, long MaxBytes);

public static class MemoryRules
{
    public const long MaxPhotoBytes = 15L * 1024 * 1024;
    public const long MaxVideoBytes = 100L * 1024 * 1024;
    public const int MaxCaptionLength = 500;

    private static readonly Dictionary<string, MemoryMediaKind> Kinds = new(StringComparer.OrdinalIgnoreCase)
    {
        ["image/jpeg"] = new("image/jpeg", ".jpg", false, MaxPhotoBytes),
        ["image/png"] = new("image/png", ".png", false, MaxPhotoBytes),
        ["image/webp"] = new("image/webp", ".webp", false, MaxPhotoBytes),
        ["image/heic"] = new("image/heic", ".heic", false, MaxPhotoBytes),
        ["image/heif"] = new("image/heif", ".heif", false, MaxPhotoBytes),
        ["video/mp4"] = new("video/mp4", ".mp4", true, MaxVideoBytes),
        ["video/quicktime"] = new("video/quicktime", ".mov", true, MaxVideoBytes)
    };

    public static MemoryMediaKind? Kind(string? contentType)
        => string.IsNullOrWhiteSpace(contentType) ? null : Kinds.GetValueOrDefault(contentType.Trim());

    /// <summary>Storage keys are built here and only here; nothing from the client reaches a path.</summary>
    public static string ObjectKey(Guid coupleId, Guid memoryId, MemoryMediaKind kind) => $"couples/{coupleId:N}/{memoryId:N}{kind.Extension}";

    /// <summary>
    /// "On this day": same month and day in an earlier year. A 29 February memory shows on
    /// 28 February in years without one, so it is not hidden three years out of four.
    /// </summary>
    public static bool IsOnThisDay(DateOnly memoryDate, DateOnly today)
    {
        if (memoryDate.Year >= today.Year) return false;
        if (memoryDate.Month == today.Month && memoryDate.Day == today.Day) return true;
        return memoryDate is { Month: 2, Day: 29 } && today is { Month: 2, Day: 28 } && !DateTime.IsLeapYear(today.Year);
    }
}
