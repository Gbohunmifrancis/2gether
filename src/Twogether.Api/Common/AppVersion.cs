namespace Twogether.Api.Common;

/// <summary>
/// Which build is running: the commit the deploy pipeline wrote to version.txt next to the app, else
/// APP_VERSION, else "dev". The pipeline polls /health until this matches the commit it deployed.
/// </summary>
public static class AppVersion
{
    public static string Current { get; } = Read();

    private static string Read()
    {
        try
        {
            var file = Path.Combine(AppContext.BaseDirectory, "version.txt");
            if (File.Exists(file) && File.ReadAllText(file).Trim() is { Length: > 0 and <= 64 } fromFile) return fromFile;
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        return Environment.GetEnvironmentVariable("APP_VERSION")?.Trim() is { Length: > 0 and <= 64 } fromEnv ? fromEnv : "dev";
    }
}
