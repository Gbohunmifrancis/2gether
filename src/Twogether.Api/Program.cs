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
var adminSettings = new Twogether.Application.Features.Admin.AdminSettings
{
    Emails = (builder.Configuration["Admin:Emails"] ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
    TokenLifetime = TimeSpan.FromHours(Math.Clamp(builder.Configuration.GetValue<double?>("Admin:TokenHours") ?? 4, 0.25, 12)),
    MonthlyPrices = builder.Configuration.GetSection("Admin:MonthlyPrices").GetChildren()
        .Where(item => decimal.TryParse(item.Value, System.Globalization.NumberStyles.Number, System.Globalization.CultureInfo.InvariantCulture, out _))
        .ToDictionary(item => item.Key, item => decimal.Parse(item.Value!, System.Globalization.CultureInfo.InvariantCulture)),
    Currency = builder.Configuration["Admin:Currency"] ?? "USD",
    FoodScanPricePerMillionInput = PositiveDecimal(builder.Configuration["Admin:FoodScanPricePerMillionInput"]),
    FoodScanPricePerMillionOutput = PositiveDecimal(builder.Configuration["Admin:FoodScanPricePerMillionOutput"]),
    StorageBudgetGb = PositiveDecimal(builder.Configuration["Admin:StorageBudgetGb"]),
};
static decimal? PositiveDecimal(string? value)
    => decimal.TryParse(value, System.Globalization.NumberStyles.Number, System.Globalization.CultureInfo.InvariantCulture, out var parsed) && parsed > 0 ? parsed : null;
builder.Services.AddSingleton(adminSettings);
// Admins' authenticator secrets are encrypted at rest; older keys still read existing ones (docs/admin.md).
builder.Services.AddSingleton(_ => new Twogether.Application.Features.Admin.AdminSecretProtector(
    builder.Configuration["Admin:SecretKey"], builder.Configuration["Admin:PreviousSecretKey"], jwtSettings.SigningKey));
builder.Services.AddScoped<Twogether.Api.Admin.AdminAudit>();
// Admin console v2: live feed, health and alerts (docs/admin/contract-v2.md).
builder.Services.AddSingleton<Twogether.Application.Features.Admin.Ops.WorkerHeartbeats>();
builder.Services.AddSingleton<Twogether.Application.Features.Admin.Ops.OpsSignals>();
builder.Services.AddSingleton<Twogether.Application.Features.Admin.Ops.AdminAlertMonitor>();
builder.Services.AddScoped<Twogether.Application.Features.Admin.Live.AdminLiveFeed>();
builder.Services.AddScoped<Twogether.Application.Features.Admin.Ops.AdminAlerts>();
builder.Services.AddScoped<Twogether.Application.Features.Admin.Ops.AdminHealth>();
builder.Services.AddScoped<Twogether.Application.Features.Admin.People.AdminJourney>();
builder.Services.AddSingleton<CouplePresenceTracker>();
builder.Services.AddSingleton<HubConnectionRegistry>();
builder.Services.AddSingleton<Twogether.Api.Realtime.GameInkGate>();
builder.Services.AddSingleton<GameLiveState>();
builder.Services.AddSingleton<Twogether.Application.Features.Location.LocationFixThrottle>();
builder.Services.AddScoped<Twogether.Api.Realtime.LocationSharingStopper>();
builder.Services.AddHostedService<GameTimeoutWorker>();
builder.Services.AddHostedService<ExerciseAlarmWorker>();
builder.Services.AddHostedService<MemoryCleanupWorker>();
builder.Services.AddHostedService<PushDispatchWorker>();
builder.Services.AddHostedService<AlbumSyncReminderWorker>();
builder.Services.AddHostedService<LocationSweepWorker>();
builder.Services.AddHostedService<AdminAuditSweepWorker>();

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
                    ? await database.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => new { user.CoupleId, user.SessionsValidFromUtc, user.DisabledAtUtc, user.LastActiveAtUtc, user.Language }).SingleOrDefaultAsync(context.HttpContext.RequestAborted)
                    : null;
                if (current is null)
                {
                    context.Fail("The account no longer exists.");
                    return;
                }
                if (current.DisabledAtUtc is not null)
                {
                    context.Fail("This account has been disabled.");
                    return;
                }
                // For active-user analytics: at most one write per user every 15 minutes, and one row per day.
                var clockNow = context.HttpContext.RequestServices.GetRequiredService<Twogether.Application.Common.Interfaces.IDateTimeProvider>().UtcNow;
                if (current.LastActiveAtUtc is null || clockNow - current.LastActiveAtUtc > TimeSpan.FromMinutes(15))
                    await Twogether.Infrastructure.Admin.UserActivity.RecordAsync(database, userId, current.LastActiveAtUtc, clockNow, context.HttpContext.RequestAborted);
                // Notifications and emails for this person are written in the language their app asks in.
                if (context.HttpContext.Request.Headers.AcceptLanguage.Count > 0 && Twogether.Shared.Localization.Loc.Language is var language && language != current.Language)
                    await Twogether.Infrastructure.Admin.UserActivity.SetLanguageAsync(database, userId, language, context.HttpContext.RequestAborted);
                // A password reset ends every session that started before it.
                if (current.SessionsValidFromUtc is { } validFrom && context.SecurityToken.ValidFrom < validFrom)
                {
                    context.Fail("This session ended when the password was reset.");
                    return;
                }
                if (context.Principal?.Identity is not System.Security.Claims.ClaimsIdentity identity) return;
                foreach (var stale in identity.FindAll(JwtTokenService.CoupleClaim).ToList()) identity.RemoveClaim(stale);
                if (current.CoupleId is { } coupleId) identity.AddClaim(new System.Security.Claims.Claim(JwtTokenService.CoupleClaim, coupleId.ToString()));
            }
        };
    })
    .AddJwtBearer(Twogether.Api.Admin.AdminAuth.Scheme, options =>
    {
        options.TokenValidationParameters = new Microsoft.IdentityModel.Tokens.TokenValidationParameters
        {
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new Microsoft.IdentityModel.Tokens.SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtSettings.SigningKey)),
            ValidateIssuer = true,
            ValidIssuer = jwtSettings.Issuer,
            ValidateAudience = true,
            ValidAudience = Twogether.Api.Admin.AdminAuth.Audience(jwtSettings),
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromSeconds(30)
        };
        options.MapInboundClaims = false;
        options.Events = new JwtBearerEvents
        {
            // An admin stays an admin only while the account exists, is enabled, is still on the
            // allowlist, and its sessions haven't been ended since the token was issued.
            OnTokenValidated = async context =>
            {
                var subject = context.Principal?.FindFirst("sub")?.Value;
                var database = context.HttpContext.RequestServices.GetRequiredService<Twogether.Application.Common.Interfaces.IApplicationDbContext>();
                var current = Guid.TryParse(subject, out var userId)
                    ? await database.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => new { user.Email, user.DisplayName, user.DisabledAtUtc, user.SessionsValidFromUtc }).SingleOrDefaultAsync(context.HttpContext.RequestAborted)
                    : null;
                if (current is null || current.DisabledAtUtc is not null || !adminSettings.IsAdmin(current.Email)
                    || (current.SessionsValidFromUtc is { } validFrom && context.SecurityToken.ValidFrom < validFrom))
                {
                    context.Fail("Not an admin session.");
                    return;
                }
                if (context.Principal?.Identity is System.Security.Claims.ClaimsIdentity identity)
                {
                    identity.AddClaim(new System.Security.Claims.Claim(System.Security.Claims.ClaimTypes.NameIdentifier, userId.ToString()));
                    identity.AddClaim(new System.Security.Claims.Claim(System.Security.Claims.ClaimTypes.Email, current.Email));
                    identity.AddClaim(new System.Security.Claims.Claim(System.Security.Claims.ClaimTypes.Name, current.DisplayName));
                }
            }
        };
    });
