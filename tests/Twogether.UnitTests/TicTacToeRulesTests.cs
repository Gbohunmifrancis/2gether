using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

/// <summary>Tic-tac-toe played at once: no turns, first to a square gets it, a pen that needs to refill (docs/games.md).</summary>
public sealed class TicTacToeRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid[] players = [Guid.NewGuid(), Guid.NewGuid()];
    private static readonly DateTime T0 = new(2026, 10, 31, 18, 0, 0, DateTimeKind.Utc);
    /// <summary>The server's clock: every move moves it on by a full cooldown unless a test says otherwise.</summary>
    private DateTime now = T0;

    private GameEngineResult Start() => engine.Start(GameType.TicTacToe, players, now, """{"region":"global"}""");

    private GameEngineResult Act(GameEngineResult from, int player, string action, int? cell, string? value = null)
        => engine.Apply(GameType.TicTacToe, from.StateJson, players[player], action, value, cell, now);

    /// <summary>A mark by <paramref name="player"/>, made after their pen has refilled.</summary>
    private GameEngineResult Place(GameEngineResult from, int player, int cell, string? value = null)
    {
        now = now.AddMilliseconds(TicTacToeRules.CooldownMs);
        return Act(from, player, "place", cell, value);
    }

    /// <summary>Marks by player one and two in turn (any order works; this one is easy to read), each with a refilled pen.</summary>
    private GameEngineResult Play(GameEngineResult from, int firstPlayer, params int[] cells)
    {
        for (var i = 0; i < cells.Length; i++) from = Place(from, (firstPlayer + i) % 2, cells[i]);
        return from;
    }

    /// <summary>What the timeout worker does once the beat after a game is over.</summary>
    private GameEngineResult NextGame(GameEngineResult from)
    {
        Assert.NotNull(from.DeadlineUtc);
        now = from.DeadlineUtc!.Value;
        return engine.Apply(GameType.TicTacToe, from.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, now);
    }

    private JsonElement State(GameEngineResult result) => JsonDocument.Parse(engine.ToPublicState(GameType.TicTacToe, result.StateJson, players[0])).RootElement;
    private static int[] Line(JsonElement state) => state.GetProperty("line").EnumerateArray().Select(cell => cell.GetInt32()).ToArray();
    private Guid? Owner(GameEngineResult result, int cell) => State(result).GetProperty("cells")[cell] is { ValueKind: JsonValueKind.String } owner ? owner.GetGuid() : null;

    // Player one takes the top row; player two the middle row; nobody wins.
    private static readonly int[] OneWins = [0, 3, 1, 4, 2];
    private static readonly int[] Draw = [0, 1, 2, 4, 3, 5, 7, 6, 8];

    [Fact]
    public void Starts_empty_for_both_at_once_with_a_cooldown_and_no_clock()
    {
        var start = Start();
        var state = State(start);
        Assert.True(engine.AnyoneMayAct(GameType.TicTacToe));
        Assert.True(engine.MovesMayCross(GameType.TicTacToe));
        Assert.True(engine.IsTimed(GameType.TicTacToe));
        Assert.Null(start.DeadlineUtc);
        Assert.Equal(9, state.GetProperty("cells").GetArrayLength());
        Assert.All(state.GetProperty("cells").EnumerateArray(), cell => Assert.Equal(JsonValueKind.Null, cell.ValueKind));
        Assert.Equal(9, state.GetProperty("marks").GetArrayLength());
        Assert.Equal("playing", state.GetProperty("phase").GetString());
        Assert.Equal(5, state.GetProperty("totalGames").GetInt32());
        Assert.Equal(3, state.GetProperty("winsNeeded").GetInt32());
        Assert.Equal(TicTacToeRules.CooldownMs, state.GetProperty("cooldownMs").GetInt32());
    }

    [Fact]
    public void Both_may_mark_at_the_same_moment_without_waiting_for_a_turn()
    {
        var start = Start();
        var first = Act(start, 1, "place", 4);  // player two moves first: there are no turns
        var both = Act(first, 0, "place", 0);   // and player one in the same instant
        Assert.Equal(players[1], Owner(both, 4));
        Assert.Equal(players[0], Owner(both, 0));
        Assert.False(both.Completed);
    }

    [Fact]
    public void A_square_goes_to_whoever_gets_there_first_and_the_second_claim_fails_cleanly()
    {
        var mine = Act(Start(), 0, "place", 4);
        var late = Assert.Throws<InvalidOperationException>(() => Act(mine, 1, "place", 4));
        Assert.Equal("That square is taken.", late.Message);
        Assert.Equal(players[0], Owner(mine, 4));
    }

    [Fact]
    public void The_pen_refills_on_the_servers_clock_after_each_of_your_own_marks()
    {
        var start = Start();
        var first = Act(start, 0, "place", 0);
        now = now.AddMilliseconds(TicTacToeRules.CooldownMs - TicTacToeRules.CooldownJitterMs - 1);
        var drying = Assert.Throws<InvalidOperationException>(() => Act(first, 0, "place", 1));
        Assert.Equal("Your ink is still drying.", drying.Message);

        // Your partner's pen is their own: they aren't held up by your mark.
        var partner = Act(first, 1, "place", 1);
        Assert.Equal(players[1], Owner(partner, 1));

        // Network jitter is forgiven: a mark that arrives a little early (the phone waited the full cooldown) lands.
        now = now.AddMilliseconds(1);
        var refilled = Act(partner, 0, "place", 2);
        Assert.Equal(players[0], Owner(refilled, 2));
    }

    [Theory]
    [InlineData(new[] { 0, 3, 1, 4, 2 }, new[] { 0, 1, 2 })] // row
    [InlineData(new[] { 1, 0, 4, 2, 7 }, new[] { 1, 4, 7 })] // column
    [InlineData(new[] { 0, 1, 4, 2, 8 }, new[] { 0, 4, 8 })] // diagonal
    [InlineData(new[] { 2, 0, 4, 1, 6 }, new[] { 2, 4, 6 })] // other diagonal
    public void Three_in_a_row_wins_the_game_marks_the_line_and_sets_the_beat(int[] moves, int[] line)
    {
        var won = Play(Start(), 0, moves);
        var state = State(won);
        Assert.False(won.Completed);
        Assert.Equal("gameOver", state.GetProperty("phase").GetString());
        Assert.Equal(line, Line(state));
        Assert.Equal(1, state.GetProperty("scores").GetProperty(players[0].ToString()).GetInt32());
        Assert.Equal(players[0], state.GetProperty("results")[0].GetGuid());
        Assert.Equal(now.AddMilliseconds(TicTacToeRules.NextGameDelayMs), won.DeadlineUtc);
        Assert.Equal(won.DeadlineUtc, state.GetProperty("nextGameAtUtc").GetDateTime());
    }

    [Fact]
    public void The_first_line_made_ends_the_game_so_a_partner_finishing_a_line_a_moment_later_is_too_late()
    {
        // Both are one square from a line: player one the top row (needs 2), player two the middle row (needs 5).
        var set = Play(Start(), 0, 0, 3, 1, 4);
        var won = Act(set, 0, "place", 2);
        Assert.Throws<InvalidOperationException>(() => Act(won, 1, "place", 5));
        Assert.Equal(players[0], State(won).GetProperty("results")[0].GetGuid());
    }

    [Fact]
    public void A_full_board_without_a_line_is_a_draw_that_scores_nobody()
    {
        var drawn = Play(Start(), 0, Draw);
        var state = State(drawn);
        Assert.Equal("gameOver", state.GetProperty("phase").GetString());
        Assert.Equal(JsonValueKind.Null, state.GetProperty("line").ValueKind);
        Assert.Equal(JsonValueKind.Null, state.GetProperty("results")[0].ValueKind);
        Assert.Equal(1, state.GetProperty("draws").GetInt32());
        Assert.All(players, player => Assert.Equal(0, state.GetProperty("scores").GetProperty(player.ToString()).GetInt32()));
    }

    [Fact]
    public void The_next_game_starts_by_itself_after_the_beat_on_a_fresh_board()
    {
        var won = Play(Start(), 0, OneWins);
        // During the beat a move is refused, and a timeout that comes early changes nothing.
        now = won.DeadlineUtc!.Value.AddMilliseconds(-TicTacToeRules.EarlyStartMs - 1);
        Assert.Throws<InvalidOperationException>(() => Act(won, 1, "place", 4, """{"game":1}"""));

        var next = NextGame(won);
        var state = State(next);
        Assert.Equal(1, state.GetProperty("game").GetInt32());
        Assert.Equal("playing", state.GetProperty("phase").GetString());
        Assert.Equal(JsonValueKind.Null, state.GetProperty("line").ValueKind);
        Assert.All(state.GetProperty("cells").EnumerateArray(), cell => Assert.Equal(JsonValueKind.Null, cell.ValueKind));
        Assert.Null(next.DeadlineUtc);
        Assert.Equal(1, state.GetProperty("scores").GetProperty(players[0].ToString()).GetInt32());

        // A timeout with nothing due does nothing.
        var again = engine.Apply(GameType.TicTacToe, next.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, now.AddSeconds(5));
        Assert.Equal(next.StateJson, again.StateJson);
    }

    [Fact]
    public void A_first_move_for_the_next_game_just_before_the_beat_ends_starts_it()
    {
        var won = Play(Start(), 0, OneWins);
        now = won.DeadlineUtc!.Value.AddMilliseconds(-TicTacToeRules.EarlyStartMs);
        var next = Act(won, 1, "place", 4, """{"game":1}""");
        var state = State(next);
        Assert.Equal(1, state.GetProperty("game").GetInt32());
        Assert.Equal(players[1], Owner(next, 4));
        Assert.Equal(1, state.GetProperty("cells").EnumerateArray().Count(cell => cell.ValueKind != JsonValueKind.Null));
    }

    [Fact]
    public void A_late_move_the_server_turns_into_the_timeout_still_lands_on_the_fresh_board_if_it_was_made_for_it()
    {
        var won = Play(Start(), 0, OneWins);
        now = won.DeadlineUtc!.Value.AddMilliseconds(30);
        // The controller plays the timeout for a move after the deadline, passing the move's square and value along.
        var next = engine.Apply(GameType.TicTacToe, won.StateJson, players[1], GameEngine.TimeoutAction, """{"game":1}""", 6, now);
        Assert.Equal(1, State(next).GetProperty("game").GetInt32());
        Assert.Equal(players[1], Owner(next, 6));

        // One made on the finished board (an extra stroke, say) is dropped, but the board still moves on.
        var stale = engine.Apply(GameType.TicTacToe, won.StateJson, players[0], GameEngine.TimeoutAction, """{"game":0,"strokes":[[[0.1,0.1]]]}""", 2, now);
        Assert.Equal(1, State(stale).GetProperty("game").GetInt32());
        Assert.Null(Owner(stale, 2));
    }

    [Fact]
    public void A_move_made_on_a_finished_game_never_lands_on_the_next_board()
    {
        var won = Play(Start(), 0, OneWins);
        var next = NextGame(won);
        now = now.AddSeconds(1);
        var crossed = Assert.Throws<InvalidOperationException>(() => Act(next, 1, "place", 5, """{"game":0}"""));
        Assert.Equal("That game is over.", crossed.Message);
    }

    [Fact]
    public void Three_wins_end_the_match_early()
    {
        var result = Play(Start(), 0, OneWins);
        result = Play(NextGame(result), 0, OneWins);
        Assert.False(result.Completed);
        result = Play(NextGame(result), 0, OneWins);
        Assert.True(result.Completed);
        Assert.Null(result.CurrentTurnUserId);
        Assert.Null(result.DeadlineUtc);

        var state = State(result);
        Assert.Equal("matchOver", state.GetProperty("phase").GetString());
        Assert.Equal(players[0], state.GetProperty("winnerUserId").GetGuid());
        Assert.Throws<InvalidOperationException>(() => Place(result, 1, 4));

        var summary = engine.Summarise(GameType.TicTacToe, result.StateJson);
        Assert.Equal(3, summary.Scores[players[0]]);
        Assert.Equal(0, summary.Scores[players[1]]);
        Assert.Equal(3, summary.RoundsPlayed);
        Assert.Equal(players[0], summary.DeclaredWinnerUserId);
    }

    [Fact]
    public void After_five_games_the_most_wins_take_the_match_and_draws_do_not_count()
    {
        var result = Play(Start(), 0, OneWins);    // game 0: player one
        result = Play(NextGame(result), 1, OneWins); // game 1: player two
        result = Play(NextGame(result), 0, Draw);    // game 2
        result = Play(NextGame(result), 0, Draw);    // game 3
        Assert.False(result.Completed);
        result = Play(NextGame(result), 0, OneWins); // game 4: player one
        Assert.True(result.Completed);

        var summary = engine.Summarise(GameType.TicTacToe, result.StateJson);
        Assert.Equal(2, summary.Scores[players[0]]);
        Assert.Equal(1, summary.Scores[players[1]]);
        Assert.Equal(5, summary.RoundsPlayed);
        Assert.Equal(players[0], summary.DeclaredWinnerUserId);
        Assert.Equal(2, State(result).GetProperty("draws").GetInt32());
    }

    [Fact]
    public void Five_drawn_games_are_a_tied_match()
    {
        var result = Play(Start(), 0, Draw);
        for (var game = 1; game < 5; game++) result = Play(NextGame(result), 0, Draw);
        Assert.True(result.Completed);
        var summary = engine.Summarise(GameType.TicTacToe, result.StateJson);
        Assert.Null(summary.DeclaredWinnerUserId);
        Assert.Equal(5, summary.RoundsPlayed);
        Assert.Equal(JsonValueKind.Null, State(result).GetProperty("winnerUserId").ValueKind);
    }

    [Fact]
    public void A_hand_drawn_mark_rides_along_and_more_strokes_can_follow_while_the_ink_is_wet()
    {
        var placed = Act(Start(), 0, "place", 4, """{"game":0,"strokes":[[[0.1,0.1],[0.9,0.9]]]}""");
        now = now.AddMilliseconds(300);
        var crossed = Act(placed, 0, "ink", 4, """{"game":0,"strokes":[[[0.9,0.1],[0.1,0.9]]]}""");
        var strokes = State(crossed).GetProperty("marks")[4].GetProperty("strokes");
        Assert.Equal(2, strokes.GetArrayLength());
        Assert.Equal(0.9, strokes[1].GetProperty("points")[0][0].GetDouble());

        // Only your own newest mark, only while wet, and only up to three strokes.
        Assert.Throws<InvalidOperationException>(() => Act(crossed, 1, "ink", 4, """{"game":0,"strokes":[[[0.5,0.5]]]}"""));
        Assert.Throws<InvalidOperationException>(() => Act(crossed, 0, "ink", 3, """{"game":0,"strokes":[[[0.5,0.5]]]}"""));
        Assert.Throws<InvalidOperationException>(() => Act(crossed, 0, "ink", 4, """{"game":0,"strokes":[[[0.5,0.5]],[[0.4,0.4]]]}"""));
        now = now.AddMilliseconds(TicTacToeRules.WetInkMs);
        Assert.Throws<InvalidOperationException>(() => Act(crossed, 0, "ink", 4, """{"game":0,"strokes":[[[0.5,0.5]]]}"""));
    }

    [Theory]
    [InlineData("""{"game":0,"strokes":[[[1.5,0.1]]]}""")]               // off the square
    [InlineData("""{"game":0,"strokes":[[[0.1]]]}""")]                   // not a point
    [InlineData("""{"game":0,"strokes":[[]]}""")]                        // an empty stroke
    [InlineData("""{"game":0,"strokes":[[[0,0]],[[0,0]],[[0,0]],[[0,0]]]}""")] // too many strokes
    [InlineData("""{"game":0,"colour":"red"}""")]                        // anything else
    [InlineData("""{"game":-1}""")]
    [InlineData("not json")]
    public void A_malformed_mark_is_refused(string value)
        => Assert.Throws<InvalidOperationException>(() => Act(Start(), 0, "place", 4, value));

    [Fact]
    public void A_stroke_has_at_most_64_points()
    {
        string Stroke(int points) => $$"""{"game":0,"strokes":[[{{string.Join(",", Enumerable.Repeat("[0.5,0.5]", points))}}]]}""";
        Assert.Equal(players[0], Owner(Act(Start(), 0, "place", 4, Stroke(TicTacToeRules.MaxPointsPerStroke)), 4));
        Assert.Throws<InvalidOperationException>(() => Act(Start(), 0, "place", 4, Stroke(TicTacToeRules.MaxPointsPerStroke + 1)));
    }

    [Fact]
    public void Refuses_squares_off_the_board_moves_of_another_kind_and_strangers()
    {
        var start = Start();
        Assert.Throws<InvalidOperationException>(() => Act(start, 0, "place", 9));
        Assert.Throws<InvalidOperationException>(() => Act(start, 0, "place", -1));
        Assert.Throws<InvalidOperationException>(() => Act(start, 0, "place", null));
        Assert.Throws<InvalidOperationException>(() => Act(start, 0, "drop", 0));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.TicTacToe, start.StateJson, Guid.NewGuid(), "place", null, 0, now));
    }

    /// <summary>A turn-by-turn game's state from before the game went live, as stored.</summary>
    private string OldState(string phase, int game, string cells, string results, int oneWins, int twoWins, string line = "null")
        => $$"""{"players":["{{players[0]}}","{{players[1]}}"],"columns":3,"rows":3,"cells":{{cells}},"game":{{game}},"totalGames":5,"winsNeeded":3,"phase":"{{phase}}","turnIndex":1,"lastMove":2,"line":{{line}},"scores":{"{{players[0]}}":{{oneWins}},"{{players[1]}}":{{twoWins}}},"draws":0,"results":{{results}},"winnerUserId":{{(phase == "matchOver" ? $"\"{players[0]}\"" : "null")}}}""";

    [Fact]
    public void A_finished_turn_by_turn_game_from_before_still_reads()
    {
        var a = $"\"{players[0]}\"";
        var b = $"\"{players[1]}\"";
        var old = OldState("matchOver", 2, $"[{a},{a},{a},{b},{b},null,null,null,null]", $"[{a},{a},{a}]", 3, 0, "[0,1,2]");
        var state = JsonDocument.Parse(engine.ToPublicState(GameType.TicTacToe, old, players[1])).RootElement;
        Assert.Equal("matchOver", state.GetProperty("phase").GetString());
        Assert.Equal([0, 1, 2], Line(state));
        Assert.Equal(players[0], state.GetProperty("cells")[0].GetGuid());
        Assert.Equal(9, state.GetProperty("marks").GetArrayLength());
        Assert.All(state.GetProperty("marks").EnumerateArray(), mark => Assert.Equal(JsonValueKind.Null, mark.ValueKind));

        var summary = engine.Summarise(GameType.TicTacToe, old);
        Assert.Equal(3, summary.Scores[players[0]]);
        Assert.Equal(3, summary.RoundsPlayed);
        Assert.Equal(players[0], summary.DeclaredWinnerUserId);
    }

    [Fact]
    public void An_open_turn_by_turn_game_from_before_carries_on_at_once()
    {
        var a = $"\"{players[0]}\"";
        var old = new GameEngineResult(OldState("playing", 0, $"[{a},null,null,null,null,null,null,null,null]", "[]", 0, 0), players[1], null, false);
        // Player one moves although it was player two's turn, and without saying which game: it's the one in play.
        var moved = Act(old, 0, "place", 1);
        Assert.Equal(players[0], Owner(moved, 1));

        // A finished board from before (no beat set) gives way to the first move of the next game.
        var b = $"\"{players[1]}\"";
        var over = new GameEngineResult(OldState("gameOver", 0, $"[{a},{a},{a},{b},{b},null,null,null,null]", $"[{a}]", 1, 0, "[0,1,2]"), players[1], null, false);
        var next = Act(over, 1, "place", 4);
        Assert.Equal(1, State(next).GetProperty("game").GetInt32());
        Assert.Equal(players[1], Owner(next, 4));
    }

    [Fact]
    public async Task Setup_is_just_the_region()
    {
        var setup = await new GameSetupFactory(null!).CreateAsync(GameType.TicTacToe, GameRegion.Nigeria, null, CancellationToken.None);
        Assert.Equal("""{"region":"ng"}""", setup);
    }
}
