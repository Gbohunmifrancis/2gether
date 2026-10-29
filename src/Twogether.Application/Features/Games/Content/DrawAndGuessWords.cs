using System.Security.Cryptography;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games.Content;

/// <summary>
/// Words to draw in Draw and guess (docs/games.md). Global words are things anyone can sketch in a minute, written in
/// English with <c>Loc.N</c> so the drawer reads them in their own language. Naija words are English and Pidgin by
/// design and aren't translated.
/// </summary>
public static class DrawAndGuessWords
{
    public static readonly IReadOnlyList<string> Global =
    [
        Loc.N("cat"), Loc.N("dog"), Loc.N("fish"), Loc.N("bird"), Loc.N("rabbit"), Loc.N("elephant"), Loc.N("giraffe"),
        Loc.N("snake"), Loc.N("spider"), Loc.N("butterfly"), Loc.N("turtle"), Loc.N("lion"), Loc.N("duck"), Loc.N("horse"),
        Loc.N("cow"), Loc.N("pig"), Loc.N("owl"), Loc.N("bee"), Loc.N("snail"), Loc.N("octopus"), Loc.N("whale"),
        Loc.N("house"), Loc.N("tree"), Loc.N("flower"), Loc.N("sun"), Loc.N("moon"), Loc.N("star"), Loc.N("cloud"),
        Loc.N("rainbow"), Loc.N("mountain"), Loc.N("island"), Loc.N("volcano"), Loc.N("umbrella"), Loc.N("pizza"),
        Loc.N("guitar"), Loc.N("banana"), Loc.N("apple"), Loc.N("ice cream"), Loc.N("cake"), Loc.N("cup"), Loc.N("hat"),
        Loc.N("shoe"), Loc.N("glasses"), Loc.N("book"), Loc.N("key"), Loc.N("clock"), Loc.N("phone"), Loc.N("car"),
        Loc.N("bicycle"), Loc.N("boat"), Loc.N("airplane"), Loc.N("train"), Loc.N("rocket"), Loc.N("ball"), Loc.N("kite"),
        Loc.N("balloon"), Loc.N("candle"), Loc.N("lamp"), Loc.N("chair"), Loc.N("bed"), Loc.N("door"), Loc.N("window"),
        Loc.N("heart"), Loc.N("ring"), Loc.N("crown"), Loc.N("scissors"), Loc.N("pencil"), Loc.N("camera"),
        Loc.N("television"), Loc.N("bridge"), Loc.N("castle"), Loc.N("tent"), Loc.N("ladder"), Loc.N("bucket"),
        Loc.N("fork"), Loc.N("spoon"), Loc.N("egg"), Loc.N("carrot"), Loc.N("strawberry"), Loc.N("cherry"),
        Loc.N("mushroom"), Loc.N("snowman"), Loc.N("ghost"), Loc.N("robot"), Loc.N("dragon"), Loc.N("anchor"),
        Loc.N("lighthouse"), Loc.N("campfire"), Loc.N("toothbrush"), Loc.N("sock"), Loc.N("backpack"), Loc.N("envelope"),
        Loc.N("gift"), Loc.N("hamburger"), Loc.N("cookie"), Loc.N("watermelon"), Loc.N("drum"), Loc.N("piano"),
        Loc.N("bell"), Loc.N("flag"), Loc.N("snowflake"), Loc.N("sandwich"),
    ];

    public static readonly IReadOnlyList<string> Nigeria =
    [
        "jollof rice", "danfo", "okada", "keke", "agbada", "gele", "suya", "talking drum", "palm tree", "mango", "yam",
        "plantain", "generator", "Third Mainland Bridge", "puff-puff", "chin chin", "moi moi", "pounded yam", "egusi soup",
        "pepper soup", "akara", "zobo", "palm wine", "kola nut", "groundnut", "coconut", "pineapple", "cassava", "garri",
        "boli", "meat pie", "Zuma Rock", "Olumo Rock", "National Theatre", "aso ebi", "owambe", "Super Eagles", "ankara",
        "fila", "coral beads", "calabash", "mortar and pestle", "lantern", "molue", "BRT bus", "go-slow", "pure water",
        "Agege bread", "agama lizard", "goat", "canoe", "fishing net", "mama put", "shekere", "fufu", "okra soup",
        "ludo", "ayo", "chewing stick", "ceiling fan", "mosquito net", "wheelbarrow", "football", "bukka",
    ];

    public static IReadOnlyList<string> For(string region) => region == GameRegion.Nigeria ? Nigeria : Global;

    /// <summary><paramref name="count"/> different words from the region's bank, in random order.</summary>
    public static IReadOnlyList<string> Pick(string region, int count)
    {
        var words = For(region).ToArray();
        RandomNumberGenerator.Shuffle(words.AsSpan());
        return words.Take(count).ToList();
    }
}
