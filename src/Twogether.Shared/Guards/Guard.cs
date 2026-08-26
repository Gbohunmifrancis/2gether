namespace Twogether.Shared.Guards;

public static class Guard
{
    public static string NotNullOrWhiteSpace(string? value, string? parameterName = null)
        => string.IsNullOrWhiteSpace(value)
            ? throw new ArgumentException("Value cannot be empty.", parameterName)
            : value.Trim();

    public static T NotNull<T>(T? value, string? parameterName = null) where T : class
        => value ?? throw new ArgumentNullException(parameterName);
}
