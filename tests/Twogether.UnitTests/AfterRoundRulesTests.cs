using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class AfterRoundRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid ada = Guid.NewGuid();
    private readonly Guid tunde = Guid.NewGuid();
    private readonly DateTime now = new(2026, 10, 24, 20, 0, 0, DateTimeKind.Utc);

    private GameEngineResult Start() => engine.Start(GameType.AfterRound, [ada, tunde], now, """{"region":"ng"}""");

    private GameEngineResult Move(GameEngineResult from, Guid actor, string action, int? index, DateTime? at = null)
        => engine.Apply(GameType.AfterRound, from.StateJson, actor, action, null, index, at ?? now);

    private GameEngineResult TimeOut(GameEngineResult from, DateTime? at = null)
        => engine.Apply(GameType.AfterRound, from.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, at ?? now);

    private JsonElement View(GameEngineResult result, Guid? viewer)
        => JsonDocument.Parse(engine.ToPublicState(GameType.AfterRound, result.StateJson, viewer)).RootElement;

    /// <summary>One whole round: both call, both lay.</summary>
    private GameEngineResult Round(GameEngineResult from, int adaCall, int tundeCall, int adaFingers, int tundeFingers)
    {
        var called = Move(Move(from, ada, "call", adaCall), tunde, "call", tundeCall);
        return Move(Move(called, ada, "lay", adaFingers), tunde, "lay", tundeFingers);
    }

    [Fact]
    public void Starts_calling_with_the_clock_running_and_either_may_move()
    {
        var started = Start();
        var view = View(started, ada);

        Assert.True(GameEngine.RulesFor(GameType.AfterRound) is AfterRoundRules);
        Assert.True(engine.AnyoneMayAct(GameType.AfterRound));
        Assert.True(engine.IsTimed(GameType.AfterRound));
        Assert.Equal(now.AddSeconds(AfterRoundRules.CallSeconds), started.DeadlineUtc);
        Assert.Equal("calling", view.GetProperty("phase").GetString());
        Assert.Equal(Enumerable.Range(0, 11), view.GetProperty("reachable").EnumerateArray().Select(n => n.GetInt32()));
    }

    [Fact]
    public void Each_number_is_called_once_a_round_and_calls_are_public()
    {
        var one = Move(Start(), tunde, "call", 6);

        Assert.Equal(ada, one.CurrentTurnUserId);
        Assert.Equal(6, View(one, ada).GetProperty("calls").GetProperty(tunde.ToString()).GetInt32());
        Assert.Throws<InvalidOperationException>(() => Move(one, ada, "call", 6));
        Assert.Throws<InvalidOperationException>(() => Move(one, tunde, "call", 3));
        Assert.Throws<InvalidOperationException>(() => Move(one, ada, "call", 11));
        Assert.Throws<InvalidOperationException>(() => Move(one, ada, "lay", 2));
        Assert.Throws<InvalidOperationException>(() => Move(one, Guid.NewGuid(), "call", 4));
    }

    [Fact]
    public void Fingers_are_secret_until_both_are_down_then_whoever_called_the_total_scores()
    {
        var called = Move(Move(Start(), ada, "call", 7), tunde, "call", 4);
        Assert.Equal(now.AddSeconds(AfterRoundRules.LaySeconds), called.DeadlineUtc);
        var one = Move(called, ada, "lay", 3);

        Assert.Equal(3, View(one, ada).GetProperty("myLay").GetInt32());
        Assert.Equal(JsonValueKind.Null, View(one, tunde).GetProperty("myLay").ValueKind);
        Assert.Equal(JsonValueKind.Null, View(one, tunde).GetProperty("last").ValueKind);

        var done = Move(one, tunde, "lay", 4);
        var last = View(done, tunde).GetProperty("last");
        Assert.Equal(7, last.GetProperty("total").GetInt32());
        Assert.Equal(ada, last.GetProperty("winner").GetGuid());
        Assert.Equal(1, View(done, ada).GetProperty("scores").GetProperty(ada.ToString()).GetInt32());
        Assert.Equal("calling", View(done, ada).GetProperty("phase").GetString());
    }

    [Fact]
    public void Nobody_lays_the_same_value_twice_running_and_the_board_shrinks_to_match()
    {
        var done = Round(Start(), 0, 1, 0, 0);
        var view = View(done, ada);

        // Both laid a fist, so neither can now: a total of 0 is out of reach.
        Assert.DoesNotContain(0, view.GetProperty("reachable").EnumerateArray().Select(n => n.GetInt32()));
        Assert.Throws<InvalidOperationException>(() => Move(done, ada, "call", 0));
        var called = Move(Move(done, ada, "call", 2), tunde, "call", 5);
        Assert.DoesNotContain(0, View(called, ada).GetProperty("myFingers").EnumerateArray().Select(n => n.GetInt32()));
        Assert.Throws<InvalidOperationException>(() => Move(called, ada, "lay", 0));
        Move(called, ada, "lay", 1);
    }

    [Fact]
    public void Timeouts_choose_a_legal_call_and_hand_and_two_idle_rounds_end_the_game()
    {
        var started = Start();
        var called = TimeOut(Move(started, ada, "call", 5));
        var calls = View(called, ada).GetProperty("calls");
        Assert.NotEqual(5, calls.GetProperty(tunde.ToString()).GetInt32());
        Assert.Equal("laying", View(called, ada).GetProperty("phase").GetString());

        var laid = TimeOut(called);
        Assert.Contains(tunde, View(laid, ada).GetProperty("last").GetProperty("auto").EnumerateArray().Select(id => id.GetGuid()));

        var idleOnce = TimeOut(TimeOut(laid));
        Assert.False(idleOnce.Completed);
        var idleTwice = TimeOut(TimeOut(idleOnce));
        Assert.True(idleTwice.Completed);
        Assert.Equal("idle", View(idleTwice, ada).GetProperty("ended").GetString());
    }

    [Fact]
    public void First_to_five_wins_then_slaps_in_a_duel()
    {
        var game = WonGame();
        var view = View(game, tunde);

        Assert.False(game.Completed);
        Assert.Equal("duel", view.GetProperty("phase").GetString());
        Assert.Equal(ada, view.GetProperty("winnerUserId").GetGuid());
        Assert.Equal(ada, view.GetProperty("duel").GetProperty("slapper").GetGuid());
        Assert.Equal(tunde, view.GetProperty("duel").GetProperty("defender").GetGuid());
        Assert.InRange(view.GetProperty("duel").GetProperty("zone").GetDouble(), 0.3, 0.7);
        Assert.Equal(now.AddSeconds(AfterRoundRules.DuelSeconds), game.DeadlineUtc);
    }

    [Fact]
    public void A_dead_centre_slap_against_a_frozen_defender_lands()
    {
        var duel = WonGame();
        var zone = View(duel, ada).GetProperty("duel").GetProperty("zone").GetDouble();
        var centre = (int)Math.Round(zone * AfterRoundRules.DuelPeriodMs);

        var pressed = Move(duel, ada, "press", centre);
        Assert.Equal(tunde, pressed.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Move(pressed, ada, "press", centre));
        var done = TimeOut(pressed);
        var result = View(done, tunde).GetProperty("duel").GetProperty("result");

        Assert.True(done.Completed);
        Assert.Equal("hit", result.GetProperty("outcome").GetString());
        Assert.Equal("perfect", result.GetProperty("slapper").GetProperty("rating").GetString());
        Assert.Equal("frozen", result.GetProperty("defender").GetProperty("rating").GetString());
    }

    [Fact]
    public void A_better_press_dodges_and_a_slapper_who_never_presses_still_slaps_weakly()
    {
        var duel = WonGame();
        var zone = View(duel, ada).GetProperty("duel").GetProperty("zone").GetDouble();
        var centre = (int)Math.Round(zone * AfterRoundRules.DuelPeriodMs);

        var dodged = Move(Move(duel, tunde, "press", centre), ada, "press", 0);
        Assert.Equal("dodged", View(dodged, ada).GetProperty("duel").GetProperty("result").GetProperty("outcome").GetString());

        var weak = TimeOut(duel);
        var result = View(weak, ada).GetProperty("duel").GetProperty("result");
        Assert.Equal("hit", result.GetProperty("outcome").GetString());
        Assert.Equal("weak", result.GetProperty("slapper").GetProperty("rating").GetString());
    }

    [Fact]
    public void The_gauge_sweeps_back_and_forth()
    {
        Assert.Equal(1, AfterRoundRules.Accuracy(550, 0.5), 3);
        Assert.Equal(1, AfterRoundRules.Accuracy(1650, 0.5), 3);
        Assert.Equal(0, AfterRoundRules.Accuracy(0, 0.5), 3);
        Assert.Equal(0, AfterRoundRules.Accuracy(1100, 0.5), 3);
    }

    [Fact]
    public void Only_nigerian_couples_are_offered_it()
    {
        Assert.True(GameRegion.Offers(GameType.AfterRound, GameRegion.Nigeria));
        Assert.False(GameRegion.Offers(GameType.AfterRound, GameRegion.Global));
        Assert.True(GameRegion.Offers(GameType.Trivia, GameRegion.Global));
    }

    /// <summary>Ada wins five rounds straight, alternating two and three fingers; Tunde alternates one and four.</summary>
    private GameEngineResult WonGame()
    {
        var game = Start();
        for (var round = 0; round < AfterRoundRules.WinningScore; round++)
        {
            var adaFingers = round % 2 == 0 ? 2 : 3;
            var tundeFingers = round % 2 == 0 ? 1 : 4;
            game = Round(game, adaFingers + tundeFingers, 10, adaFingers, tundeFingers);
        }
        Assert.Equal("duel", View(game, ada).GetProperty("phase").GetString());
        return game;
    }
}
