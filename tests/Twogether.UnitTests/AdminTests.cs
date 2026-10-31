using System.Text;
using Twogether.Application.Features.Admin;
using Twogether.Core.Entities;

namespace Twogether.UnitTests;

public sealed class AdminTests
{
    // RFC 6238 appendix B: SHA-1 key "12345678901234567890"; the last six digits of the 8-digit codes.
    private static readonly string RfcSecret = Totp.ToBase32(Encoding.ASCII.GetBytes("12345678901234567890"));

    [Theory]
    [InlineData(59L, "287082")]
    [InlineData(1111111109L, "081804")]
    [InlineData(1234567890L, "005924")]
    [InlineData(2000000000L, "279037")]
    public void Codes_match_the_rfc_test_vectors(long unixSeconds, string expected)
        => Assert.Equal(expected, Totp.Code(RfcSecret, unixSeconds / Totp.StepSeconds));

    [Fact]
    public void Base32_round_trips_and_secrets_are_160_bits()
    {
        var bytes = Enumerable.Range(0, 20).Select(i => (byte)(i * 13)).ToArray();
        Assert.Equal(bytes, Totp.FromBase32(Totp.ToBase32(bytes)));
        Assert.Equal(32, Totp.NewSecret().Length);
    }

    [Fact]
    public void A_code_is_accepted_within_a_step_of_drift_and_never_twice()
    {
        var secret = Totp.NewSecret();
        var now = new DateTime(2026, 10, 11, 12, 0, 15, DateTimeKind.Utc);
        var step = Totp.StepAt(now);
        Assert.Equal(step, Totp.Verify(secret, Totp.Code(secret, step), now, 0));
        Assert.Equal(step - 1, Totp.Verify(secret, Totp.Code(secret, step - 1), now, 0));
        Assert.Null(Totp.Verify(secret, Totp.Code(secret, step - 2), now, 0));
        Assert.Null(Totp.Verify(secret, Totp.Code(secret, step), now, lastUsedStep: step));
        Assert.Null(Totp.Verify(secret, "12345", now, 0));
        Assert.Null(Totp.Verify(secret, null, now, 0));
        var spaced = Totp.Code(secret, step).Insert(3, " ");
        Assert.Equal(step, Totp.Verify(secret, spaced, now, 0));
    }

    [Fact]
    public void Only_allowlisted_emails_are_admins_and_the_uri_names_the_issuer()
    {
        var settings = new AdminSettings { Emails = ["ops@heartpact.me"] };
        Assert.True(settings.IsAdmin(" OPS@heartpact.me "));
        Assert.False(settings.IsAdmin("maya@example.com"));
        Assert.False(new AdminSettings().IsAdmin("ops@heartpact.me"));
        Assert.StartsWith("otpauth://totp/Heartpact%20Admin:ops%40heartpact.me?secret=", Totp.Uri("ABC", "ops@heartpact.me"));
    }

    [Fact]
    public void Day_series_have_every_day_with_zeros()
    {
        var from = new DateOnly(2026, 10, 1);
        var series = AdminAnalytics.Fill(from, from.AddDays(4), [(from.AddDays(1), 3), (from.AddDays(1), 2), (from.AddDays(4), 1)]);
        Assert.Equal(["2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05"], series.Select(item => item.Date));
        Assert.Equal([0d, 5, 0, 0, 1], series.Select(item => item.Value));
        Assert.Equal(7, AdminAnalytics.ClampDays(1));
        Assert.Equal(365, AdminAnalytics.ClampDays(9999));
    }

    [Fact]
    public void Disabling_ends_sessions_including_this_second_and_grants_expire()
    {
        var now = new DateTime(2026, 10, 11, 12, 0, 0, 400, DateTimeKind.Utc);
        var user = User.Create("maya@example.com", "Maya", new DateOnly(1996, 5, 14), now, "hash");
        user.Disable("chargeback fraud", now);
        Assert.NotNull(user.DisabledAtUtc);
        Assert.Equal(new DateTime(2026, 10, 11, 12, 0, 1, DateTimeKind.Utc), user.SessionsValidFromUtc);
        user.Enable(now);
        Assert.Null(user.DisabledAtUtc);

        var grant = PlusGrant.Create(Guid.NewGuid(), now.AddDays(30), "Sorry about the outage", Guid.NewGuid(), now);
        Assert.True(grant.IsActive(now.AddDays(29)));
        Assert.False(grant.IsActive(now.AddDays(30)));
        grant.Revoke(now);
        Assert.False(grant.IsActive(now));
    }
}

public sealed class AdminSecretProtectorTests
{
    private const string Secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    [Fact]
    public void Protected_secrets_round_trip_and_never_repeat()
    {
        var protector = new AdminSecretProtector("admin-key");
        var first = protector.Protect(Secret);
        var second = protector.Protect(Secret);
        Assert.StartsWith("v1.", first);
        Assert.DoesNotContain(Secret, first);
        Assert.NotEqual(first, second);
        Assert.True(first.Length <= 128);
        Assert.Equal(new UnprotectedSecret(Secret, false), protector.Unprotect(first));
    }

    [Fact]
    public void Plain_text_from_before_encryption_is_read_once_and_flagged_for_rewrite()
        => Assert.Equal(new UnprotectedSecret(Secret, true), new AdminSecretProtector("admin-key").Unprotect(Secret));

    [Fact]
    public void Older_keys_still_read_and_flag_a_rewrite_but_unknown_keys_and_tampering_read_nothing()
    {
        var old = new AdminSecretProtector("jwt-key").Protect(Secret);
        Assert.Equal(new UnprotectedSecret(Secret, true), new AdminSecretProtector("new-admin-key", null, "jwt-key").Unprotect(old));
        Assert.Null(new AdminSecretProtector("someone-else").Unprotect(old).Secret);

        var tampered = old[..^2] + (old[^2] == 'A' ? "B" : "A") + old[^1];
        Assert.Null(new AdminSecretProtector("jwt-key").Unprotect(tampered).Secret);
        Assert.Null(new AdminSecretProtector("jwt-key").Unprotect("v1.not*base64").Secret);
        Assert.Null(new AdminSecretProtector("jwt-key").Unprotect("v1.AAAA").Secret);
    }

    [Fact]
    public void A_key_is_required() => Assert.Throws<ArgumentException>(() => new AdminSecretProtector(null, " ", ""));
}
