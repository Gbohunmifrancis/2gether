using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Application.Features.Games.Content;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class WouldYouRatherRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime now = DateTime.UtcNow;

    private GameEngineResult Start(string region = GameRegion.Global)
        => engine.Start(GameType.WouldYouRather, [maya, theo], now, WouldYouRatherRules.CreateSetup(region));

    private GameEngineResult Pick(GameEngineResult from, Guid actor, int? index)
        => engine.Apply(GameType.WouldYouRather, from.StateJson, actor, "pick", null, index, now);

    private JsonElement View(GameEngineResult result, Guid? viewer)
        => JsonDocument.Parse(engine.ToPublicState(GameType.WouldYouRather, result.StateJson, viewer)).RootElement;

    [Fact]
    public void Starts_with_ten_questions_both_may_pick_and_there_is_no_clock()
    {
        var started = Start();
        var view = View(started, maya);

        Assert.True(GameEngine.RulesFor(GameType.WouldYouRather) is WouldYouRatherRules);
        Assert.True(engine.AnyoneMayAct(GameType.WouldYouRather));
        Assert.False(engine.IsTimed(GameType.WouldYouRather));
        Assert.Null(started.DeadlineUtc);
        Assert.Equal(10, view.GetProperty("totalRounds").GetInt32());
        Assert.Equal(0, view.GetProperty("round").GetInt32());
        Assert.False(string.IsNullOrWhiteSpace(view.GetProperty("a").GetString()));
        Assert.False(string.IsNullOrWhiteSpace(view.GetProperty("b").GetString()));
        Assert.Equal(0, view.GetProperty("picked").GetArrayLength());
    }

    [Fact]
    public void Either_partner_may_pick_first_but_only_once_a_round_and_only_a_or_b()
    {
        var started = Start();
        var theoFirst = Pick(started, theo, 1);
        Assert.Equal(maya, theoFirst.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Pick(theoFirst, theo, 0));
        Assert.Throws<InvalidOperationException>(() => Pick(started, maya, 2));
        Assert.Throws<InvalidOperationException>(() => Pick(started, maya, null));
        Assert.Throws<InvalidOperationException>(() => Pick(started, Guid.NewGuid(), 0));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.WouldYouRather, started.StateJson, maya, "answer", "A", 0, now));
    }

    [Fact]
    public void A_pick_is_secret_until_both_are_in()
    {
        var one = Pick(Start(), maya, 1);

        Assert.Equal(1, View(one, maya).GetProperty("myPick").GetInt32());
        Assert.Equal(JsonValueKind.Null, View(one, theo).GetProperty("myPick").ValueKind);
        Assert.Equal(JsonValueKind.Null, View(one, null).GetProperty("myPick").ValueKind);
        Assert.Equal(maya, View(one, theo).GetProperty("picked")[0].GetGuid());
        Assert.Equal(JsonValueKind.Null, View(one, theo).GetProperty("last").ValueKind);

        var both = Pick(one, theo, 0);
        var last = View(both, theo).GetProperty("last");
        Assert.Equal(1, last.GetProperty("picks").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(0, last.GetProperty("picks").GetProperty(theo.ToString()).GetInt32());
        Assert.False(last.GetProperty("match").GetBoolean());
        Assert.Equal(0, View(both, theo).GetProperty("picked").GetArrayLength());
    }

    [Fact]
    public void Matching_picks_give_both_a_point()
    {
        var matched = Pick(Pick(Start(), maya, 0), theo, 0);
        var view = View(matched, maya);
        Assert.True(view.GetProperty("last").GetProperty("match").GetBoolean());
        Assert.Equal(1, view.GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
        Assert.Equal(1, view.GetProperty("scores").GetProperty(theo.ToString()).GetInt32());

        var missed = Pick(Pick(matched, maya, 0), theo, 1);
        Assert.Equal(1, View(missed, maya).GetProperty("scores").GetProperty(maya.ToString()).GetInt32());
    }

    [Fact]
    public void A_full_game_ends_after_ten_rounds_with_the_scores()
    {
        var state = Start();
        for (var round = 0; round < 10; round++)
        {
            Assert.False(state.Completed);
            state = Pick(state, maya, 0);
            state = Pick(state, theo, round < 7 ? 0 : 1);
        }

        Assert.True(state.Completed);
        Assert.Null(state.CurrentTurnUserId);
        var summary = engine.Summarise(GameType.WouldYouRather, state.StateJson);
        Assert.Equal(10, summary.RoundsPlayed);
        Assert.Equal(7, summary.Scores[maya]);
        Assert.Equal(7, summary.Scores[theo]);
        Assert.Throws<InvalidOperationException>(() => Pick(state, maya, 0));
    }

    [Fact]
    public void Nigerian_couples_get_naija_questions_and_others_get_global_ones()
    {
        Assert.True(WouldYouRatherBank.Global.Count >= 50);
        Assert.True(WouldYouRatherBank.Nigerian.Count >= 40);
        var naija = WouldYouRatherBank.Nigerian.Select(pair => pair.A).ToHashSet();
        var global = WouldYouRatherBank.Global.Select(pair => pair.A).ToHashSet();
        for (var i = 0; i < 5; i++)
        {
            Assert.Contains(View(Start(GameRegion.Nigeria), maya).GetProperty("a").GetString()!, naija);
            Assert.Contains(View(Start(GameRegion.Global), maya).GetProperty("a").GetString()!, global);
        }
        using var setup = JsonDocument.Parse(WouldYouRatherRules.CreateSetup(GameRegion.Nigeria));
        Assert.Equal(10, setup.RootElement.GetProperty("pairs").EnumerateArray().Select(pair => pair.GetProperty("a").GetString()).Distinct().Count());
        Assert.Equal("ng", setup.RootElement.GetProperty("region").GetString());
    }
}
