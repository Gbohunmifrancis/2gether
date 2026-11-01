using Twogether.Application.Features.Auth;
using Twogether.Shared.Localization;

namespace Twogether.UnitTests;

/// <summary>The branded email: what's in it, what's escaped, and the direction per language.</summary>
public sealed class EmailLayoutTests
{
    [Fact]
    public void The_reset_email_has_a_branded_html_version_and_keeps_the_plain_text()
    {
        var email = PasswordResetRules.Email("maya@example.com", "482039");

        Assert.Contains("482039", email.TextBody);
        Assert.NotNull(email.HtmlBody);
        Assert.Contains("482039", email.HtmlBody);
        Assert.Contains("Reset your password", email.HtmlBody);
        Assert.Contains(EmailLayout.LogoUrl, email.HtmlBody);
        Assert.Contains("prefers-color-scheme: dark", email.HtmlBody);
        Assert.Contains("dir=\"ltr\"", email.HtmlBody);
        // Nothing loaded but the logo: no scripts, web fonts or tracking pixels.
        Assert.DoesNotContain("<script", email.HtmlBody);
        Assert.DoesNotContain("fonts.googleapis", email.HtmlBody);
        Preview("reset-en", email.HtmlBody);
    }

    [Fact]
    public void Arabic_reads_right_to_left_with_the_code_still_left_to_right()
    {
        string html;
        using (Loc.Use("ar")) html = PasswordResetRules.Email("maya@example.com", "482039").HtmlBody!;

        Assert.Contains("<html lang=\"ar\" dir=\"rtl\"", html);
        Assert.Contains("<span class=\"code\" dir=\"ltr\"", html);
        Preview("reset-ar", html);
    }

    [Fact]
    public void Text_is_escaped()
    {
        var html = EmailLayout.Code("<b>Hi</b>", "a & b", "1<2", "x", "y", "z");

        Assert.Contains("&lt;b&gt;Hi&lt;/b&gt;", html);
        Assert.Contains("a &amp; b", html);
        Assert.Contains("1&lt;2", html);
        Assert.DoesNotContain("<b>Hi</b>", html);
    }

    /// <summary>EMAIL_PREVIEW_DIR=... writes the rendered emails there, to look at in a browser.</summary>
    private static void Preview(string name, string html)
    {
        var dir = Environment.GetEnvironmentVariable("EMAIL_PREVIEW_DIR");
        if (!string.IsNullOrEmpty(dir)) File.WriteAllText(Path.Combine(dir, name + ".html"), html);
    }
}
