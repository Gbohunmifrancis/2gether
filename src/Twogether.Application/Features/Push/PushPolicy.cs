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
    /// <summary>The app creates this channel with low importance: no sound, no vibration, no heads-up.</summary>
    public const string QuietChannel = "quiet";
    /// <summary>
    /// The app creates this channel with high importance, its own short vibration and light: the partner is waiting on
    /// you (a message, a game, a call-off to answer) or just finished something worth cheering.
    /// </summary>
    public const string PartnerChannel = "partner";

    /// <summary>Notices where the partner is waiting on an answer, or a cheer is worth sending at once.</summary>
    public static bool IsPartnerAlert(NotificationType type) => type is NotificationType.Message or NotificationType.GameInvite
        or NotificationType.GameTurn or NotificationType.ExerciseCompleted or NotificationType.ExerciseCallOffAsked;

    /// <summary>The app's channel for a notice; the app picks the same one from the type and <c>quiet</c> (push/PushActions.kt).</summary>
    public static string ChannelFor(NotificationType type, PushRoute route)
    {
        if (type == NotificationType.ExerciseDue) return AlarmChannel;
        if (route == PushRoute.Quiet) return QuietChannel;
        return IsPartnerAlert(type) ? PartnerChannel : GeneralChannel;
    }

    /// <summary>
    /// How long a push is worth sending. An alarm push after its window closed would ring for
    /// nothing; a "goal missed" a day late is noise.
    /// </summary>
    public static TimeSpan TimeToLive(NotificationType type) => type switch
    {
        NotificationType.ExerciseDue => TimeSpan.FromMinutes(30),
        // A call-off request only matters while the alarm window is open.
        NotificationType.Message or NotificationType.GameInvite or NotificationType.GameStarted or NotificationType.ExerciseCallOffAsked => TimeSpan.FromHours(1),
        _ => TimeSpan.FromDays(1)
    };

    public static bool IsStale(Notification notification, DateTime utcNow) => utcNow - notification.CreatedAtUtc > TimeToLive(notification.Type);

    /// <summary>15 s, 30 s, 60 s, 120 s... capped at 10 minutes.</summary>
    public static TimeSpan RetryDelay(int attemptsSoFar) => TimeSpan.FromSeconds(Math.Min(600, 15 * Math.Pow(2, Math.Max(0, attemptsSoFar))));

    public static PushMessage Build(Notification notification, string token, PushTokenKind tokenKind, DateTime utcNow, PushRoute route = PushRoute.Normal)
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
        var quiet = route == PushRoute.Quiet && !alarm;
        if (quiet) data["quiet"] = "true";
        // Every push is data only: the app draws each notification itself, so it can add its actions (reply, cheer,
        // play, let off), the partner's picture and grouping, open the full-screen alarm, and hold the album nudge
        // until Wi-Fi. A notification payload would be drawn by the system, plain, whenever the app is in the background.
        // The alarm and a partner alert are high priority (shown at once, even in Doze); a quiet one never is.
        var urgent = alarm || (!quiet && IsPartnerAlert(notification.Type));
        return new PushMessage(
            token,
            tokenKind,
            notification.Title,
            notification.Body,
            data,
            urgent ? PushPriority.High : PushPriority.Normal,
            ChannelFor(notification.Type, quiet ? PushRoute.Quiet : PushRoute.Normal),
            remaining,
            DataOnly: true);
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
