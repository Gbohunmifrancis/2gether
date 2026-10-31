namespace Twogether.Core.Users;

/// <summary>
/// What a profile picture may be. The type is decided by the first bytes of the file, never by what the upload
/// claims, so a page, script or archive renamed to .jpg is refused.
/// </summary>
public static class AvatarImage
{
    public const int MaxBytes = 400 * 1024;

    public const string Jpeg = "image/jpeg";
    public const string Png = "image/png";
    public const string WebP = "image/webp";

    /// <summary>The content types an upload may carry.</summary>
    public static bool IsAllowedContentType(string? contentType)
        => contentType is not null && (contentType.Equals(Jpeg, StringComparison.OrdinalIgnoreCase)
            || contentType.Equals(Png, StringComparison.OrdinalIgnoreCase)
            || contentType.Equals(WebP, StringComparison.OrdinalIgnoreCase));

    /// <summary>JPEG (FF D8 FF), PNG (89 "PNG" CR LF 1A LF) or WebP ("RIFF" size "WEBP"); null for anything else.</summary>
    public static string? Detect(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) return Jpeg;
        if (bytes.Length >= 8 && bytes[..8].SequenceEqual((ReadOnlySpan<byte>)[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])) return Png;
        if (bytes.Length >= 12
            && bytes[..4].SequenceEqual((ReadOnlySpan<byte>)[0x52, 0x49, 0x46, 0x46])
            && bytes[8..12].SequenceEqual((ReadOnlySpan<byte>)[0x57, 0x45, 0x42, 0x50])) return WebP;
        return null;
    }
}
