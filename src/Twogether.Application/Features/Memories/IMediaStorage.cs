namespace Twogether.Application.Features.Memories;

public sealed record StoredObjectInfo(long SizeBytes, string? ContentType);

/// <summary>Outbound port to object storage. Bytes never pass through the API: clients use signed URLs.</summary>
public interface IMediaStorage
{
    bool IsConfigured { get; }
    /// <summary>A PUT URL bound to this key and content type; the client must send the same Content-Type header.</summary>
    Uri PresignUpload(string key, string contentType, TimeSpan lifetime);
    Uri PresignDownload(string key, TimeSpan lifetime);
    /// <summary>Null when the object does not exist.</summary>
    Task<StoredObjectInfo?> HeadAsync(string key, CancellationToken cancellationToken);
    /// <summary>Idempotent: deleting a missing object succeeds.</summary>
    Task DeleteAsync(string key, CancellationToken cancellationToken);
}

/// <summary>Bound from the "Media" section. Any S3-compatible service: Cloudflare R2, AWS S3, MinIO.</summary>
public sealed class MediaSettings
{
    public string? ServiceUrl { get; init; }
    public string? Bucket { get; init; }
    public string? AccessKey { get; init; }
    public string? SecretKey { get; init; }
    public string Region { get; init; } = "auto";
    public TimeSpan UploadUrlLifetime { get; init; } = TimeSpan.FromMinutes(15);
    public TimeSpan DownloadUrlLifetime { get; init; } = TimeSpan.FromHours(1);
    /// <summary>An upload slot not completed within this window is treated as abandoned.</summary>
    public TimeSpan PendingLifetime { get; init; } = TimeSpan.FromHours(1);
}
