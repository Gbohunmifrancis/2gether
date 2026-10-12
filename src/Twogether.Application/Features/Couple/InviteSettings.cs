using System.Security.Cryptography;

namespace Twogether.Application.Features.Couple;

/// <summary>Bound from the "Invites" section.</summary>
public sealed class InviteSettings
{
    public const int MinCodeDigits = 6;
    public const int MaxCodeDigits = 10;

    /// <summary>
    /// Six fits the current web screen. Each extra digit makes guessing ten times harder; move
    /// to eight once the app's code field accepts it.
    /// </summary>
    public int CodeDigits { get; init; } = MinCodeDigits;

    /// <summary>Partners usually accept within minutes. Fewer live codes means fewer to guess.</summary>
    public TimeSpan Lifetime { get; init; } = TimeSpan.FromHours(48);
}

public static class InviteCodes
{
    /// <summary>Uniformly random digits, leading zeros allowed.</summary>
    public static string Generate(int digits)
    {
        if (digits is < InviteSettings.MinCodeDigits or > InviteSettings.MaxCodeDigits) throw new ArgumentOutOfRangeException(nameof(digits));
        return string.Create(digits, 0, (span, _) =>
        {
            for (var i = 0; i < span.Length; i++) span[i] = (char)('0' + RandomNumberGenerator.GetInt32(10));
        });
    }

    /// <summary>What people type: spaces and dashes are ignored ("123 456", "1234-5678").</summary>
    public static string? Normalize(string? typed)
    {
        if (string.IsNullOrWhiteSpace(typed)) return null;
        var digits = new string(typed.Where(ch => ch is not (' ' or '-')).ToArray());
        return digits.Length is >= InviteSettings.MinCodeDigits and <= InviteSettings.MaxCodeDigits && digits.All(char.IsAsciiDigit) ? digits : null;
    }
}
