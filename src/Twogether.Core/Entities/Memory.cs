using Twogether.Core.Enums;
using Twogether.Core.Memories;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// One photo or video in the couple's shared album. Bytes live in object storage under
/// ObjectKey; this row is the only way to reach them, through short-lived signed URLs.
/// </summary>
public sealed class Memory : BaseEntity
{
    private Memory() { }

    public Guid CoupleId { get; private set; }
    public Guid UploadedByUserId { get; private set; }
    public string ObjectKey { get; private set; } = string.Empty;
    public string ContentType { get; private set; } = string.Empty;
    public bool IsVideo { get; private set; }
    /// <summary>Declared at upload time, replaced by the stored size (never larger) on completion.</summary>
    public long SizeBytes { get; private set; }
    public string? Caption { get; private set; }
    /// <summary>When the photo was taken, as the device reports it. Falls back to CreatedAtUtc for sorting.</summary>
    public DateTime? TakenAtUtc { get; private set; }
    public MemorySource Source { get; private set; }
    public MemoryStatus Status { get; private set; } = MemoryStatus.Pending;
    public DateTime? ReadyAtUtc { get; private set; }
    /// <summary>Set once a thumbnail has been verified in storage, under <see cref="ThumbnailKey"/>.</summary>
    public string? ThumbnailContentType { get; private set; }
    public long ThumbnailBytes { get; private set; }
    public bool HasThumbnail => ThumbnailContentType is not null;
    public string ThumbnailKey => MemoryRules.ThumbnailKey(CoupleId, Id);

    /// <summary>
    /// When the last upload link handed out for this memory stops working. Until then the
    /// uploader can still overwrite what was verified, so the cleanup worker checks the stored
    /// objects again after this time and then clears it.
    /// </summary>
    public DateTime? UploadWindowEndsAtUtc { get; private set; }

    /// <summary>TakenAtUtc, or the upload time when unknown. Stored so the album can be paged by index.</summary>
    public DateTime SortAtUtc { get; private set; }

    /// <summary>
    /// The phone's own id for the photo, sent by offline-first sync so a retried batch finds the
    /// memory it already made instead of adding it twice. Unique per uploader; null for uploads
    /// made through the single-photo flow.
    /// </summary>
    public Guid? ClientId { get; private set; }

    public static Memory Reserve(Guid coupleId, Guid uploadedByUserId, MemoryMediaKind kind, long declaredBytes, string? caption, DateTime? takenAtUtc, MemorySource source, DateTime utcNow, Guid? clientId = null)
    {
        if (declaredBytes <= 0 || declaredBytes > kind.MaxBytes) throw new ArgumentOutOfRangeException(nameof(declaredBytes));
        if (clientId == Guid.Empty) throw new ArgumentException("A client id cannot be empty.", nameof(clientId));
        var memory = new Memory
        {
            CoupleId = coupleId,
            UploadedByUserId = uploadedByUserId,
            ClientId = clientId,
            ContentType = kind.ContentType,
            IsVideo = kind.IsVideo,
            SizeBytes = declaredBytes,
            Source = source,
            CreatedAtUtc = utcNow,
            UpdatedAtUtc = utcNow
        };
        memory.ObjectKey = MemoryRules.ObjectKey(coupleId, memory.Id, kind);
        memory.Describe(caption, takenAtUtc, utcNow);
        return memory;
    }

    public void AttachThumbnail(string contentType, long storedBytes, DateTime utcNow)
    {
        if (Status == MemoryStatus.Deleting) throw new InvalidOperationException("A memory being deleted cannot get a thumbnail.");
        var type = MemoryRules.ThumbnailType(contentType) ?? throw new ArgumentException("Unsupported thumbnail type.", nameof(contentType));
        if (storedBytes <= 0 || storedBytes > MemoryRules.MaxThumbnailBytes) throw new ArgumentOutOfRangeException(nameof(storedBytes));
        ThumbnailContentType = type;
        ThumbnailBytes = storedBytes;
        UpdatedAtUtc = utcNow;
    }

    public void OpenUploadWindow(DateTime endsAtUtc)
    {
        if (UploadWindowEndsAtUtc is null || endsAtUtc > UploadWindowEndsAtUtc) UploadWindowEndsAtUtc = endsAtUtc;
    }

    public void CloseUploadWindow(DateTime utcNow)
    {
        UploadWindowEndsAtUtc = null;
        UpdatedAtUtc = utcNow;
    }

    public void DetachThumbnail(DateTime utcNow)
    {
        ThumbnailContentType = null;
        ThumbnailBytes = 0;
        UpdatedAtUtc = utcNow;
    }

    public void MarkReady(long storedBytes, DateTime utcNow)
    {
        if (Status != MemoryStatus.Pending) throw new InvalidOperationException("Only a pending upload can be completed.");
        // The quota was checked against the declared size, so the stored file may not be larger.
        if (storedBytes <= 0 || storedBytes > SizeBytes) throw new ArgumentOutOfRangeException(nameof(storedBytes));
        SizeBytes = storedBytes;
        Status = MemoryStatus.Ready;
        ReadyAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }

    public void Describe(string? caption, DateTime? takenAtUtc, DateTime utcNow)
    {
        var clean = string.IsNullOrWhiteSpace(caption) ? null : caption.Trim();
        if (clean?.Length > MemoryRules.MaxCaptionLength) throw new ArgumentException("Caption is too long.", nameof(caption));
        Caption = clean;
        TakenAtUtc = takenAtUtc is { } taken ? DateTime.SpecifyKind(taken, DateTimeKind.Utc) : null;
        SortAtUtc = TakenAtUtc ?? CreatedAtUtc;
        UpdatedAtUtc = utcNow;
    }

    public void MarkDeleting(DateTime utcNow)
    {
        Status = MemoryStatus.Deleting;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>
    /// The upload was refused: it did not match what was declared, or no longer fits the album.
    /// Deleted like any other memory, but the phone's id is let go so trying the photo again makes
    /// a fresh memory instead of finding this one.
    /// </summary>
    public void Reject(DateTime utcNow)
    {
        ClientId = null;
        MarkDeleting(utcNow);
    }

    /// <summary>
    /// A link handed out for this memory may still work, so a PUT can still put bytes at its keys.
    /// A deleted memory's row stays (hidden) until then, and its objects are deleted once more after.
    /// </summary>
    public bool UploadWindowOpen(DateTime utcNow) => UploadWindowEndsAtUtc > utcNow;
}
