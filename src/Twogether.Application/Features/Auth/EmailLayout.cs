using System.Net;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Auth;

/// <summary>
/// Heartpact's look for email (docs/secrets.md, Email): the app's lavender page, a white sticker card with the logo, and the code
/// in an indigo pill. Tables and inline styles, since that is what mail apps render the same way; a dark variant for the
/// apps that honour <c>prefers-color-scheme</c>; right to left in Arabic. No web fonts, scripts or tracking: the only
/// images are the logo, from heartpact.me. Every piece of text is HTML-escaped here.
/// </summary>
public static class EmailLayout
{
    public const string Site = "https://heartpact.me";
    public const string Support = "support@heartpact.me";
    public const string LogoUrl = Site + "/assets/email/heartpact-logo.png";
    public const string LogoDarkUrl = Site + "/assets/email/heartpact-logo-dark.png";

    /// <summary>An email built around a one-time code: heading, a line on what it's for, the code, how long it lasts, and why it came.</summary>
    public static string Code(string heading, string intro, string code, string validity, string footnote, string preheader)
    {
        var language = Loc.Language;
        var rtl = language == "ar";
        var dir = rtl ? "rtl" : "ltr";
        var align = rtl ? "right" : "left";
        string E(string text) => WebUtility.HtmlEncode(text);
        var support = Loc.T("Questions? Write to {email}.", ("email", Support));
        var tagline = Loc.T("Keep your promises to each other.");
        return $$"""
            <!DOCTYPE html>
            <html lang="{{language}}" dir="{{dir}}" xmlns="http://www.w3.org/1999/xhtml">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light dark">
            <meta name="supported-color-schemes" content="light dark">
            <title>{{E(heading)}}</title>
            <style>
              body, table, td { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", "Noto Sans Arabic", "Noto Sans Devanagari", sans-serif; }
              .logo-dark { display: none; }
              @media (prefers-color-scheme: dark) {
                .page { background: #12142B !important; }
                .card { background: #1C1F3D !important; border-color: #2E3266 !important; }
                .ink { color: #F4F5FC !important; }
                .ink2 { color: #B9BDE3 !important; }
                .pill { background: #2A2E5C !important; color: #FFFFFF !important; }
                .logo-light { display: none !important; }
                .logo-dark { display: inline-block !important; }
              }
              @media (max-width: 520px) {
                .card-cell { padding: 28px 22px !important; }
                .code { font-size: 32px !important; letter-spacing: 6px !important; }
              }
            </style>
            </head>
            <body class="page" style="margin:0;padding:0;background:#EAECF9;">
            <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">{{E(preheader)}}</div>
            <table role="presentation" class="page" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#EAECF9;">
              <tr>
                <td align="center" style="padding:32px 16px;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;">
                    <tr>
                      <td align="{{align}}" style="padding:0 8px 18px;">
                        <a href="{{Site}}" style="text-decoration:none;">
                          <img class="logo-light" src="{{LogoUrl}}" width="180" height="43" alt="Heartpact" style="display:inline-block;border:0;outline:none;">
                          <img class="logo-dark" src="{{LogoDarkUrl}}" width="180" height="43" alt="Heartpact" style="border:0;outline:none;">
                        </a>
                      </td>
                    </tr>
                    <tr>
                      <td class="card card-cell" dir="{{dir}}" align="{{align}}" style="background:#FFFFFF;border:1px solid #DCDFF5;border-radius:24px;padding:36px 32px;text-align:{{align}};">
                        <h1 class="ink" style="margin:0 0 10px;font-size:24px;line-height:30px;font-weight:800;color:#1F2557;">{{E(heading)}}</h1>
                        <p class="ink2" style="margin:0 0 24px;font-size:16px;line-height:24px;color:#4E5383;">{{E(intro)}}</p>
                        <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%">
                          <tr>
                            <td class="pill" align="center" style="background:#E3E6FB;border-radius:999px;padding:16px 12px;color:#1F2557;">
                              <span class="code" dir="ltr" style="font-size:36px;line-height:40px;font-weight:800;letter-spacing:8px;font-family:'SF Mono',Menlo,Consolas,'Roboto Mono',monospace;">{{E(code)}}</span>
                            </td>
                          </tr>
                        </table>
                        <p class="ink2" style="margin:20px 0 0;font-size:14px;line-height:21px;color:#4E5383;">{{E(validity)}}</p>
                        <hr style="border:0;border-top:1px dashed #C9CDF0;margin:26px 0 18px;">
                        <p class="ink2" style="margin:0;font-size:13px;line-height:20px;color:#6A6F99;">{{E(footnote)}}</p>
                      </td>
                    </tr>
                    <tr>
                      <td align="center" style="padding:22px 8px 0;">
                        <p class="ink2" style="margin:0 0 4px;font-size:13px;line-height:20px;color:#6A6F99;">{{E(tagline)}}</p>
                        <p class="ink2" style="margin:0;font-size:12px;line-height:18px;color:#8A8EB5;">{{E(support)}} &middot; <a href="{{Site}}" style="color:#4E5383;text-decoration:underline;">heartpact.me</a></p>
                      </td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
            </body>
            </html>
            """;
    }
}
