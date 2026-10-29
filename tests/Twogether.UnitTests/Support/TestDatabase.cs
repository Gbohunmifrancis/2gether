using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests.Support;

/// <summary>
/// A real database for controller and worker tests: the production model on SQLite, in a file so
/// that parallel requests each get their own connection, as they would in the API. SQLite runs one
/// write transaction at a time and has no advisory locks, so a test of a race checks that the
/// check-and-reserve happens inside one transaction, before the slow call. The PostgreSQL locks
/// themselves are covered by tests/Twogether.IntegrationTests.
/// </summary>
public sealed class TestDatabase : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), $"twogether-test-{Guid.NewGuid():N}.db");

    public TestDatabase()
    {
        using var db = CreateContext();
        db.Database.EnsureCreated();
        // Readers do not wait for the writer; writers queue (Default Timeout) instead of failing.
        db.Database.ExecuteSqlRaw("PRAGMA journal_mode=WAL;");
    }

    public ApplicationDbContext CreateContext(params IInterceptor[] interceptors)
        => new(new DbContextOptionsBuilder<ApplicationDbContext>()
            .UseSqlite($"Data Source={path};Default Timeout=30")
            .ReplaceService<IModelCustomizer, SqliteModelCustomizer>()
            .AddInterceptors(interceptors)
            .Options);

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var file in new[] { path, path + "-wal", path + "-shm" })
            try { File.Delete(file); } catch (IOException) { }
    }

    /// <summary>The few PostgreSQL-only bits of the model, made portable. Everything else is the production mapping.</summary>
    private sealed class SqliteModelCustomizer(ModelCustomizerDependencies dependencies) : RelationalModelCustomizer(dependencies)
    {
        public override void Customize(ModelBuilder modelBuilder, DbContext context)
        {
            base.Customize(modelBuilder, context);
            foreach (var property in modelBuilder.Model.GetEntityTypes().SelectMany(entity => entity.GetProperties()))
            {
                // "'{}'::jsonb" and the like.
                if (property.GetDefaultValueSql() is { } sql && sql.Contains("::")) property.SetDefaultValueSql(sql[..sql.IndexOf("::", StringComparison.Ordinal)]);
                // PostgreSQL's system row version; SQLite has none to fill it.
                if (property.Name == "xmin")
                {
                    property.ValueGenerated = ValueGenerated.Never;
                    property.IsConcurrencyToken = false;
                }
            }
        }
    }
}
