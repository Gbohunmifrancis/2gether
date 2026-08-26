using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;
using Twogether.Application.Common.Interfaces;
using Twogether.Infrastructure.Persistence;
using Twogether.Infrastructure.Security;
using Twogether.Infrastructure.Time;
using Twogether.Infrastructure.Games;
using Twogether.Application.Features.Games;

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
        return services;
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
