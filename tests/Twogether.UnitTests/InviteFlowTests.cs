using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Twogether.Api.Controllers;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Couple;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Persistence;
using Twogether.Api.Common;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

/// <summary>Linking from the app's side: who sent a link (by first name only), and how often a new code may be made.</summary>
public sealed class InviteFlowTests : IDisposable
{
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);
    private readonly FakeHub hub = new();
    private readonly AttemptLimiter limiter;

    public InviteFlowTests() => limiter = new AttemptLimiter(clock);

    public void Dispose() => database.Dispose();

    private sealed class Tokens : ITokenService
    {
        public string CreateAccessToken(Guid userId, Guid? coupleId, string email, string displayName, DateTime utcNow) => $"token-{userId:N}";
    }

    private CoupleController Controller(ApplicationDbContext db, Guid userId, Guid? coupleId) =>
        new(db, new FakeCurrentUser(userId, coupleId), clock, new Tokens(), hub, limiter, new InviteSettings(), NullLogger<CoupleController>.Instance)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
        };

    /// <summary>Someone who just signed up: their own couple, waiting for a partner.</summary>
    private static async Task<(Guid UserId, Guid CoupleId)> NewcomerAsync(ApplicationDbContext db, string name)
    {
        var user = User.Create($"{Guid.NewGuid():N}@example.com", name, new DateOnly(1996, 5, 14), TestCouple.Now);
        var couple = Couple.Create(user.Id, TestCouple.Now);
        user.SetCouple(couple.Id, TestCouple.Now);
        db.AddRange(user, couple);
        await db.SaveChangesAsync();
        return (user.Id, couple.Id);
    }

    private static T Ok<T>(ActionResult<T> result) => Assert.IsType<T>(Assert.IsType<OkObjectResult>(result.Result).Value);

    [Fact]
    public async Task A_link_tells_who_sent_it_by_first_name_and_nothing_more()
    {
        await using var db = database.CreateContext();
        var maya = await NewcomerAsync(db, "  Maya Okafor ");
        var invite = Ok(await Controller(db, maya.UserId, maya.CoupleId).CreateInvite(default));

        var preview = Ok(await Controller(db, Guid.NewGuid(), null).PreviewInvite(new InvitePreviewRequest(invite.Token), default));

        Assert.Equal("Maya", preview.InviterFirstName);
        Assert.Equal(invite.ExpiresAtUtc, preview.ExpiresAtUtc);
        // Only the first name and the expiry go out: no email, no id, no surname.
        Assert.Equal(["InviterFirstName", "ExpiresAtUtc"], typeof(InvitePreviewDto).GetProperties().Select(property => property.Name));
    }

    [Fact]
    public async Task A_code_an_unknown_token_or_a_used_link_shows_nobody()
    {
        await using var db = database.CreateContext();
        var maya = await NewcomerAsync(db, "Maya");
        var theo = await NewcomerAsync(db, "Theo");
        var invite = Ok(await Controller(db, maya.UserId, maya.CoupleId).CreateInvite(default));
        var stranger = Controller(db, Guid.NewGuid(), null);

        // A six-digit code could be guessed, so it never names anyone here.
        Assert.IsType<NotFoundObjectResult>((await stranger.PreviewInvite(new InvitePreviewRequest(invite.Code), default)).Result);
        Assert.IsType<NotFoundObjectResult>((await stranger.PreviewInvite(new InvitePreviewRequest(new string('a', 48)), default)).Result);
        Assert.IsType<NotFoundObjectResult>((await stranger.PreviewInvite(new InvitePreviewRequest(null), default)).Result);
        Assert.IsType<NotFoundObjectResult>((await stranger.PreviewInvite(new InvitePreviewRequest(new string('a', 500)), default)).Result);

        Ok(await Controller(db, theo.UserId, theo.CoupleId).AcceptInvite(new AcceptPartnerInviteRequest(null, invite.Token), default));
        var used = (await stranger.PreviewInvite(new InvitePreviewRequest(invite.Token), default)).Result;
        Assert.Equal("invite.invalid", Assert.IsType<ApiError>(Assert.IsType<NotFoundObjectResult>(used).Value).Code);
    }

    [Fact]
    public async Task An_expired_or_replaced_link_shows_nobody()
    {
        await using var db = database.CreateContext();
        var maya = await NewcomerAsync(db, "Maya");
        var first = Ok(await Controller(db, maya.UserId, maya.CoupleId).CreateInvite(default));
        var second = Ok(await Controller(db, maya.UserId, maya.CoupleId).CreateInvite(default));
        var stranger = Controller(db, Guid.NewGuid(), null);

        Assert.IsType<NotFoundObjectResult>((await stranger.PreviewInvite(new InvitePreviewRequest(first.Token), default)).Result);
        Ok(await stranger.PreviewInvite(new InvitePreviewRequest(second.Token), default));

        clock.UtcNow = second.ExpiresAtUtc.AddSeconds(1);
        Assert.IsType<NotFoundObjectResult>((await stranger.PreviewInvite(new InvitePreviewRequest(second.Token), default)).Result);
    }

    [Fact]
    public async Task New_codes_have_a_budget_per_person_and_it_refills()
    {
        await using var db = database.CreateContext();
        var maya = await NewcomerAsync(db, "Maya");
        var theo = await NewcomerAsync(db, "Theo");
        for (var i = 0; i < AttemptRules.InviteCreatePerUser.MaxFailures; i++)
            Ok(await Controller(db, maya.UserId, maya.CoupleId).CreateInvite(default));

        var controller = Controller(db, maya.UserId, maya.CoupleId);
        var refused = Assert.IsType<ObjectResult>((await controller.CreateInvite(default)).Result);
        Assert.Equal(StatusCodes.Status429TooManyRequests, refused.StatusCode);
        Assert.Equal("rate_limited", Assert.IsType<ApiError>(refused.Value).Code);
        Assert.True(controller.Response.Headers.ContainsKey("Retry-After"));
        // The code from before the refusal still works: nothing was revoked.
        Assert.Equal(1, await db.PartnerInvites.CountAsync(invite => invite.CoupleId == maya.CoupleId && invite.Status == PartnerInviteStatus.Pending));

        // Someone else's budget is their own.
        Ok(await Controller(db, theo.UserId, theo.CoupleId).CreateInvite(default));

        clock.UtcNow = clock.UtcNow.Add(AttemptRules.InviteCreatePerUser.Window);
        Ok(await Controller(db, maya.UserId, maya.CoupleId).CreateInvite(default));
    }

    [Theory]
    [InlineData("Maya Okafor", "Maya")]
    [InlineData("  Theo  ", "Theo")]
    [InlineData("Ana\tLuísa", "Ana")]
    [InlineData("محمد علي", "محمد")]
    public void First_names_are_the_first_word(string displayName, string expected) => Assert.Equal(expected, CoupleController.FirstName(displayName));

    [Fact]
    public void A_very_long_first_word_is_cut() => Assert.Equal(40, CoupleController.FirstName(new string('x', 90)).Length);
}
