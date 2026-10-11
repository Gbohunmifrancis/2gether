using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.OpenApi.Models;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Twogether.Application;
using Twogether.Api.Auth;
using Twogether.Api.Hubs;
using Twogether.Api.Presence;
using Twogether.Infrastructure;
using Twogether.Infrastructure.Persistence;
using Twogether.Api.Background;

var builder = WebApplication.CreateBuilder(args);

builder.Configuration.AddEnvironmentVariables();

// Railway supplies the listening port through PORT; keep local launch behavior unchanged.
var railwayPort = Environment.GetEnvironmentVariable("PORT");
if (int.TryParse(railwayPort, out var parsedPort) && parsedPort is > 0 and <= 65535)
{
    builder.WebHost.UseUrls($"http://0.0.0.0:{parsedPort}");
}

builder.Services.AddApplication();
builder.Services.AddInfrastructure(builder.Configuration);
builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<Twogether.Application.Common.Interfaces.ICurrentUser, Twogether.Api.Auth.CurrentUser>();

var jwtSettings = builder.Configuration.GetSection("Jwt").Get<Twogether.Api.Auth.JwtSettings>() ?? new Twogether.Api.Auth.JwtSettings();
builder.Services.AddSingleton(jwtSettings);
builder.Services.AddSingleton<Twogether.Application.Common.Interfaces.ITokenService, Twogether.Api.Auth.JwtTokenService>();
builder.Services.AddSingleton<CouplePresenceTracker>();
builder.Services.AddSingleton<GameLiveState>();
builder.Services.AddHostedService<GameTimeoutWorker>();
builder.Services.AddHostedService<ExerciseAlarmWorker>();
builder.Services.AddHostedService<MemoryCleanupWorker>();
builder.Services.AddHostedService<PushDispatchWorker>();

builder.Services.AddControllers()
    .AddJsonOptions(options =>
    {
        options.JsonSerializerOptions.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase));
    });
builder.Services.AddResponseCaching();

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.TokenValidationParameters = new Microsoft.IdentityModel.Tokens.TokenValidationParameters
        {
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new Microsoft.IdentityModel.Tokens.SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtSettings.SigningKey)),
            ValidateIssuer = true,
            ValidIssuer = jwtSettings.Issuer,
            ValidateAudience = true,
            ValidAudience = jwtSettings.Audience,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromSeconds(30)
        };
        options.Events = new JwtBearerEvents
        {
            OnMessageReceived = context =>
            {
                var accessToken = context.Request.Query["access_token"];
                var path = context.HttpContext.Request.Path;
                if (!string.IsNullOrWhiteSpace(accessToken)
                    && (path.StartsWithSegments("/hubs/couple") || path.StartsWithSegments("/hubs/game")))
                {
                    context.Token = accessToken;
                }

                return Task.CompletedTask;
            },
            // Tokens live for days, but the account and its couple can change sooner. A deleted
            // account must stop writing at once, and after a breakup the old couple id in the
            // token must not keep opening the old chat, games and album. So the couple claim is
            // replaced by the one stored now, and every endpoint and hub sees the current couple.
            OnTokenValidated = async context =>
            {
                var subject = context.Principal?.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)?.Value
                    ?? context.Principal?.FindFirst("sub")?.Value;
                var database = context.HttpContext.RequestServices.GetRequiredService<Twogether.Application.Common.Interfaces.IApplicationDbContext>();
                var current = Guid.TryParse(subject, out var userId)
                    ? await database.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => new { user.CoupleId }).SingleOrDefaultAsync(context.HttpContext.RequestAborted)
                    : null;
                if (current is null)
                {
                    context.Fail("The account no longer exists.");
                    return;
                }
                if (context.Principal?.Identity is not System.Security.Claims.ClaimsIdentity identity) return;
                foreach (var stale in identity.FindAll(JwtTokenService.CoupleClaim).ToList()) identity.RemoveClaim(stale);
                if (current.CoupleId is { } coupleId) identity.AddClaim(new System.Security.Claims.Claim(JwtTokenService.CoupleClaim, coupleId.ToString()));
            }
        };
    });
