using System.Text.Json;

namespace Twogether.Application.Features.Billing;

public static class PlayNotificationParser
{
    /// <summary>Pub/Sub envelope -> base64 developer notification -> purchase token. Null for test or foreign notifications.</summary>
    public static string? ReadPurchaseToken(JsonElement body, string? expectedPackage, out string kind)
    {
        kind = "unreadable";
        try
        {
            if (body.ValueKind != JsonValueKind.Object || !body.TryGetProperty("message", out var message)
                || !message.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.String) return null;
            using var notification = JsonDocument.Parse(Convert.FromBase64String(data.GetString()!));
            var root = notification.RootElement;
            if (root.TryGetProperty("testNotification", out _)) { kind = "test"; return null; }
            if (expectedPackage is not null && root.TryGetProperty("packageName", out var package) && package.GetString() != expectedPackage) { kind = "other_package"; return null; }
            if (!root.TryGetProperty("subscriptionNotification", out var subscription)) { kind = "not_subscription"; return null; }
            kind = subscription.TryGetProperty("notificationType", out var type) && type.TryGetInt32(out var code) ? $"type_{code}" : "subscription";
            return subscription.TryGetProperty("purchaseToken", out var token) && token.ValueKind == JsonValueKind.String ? token.GetString() : null;
        }
        catch (Exception exception) when (exception is FormatException or JsonException)
        {
            return null;
        }
    }
}
