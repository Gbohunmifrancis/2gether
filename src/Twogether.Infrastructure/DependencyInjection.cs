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
        return services;
    }

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
            MaxBytesPerCouple = long.TryParse(configuration["Media:MaxBytesPerCouple"], out var quota) && quota > 0 ? quota : defaults.MaxBytesPerCouple
        };
    }

    private static FoodSettings BuildFoodSettings(IConfiguration configuration)
    {
        var defaults = new FoodSettings();
        return new FoodSettings
        {
            Model = string.IsNullOrWhiteSpace(configuration["Food:Model"]) ? defaults.Model : configuration["Food:Model"]!.Trim(),
            AnthropicApiKey = configuration["Food:AnthropicApiKey"],
            DailyScanLimit = int.TryParse(configuration["Food:DailyScanLimit"], out var limit) && limit > 0 ? limit : defaults.DailyScanLimit,
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