builder.Services.AddAuthorization();
builder.Services.AddSignalR(options => options.AddFilter<CoupleMembershipHubFilter>())
    .AddJsonProtocol(options =>
    {
        // Hub payloads must match the controller contract: enums as camelCase
        // strings. Without this, GameSessionDto arrives with numeric status and
        // gameType and the client renders them as text.
        options.PayloadSerializerOptions.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase));
    });
builder.Services.AddCors(options =>
    {
        options.AddPolicy("Frontend", policy =>
        {
            var origins = (builder.Configuration["Frontend:Origin"] ?? "http://localhost:3000")
                .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

            policy.WithOrigins(origins)
                .AllowAnyHeader()
                .AllowAnyMethod()
                .AllowCredentials();
    });
});

builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc("v1", new OpenApiInfo { Title = "Twogether API", Version = "v1" });
});

// On unless turned off for local load or smoke tests; Production refuses to run without it.
var rateLimitsEnabled = builder.Configuration.GetValue<bool?>("RateLimits:Enabled") ?? true;
builder.Services.AddSingleton(provider => new Twogether.Application.Common.Security.AttemptLimiter(
    provider.GetRequiredService<Twogether.Application.Common.Interfaces.IDateTimeProvider>(), rateLimitsEnabled));
var inviteDigits = builder.Configuration.GetValue<int?>("Invites:CodeDigits") ?? Twogether.Application.Features.Couple.InviteSettings.MinCodeDigits;
if (inviteDigits is < Twogether.Application.Features.Couple.InviteSettings.MinCodeDigits or > Twogether.Application.Features.Couple.InviteSettings.MaxCodeDigits)
    throw new InvalidOperationException("Invites:CodeDigits must be between 6 and 10.");
builder.Services.AddSingleton(new Twogether.Application.Features.Couple.InviteSettings { CodeDigits = inviteDigits });

// Behind Railway (or any proxy) every request arrives from the proxy, which would put all users
// in one per-address budget. Trust only the nearest hop's X-Forwarded-For: the value that proxy
// appended, not anything the client sent before it.
var trustForwardedHeaders = builder.Configuration.GetValue<bool>("ForwardedHeaders:Enabled");
if (trustForwardedHeaders)
{
    builder.Services.Configure<Microsoft.AspNetCore.Builder.ForwardedHeadersOptions>(options =>
    {
        options.ForwardedHeaders = Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor | Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto;
        options.ForwardLimit = 1;
        options.KnownIPNetworks.Clear();
        options.KnownProxies.Clear();
    });
}

var app = builder.Build();

if (app.Environment.IsProduction() && !rateLimitsEnabled)
{
    throw new InvalidOperationException("RateLimits:Enabled=false leaves sign-in and invite codes open to guessing and is refused in Production.");
}

if (trustForwardedHeaders) app.UseForwardedHeaders();
else if (app.Environment.IsProduction())
    app.Logger.LogWarning("ForwardedHeaders:Enabled is off. Behind a proxy, per-address sign-in and invite limits treat all users as one address.");

if (app.Environment.IsProduction() && string.Equals(app.Configuration["Billing:Provider"]?.Trim(), "fake", StringComparison.OrdinalIgnoreCase))
{
    throw new InvalidOperationException("Billing:Provider=fake grants paid access for free and is refused in Production.");
}

if (app.Environment.IsProduction() && jwtSettings.SigningKey.Contains("change-before-production", StringComparison.OrdinalIgnoreCase))
{
    throw new InvalidOperationException("Jwt:SigningKey must be configured with a production secret.");
}

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseRouting();
app.UseResponseCaching();
app.UseCors("Frontend");
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();
app.MapHub<CoupleHub>("/hubs/couple");
app.MapHub<GameHub>("/hubs/game");
app.MapGet("/health", () => Results.Ok(new { status = "ok", service = "twogether-api" }));

var ensureDatabase = app.Configuration.GetValue<bool?>("Database:EnsureCreated") ?? app.Environment.IsProduction();
if (ensureDatabase)
{
    await using var scope = app.Services.CreateAsyncScope();
    var database = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
    await database.Database.EnsureCreatedAsync();
    await DatabaseSchemaUpgrade.ApplyAsync(database);
}

app.Run();

public partial class Program;
