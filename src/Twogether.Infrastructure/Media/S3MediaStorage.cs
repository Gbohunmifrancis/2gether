using System.Net;
using Amazon.Runtime;
using Amazon.S3;
using Amazon.S3.Model;
using Twogether.Application.Features.Memories;

namespace Twogether.Infrastructure.Media;

/// <summary>Object storage over the S3 API. Works with Cloudflare R2, AWS S3 and MinIO.</summary>
public sealed class S3MediaStorage : IMediaStorage, IDisposable
{
    private readonly AmazonS3Client? _client;
    private readonly string _bucket = string.Empty;
    private readonly Protocol _protocol = Protocol.HTTPS;

    public S3MediaStorage(MediaSettings settings)
    {
        if (string.IsNullOrWhiteSpace(settings.ServiceUrl) || string.IsNullOrWhiteSpace(settings.Bucket)
            || string.IsNullOrWhiteSpace(settings.AccessKey) || string.IsNullOrWhiteSpace(settings.SecretKey))
            return;
        var config = new AmazonS3Config
        {
            ServiceURL = settings.ServiceUrl,
            ForcePathStyle = true,
            AuthenticationRegion = settings.Region
        };
        _client = new AmazonS3Client(new BasicAWSCredentials(settings.AccessKey, settings.SecretKey), config);
        _bucket = settings.Bucket;
        // Signed URLs default to HTTPS; a local http endpoint (MinIO in development) needs HTTP.
        _protocol = settings.ServiceUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ? Protocol.HTTP : Protocol.HTTPS;
    }

    public bool IsConfigured => _client is not null;

    public Uri PresignUpload(string key, string contentType, TimeSpan lifetime)
        => new(Client.GetPreSignedURL(new GetPreSignedUrlRequest
        {
            BucketName = _bucket,
            Key = key,
            Verb = HttpVerb.PUT,
            ContentType = contentType,
            Expires = DateTime.UtcNow.Add(lifetime),
            Protocol = _protocol
        }));

    public Uri PresignDownload(string key, TimeSpan lifetime)
        => new(Client.GetPreSignedURL(new GetPreSignedUrlRequest
        {
            BucketName = _bucket,
            Key = key,
            Verb = HttpVerb.GET,
            Expires = DateTime.UtcNow.Add(lifetime),
            Protocol = _protocol
        }));

    public async Task<StoredObjectInfo?> HeadAsync(string key, CancellationToken cancellationToken)
    {
        try
        {
            var metadata = await Client.GetObjectMetadataAsync(_bucket, key, cancellationToken);
            return new StoredObjectInfo(metadata.ContentLength, metadata.Headers.ContentType);
        }
        catch (AmazonS3Exception exception) when (exception.StatusCode == HttpStatusCode.NotFound)
        {
            return null;
        }
    }

    public async Task DeleteAsync(string key, CancellationToken cancellationToken)
    {
        try
        {
            await Client.DeleteObjectAsync(_bucket, key, cancellationToken);
        }
        catch (AmazonS3Exception exception) when (exception.StatusCode == HttpStatusCode.NotFound)
        {
            // Already gone: deletion is idempotent.
        }
    }

    public void Dispose() => _client?.Dispose();

    private AmazonS3Client Client => _client ?? throw new InvalidOperationException("Media storage is not configured.");
}
