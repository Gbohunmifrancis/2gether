namespace Twogether.Core.Entities;

/// <summary>
/// A Bachs webhook event that was handled, by its id (evt_...). Bachs delivers at least once, so a second delivery of
/// the same id is answered 2xx and changes nothing. Written in the same save as the state the event changed.
/// </summary>
public sealed class BachsWebhookEvent
{
    public const int MaxIdLength = 100;
    public const int MaxTypeLength = 100;

    private BachsWebhookEvent() { }

    public string Id { get; private set; } = string.Empty;
    public string Type { get; private set; } = string.Empty;
    public DateTime ReceivedAtUtc { get; private set; }

    public static BachsWebhookEvent Record(string id, string type, DateTime utcNow)
    {
        if (string.IsNullOrWhiteSpace(id) || id.Trim().Length > MaxIdLength) throw new ArgumentException("An event id of 1 to 100 characters is required.", nameof(id));
        var kind = string.IsNullOrWhiteSpace(type) ? "unknown" : type.Trim();
        return new BachsWebhookEvent { Id = id.Trim(), Type = kind.Length > MaxTypeLength ? kind[..MaxTypeLength] : kind, ReceivedAtUtc = utcNow };
    }
}
