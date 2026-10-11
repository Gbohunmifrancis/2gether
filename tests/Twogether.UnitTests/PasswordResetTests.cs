using Twogether.Application.Features.Auth;
using Twogether.Core.Entities;

namespace Twogether.UnitTests;

public sealed class PasswordResetTests
{
    private static readonly DateTime Now = new(2026, 10, 11, 15, 0, 0, 250, DateTimeKind.Utc);

    [Fact]
    public void Codes_are_six_digits_and_hashes_are_bound_to_the_user()
    {
        var code = PasswordResetRules.NewCode();
        Assert.Matches("^[0-9]{6}$", code);
        var a = Guid.NewGuid();
        var b = Guid.NewGuid();
        Assert.NotEqual(PasswordResetRules.Hash(a, "123456"), PasswordResetRules.Hash(b, "123456"));
        Assert.True(PasswordResetRules.Matches(a, " 123456 ", PasswordResetRules.Hash(a, "123456")));
        Assert.False(PasswordResetRules.Matches(a, "123457", PasswordResetRules.Hash(a, "123456")));
        Assert.Equal(64, PasswordResetRules.Hash(a, code).Length);
    }

    [Fact]
    public void A_code_dies_after_fifteen_minutes_five_tries_or_one_use()
    {
        var user = Guid.NewGuid();
        var code = PasswordResetCode.Issue(user, PasswordResetRules.Hash(user, "000111"), Now);
        Assert.True(code.IsLive(Now.AddMinutes(14)));
        Assert.False(code.IsLive(Now.AddMinutes(15)));

        for (var i = 0; i < PasswordResetCode.MaxAttempts; i++) code.RecordAttempt(Now);
        Assert.False(code.IsLive(Now));

        var used = PasswordResetCode.Issue(user, "x", Now);
        used.Retire(Now);
        Assert.False(used.IsLive(Now));
    }

    [Fact]
    public void A_reset_verifies_the_email_and_ends_sessions_from_the_same_whole_second()
    {
        var user = User.Create("maya@example.com", "Maya", new DateOnly(1996, 5, 14), Now, "old-hash");
        user.ResetPassword("new-hash", Now);
        Assert.Equal("new-hash", user.PasswordHash);
        Assert.True(user.EmailVerified);
        // JWT nbf has whole seconds: the token issued with the reset (nbf 15:00:00) must still pass.
        Assert.Equal(new DateTime(2026, 10, 11, 15, 0, 0, DateTimeKind.Utc), user.SessionsValidFromUtc);
    }

    [Fact]
    public void The_reset_email_says_how_long_the_code_lasts_and_what_to_do_if_unasked()
    {
        var message = PasswordResetRules.Email("maya@example.com", "042917");
        Assert.Contains("042917", message.Subject);
        Assert.Contains("15 minutes", message.TextBody);
        Assert.Contains("ignore this email", message.TextBody);
    }
}
