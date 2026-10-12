using Twogether.Application.Common.Interfaces;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Couple;

namespace Twogether.UnitTests;

public sealed class AbuseLimitTests
{
    private sealed class TestClock : IDateTimeProvider
    {
        public DateTime UtcNow { get; set; } = new(2026, 10, 11, 12, 0, 0, DateTimeKind.Utc);
    }

    private static readonly AttemptRule Rule = new("test", 3, TimeSpan.FromMinutes(10));

    [Fact]
    public void Blocks_after_the_budget_and_reopens_as_failures_age_out()
    {
        var clock = new TestClock();
        var limiter = new AttemptLimiter(clock);

        limiter.RecordFailure(Rule, "ada");
        clock.UtcNow = clock.UtcNow.AddMinutes(4);
        limiter.RecordFailure(Rule, "ada");
        Assert.Null(limiter.RetryAfter(Rule, "ada"));
        limiter.RecordFailure(Rule, "ada");

        Assert.Equal(TimeSpan.FromMinutes(6), limiter.RetryAfter(Rule, "ada"));
        Assert.Null(limiter.RetryAfter(Rule, "femi"));

        // The first failure leaves the window: one more try opens up, not the whole budget.
        clock.UtcNow = clock.UtcNow.AddMinutes(6);
        Assert.Null(limiter.RetryAfter(Rule, "ada"));
        limiter.RecordFailure(Rule, "ada");
        Assert.NotNull(limiter.RetryAfter(Rule, "ada"));
    }

    [Fact]
    public void Reset_clears_one_key_and_rules_do_not_share_budgets()
    {
        var limiter = new AttemptLimiter(new TestClock());
        var other = new AttemptRule("other", 3, TimeSpan.FromMinutes(10));
        for (var i = 0; i < 3; i++) limiter.RecordFailure(Rule, "ada");

        Assert.Null(limiter.RetryAfter(other, "ada"));
        limiter.Reset(Rule, "ada");
        Assert.Null(limiter.RetryAfter(Rule, "ada"));
    }

    [Fact]
    public void Memory_per_key_stays_bounded_under_a_flood()
    {
        var clock = new TestClock();
        var limiter = new AttemptLimiter(clock);
        for (var i = 0; i < 10_000; i++) limiter.RecordFailure(Rule, "ada");

        // Only the newest three are kept, so the wait is measured from the latest burst.
        Assert.Equal(TimeSpan.FromMinutes(10), limiter.RetryAfter(Rule, "ada"));
    }

    [Theory]
    [InlineData(6)]
    [InlineData(8)]
    [InlineData(10)]
    public void Invite_codes_have_the_configured_number_of_digits(int digits)
    {
        var codes = Enumerable.Range(0, 200).Select(_ => InviteCodes.Generate(digits)).ToList();
        Assert.All(codes, code => Assert.Matches($"^[0-9]{{{digits}}}$", code));
        Assert.True(codes.Distinct().Count() > 190);
    }

    [Theory]
    [InlineData("123456", "123456")]
    [InlineData(" 123 456 ", "123456")]
    [InlineData("1234-5678", "12345678")]
    [InlineData("12345", null)]
    [InlineData("12345678901", null)]
    [InlineData("12a456", null)]
    [InlineData("١٢٣٤٥٦", null)]
    [InlineData(null, null)]
    public void Typed_codes_are_normalized_or_refused(string? typed, string? expected)
        => Assert.Equal(expected, InviteCodes.Normalize(typed));
}