builder.Services.AddAuthorization(options =>
{
    options.AddPolicy(Twogether.Api.Admin.AdminAuth.Policy, policy => policy
        .AddAuthenticationSchemes(Twogether.Api.Admin.AdminAuth.Scheme)
        .RequireAuthenticatedUser()
        .RequireClaim(Twogether.Api.Admin.AdminAuth.RoleClaim, Twogether.Api.Admin.AdminAuth.RoleValue));
});
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

// Requests are small JSON everywhere except the food scan, which raises its own limit. The
// 30 MB default would let any endpoint be made to buffer and parse a huge body.
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = 1024 * 1024);

var app = builder.Build();

var foodSettings = app.Services.GetRequiredService<Twogether.Application.Features.Food.FoodSettings>();
if (!Twogether.Application.Features.Food.FoodSettings.FitsInRequest(foodSettings.MaxImageBytes))
{
    throw new InvalidOperationException($"Food:MaxImageBytes={foodSettings.MaxImageBytes} does not fit in a scan request once base64-encoded; keep it under about 6 MB.");
}

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
if (app.Environment.IsProduction() && string.Equals(app.Configuration["Integrity:Provider"]?.Trim(), "fake", StringComparison.OrdinalIgnoreCase))
{
    throw new InvalidOperationException("Integrity:Provider=fake trusts any app and is refused in Production.");
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

// Messages follow the caller's Accept-Language (docs/translation.md). Only the UI language changes: numbers and dates
// the server formats or parses stay in the invariant English culture.
app.UseRequestLocalization(new Microsoft.AspNetCore.Builder.RequestLocalizationOptions
{
    DefaultRequestCulture = new Microsoft.AspNetCore.Localization.RequestCulture("en", "en"),
    SupportedCultures = [System.Globalization.CultureInfo.GetCultureInfo("en")],
    SupportedUICultures = Twogether.Shared.Localization.Loc.Languages.Select(System.Globalization.CultureInfo.GetCultureInfo).ToList(),
    FallBackToParentUICultures = true,
});
app.Use((context, next) =>
{
    // A cached answer in one language must not be served to someone asking in another.
    context.Response.Headers.Append("Vary", "Accept-Language");
    return next(context);
});
app.UseRouting();
app.UseResponseCaching();
app.UseCors("Frontend");
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();
app.MapHub<CoupleHub>("/hubs/couple");
app.MapHub<GameHub>("/hubs/game");
app.MapGet("/health", () => Results.Ok(new { status = "ok", service = "twogether-api", version = Twogether.Api.Common.AppVersion.Current }));

var ensureDatabase = app.Configuration.GetValue<bool?>("Database:EnsureCreated") ?? app.Environment.IsProduction();
if (ensureDatabase)
{
    await using var scope = app.Services.CreateAsyncScope();
    var database = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
    await database.Database.EnsureCreatedAsync();
    await DatabaseSchemaUpgrade.ApplyAsync(database);
    var rewritten = await Twogether.Infrastructure.Admin.AdminSecretMigration.RunAsync(database, app.Services.GetRequiredService<Twogether.Application.Features.Admin.AdminSecretProtector>());
    if (rewritten > 0) app.Logger.LogInformation("Stored {Count} admin authenticator secrets under the current key.", rewritten);
}

app.Run();

public partial class Program;
