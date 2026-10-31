using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;

namespace Twogether.ApiTests;

/// <summary>
/// The whole API host in memory, with no database behind it: what a request meets before any
/// controller code runs. Nothing here may touch PostgreSQL.
/// </summary>
public sealed class PipelineTests(PipelineTests.Host host) : IClassFixture<PipelineTests.Host>
{
    public sealed class Host : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Development");
            // Nowhere: a developer's local database must never be touched by a test run.
            builder.UseSetting("Database:ConnectionString", "Host=127.0.0.1;Port=1;Database=none;Username=none;Timeout=1");
            builder.UseSetting("Database:EnsureCreated", "false");
            // The background workers poll the database; requests are what is under test.
            builder.ConfigureServices(services => services.RemoveAll<IHostedService>());
        }
    }

    [Fact]
    public async Task Health_answers_without_a_database()
    {
        var response = await host.CreateClient().GetAsync("/health");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("ok", (await response.Content.ReadFromJsonAsync<Dictionary<string, object>>())!["status"].ToString());
    }

    [Theory]
    [InlineData("POST", "/api/memories/uploads")]
    [InlineData("POST", "/api/memories/sync")]
    [InlineData("POST", "/api/food/scans")]
    [InlineData("POST", "/api/couple/breakup")]
    [InlineData("POST", "/api/account/delete")]
    public async Task Album_food_and_couple_endpoints_need_a_signed_in_user(string method, string path)
    {
        var request = new HttpRequestMessage(new HttpMethod(method), path) { Content = JsonContent.Create(new { }) };

        var response = await host.CreateClient().SendAsync(request);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Theory]
    [InlineData("PUT", "/api/account/avatar")]
    [InlineData("DELETE", "/api/account/avatar")]
    [InlineData("GET", "/api/users/3f2b8c1e-6b7a-4c1d-9a55-0d7b8f4e1a11/avatar")]
    public async Task Profile_picture_endpoints_need_a_signed_in_user(string method, string path)
    {
        var response = await host.CreateClient().SendAsync(new HttpRequestMessage(new HttpMethod(method), path));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Theory]
    [InlineData("GET")]
    [InlineData("PUT")]
    public async Task Admin_features_need_an_admin_session(string method)
    {
        var request = new HttpRequestMessage(new HttpMethod(method), "/api/admin/features") { Content = JsonContent.Create(new { christmas = true }) };

        var response = await host.CreateClient().SendAsync(request);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task The_public_config_needs_no_sign_in()
    {
        // No database behind this host, so the read itself fails; what matters is that the request got past authentication.
        var response = await host.CreateClient().GetAsync("/api/config");

        Assert.NotEqual(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.NotEqual(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Theory]
    [InlineData("/api/billing/bachs/checkout")]
    [InlineData("/api/billing/bachs/portal")]
    public async Task Card_checkout_and_the_portal_need_a_signed_in_user(string path)
    {
        var response = await host.CreateClient().PostAsync(path, JsonContent.Create(new { plan = "monthly" }));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task The_bachs_webhook_is_anonymous_and_503_until_bachs_is_set_up()
    {
        var response = await host.CreateClient().PostAsync("/api/billing/bachs/webhook", new StringContent("{}", System.Text.Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
    }

    [Fact]
    public async Task A_webhook_with_a_bad_signature_is_400_before_anything_is_read()
    {
        using var configured = host.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Bachs:ApiKey", "sk_sandbox_test");
            builder.UseSetting("Bachs:WebhookSecret", "whsec_test");
            builder.UseSetting("Bachs:MonthlyProductId", "prod_month");
            builder.UseSetting("Bachs:YearlyProductId", "prod_year");
        });
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/billing/bachs/webhook") { Content = new StringContent("{\"id\":\"evt_1\"}", System.Text.Encoding.UTF8, "application/json") };
        request.Headers.TryAddWithoutValidation("X-Bachs-Signature-V2", $"t={DateTimeOffset.UtcNow.ToUnixTimeSeconds()},v1={new string('0', 64)}");

        var response = await configured.CreateClient().SendAsync(request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
}
