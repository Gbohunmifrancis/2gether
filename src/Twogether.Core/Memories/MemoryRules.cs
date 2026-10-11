namespace Twogether.Core.Memories;

public sealed record MemoryMediaKind(string ContentType, string Extension, bool IsVideo, long MaxBytes);

public static class MemoryRules
{
    public const long MaxPhotoBytes = 15L * 1024 * 1024;
    public const long MaxVideoBytes = 100L * 1024 * 1024;
    public const int MaxCaptionLength = 500;
    /// <summary>A grid tile, not a photo: about 512 px on the long side.</summary>
    public const long MaxThumbnailBytes = 256 * 1024;
    private static readonly HashSet<string> ThumbnailTypes = new(StringComparer.OrdinalIgnoreCase) { "image/jpeg", "image/webp" };

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
    /// One fixed key per memory, whatever the type: replacing a thumbnail overwrites it, and a
    /// delete always knows what to remove even if the upload was never confirmed.
    /// </summary>
    public static string ThumbnailKey(Guid coupleId, Guid memoryId) => $"couples/{coupleId:N}/{memoryId:N}-thumb";

    /// <summary>The canonical spelling of a supported thumbnail type, or null.</summary>
    public static string? ThumbnailType(string? contentType)
        => string.IsNullOrWhiteSpace(contentType) || !ThumbnailTypes.TryGetValue(contentType.Split(';')[0].Trim(), out var type) ? null : type.ToLowerInvariant();

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
