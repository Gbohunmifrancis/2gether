namespace Twogether.Infrastructure.Persistence;

public sealed class DatabaseSettings
{
    public string ConnectionString { get; set; } = "Host=localhost;Port=5432;Database=twogether;Username=twogether;Password=twogether_dev";
}
