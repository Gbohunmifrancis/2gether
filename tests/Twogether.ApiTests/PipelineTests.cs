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
}
