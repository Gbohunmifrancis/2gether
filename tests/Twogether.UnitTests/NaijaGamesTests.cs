using System.Text.Json;
using System.Text.Json.Nodes;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

/// <summary>The Nigerian versions of I call on and memory match (docs/games.md): same rules, Naija content.</summary>
public sealed class NaijaGamesTests
{
    private static readonly DateTime Now = new(2026, 10, 24, 18, 0, 0, DateTimeKind.Utc);
    private readonly GameEngine engine = new();
    private readonly Guid ada = Guid.NewGuid();
    private readonly Guid tunde = Guid.NewGuid();

    private Guid[] Players => [ada, tunde];

    private JsonElement View(GameType type, string stateJson)
    {
        using var document = JsonDocument.Parse(engine.ToPublicState(type, stateJson));
        return document.RootElement.Clone();
    }

    private static string Answers(string name, string place, string third, string food, string thirdCategory)
        => JsonSerializer.Serialize(new Dictionary<string, string> { ["Name"] = name, ["Place"] = place, [thirdCategory] = third, ["Food"] = food });

    [Fact]
    public void Nigerian_i_call_on_plays_name_place_animal_and_food()
    {
        var naija = View(GameType.ICallOn, engine.Start(GameType.ICallOn, Players, Now, """{"region":"ng"}""").StateJson);
        Assert.Equal(["Name", "Place", "Animal", "Food"], naija.GetProperty("categories").EnumerateArray().Select(item => item.GetString()));

        var global = View(GameType.ICallOn, engine.Start(GameType.ICallOn, Players, Now, """{"region":"global"}""").StateJson);
        Assert.Equal(["Name", "Place", "Thing", "Food"], global.GetProperty("categories").EnumerateArray().Select(item => item.GetString()));
    }

    [Fact]
    public void Every_letter_a_nigerian_round_draws_has_a_word_in_every_category()
    {
        var playable = GameEngine.PlayableLetters(GameRegion.Nigeria);
        Assert.True(playable.Count >= 20, string.Join("", playable));
        Assert.DoesNotContain("X", playable);
        // The Animal list is Nigerian only, so every playable letter must have an animal of its own.
        Assert.All(playable, letter => Assert.Contains(NaijaICallOnWords.Animals, animal => animal.StartsWith(letter, StringComparison.OrdinalIgnoreCase)));

        for (var i = 0; i < 200; i++)
        {
            var letter = View(GameType.ICallOn, engine.Start(GameType.ICallOn, Players, Now, """{"region":"ng"}""").StateJson).GetProperty("letter").GetString();
            Assert.Contains(letter, playable);
        }
    }

    [Fact]
    public void Nigerian_words_count_offline_without_the_partner_judging()
    {
        var state = engine.Start(GameType.ICallOn, Players, Now, """{"region":"ng","letter":"A"}""").StateJson;
        state = engine.Apply(GameType.ICallOn, state, ada, "submit", Answers("Adaeze", "Abeokuta", "Antelope", "Amala", "Animal"), null, Now).StateJson;
        var scored = engine.Apply(GameType.ICallOn, state, tunde, "submit", Answers("Babatunde", "Aba", "Aardvark", "Akara", "Animal"), null, Now);

        var view = View(GameType.ICallOn, scored.StateJson);
        Assert.Empty(view.GetProperty("pendingVetting").EnumerateArray());
        var entries = view.GetProperty("reveal").GetProperty("entries").EnumerateArray().ToList();
        Assert.All(entries.Where(entry => entry.GetProperty("player").GetString() == ada.ToString()), entry => Assert.True(entry.GetProperty("counted").GetBoolean()));
        // Wrong letter: Babatunde doesn't start with A.
        Assert.Contains(entries, entry => entry.GetProperty("answer").GetString() == "Babatunde" && !entry.GetProperty("counted").GetBoolean());
        Assert.Equal(8, view.GetProperty("scores").GetProperty(ada.ToString()).GetInt32());
        // Later rounds draw from the Nigerian letters too.
        Assert.Contains(view.GetProperty("letter").GetString(), GameEngine.PlayableLetters(GameRegion.Nigeria));
    }

    [Fact]
    public void A_game_set_up_before_regions_keeps_the_global_lists()
    {
        var state = JsonNode.Parse(engine.Start(GameType.ICallOn, Players, Now, """{"letter":"A"}""").StateJson)!.AsObject();
        state.Remove("Region");
        var old = state.ToJsonString();
        Assert.Equal(["Name", "Place", "Thing", "Food"], View(GameType.ICallOn, old).GetProperty("categories").EnumerateArray().Select(item => item.GetString()));

        var next = engine.Apply(GameType.ICallOn, old, ada, "submit", Answers("Adaeze", "Abuja", "Anchor", "Apple", "Thing"), null, Now).StateJson;
        next = engine.Apply(GameType.ICallOn, next, tunde, "submit", Answers("Anna", "Accra", "Anchor", "Avocado", "Thing"), null, Now).StateJson;
        // Adaeze isn't in the global names, so Tunde judges it.
        var pending = Assert.Single(View(GameType.ICallOn, next).GetProperty("pendingVetting").EnumerateArray());
        Assert.Equal("Adaeze", pending.GetProperty("answer").GetString());
    }

    [Fact]
    public void Nigerian_memory_match_carries_the_naija_theme()
    {
        var naija = View(GameType.MemoryMatch, engine.Start(GameType.MemoryMatch, Players, Now, """{"region":"ng"}""").StateJson);
        Assert.Equal("naija", naija.GetProperty("theme").GetString());
        Assert.Equal(12, naija.GetProperty("cards").GetArrayLength());

        var global = View(GameType.MemoryMatch, engine.Start(GameType.MemoryMatch, Players, Now, """{"region":"global"}""").StateJson);
        Assert.Equal(JsonValueKind.Null, global.GetProperty("theme").ValueKind);
        var old = View(GameType.MemoryMatch, engine.Start(GameType.MemoryMatch, Players, Now).StateJson);
        Assert.Equal(JsonValueKind.Null, old.GetProperty("theme").ValueKind);
    }
}
