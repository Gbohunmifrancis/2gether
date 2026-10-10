using System.Text.Json;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Push;

public enum PushDecision { Sent, Retry, Skip, Fail }

/// <summary>Pure rules for turning notifications into pushes and push results into state. No I/O.</summary>
public static class PushPolicy
{
    public const int MaxAttempts = 5;
    public const string AlarmChannel = "alarms";
    public const string GeneralChannel = "general";

    /// <summary>
    /// How long a push is worth sending. An alarm push after its window closed would ring for
    /// nothing; a "goal missed" a day late is noise.
    /// </summary>
    public static TimeSpan TimeToLive(NotificationType type) => type switch
    {
        NotificationType.ExerciseDue => TimeSpan.FromMinutes(30),
        NotificationType.Message or NotificationType.GameInvite or NotificationType.GameStarted => TimeSpan.FromHours(1),
        _ => TimeSpan.FromDays(1)
    };

    public static bool IsStale(Notification notification, DateTime utcNow) => utcNow - notification.CreatedAtUtc > TimeToLive(notification.Type);

    /// <summary>15 s, 30 s, 60 s, 120 s... capped at 10 minutes.</summary>
    public static TimeSpan RetryDelay(int attemptsSoFar) => TimeSpan.FromSeconds(Math.Min(600, 15 * Math.Pow(2, Math.Max(0, attemptsSoFar))));

    public static PushMessage Build(Notification notification, string token, PushTokenKind tokenKind, DateTime utcNow)
    {
        var data = new Dictionary<string, string>
        {
            // The app drops a push it has already seen by this id: delivery is at least once.
            ["notificationId"] = notification.Id.ToString(),
            ["type"] = JsonNamingPolicy.CamelCase.ConvertName(notification.Type.ToString()),
            ["title"] = notification.Title,
            ["body"] = notification.Body
        };
        if (!string.IsNullOrWhiteSpace(notification.DataJson)) data["data"] = notification.DataJson!;
        var remaining = TimeToLive(notification.Type) - (utcNow - notification.CreatedAtUtc);
        if (remaining < TimeSpan.FromSeconds(1)) remaining = TimeSpan.FromSeconds(1);
        var alarm = notification.Type == NotificationType.ExerciseDue;
        return new PushMessage(
            token,
            tokenKind,
            notification.Title,
            notification.Body,
            data,
            alarm ? PushPriority.High : PushPriority.Normal,
            alarm ? AlarmChannel : GeneralChannel,
            remaining,
            DataOnly: alarm);
    }

    /// <summary>
    /// One decision per notification from its per-device results. Delivered anywhere wins: a retry
    /// would ring the device that already got it.
    /// </summary>
    public static PushDecision Decide(IReadOnlyCollection<PushOutcome> outcomes)
    {
        if (outcomes.Count == 0) return PushDecision.Skip;
        if (outcomes.Contains(PushOutcome.Delivered)) return PushDecision.Sent;
        if (outcomes.Contains(PushOutcome.RetryLater)) return PushDecision.Retry;
        if (outcomes.All(outcome => outcome == PushOutcome.InvalidToken)) return PushDecision.Skip;
        return PushDecision.Fail;
    }

    public static string TokenSuffix(string token) => token.Length <= 6 ? token : "..." + token[^6..];
}
