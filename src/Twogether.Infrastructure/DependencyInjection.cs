using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;
using Twogether.Application.Common.Interfaces;
using Twogether.Infrastructure.Persistence;
using Twogether.Infrastructure.Security;
using Twogether.Infrastructure.Time;
using Twogether.Infrastructure.Games;
using Twogether.Infrastructure.Food;
using Twogether.Infrastructure.Media;
using Twogether.Infrastructure.Push;
using Twogether.Infrastructure.Auth;
using Twogether.Application.Features.Auth;
using Twogether.Infrastructure.Billing;
using Twogether.Infrastructure.Exercise;
using Twogether.Infrastructure.Account;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Exercise;
using Twogether.Application.Features.Push;
using Twogether.Application.Features.Memories;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Food;

namespace Twogether.Infrastructure;

public static class DependencyInjection
{
    public static IServiceCollection AddInfrastructure(this IServiceCollection services, IConfiguration configuration)
    {
        var configuredConnectionString = configuration["Database:ConnectionString"];
        var railwayConnectionString = BuildRailwayConnectionString(configuration);
        var database = new DatabaseSettings
        {
            ConnectionString = railwayConnectionString
                ?? configuredConnectionString
                ?? new DatabaseSettings().ConnectionString
        };
        services.AddSingleton(database);
        services.AddDbContext<ApplicationDbContext>(options => options.UseNpgsql(database.ConnectionString));
        services.AddScoped<IApplicationDbContext>(provider => provider.GetRequiredService<ApplicationDbContext>());
        services.AddSingleton<IPasswordHasher, PasswordHasher>();
        services.AddSingleton<IDateTimeProvider, SystemDateTimeProvider>();
        services.AddSingleton(new HttpClient { Timeout = TimeSpan.FromSeconds(5) });
        services.AddSingleton<IGameWordValidator, PublicGameWordValidator>();
        services.AddSingleton<IGameQuestionProvider, OpenTriviaQuestionProvider>();
        services.AddSingleton(BuildFoodSettings(configuration));
        services.AddSingleton<IFoodPhotoEstimator, AnthropicFoodPhotoEstimator>();
        services.AddSingleton(BuildMediaSettings(configuration));
        services.AddSingleton<IMediaStorage, S3MediaStorage>();
        var push = BuildPushSettings(configuration);
        services.AddSingleton(push);
        switch (push.Provider?.Trim().ToLowerInvariant())
        {
            case "fcm": services.AddSingleton<IPushSender, FcmPushSender>(); break;
            case "log": services.AddSingleton<IPushSender, LogPushSender>(); break;
            default: services.AddSingleton<IPushSender, DisabledPushSender>(); break;
        }
        services.AddScoped<IPushOutbox, PushOutbox>();

        var email = BuildEmailSettings(configuration);
        services.AddSingleton(email);
        switch (email.Provider?.Trim().ToLowerInvariant())
        {
            case "smtp": services.AddSingleton<IEmailSender, SmtpEmailSender>(); break;
            case "log": services.AddSingleton<IEmailSender, LogEmailSender>(); break;
            default: services.AddSingleton<IEmailSender, DisabledEmailSender>(); break;
        }
        services.AddSingleton(new GoogleAuthSettings
        {
            ClientIds = (configuration["Auth:GoogleClientIds"] ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
        });
        services.AddSingleton<IGoogleTokenVerifier, GoogleTokenVerifier>();
        services.AddScoped<IAccountEraser, AccountEraser>();
        var billing = BuildBillingSettings(configuration);
        services.AddSingleton(billing);
        switch (billing.Provider?.Trim().ToLowerInvariant())
        {
            case "googleplay": services.AddSingleton<IGooglePlayBilling, GooglePlayBilling>(); break;
            case "fake": services.AddSingleton<IGooglePlayBilling, FakeGooglePlayBilling>(); break;
            default: services.AddSingleton<IGooglePlayBilling, DisabledGooglePlayBilling>(); break;
        }
        // Play Integrity reuses the billing service account and package unless given its own.
        var integrity = new PlayIntegritySettings
        {
            Provider = configuration["Integrity:Provider"],
            PackageName = string.IsNullOrWhiteSpace(configuration["Integrity:PackageName"]) ? billing.GooglePlayPackageName : configuration["Integrity:PackageName"],
            CredentialsJson = string.IsNullOrWhiteSpace(configuration["Integrity:CredentialsJson"]) ? billing.GooglePlayCredentialsJson : configuration["Integrity:CredentialsJson"],
        };
        services.AddSingleton(integrity);
        switch (integrity.Provider?.Trim().ToLowerInvariant())
        {
            case "playintegrity": services.AddSingleton<IPlayIntegrityVerifier, PlayIntegrityVerifier>(); break;
            case "fake": services.AddSingleton<IPlayIntegrityVerifier, FakePlayIntegrityVerifier>(); break;
            default: services.AddSingleton<IPlayIntegrityVerifier, DisabledPlayIntegrityVerifier>(); break;
        }
        return services;
    }

    private static BillingSettings BuildBillingSettings(IConfiguration configuration)
    {
        var defaults = new BillingSettings();
        int Int(string key, int fallback) => int.TryParse(configuration[key], out var value) && value > 0 ? value : fallback;
        long Long(string key, long fallback) => long.TryParse(configuration[key], out var value) && value > 0 ? value : fallback;
        return new BillingSettings
        {
            Provider = configuration["Billing:Provider"],
            GooglePlayPackageName = configuration["Billing:GooglePlayPackageName"],
            GooglePlayCredentialsJson = configuration["Billing:GooglePlayCredentialsJson"],
            ProductIds = (configuration["Billing:ProductIds"] ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
            RtdnSecret = configuration["Billing:RtdnSecret"],
            Free = new PlanLimits(Int("Billing:Free:FoodScansPerDay", defaults.Free.FoodScansPerDay), Long("Billing:Free:MemoriesBytes", defaults.Free.MemoriesBytes), Int("Billing:Free:QuestionHistoryDays", defaults.Free.QuestionHistoryDays)),
            Plus = new PlanLimits(Int("Billing:Plus:FoodScansPerDay", defaults.Plus.FoodScansPerDay), Long("Billing:Plus:MemoriesBytes", defaults.Plus.MemoriesBytes), Int("Billing:Plus:QuestionHistoryDays", defaults.Plus.QuestionHistoryDays))
        };
    }

    private static EmailSettings BuildEmailSettings(IConfiguration configuration) => new()
    {
        Provider = configuration["Email:Provider"],
        Host = configuration["Email:Host"],
        Port = int.TryParse(configuration["Email:Port"], out var port) && port > 0 ? port : 587,
        User = configuration["Email:User"],
        Password = configuration["Email:Password"],
        From = configuration["Email:From"],
        FromName = string.IsNullOrWhiteSpace(configuration["Email:FromName"]) ? "Heartpact" : configuration["Email:FromName"]!,
        StartTls = !bool.TryParse(configuration["Email:StartTls"], out var startTls) || startTls,
    };

    private static PushSettings BuildPushSettings(IConfiguration configuration) => new()
    {
        Provider = configuration["Push:Provider"],
        FcmProjectId = configuration["Push:FcmProjectId"],
        FcmCredentialsJson = configuration["Push:FcmCredentialsJson"],
        MaxDevicesPerUser = int.TryParse(configuration["Push:MaxDevicesPerUser"], out var max) && max > 0 ? max : 10
    };

    private static MediaSettings BuildMediaSettings(IConfiguration configuration)
    {
        var defaults = new MediaSettings();
        return new MediaSettings
        {
            ServiceUrl = configuration["Media:ServiceUrl"],
            Bucket = configuration["Media:Bucket"],
            AccessKey = configuration["Media:AccessKey"],
            SecretKey = configuration["Media:SecretKey"],
            Region = string.IsNullOrWhiteSpace(configuration["Media:Region"]) ? defaults.Region : configuration["Media:Region"]!.Trim(),
            BreakupRetention = int.TryParse(configuration["Media:BreakupRetentionDays"], out var days) && days is >= 1 and <= 365
                ? TimeSpan.FromDays(days)
                : defaults.BreakupRetention
        };
    }

    private static FoodSettings BuildFoodSettings(IConfiguration configuration)
    {
        var defaults = new FoodSettings();
        return new FoodSettings
        {
            Model = string.IsNullOrWhiteSpace(configuration["Food:Model"]) ? defaults.Model : configuration["Food:Model"]!.Trim(),
            AnthropicApiKey = configuration["Food:AnthropicApiKey"],
            MaxImageBytes = int.TryParse(configuration["Food:MaxImageBytes"], out var bytes) && bytes > 0 ? bytes : defaults.MaxImageBytes,
            TimeoutSeconds = int.TryParse(configuration["Food:TimeoutSeconds"], out var seconds) && seconds > 0 ? seconds : defaults.TimeoutSeconds
        };
    }

    private static string? BuildRailwayConnectionString(IConfiguration configuration)
    {
        var databaseUrl = configuration["DATABASE_URL"];
        if (!string.IsNullOrWhiteSpace(databaseUrl))
        {
            if (Uri.TryCreate(databaseUrl, UriKind.Absolute, out var uri))
            {
                var userInfo = uri.UserInfo.Split(':', 2);
                var uriBuilder = new NpgsqlConnectionStringBuilder
                {
                    Host = uri.Host,
                    Port = uri.Port > 0 ? uri.Port : 5432,
                    Database = uri.AbsolutePath.TrimStart('/'),
                    Username = userInfo.Length > 0 ? Uri.UnescapeDataString(userInfo[0]) : string.Empty,
                    Password = userInfo.Length > 1 ? Uri.UnescapeDataString(userInfo[1]) : string.Empty,
                    SslMode = SslMode.Prefer
                };

                return uriBuilder.ConnectionString;
            }
        }

        var host = configuration["PGHOST"];
        var database = configuration["PGDATABASE"];
        var username = configuration["PGUSER"];
        var password = configuration["PGPASSWORD"];

        if (string.IsNullOrWhiteSpace(host)
            || string.IsNullOrWhiteSpace(database)
            || string.IsNullOrWhiteSpace(username)
            || string.IsNullOrWhiteSpace(password))
        {
            return null;
        }

        var builder = new NpgsqlConnectionStringBuilder
        {
            Host = host,
            Database = database,
            Username = username,
            Password = password,
            SslMode = SslMode.Prefer
        };

        if (int.TryParse(configuration["PGPORT"], out var port))
        {
            builder.Port = port;
        }

        return builder.ConnectionString;
    }
}
