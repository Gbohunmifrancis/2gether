namespace Twogether.Core.Users;

/// <summary>
/// Countries (ISO 3166-1 alpha-2) and the content region each belongs to, so the app shows people someone they can
/// see themselves in (docs/regional-images.md). Kept in step with the time-zone lists in website/assets/boot.js.
/// </summary>
public static class ContentRegions
{
    public const string Default = "default";
    public const string Africa = "africa";
    public const string Mena = "mena";
    public const string SouthAsia = "southasia";
    public const string EastAsia = "eastasia";
    public const string LatinAmerica = "latam";

    /// <summary>Every officially assigned ISO 3166-1 alpha-2 code.</summary>
    public static readonly IReadOnlySet<string> Countries = new HashSet<string>(StringComparer.Ordinal)
    {
        "AD", "AE", "AF", "AG", "AI", "AL", "AM", "AO", "AQ", "AR", "AS", "AT", "AU", "AW", "AX", "AZ",
        "BA", "BB", "BD", "BE", "BF", "BG", "BH", "BI", "BJ", "BL", "BM", "BN", "BO", "BQ", "BR", "BS", "BT", "BV", "BW", "BY", "BZ",
        "CA", "CC", "CD", "CF", "CG", "CH", "CI", "CK", "CL", "CM", "CN", "CO", "CR", "CU", "CV", "CW", "CX", "CY", "CZ",
        "DE", "DJ", "DK", "DM", "DO", "DZ",
        "EC", "EE", "EG", "EH", "ER", "ES", "ET",
        "FI", "FJ", "FK", "FM", "FO", "FR",
        "GA", "GB", "GD", "GE", "GF", "GG", "GH", "GI", "GL", "GM", "GN", "GP", "GQ", "GR", "GS", "GT", "GU", "GW", "GY",
        "HK", "HM", "HN", "HR", "HT", "HU",
        "ID", "IE", "IL", "IM", "IN", "IO", "IQ", "IR", "IS", "IT",
        "JE", "JM", "JO", "JP",
        "KE", "KG", "KH", "KI", "KM", "KN", "KP", "KR", "KW", "KY", "KZ",
        "LA", "LB", "LC", "LI", "LK", "LR", "LS", "LT", "LU", "LV", "LY",
        "MA", "MC", "MD", "ME", "MF", "MG", "MH", "MK", "ML", "MM", "MN", "MO", "MP", "MQ", "MR", "MS", "MT", "MU", "MV", "MW", "MX", "MY", "MZ",
        "NA", "NC", "NE", "NF", "NG", "NI", "NL", "NO", "NP", "NR", "NU", "NZ",
        "OM",
        "PA", "PE", "PF", "PG", "PH", "PK", "PL", "PM", "PN", "PR", "PS", "PT", "PW", "PY",
        "QA",
        "RE", "RO", "RS", "RU", "RW",
        "SA", "SB", "SC", "SD", "SE", "SG", "SH", "SI", "SJ", "SK", "SL", "SM", "SN", "SO", "SR", "SS", "ST", "SV", "SX", "SY", "SZ",
        "TC", "TD", "TF", "TG", "TH", "TJ", "TK", "TL", "TM", "TN", "TO", "TR", "TT", "TV", "TW", "TZ",
        "UA", "UG", "UM", "US", "UY", "UZ",
        "VA", "VC", "VE", "VG", "VI", "VN", "VU",
        "WF", "WS",
        "YE", "YT",
        "ZA", "ZM", "ZW"
    };

    private static readonly Dictionary<string, string> RegionByCountry = Build(
        (Mena, ["DZ", "BH", "EG", "EH", "IL", "IQ", "IR", "JO", "KW", "LB", "LY", "MA", "OM", "PS", "QA", "SA", "SD", "SY", "TN", "AE", "YE"]),
        (Africa, ["AO", "BF", "BI", "BJ", "BW", "CD", "CF", "CG", "CI", "CM", "CV", "DJ", "ER", "ET", "GA", "GH", "GM", "GN", "GQ", "GW",
            "KE", "KM", "LR", "LS", "MG", "ML", "MR", "MU", "MW", "MZ", "NA", "NE", "NG", "RE", "RW", "SC", "SH", "SL", "SN", "SO", "SS",
            "ST", "SZ", "TD", "TG", "TZ", "UG", "YT", "ZA", "ZM", "ZW"]),
        (SouthAsia, ["AF", "BD", "BT", "IN", "LK", "MV", "NP", "PK"]),
        (EastAsia, ["BN", "CN", "HK", "ID", "JP", "KH", "KP", "KR", "LA", "MM", "MN", "MO", "MY", "PH", "SG", "TH", "TL", "TW", "VN"]),
        (LatinAmerica, ["AR", "BO", "BR", "BZ", "CL", "CO", "CR", "CU", "DO", "EC", "GF", "GT", "GY", "HN", "HT", "MX", "NI", "PA", "PE",
            "PR", "PY", "SR", "SV", "UY", "VE"]));

    /// <summary>The upper-case code when <paramref name="code"/> is an assigned country, otherwise null.</summary>
    public static string? Normalize(string? code)
    {
        var upper = code?.Trim().ToUpperInvariant();
        return upper is { Length: 2 } && Countries.Contains(upper) ? upper : null;
    }

    /// <summary>The region for a country code; <see cref="Default"/> when it is missing or not mapped.</summary>
    public static string For(string? countryCode)
        => Normalize(countryCode) is { } code && RegionByCountry.TryGetValue(code, out var region) ? region : Default;

    private static Dictionary<string, string> Build(params (string Region, string[] Codes)[] groups)
    {
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var (region, codes) in groups)
            foreach (var code in codes)
                map.Add(code, region);
        return map;
    }
}
