using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class GameFixesTests
{
    private readonly GameEngine engine = new();

    [Fact]
    public void Turn_by_turn_games_have_no_deadline_but_i_call_on_does()
    {
        var players = new[] { Guid.NewGuid(), Guid.NewGuid() };
        var now = DateTime.UtcNow;
        Assert.Null(engine.Start(GameType.CoupleQuiz, players, now).DeadlineUtc);
        Assert.Null(engine.Start(GameType.MemoryMatch, players, now).DeadlineUtc);
        Assert.NotNull(engine.Start(GameType.ICallOn, players, now).DeadlineUtc);

        var quiz = engine.Start(GameType.CoupleQuiz, players, now);
        Assert.Null(engine.Apply(GameType.CoupleQuiz, quiz.StateJson, players[0], "answer", "yes", null, now).DeadlineUtc);
    }

    [Fact]
    public void I_call_on_starts_with_the_chosen_letter()
    {
        var players = new[] { Guid.NewGuid(), Guid.NewGuid() };
        var started = engine.Start(GameType.ICallOn, players, DateTime.UtcNow, """{"letter":"q"}""");
        using var state = JsonDocument.Parse(engine.ToPublicState(GameType.ICallOn, started.StateJson));
        Assert.Equal("Q", state.RootElement.GetProperty("letter").GetString());

        var random = engine.Start(GameType.ICallOn, players, DateTime.UtcNow, """{"letter":null}""");
        using var randomState = JsonDocument.Parse(engine.ToPublicState(GameType.ICallOn, random.StateJson));
        Assert.Matches("^[A-Z]$", randomState.RootElement.GetProperty("letter").GetString());
    }

    [Fact]
    public void A_memory_miss_leaves_both_cards_face_up_until_the_next_flip()
    {
        var players = new[] { Guid.NewGuid(), Guid.NewGuid() };
        var now = DateTime.UtcNow;
        var state = engine.Start(GameType.MemoryMatch, players, now).StateJson;

        // Find two cards that differ by flipping the first and peeking at each other card's value as it flips.
        var first = engine.Apply(GameType.MemoryMatch, state, players[0], "flip", null, 0, now);
        int firstValue;
        using (var s = JsonDocument.Parse(engine.ToPublicState(GameType.MemoryMatch, first.StateJson)))
            firstValue = s.RootElement.GetProperty("cards")[0].GetProperty("value").GetInt32();
        var miss = Enumerable.Range(1, 11)
            .Select(i => (i, result: engine.Apply(GameType.MemoryMatch, first.StateJson, players[0], "flip", null, i, now)))
            .First(t => engine.ToPublicState(GameType.MemoryMatch, t.result.StateJson).Contains("\"different\"", StringComparison.Ordinal));

        using var shown = JsonDocument.Parse(engine.ToPublicState(GameType.MemoryMatch, miss.result.StateJson));
        var pair = shown.RootElement.GetProperty("lastPair");
        Assert.Equal(2, pair.GetArrayLength());
        Assert.Equal((0, firstValue), (pair[0].GetProperty("index").GetInt32(), pair[0].GetProperty("value").GetInt32()));
        Assert.Equal(miss.i, pair[1].GetProperty("index").GetInt32());
        Assert.NotEqual(firstValue, pair[1].GetProperty("value").GetInt32());
        Assert.Equal(players[1], miss.result.CurrentTurnUserId);

        var next = engine.Apply(GameType.MemoryMatch, miss.result.StateJson, players[1], "flip", null, 0, now);
        using var after = JsonDocument.Parse(engine.ToPublicState(GameType.MemoryMatch, next.StateJson));
        Assert.Equal(JsonValueKind.Null, after.RootElement.GetProperty("lastPair").ValueKind);
    }

    [Fact]
    public void A_memory_match_names_the_pair_so_both_phones_can_celebrate_it()
    {
        var players = new[] { Guid.NewGuid(), Guid.NewGuid() };
        var now = DateTime.UtcNow;
        var state = engine.Start(GameType.MemoryMatch, players, now).StateJson;
        int[] values;
        using (var raw = JsonDocument.Parse(state)) values = raw.RootElement.GetProperty("Cards").EnumerateArray().Select(card => card.GetProperty("Value").GetInt32()).ToArray();
        var twin = Array.IndexOf(values, values[0], 1);

        var first = engine.Apply(GameType.MemoryMatch, state, players[0], "flip", null, 0, now);
        var match = engine.Apply(GameType.MemoryMatch, first.StateJson, players[0], "flip", null, twin, now);

        using var shown = JsonDocument.Parse(engine.ToPublicState(GameType.MemoryMatch, match.StateJson));
        Assert.Equal("match", shown.RootElement.GetProperty("lastResult").GetString());
        var pair = shown.RootElement.GetProperty("lastPair").EnumerateArray().Select(card => card.GetProperty("index").GetInt32()).ToList();
        Assert.Equal([0, twin], pair);
        // A pair found plays again.
        Assert.Equal(players[0], match.CurrentTurnUserId);
    }
}
