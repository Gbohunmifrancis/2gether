using System.Globalization;
using System.Text;
using Twogether.Core.Enums;
using Twogether.Core.Memories;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Memories;

public sealed record MemoryDto(
    Guid Id,
    Guid UploadedByUserId,
    string UploadedByName,
    string ContentType,
    bool IsVideo,
    long SizeBytes,
    string? Caption,
    DateTime? TakenAtUtc,
    DateTime SortAtUtc,
    MemorySource Source,
    DateTime CreatedAtUtc,
    string Url,
    DateTime UrlExpiresAtUtc);

public sealed record MemoryPageDto(IReadOnlyList<MemoryDto> Items, string? NextCursor);

/// <summary>A photo the caller uploaded to a couple that has since ended, downloadable until the deadline.</summary>
public sealed record ArchivedMemoryDto(MemoryDto Memory, DateTime CoupleEndedAtUtc, DateTime AvailableUntilUtc);

public sealed record MemoryArchivePageDto(IReadOnlyList<ArchivedMemoryDto> Items, string? NextCursor);

public sealed record MemoryUploadDto(Guid MemoryId, string UploadUrl, string Method, IReadOnlyDictionary<string, string> Headers, DateTime ExpiresAtUtc);

public sealed record MemoryUsageDto(int Count, long UsedBytes, long LimitBytes);

public sealed record ReserveMemoryRequest(string ContentType, long SizeBytes, string? Caption, DateTime? TakenAtUtc, MemorySource Source = MemorySource.Gallery);

public sealed record UpdateMemoryRequest(string? Caption, DateTime? TakenAtUtc);

public sealed record MemoryCursor(DateTime SortAtUtc, Guid Id)
{
    public string Encode() => Convert.ToBase64String(Encoding.UTF8.GetBytes($"{SortAtUtc.Ticks.ToString(CultureInfo.InvariantCulture)}_{Id:N}")).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static MemoryCursor? Decode(string? cursor)
    {
        if (string.IsNullOrWhiteSpace(cursor) || cursor.Length > 80) return null;
        try
        {
            var padded = cursor.Replace('-', '+').Replace('_', '/');
            padded += new string('=', (4 - padded.Length % 4) % 4);
            var parts = Encoding.UTF8.GetString(Convert.FromBase64String(padded)).Split('_');
            return parts.Length == 2 && long.TryParse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture, out var ticks) && Guid.TryParseExact(parts[1], "N", out var id)
                && ticks is >= 0 && ticks <= DateTime.MaxValue.Ticks
                ? new MemoryCursor(new DateTime(ticks, DateTimeKind.Utc), id)
                : null;
        }
        catch (FormatException) { return null; }
    }
}

public static class MemoryRequestRules
{
    public static Result<MemoryMediaKind> ValidateReserve(ReserveMemoryRequest request, DateTime utcNow)
    {
        var kind = MemoryRules.Kind(request.ContentType);
        if (kind is null) return Error.Validation("memories.unsupported_type", "Photos must be JPEG, PNG, WebP or HEIC; videos MP4 or MOV.");
        if (request.SizeBytes <= 0) return Error.Validation("memories.invalid_size", "The file is empty.");
        if (request.SizeBytes > kind.MaxBytes) return Error.Validation("memories.too_large", $"{(kind.IsVideo ? "Videos" : "Photos")} must be under {kind.MaxBytes / (1024 * 1024)} MB.");
        if (!Enum.IsDefined(request.Source)) return Error.Validation("memories.invalid_source", "Unknown source.");
        return ValidateDescription(request.Caption, request.TakenAtUtc, utcNow) is { IsFailure: true } bad ? Result.Failure<MemoryMediaKind>(bad.Error) : kind;
    }

    public static Result ValidateDescription(string? caption, DateTime? takenAtUtc, DateTime utcNow)
    {
        if (caption?.Trim().Length > MemoryRules.MaxCaptionLength) return Error.Validation("memories.invalid_caption", $"Captions are at most {MemoryRules.MaxCaptionLength} characters.");
        if (takenAtUtc is { } taken && (taken.Year < 1900 || taken > utcNow.AddDays(1))) return Error.Validation("memories.invalid_taken_at", "The date taken is out of range.");
        return Result.Success();
    }
}
