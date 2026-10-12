using MailKit.Net.Smtp;
using MailKit.Security;
using Microsoft.Extensions.Logging;
using MimeKit;
using Twogether.Application.Features.Auth;

namespace Twogether.Infrastructure.Auth;

/// <summary>Any SMTP service (SES, Postmark, Mailgun, Resend, Gmail Workspace...). One connection per message: volumes are tiny.</summary>
public sealed class SmtpEmailSender(EmailSettings settings, ILogger<SmtpEmailSender> logger) : IEmailSender
{
    public bool IsConfigured => !string.IsNullOrWhiteSpace(settings.Host) && !string.IsNullOrWhiteSpace(settings.From);

    public async Task SendAsync(EmailMessage message, CancellationToken cancellationToken)
    {
        if (settings.Host is not { Length: > 0 } host || settings.From is not { Length: > 0 } from)
            throw new InvalidOperationException("SMTP is not configured; check IsConfigured before sending.");
        var mime = new MimeMessage();
        mime.From.Add(new MailboxAddress(settings.FromName, from));
        mime.To.Add(MailboxAddress.Parse(message.To));
        mime.Subject = message.Subject;
        mime.Body = new TextPart("plain") { Text = message.TextBody };

        using var client = new SmtpClient();
        await client.ConnectAsync(host, settings.Port, settings.StartTls ? SecureSocketOptions.StartTls : SecureSocketOptions.SslOnConnect, cancellationToken);
        if (!string.IsNullOrWhiteSpace(settings.User)) await client.AuthenticateAsync(settings.User, settings.Password ?? string.Empty, cancellationToken);
        await client.SendAsync(mime, cancellationToken);
        await client.DisconnectAsync(true, cancellationToken);
        logger.LogInformation("Sent {Subject} email.", message.Subject.Split(':')[0]);
    }
}

/// <summary>Development: writes the message to the log instead of sending it.</summary>
public sealed class LogEmailSender(ILogger<LogEmailSender> logger) : IEmailSender
{
    public bool IsConfigured => true;

    public Task SendAsync(EmailMessage message, CancellationToken cancellationToken)
    {
        logger.LogWarning("Email (not sent, Email:Provider=log) to {To}: {Subject}\n{Body}", message.To, message.Subject, message.TextBody);
        return Task.CompletedTask;
    }
}

public sealed class DisabledEmailSender : IEmailSender
{
    public bool IsConfigured => false;
    public Task SendAsync(EmailMessage message, CancellationToken cancellationToken) => throw new InvalidOperationException("Email is not configured.");
}
