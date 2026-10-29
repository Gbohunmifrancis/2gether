using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Infrastructure.Persistence;

namespace Twogether.IntegrationTests;

/// <summary>
/// Runs only when TWOGETHER_TEST_POSTGRES holds a connection string to a throwaway PostgreSQL
/// server; skipped otherwise, so the suite still passes on a machine without one.
/// </summary>
public sealed class PostgresFactAttribute : FactAttribute
{
    public const string Variable = "TWOGETHER_TEST_POSTGRES";

    public PostgresFactAttribute()
    {
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(Variable)))
            Skip = $"Set {Variable} to a PostgreSQL connection string to run this test.";
    }
}

/// <summary>A fresh database per test, made the way production makes it (EnsureCreated, then the upgrade SQL), dropped after.</summary>
public sealed class PostgresDatabase : IAsyncLifetime
{
    private readonly string name = $"twogether_test_{Guid.NewGuid():N}";
    private readonly NpgsqlConnectionStringBuilder server = new(Environment.GetEnvironmentVariable(PostgresFactAttribute.Variable));

    public ApplicationDbContext CreateContext()
        => new(new DbContextOptionsBuilder<ApplicationDbContext>()
            .UseNpgsql(new NpgsqlConnectionStringBuilder(server.ConnectionString) { Database = name }.ConnectionString)
            .Options);

    public async Task InitializeAsync()
    {
        await ExecuteOnServerAsync($"CREATE DATABASE \"{name}\"");
        await using var db = CreateContext();
        await db.Database.EnsureCreatedAsync();
        await DatabaseSchemaUpgrade.ApplyAsync(db);
        // Upgrading a database that is already current must change nothing and fail nothing.
        await DatabaseSchemaUpgrade.ApplyAsync(db);
    }

    public async Task DisposeAsync()
    {
        NpgsqlConnection.ClearAllPools();
        await ExecuteOnServerAsync($"DROP DATABASE IF EXISTS \"{name}\" WITH (FORCE)");
    }

    private async Task ExecuteOnServerAsync(string sql)
    {
        await using var connection = new NpgsqlConnection(new NpgsqlConnectionStringBuilder(server.ConnectionString) { Database = "postgres" }.ConnectionString);
        await connection.OpenAsync();
        await using var command = new NpgsqlCommand(sql, connection);
        await command.ExecuteNonQueryAsync();
    }
}
