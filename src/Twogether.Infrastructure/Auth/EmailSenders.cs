using MailKit.Net.Smtp;
using MailKit.Security;
using Microsoft.Extensions.Logging;
using MimeKit;
using System.Net.Http.Headers;
using System.Net.Http.Json;
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
        // Both versions: the branded HTML, and the plain text for mail apps that don't show HTML.
        mime.Body = new BodyBuilder { TextBody = message.TextBody, HtmlBody = message.HtmlBody }.ToMessageBody();

        using var client = new SmtpClient();
        await client.ConnectAsync(host, settings.Port, settings.StartTls ? SecureSocketOptions.StartTls : SecureSocketOptions.SslOnConnect, cancellationToken);
        if (!string.IsNullOrWhiteSpace(settings.User)) await client.AuthenticateAsync(settings.User, settings.Password ?? string.Empty, cancellationToken);
        await client.SendAsync(mime, cancellationToken);
        await client.DisconnectAsync(true, cancellationToken);
        logger.LogInformation("Sent {Subject} email.", message.Subject.Split(':')[0]);
    }
}

/// <summary>Resend's HTTP API (resend.com): no SMTP connection to keep open, and a failure says why.</summary>
public sealed class ResendEmailSender(EmailSettings settings, ILogger<ResendEmailSender> logger) : IEmailSender, IDisposable
{
    private readonly HttpClient http = new() { BaseAddress = new Uri("https://api.resend.com/"), Timeout = TimeSpan.FromSeconds(20) };

    public bool IsConfigured => !string.IsNullOrWhiteSpace(settings.ApiKey) && !string.IsNullOrWhiteSpace(settings.From);

    public async Task SendAsync(EmailMessage message, CancellationToken cancellationToken)
    {
        if (!IsConfigured) throw new InvalidOperationException("Resend is not configured; check IsConfigured before sending.");
        using var request = new HttpRequestMessage(HttpMethod.Post, "emails")
        {
            Content = JsonContent.Create(message.HtmlBody is null
                ? (object)new { from = $"{settings.FromName} <{settings.From}>", to = new[] { message.To }, subject = message.Subject, text = message.TextBody }
                : new { from = $"{settings.FromName} <{settings.From}>", to = new[] { message.To }, subject = message.Subject, text = message.TextBody, html = message.HtmlBody }),
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.ApiKey);
        using var response = await http.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            // Resend's body names the problem (an unverified domain, a bad key); it never contains our message.
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            throw new InvalidOperationException($"Resend rejected the email ({(int)response.StatusCode}): {body}");
        }
        logger.LogInformation("Sent {Subject} email.", message.Subject.Split(':')[0]);
    }

    public void Dispose() => http.Dispose();
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
