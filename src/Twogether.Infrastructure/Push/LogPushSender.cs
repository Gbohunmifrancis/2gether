using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Push;

namespace Twogether.Infrastructure.Push;

/// <summary>Development provider: writes each push to the log instead of sending it. Never logs full tokens.</summary>
public sealed class LogPushSender(ILogger<LogPushSender> logger) : IPushSender
{
    public string Name => "log";
    public bool IsConfigured => true;

    public Task<IReadOnlyList<PushResult>> SendAsync(IReadOnlyList<PushMessage> messages, CancellationToken cancellationToken)
    {
        foreach (var message in messages)
            logger.LogInformation("PUSH to {Token}: {Type} priority={Priority} channel={Channel} dataOnly={DataOnly} ttl={Ttl}s notification={NotificationId} title=\"{Title}\"",
                PushPolicy.TokenSuffix(message.Token), message.Data.GetValueOrDefault("type"), message.Priority, message.ChannelId, message.DataOnly,
                (int)message.TimeToLive.TotalSeconds, message.Data.GetValueOrDefault("notificationId"), message.Title);
        return Task.FromResult<IReadOnlyList<PushResult>>(messages.Select(_ => new PushResult(PushOutcome.Delivered)).ToList());
    }
}

/// <summary>Push is off: the dispatcher marks pending pushes skipped so they never pile up.</summary>
public sealed class DisabledPushSender : IPushSender
{
    public string Name => "off";
    public bool IsConfigured => false;
    public Task<IReadOnlyList<PushResult>> SendAsync(IReadOnlyList<PushMessage> messages, CancellationToken cancellationToken)
        => throw new InvalidOperationException("Push is not configured.");
}
