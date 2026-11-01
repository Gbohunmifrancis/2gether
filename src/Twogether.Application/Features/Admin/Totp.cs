using System.Security.Cryptography;

namespace Twogether.Application.Features.Admin;

/// <summary>RFC 6238 time-based codes (SHA-1, 6 digits, 30 s), as every authenticator app expects.</summary>
public static class Totp
{
    public const int StepSeconds = 30;
    private const string Base32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static string NewSecret() => ToBase32(RandomNumberGenerator.GetBytes(20));

    public static long StepAt(DateTime utc) => new DateTimeOffset(utc, TimeSpan.Zero).ToUnixTimeSeconds() / StepSeconds;

    public static string Code(string base32Secret, long step)
    {
        var counter = new byte[8];
        for (var i = 7; i >= 0; i--) { counter[i] = (byte)(step & 0xff); step >>= 8; }
        using var hmac = new HMACSHA1(FromBase32(base32Secret));
        var hash = hmac.ComputeHash(counter);
        var offset = hash[^1] & 0x0f;
        var binary = ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
        return (binary % 1_000_000).ToString("D6");
    }

    /// <summary>The step the code belongs to, allowing one step of clock drift each way; null when it doesn't check out or was already used.</summary>
    public static long? Verify(string base32Secret, string? code, DateTime utcNow, long lastUsedStep)
    {
        var clean = (code ?? string.Empty).Replace(" ", string.Empty);
        if (clean.Length != 6 || !clean.All(char.IsAsciiDigit)) return null;
        var now = StepAt(utcNow);
        for (var step = now - 1; step <= now + 1; step++)
        {
            if (step <= lastUsedStep) continue;
            if (CryptographicOperations.FixedTimeEquals(System.Text.Encoding.ASCII.GetBytes(Code(base32Secret, step)), System.Text.Encoding.ASCII.GetBytes(clean))) return step;
        }
        return null;
    }

    public static string Uri(string base32Secret, string email, string issuer = "Heartpact Admin")
        => $"otpauth://totp/{System.Uri.EscapeDataString(issuer)}:{System.Uri.EscapeDataString(email)}?secret={base32Secret}&issuer={System.Uri.EscapeDataString(issuer)}&algorithm=SHA1&digits=6&period={StepSeconds}";

    public static string ToBase32(byte[] data)
    {
        var output = new System.Text.StringBuilder();
        int buffer = 0, bits = 0;
        foreach (var b in data)
        {
            buffer = (buffer << 8) | b; bits += 8;
            while (bits >= 5) { output.Append(Base32[(buffer >> (bits - 5)) & 31]); bits -= 5; }
        }
        if (bits > 0) output.Append(Base32[(buffer << (5 - bits)) & 31]);
        return output.ToString();
    }

    public static byte[] FromBase32(string text)
    {
        var clean = text.TrimEnd('=').ToUpperInvariant();
        var output = new List<byte>(clean.Length * 5 / 8);
        int buffer = 0, bits = 0;
        foreach (var c in clean)
        {
            var value = Base32.IndexOf(c);
            if (value < 0) throw new FormatException("Not base32.");
            buffer = (buffer << 5) | value; bits += 5;
            if (bits >= 8) { output.Add((byte)((buffer >> (bits - 8)) & 0xff)); bits -= 8; }
        }
        return output.ToArray();
    }
}

/// <summary>Bound from the "Admin" section.</summary>
public sealed class AdminSettings
{
    /// <summary>Accounts allowed into the admin dashboard (Admin:Emails, comma-separated). Empty: nobody.</summary>
    public IReadOnlyList<string> Emails { get; init; } = [];
    public TimeSpan TokenLifetime { get; init; } = TimeSpan.FromDays(7);
    /// <summary>Estimated monthly revenue per product id, in the currency below (Admin:MonthlyPrices:{productId}).</summary>
    public IReadOnlyDictionary<string, decimal> MonthlyPrices { get; init; } = new Dictionary<string, decimal>();
    public string Currency { get; init; } = "USD";
    /// <summary>Food photo estimate price per million tokens, in <see cref="Currency"/>; the cost estimate is null without both.</summary>
    public decimal? FoodScanPricePerMillionInput { get; init; }
    public decimal? FoodScanPricePerMillionOutput { get; init; }
    /// <summary>Album storage budget in GB (10^9 bytes); the storage alert fires at 80%. Null: no alert.</summary>
    public decimal? StorageBudgetGb { get; init; }

    public bool IsAdmin(string? email) => email is not null && Emails.Any(item => string.Equals(item, email.Trim(), StringComparison.OrdinalIgnoreCase));
}
