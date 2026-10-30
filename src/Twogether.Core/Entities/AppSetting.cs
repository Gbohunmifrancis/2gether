namespace Twogether.Core.Entities;

/// <summary>
/// A small server-wide setting an admin can change without a deploy, held as a key and a text value. Today these are
/// feature flags ("true" / "false"); a key with no row means the flag is off.
/// </summary>
public sealed class AppSetting
{
    public const int MaxKeyLength = 64;
    public const int MaxValueLength = 1000;

    private AppSetting() { }

    public string Key { get; private set; } = string.Empty;
    public string Value { get; private set; } = string.Empty;
    public DateTime UpdatedAtUtc { get; private set; }

    public static AppSetting Create(string key, string value, DateTime utcNow) => new() { Key = key, Value = value, UpdatedAtUtc = utcNow };
}
