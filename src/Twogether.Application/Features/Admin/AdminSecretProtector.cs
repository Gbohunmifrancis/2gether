using System.Security.Cryptography;
using System.Text;

namespace Twogether.Application.Features.Admin;

/// <summary>What reading a stored authenticator secret gave.</summary>
/// <param name="Secret">The base32 secret, or null when no configured key opens it.</param>
/// <param name="NeedsRewrite">Stored as plain text or under an older key: store it again under the current key.</param>
public readonly record struct UnprotectedSecret(string? Secret, bool NeedsRewrite);

/// <summary>
/// Encrypts admins' authenticator (TOTP) secrets at rest with AES-256-GCM, so a copy of the database
/// alone doesn't hand over the second factor. The key lives in the host environment: Admin:SecretKey,
/// or one derived from Jwt:SigningKey when that isn't set. Older keys (Admin:PreviousSecretKey, then the
/// JWT-derived one) still open existing secrets, which are then rewritten under the current key.
/// Stored form: "v1." + base64url(nonce ‖ ciphertext ‖ tag). Base32 has no dots, so plain text from
/// before encryption is told apart and accepted once.
/// </summary>
public sealed class AdminSecretProtector
{
    private const string Prefix = "v1.";
    private const int NonceSize = 12;
    private const int TagSize = 16;
    private static readonly byte[] Info = Encoding.ASCII.GetBytes("heartpact admin totp v1");
    private readonly byte[][] keys;

    /// <param name="keyMaterial">Current key first, then older ones to read with; blanks are skipped.</param>
    public AdminSecretProtector(params string?[] keyMaterial)
    {
        keys = keyMaterial.Where(item => !string.IsNullOrWhiteSpace(item)).Distinct(StringComparer.Ordinal)
            .Select(item => HKDF.DeriveKey(HashAlgorithmName.SHA256, Encoding.UTF8.GetBytes(item!), 32, salt: [], info: Info))
            .ToArray();
        if (keys.Length == 0) throw new ArgumentException("An admin secret key is required (Admin:SecretKey or Jwt:SigningKey).", nameof(keyMaterial));
    }

    public static bool IsProtected(string stored) => stored.StartsWith(Prefix, StringComparison.Ordinal);

    public string Protect(string secret)
    {
        var plain = Encoding.UTF8.GetBytes(secret);
        var output = new byte[NonceSize + plain.Length + TagSize];
        var nonce = output.AsSpan(0, NonceSize);
        RandomNumberGenerator.Fill(nonce);
        using var aes = new AesGcm(keys[0], TagSize);
        aes.Encrypt(nonce, plain, output.AsSpan(NonceSize, plain.Length), output.AsSpan(NonceSize + plain.Length, TagSize));
        return Prefix + Base64Url(output);
    }

    public UnprotectedSecret Unprotect(string stored)
    {
        if (!IsProtected(stored)) return new UnprotectedSecret(stored, NeedsRewrite: true);
        byte[] data;
        try { data = FromBase64Url(stored[Prefix.Length..]); }
        catch (FormatException) { return new UnprotectedSecret(null, false); }
        if (data.Length < NonceSize + TagSize) return new UnprotectedSecret(null, false);
        var length = data.Length - NonceSize - TagSize;
        var plain = new byte[length];
        for (var index = 0; index < keys.Length; index++)
        {
            try
            {
                using var aes = new AesGcm(keys[index], TagSize);
                aes.Decrypt(data.AsSpan(0, NonceSize), data.AsSpan(NonceSize, length), data.AsSpan(NonceSize + length, TagSize), plain);
                return new UnprotectedSecret(Encoding.UTF8.GetString(plain), NeedsRewrite: index > 0);
            }
            catch (AuthenticationTagMismatchException) { }
        }
        return new UnprotectedSecret(null, false);
    }

    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromBase64Url(string text)
    {
        var padded = text.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(padded + new string('=', (4 - padded.Length % 4) % 4));
    }
}
