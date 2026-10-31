using Microsoft.EntityFrameworkCore;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Config;
using Twogether.Infrastructure.Persistence;
using Twogether.UnitTests.Support;

namespace Twogether.IntegrationTests;

/// <summary>Profile pictures and feature flags on real PostgreSQL, including the additive upgrade of a database that predates them.</summary>
public sealed class PostgresAvatarAndFlagTests : IAsyncLifetime
{
    private static readonly byte[] Png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0xFF, 0x00];
    private readonly PostgresDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);

    public Task InitializeAsync() => database.InitializeAsync();
    public Task DisposeAsync() => database.DisposeAsync();

    [PostgresFact]
    public async Task An_older_database_gains_the_picture_columns_and_the_settings_table_and_they_work()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await db.Database.ExecuteSqlRawAsync("ALTER TABLE users DROP COLUMN \"AvatarBytes\", DROP COLUMN \"AvatarVersion\"; DROP TABLE app_settings;");

        await DatabaseSchemaUpgrade.ApplyAsync(db);
        await DatabaseSchemaUpgrade.ApplyAsync(db);

        var avatars = new AvatarService(db, clock);
        Assert.Equal(1, (await avatars.SetAsync(couple.MayaId, "image/png", Png, default)).Value.AvatarVersion);
        Assert.Equal(2, (await avatars.SetAsync(couple.MayaId, "image/png", Png, default)).Value.AvatarVersion);
        Assert.Equal(2, await avatars.VisibleVersionAsync(couple.TheoId, couple.MayaId, default));
        Assert.Equal(Png, (await avatars.LoadAsync(couple.TheoId, couple.MayaId, default))!.Bytes);
        Assert.True(await avatars.ClearAsync(couple.MayaId, default));
        Assert.Null(await avatars.VisibleVersionAsync(couple.TheoId, couple.MayaId, default));

        var flags = new FeatureFlagService(db, clock);
        Assert.False((await flags.GetAsync(default)).Christmas);
        await flags.SetAsync(new FeatureFlagsUpdate(true), default);
        await flags.SetAsync(new FeatureFlagsUpdate(true), default);
        Assert.True((await flags.GetAsync(default)).Christmas);
        Assert.Equal(1, await db.AppSettings.CountAsync());
    }
}
