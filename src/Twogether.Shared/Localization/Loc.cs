using System.Globalization;
using System.Reflection;
using System.Text.Json;

namespace Twogether.Shared.Localization;

/// <summary>
/// Server text in the reader's language (docs/translation.md). English is written in the code and is also the key:
/// <c>Loc.T("Choose your country from the list.")</c>. Each other language is one embedded file,
/// <c>Localization/i18n/&lt;language&gt;.json</c>, mapping the English to the translation; anything missing falls back to
/// English. The language is the current UI culture: the request's (Accept-Language) or, for text written for someone
/// else such as a partner's notification, the one set with <see cref="Use"/>.
/// </summary>
public static class Loc
{
    public static readonly IReadOnlyList<string> Languages = ["en", "fr", "pt", "es", "ar", "hi"];

    private static readonly Dictionary<string, Catalog> Catalogs = Languages.Skip(1).ToDictionary(language => language, Load);

    /// <summary>The current language, one of <see cref="Languages"/>.</summary>
    public static string Language => Normalize(CultureInfo.CurrentUICulture.TwoLetterISOLanguageName) ?? "en";

    /// <summary>A supported language code for <paramref name="language"/> ("pt-BR" gives "pt"), or null.</summary>
    public static string? Normalize(string? language)
    {
        var code = language?.Trim().Split('-', '_')[0].ToLowerInvariant();
        return code is not null && Languages.Contains(code) ? code : null;
    }

    /// <summary>Writes in <paramref name="language"/> until disposed: <c>using (Loc.Use(partner.Language)) { ... }</c>.</summary>
    public static IDisposable Use(string? language)
    {
        var previous = CultureInfo.CurrentUICulture;
        CultureInfo.CurrentUICulture = CultureInfo.GetCultureInfo(Normalize(language) ?? "en");
        return new Restore(previous);
    }

    /// <summary>Translates <paramref name="english"/> (one plain string literal), then fills <c>{name}</c> placeholders.</summary>
    public static string T(string english, params (string Name, object? Value)[] args)
        => Fill(Catalogs.TryGetValue(Language, out var catalog) && catalog.Words.TryGetValue(english, out var text) ? text : english, args);

    /// <summary>A count in words: <c>Loc.Plural(n, "{count} day", "{count} days")</c>. <c>{count}</c> is filled in.</summary>
    public static string Plural(long count, string one, string other, params (string Name, object? Value)[] args)
    {
        var language = Language;
        var text = Catalogs.TryGetValue(language, out var catalog) && catalog.Plurals.TryGetValue(one + "|" + other, out var forms)
            ? forms.GetValueOrDefault(Category(language, count)) ?? forms.GetValueOrDefault("other") ?? other
            : count == 1 ? one : other;
        return Fill(text, [("count", count), .. args]);
    }

    /// <summary>
    /// Marks text that is translated later, where it is shown (a question in the bank, an exercise name): returns the
    /// English unchanged, but the catalog check finds it.
    /// </summary>
    public static string N(string english) => english;

    /// <summary>CLDR plural categories for whole numbers, for the languages we ship (the same rules as the app).</summary>
    public static string Category(string language, long count)
    {
        var n = Math.Abs(count);
        return language switch
        {
            "fr" or "pt" or "hi" => n <= 1 ? "one" : "other",
            "ar" => n switch
            {
                0 => "zero",
                1 => "one",
                2 => "two",
                _ when n % 100 is >= 3 and <= 10 => "few",
                _ when n % 100 is >= 11 and <= 99 => "many",
                _ => "other"
            },
            _ => n == 1 ? "one" : "other"
        };
    }

    private static string Fill(string text, (string Name, object? Value)[] args)
    {
        if (args.Length == 0 || !text.Contains('{')) return text;
        foreach (var (name, value) in args)
            text = text.Replace("{" + name + "}", Convert.ToString(value, CultureInfo.CurrentCulture) ?? string.Empty);
        return text;
    }

    private static Catalog Load(string language)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream($"Twogether.Shared.Localization.i18n.{language}.json");
        if (stream is null) return new Catalog(new Dictionary<string, string>(), new Dictionary<string, Dictionary<string, string>>());
        using var document = JsonDocument.Parse(stream);
        var words = new Dictionary<string, string>(StringComparer.Ordinal);
        var plurals = new Dictionary<string, Dictionary<string, string>>(StringComparer.Ordinal);
        foreach (var property in document.RootElement.EnumerateObject())
        {
            // "@..." keys are notes for translators.
            if (property.Name.StartsWith('@')) continue;
            if (property.Value.ValueKind == JsonValueKind.String && property.Value.GetString() is { Length: > 0 } word) words[property.Name] = word;
            else if (property.Value.ValueKind == JsonValueKind.Object)
                plurals[property.Name] = property.Value.EnumerateObject().ToDictionary(form => form.Name, form => form.Value.GetString() ?? string.Empty);
        }
        return new Catalog(words, plurals);
    }

    private sealed record Catalog(Dictionary<string, string> Words, Dictionary<string, Dictionary<string, string>> Plurals);

    private sealed class Restore(CultureInfo previous) : IDisposable
    {
        public void Dispose() => CultureInfo.CurrentUICulture = previous;
    }
}
