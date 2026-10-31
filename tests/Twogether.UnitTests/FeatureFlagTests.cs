using System.Security.Claims;
using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Admin;
using Twogether.Api.Controllers;
using Twogether.Api.Controllers.Admin;
using Twogether.Application.Features.Config;
using Twogether.Core.Entities;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

public sealed class FeatureFlagTests : IDisposable
{
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);

    public void Dispose() => database.Dispose();

    private AdminFeaturesController Admin(ApplicationDbContext db)
    {
        var http = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, Guid.NewGuid().ToString()), new Claim(ClaimTypes.Email, "ops@heartpact.me")], "test")),
        };
        return new AdminFeaturesController(new FeatureFlagService(db, clock), new AdminAudit(db, clock, new HttpContextAccessor { HttpContext = http }), db)
        {
            ControllerContext = new ControllerContext { HttpContext = http },
        };
    }

    [Fact]
    public async Task A_new_database_has_no_rows_and_every_flag_is_off()
    {
        await using var db = database.CreateContext();

        Assert.False((await new FeatureFlagService(db, clock).GetAsync(default)).Christmas);
        Assert.Empty(await db.AppSettings.ToListAsync());
    }

    [Fact]
    public async Task Setting_a_flag_creates_the_row_once_and_later_changes_update_it()
    {
        await using var db = database.CreateContext();
        var service = new FeatureFlagService(db, clock);

        await service.SetAsync(new FeatureFlagsUpdate(true), default);
        Assert.True((await service.GetAsync(default)).Christmas);
        clock.UtcNow = clock.UtcNow.AddHours(1);
        await service.SetAsync(new FeatureFlagsUpdate(false), default);
        Assert.False((await service.GetAsync(default)).Christmas);
        await service.SetAsync(new FeatureFlagsUpdate(true), default);

        await using var check = database.CreateContext();
        var row = await check.AppSettings.AsNoTracking().SingleAsync();
        Assert.Equal("feature.christmas", row.Key);
        Assert.Equal("true", row.Value);
        Assert.Equal(clock.UtcNow, row.UpdatedAtUtc);
    }

    [Fact]
    public async Task Anything_but_true_in_the_row_reads_as_off()
    {
        await using var db = database.CreateContext();
        db.AppSettings.Add(AppSetting.Create(FeatureFlagService.ChristmasKey, "yes please", clock.UtcNow));
        await db.SaveChangesAsync();

        Assert.False((await new FeatureFlagService(db, clock).GetAsync(default)).Christmas);
        Assert.True(FeatureFlagService.IsOn("TRUE"));
        Assert.False(FeatureFlagService.IsOn(null));
    }

    [Fact]
    public async Task Two_admins_setting_a_new_flag_at_once_leave_one_row()
    {
        await using var first = database.CreateContext();
        await using var second = database.CreateContext();

        await Task.WhenAll(
            new FeatureFlagService(first, clock).SetAsync(new FeatureFlagsUpdate(true), default),
            new FeatureFlagService(second, clock).SetAsync(new FeatureFlagsUpdate(true), default));

        await using var check = database.CreateContext();
        Assert.Equal("true", (await check.AppSettings.AsNoTracking().SingleAsync()).Value);
    }

    [Fact]
    public async Task The_public_config_is_anonymous_cacheable_for_60_seconds_and_camel_case()
    {
        await using var db = database.CreateContext();
        await new FeatureFlagService(db, clock).SetAsync(new FeatureFlagsUpdate(true), default);
        var method = typeof(ConfigController).GetMethod(nameof(ConfigController.Get))!;

        Assert.NotNull(method.GetCustomAttributes(typeof(AllowAnonymousAttribute), true).SingleOrDefault());
        var cache = Assert.IsType<ResponseCacheAttribute>(method.GetCustomAttributes(typeof(ResponseCacheAttribute), true).Single());
        Assert.Equal(60, cache.Duration);
        Assert.Equal(ResponseCacheLocation.Any, cache.Location);
        Assert.False(cache.NoStore);

        var result = await new ConfigController(new FeatureFlagService(db, clock)).Get(default);
        var dto = Assert.IsType<PublicConfigDto>(Assert.IsType<OkObjectResult>(result.Result).Value);
        Assert.Equal("{\"christmas\":true}", JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
    }

    [Fact]
    public async Task The_admin_reads_and_changes_the_flag_and_every_change_is_audited()
    {
        await using var db = database.CreateContext();
        var admin = Admin(db);

        Assert.False(Assert.IsType<FeatureFlagsDto>(Assert.IsType<OkObjectResult>((await admin.Get(default)).Result).Value).Christmas);
        var on = Assert.IsType<FeatureFlagsDto>(Assert.IsType<OkObjectResult>((await admin.Put(new AdminFeaturesRequest(true), default)).Result).Value);
        await admin.Put(new AdminFeaturesRequest(false), default);

        Assert.True(on.Christmas);
        await using var check = database.CreateContext();
        Assert.False((await new FeatureFlagService(check, clock).GetAsync(default)).Christmas);
        var events = (await check.AdminAuditEvents.AsNoTracking().ToListAsync()).Select(item => (item.Action, item.TargetType, item.TargetId, item.Details, item.AdminEmail)).ToList();
        Assert.Contains(("features.updated", "feature", "christmas", "christmas: off -> on", "ops@heartpact.me"), events);
        Assert.Contains(("features.updated", "feature", "christmas", "christmas: on -> off", "ops@heartpact.me"), events);
    }

    [Fact]
    public async Task Card_payments_in_the_app_start_off_and_switch_alone_without_touching_christmas()
    {
        await using var db = database.CreateContext();
        await new FeatureFlagService(db, clock).SetAsync(new FeatureFlagsUpdate(Christmas: true), default);
        var admin = Admin(db);

        Assert.False(Assert.IsType<FeatureFlagsDto>(Assert.IsType<OkObjectResult>((await admin.Get(default)).Result).Value).BachsInApp);
        var on = Assert.IsType<FeatureFlagsDto>(Assert.IsType<OkObjectResult>((await admin.Put(new AdminFeaturesRequest(BachsInApp: true), default)).Result).Value);

        Assert.Equal(new FeatureFlagsDto(Christmas: true, BachsInApp: true), on);
        Assert.Equal("{\"christmas\":true,\"bachsInApp\":true}", JsonSerializer.Serialize(on, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        await using var check = database.CreateContext();
        Assert.Equal("true", (await check.AppSettings.AsNoTracking().SingleAsync(item => item.Key == "feature.bachsInApp")).Value);
        var events = (await check.AdminAuditEvents.AsNoTracking().ToListAsync()).Select(item => (item.Action, item.TargetId, item.Details)).ToList();
        Assert.Equal([("features.updated", "bachsInApp", "bachsInApp: off -> on")], events);
        // The public config stays what every app needs at start; card payment is per person, in the entitlement.
        var config = await new ConfigController(new FeatureFlagService(check, clock)).Get(default);
        Assert.Equal("{\"christmas\":true}", JsonSerializer.Serialize(Assert.IsType<OkObjectResult>(config.Result).Value, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
    }

    [Fact]
    public async Task A_body_without_the_flag_is_refused_instead_of_switching_it_off()
    {
        await using var db = database.CreateContext();
        await new FeatureFlagService(db, clock).SetAsync(new FeatureFlagsUpdate(true), default);

        var result = await Admin(db).Put(new AdminFeaturesRequest(null), default);

        Assert.Equal(422, Assert.IsType<UnprocessableEntityObjectResult>(result.Result).StatusCode);
        Assert.True((await new FeatureFlagService(db, clock).GetAsync(default)).Christmas);
        Assert.Empty(await db.AdminAuditEvents.ToListAsync());
    }

    [Fact]
    public void Changing_the_flag_needs_a_fresh_code_and_the_admin_policy()
    {
        var put = typeof(AdminFeaturesController).GetMethod(nameof(AdminFeaturesController.Put))!;
        Assert.NotEmpty(put.GetCustomAttributes(typeof(RequireStepUpAttribute), false));
        var authorize = Assert.IsType<AuthorizeAttribute>(typeof(AdminFeaturesController).GetCustomAttributes(typeof(AuthorizeAttribute), true).Single());
        Assert.Equal(AdminAuth.Policy, authorize.Policy);
    }
}
