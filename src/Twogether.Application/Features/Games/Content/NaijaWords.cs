namespace Twogether.Application.Features.Games.Content;

/// <summary>
/// Everyday Nigerian words an English dictionary doesn't know: food, clothes, places, Pidgin. Word dash accepts them
/// for Nigerian couples (docs/games.md). Letters only, lower case. Kept warm and inclusive: no insults or slurs.
/// </summary>
public static class NaijaWords
{
    public static bool Contains(string word) => Words.Contains(word);

    private static readonly HashSet<string> Words = new(StringComparer.OrdinalIgnoreCase)
    {
        // Food and drink
        "jollof", "suya", "amala", "egusi", "akara", "ewedu", "gbegiri", "fufu", "eba", "garri", "gari", "moimoi",
        "dodo", "chinchin", "ogbono", "ofada", "kilishi", "zobo", "kunu", "efo", "edikaikong", "afang", "nkwobi",
        "isiewu", "ugwu", "tuwo", "masa", "kosai", "fura", "nono", "boli", "ofe", "okpa", "abacha", "ukwa",
        "gizdodo", "asun", "ayamase", "agoyin", "ewa", "agbalumo", "udara", "pupuru", "lafun", "semo", "iyan",
        "ikokore", "ekuru", "dundun", "banga", "oha", "onugbu", "miyan", "kuka", "dambu", "puffpuff",
        "shawarma", "ogi", "pap", "akamu", "ofeakwu", "peppersoup", "ponmo", "shaki", "kpomo", "abula",
        // Clothes
        "agbada", "gele", "buba", "iro", "aso", "ebi", "asoebi", "fila", "danshiki", "babanriga", "isiagu", "ankara",
        "adire", "aran", "kaftan", "wrapper",
        // Getting about and places
        "danfo", "okada", "keke", "molue", "naija", "buka", "mama", "bukateria", "owambe", "naira", "kobo",
        // Pidgin and everyday talk
        "wahala", "abeg", "oga", "madam", "wetin", "jare", "sha", "sef", "oya", "chai", "ehen", "abi", "omo",
        "pikin", "padi", "sabi", "chop", "waka", "japa", "ajebo", "ajebutter", "gbese", "shayo", "gist", "yawa",
        "ginger", "gbam", "kpatakpata", "kuku", "jor", "biko", "nna", "nwanne", "kai", "haba", "ehn", "una",
        "dey", "wey", "don", "dem", "na", "ehya", "ashia", "ndo", "orobo", "gbosa", "awoof", "katakata",
        "ojoro", "kampe", "yanga", "ofofo", "amebo", "jaiye", "faaji", "enjoyment", "jollification",
    };
}
