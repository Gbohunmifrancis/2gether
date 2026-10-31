using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class ConnectFourRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid[] players = [Guid.NewGuid(), Guid.NewGuid()];
    private readonly DateTime now = DateTime.UtcNow;

    private GameEngineResult Start() => engine.Start(GameType.ConnectFour, players, now, """{"region":"global"}""");

    private GameEngineResult Drop(GameEngineResult from, int player, int column)
        => engine.Apply(GameType.ConnectFour, from.StateJson, players[player], "drop", null, column, now);

    /// <summary>The server's beat after a game: the timeout worker plays the timeout once the deadline has passed.</summary>
    private GameEngineResult Beat(GameEngineResult from)
        => from.DeadlineUtc is { } at ? engine.Apply(GameType.ConnectFour, from.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, at) : from;

    /// <summary>Drops discs in turn, starting with whoever's turn it is (after the beat, when a finished board is up).</summary>
    private GameEngineResult Play(GameEngineResult from, params int[] columns)
    {
        foreach (var column in columns) from = Drop(Beat(from), Array.IndexOf(players, from.CurrentTurnUserId!.Value), column);
        return from;
    }

    private JsonElement State(GameEngineResult result) => JsonDocument.Parse(engine.ToPublicState(GameType.ConnectFour, result.StateJson, players[1])).RootElement;

    private static int[] Line(JsonElement state) => state.GetProperty("line").EnumerateArray().Select(cell => cell.GetInt32()).ToArray();

    private static int Cell(int column, int row) => row * 7 + column;

    private static readonly int[] StarterWins = [0, 1, 0, 1, 0, 1, 0];
    private static readonly int[] OtherWins = [0, 1, 0, 1, 0, 1, 6, 1];

    /// <summary>
    /// Fills the board with no four in a row: columns 0, 1, 4 and 5 stack starter, other, starter… from the bottom;
    /// columns 2, 3 and 6 the other way round, so rows read AABBAAB and every diagonal flips at least every two.
    /// </summary>
    private static readonly int[] Draw = Enumerable.Repeat(new[] { 0, 2, 2, 0, 1, 3, 3, 1, 4, 6, 6, 4, 5, 5 }, 3).SelectMany(moves => moves).ToArray();

    [Fact]
    public void Starts_with_an_empty_seven_by_six_board_and_no_clock()
    {
        var start = Start();
        var state = State(start);
        Assert.Equal(players[0], start.CurrentTurnUserId);
        Assert.Null(start.DeadlineUtc);
        // Only the beat between games is timed: the turns have no clock (and so a "your turn" push).
        Assert.True(engine.IsTimed(GameType.ConnectFour));
        Assert.False(GameEngine.HasTurnClock(GameType.ConnectFour));
        Assert.Equal(7, state.GetProperty("columns").GetInt32());
        Assert.Equal(6, state.GetProperty("rows").GetInt32());
        Assert.Equal(42, state.GetProperty("cells").GetArrayLength());
        Assert.Equal(3, state.GetProperty("totalGames").GetInt32());
        Assert.Equal(2, state.GetProperty("winsNeeded").GetInt32());
    }

    [Fact]
    public void A_disc_falls_to_the_lowest_free_row()
    {
        var first = Drop(Start(), 0, 3);
        var second = Drop(first, 1, 3);
        var state = State(second);
        Assert.Equal(players[0].ToString(), state.GetProperty("cells")[Cell(3, 5)].GetString());
        Assert.Equal(players[1].ToString(), state.GetProperty("cells")[Cell(3, 4)].GetString());
        Assert.Equal(Cell(3, 4), state.GetProperty("lastMove").GetInt32());
    }

    [Fact]
    public void Four_in_a_column_wins()
        => Assert.Equal([Cell(0, 2), Cell(0, 3), Cell(0, 4), Cell(0, 5)], Line(State(Play(Start(), StarterWins))));

    [Fact]
    public void Four_in_a_row_wins()
        => Assert.Equal([Cell(0, 5), Cell(1, 5), Cell(2, 5), Cell(3, 5)], Line(State(Play(Start(), 0, 0, 1, 1, 2, 2, 3))));

    [Fact]
    public void Four_on_a_rising_diagonal_wins()
        => Assert.Equal([Cell(3, 2), Cell(2, 3), Cell(1, 4), Cell(0, 5)], Line(State(Play(Start(), 0, 1, 1, 2, 2, 3, 2, 3, 3, 6, 3))));

    [Fact]
    public void Four_on_a_falling_diagonal_wins()
        => Assert.Equal([Cell(3, 2), Cell(4, 3), Cell(5, 4), Cell(6, 5)], Line(State(Play(Start(), 6, 5, 5, 4, 4, 3, 4, 3, 3, 0, 3))));

    [Fact]
    public void Five_in_a_row_marks_all_five()
    {
        // Player one holds 0, 1, 3 and 4 on the bottom row, then fills the gap.
        var won = Play(Start(), 0, 0, 1, 1, 3, 3, 4, 4, 2);
        Assert.Equal([Cell(0, 5), Cell(1, 5), Cell(2, 5), Cell(3, 5), Cell(4, 5)], Line(State(won)));
    }

    [Fact]
    public void A_disc_that_makes_two_fours_at_once_marks_both()
    {
        // The last disc, bottom of column 3, finishes the bottom row and the diagonal up to column 0, row 2. The app
        // strikes each through on its own (GridMatch.winRuns), so both must be in the line.
        var won = Play(Start(), 0, 0, 1, 1, 2, 0, 2, 6, 1, 6, 0, 5, 3);
        Assert.Equal([Cell(0, 2), Cell(1, 3), Cell(2, 4), Cell(0, 5), Cell(1, 5), Cell(2, 5), Cell(3, 5)], Line(State(won)));
        Assert.Equal(players[0], Guid.Parse(State(won).GetProperty("results")[0].GetString()!));
    }

    [Fact]
    public void A_win_scores_the_winner_a_game()
    {
        var state = State(Play(Start(), StarterWins));
        Assert.Equal("gameOver", state.GetProperty("phase").GetString());
        Assert.Equal(1, state.GetProperty("scores").GetProperty(players[0].ToString()).GetInt32());
        Assert.Equal(0, state.GetProperty("scores").GetProperty(players[1].ToString()).GetInt32());
    }

    [Fact]
    public void A_full_board_without_four_is_a_draw()
    {
        var drawn = Play(Start(), Draw);
        var state = State(drawn);
        Assert.False(drawn.Completed);
        Assert.Equal("gameOver", state.GetProperty("phase").GetString());
        Assert.All(state.GetProperty("cells").EnumerateArray(), cell => Assert.Equal(JsonValueKind.String, cell.ValueKind));
        Assert.Equal(JsonValueKind.Null, state.GetProperty("line").ValueKind);
        Assert.Equal(JsonValueKind.Null, state.GetProperty("results")[0].ValueKind);
        Assert.Equal(1, state.GetProperty("draws").GetInt32());
    }

    [Fact]
    public void Refuses_a_full_column_a_column_off_the_board_a_move_out_of_turn_and_other_actions()
    {
        var full = Play(Start(), 0, 0, 0, 0, 0, 0);
        Assert.Equal(players[0], full.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Drop(full, 0, 0));
        Assert.Throws<InvalidOperationException>(() => Drop(full, 0, 7));
        Assert.Throws<InvalidOperationException>(() => Drop(full, 0, -1));
        Assert.Throws<InvalidOperationException>(() => Drop(full, 1, 3));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ConnectFour, full.StateJson, players[0], "drop", null, null, now));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ConnectFour, full.StateJson, players[0], "place", null, 3, now));
    }

    [Fact]
    public void A_finished_game_stays_up_for_a_beat_then_the_server_starts_the_next()
    {
        var won = Play(Start(), StarterWins);
        var state = State(won);
        var at = now.AddMilliseconds(GridMatchRules.NextGameDelayMs);
        // The finished board, its line and the next game's start, which is the session's deadline.
        Assert.Equal("gameOver", state.GetProperty("phase").GetString());
        Assert.Equal(at, state.GetProperty("nextGameAtUtc").GetDateTime());
        Assert.Equal(at, won.DeadlineUtc);
        Assert.Equal(players[1], won.CurrentTurnUserId);
        Assert.False(won.Completed);

        // The timeout worker, once the beat is over: a fresh board, the other partner to start.
        var next = engine.Apply(GameType.ConnectFour, won.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, at);
        var fresh = State(next);
        Assert.Equal("playing", fresh.GetProperty("phase").GetString());
        Assert.Equal(1, fresh.GetProperty("game").GetInt32());
        Assert.All(fresh.GetProperty("cells").EnumerateArray(), cell => Assert.Equal(JsonValueKind.Null, cell.ValueKind));
        Assert.Equal(JsonValueKind.Null, fresh.GetProperty("line").ValueKind);
        Assert.Equal(JsonValueKind.Null, fresh.GetProperty("nextGameAtUtc").ValueKind);
        Assert.Equal(players[1], next.CurrentTurnUserId);
        Assert.Null(next.DeadlineUtc);
        Assert.Equal(1, fresh.GetProperty("scores").GetProperty(players[0].ToString()).GetInt32());
    }

    [Fact]
    public void A_timeout_before_the_beat_is_over_or_during_play_changes_nothing()
    {
        var won = Play(Start(), StarterWins);
        var early = engine.Apply(GameType.ConnectFour, won.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, now.AddMilliseconds(500));
        Assert.Equal("gameOver", State(early).GetProperty("phase").GetString());
        Assert.Equal(won.DeadlineUtc, early.DeadlineUtc);

        var playing = Drop(Start(), 0, 3);
        var still = engine.Apply(GameType.ConnectFour, playing.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, now.AddHours(1));
        Assert.Equal(playing.StateJson, still.StateJson);
    }

    [Fact]
    public void The_next_starter_may_move_a_moment_early_but_not_during_the_beat()
    {
        var won = Play(Start(), StarterWins);
        var at = won.DeadlineUtc!.Value;
        // Still showing the result: too soon. And the one who didn't start the next game may never go first.
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ConnectFour, won.StateJson, players[1], "drop", null, 3, now));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ConnectFour, won.StateJson, players[0], "drop", null, 3, at));

        var second = engine.Apply(GameType.ConnectFour, won.StateJson, players[1], "drop", null, 3, at.AddMilliseconds(-GridMatchRules.EarlyStartMs));
        var state = State(second);
        Assert.Equal(1, state.GetProperty("game").GetInt32());
        Assert.Equal(1, state.GetProperty("cells").EnumerateArray().Count(cell => cell.ValueKind != JsonValueKind.Null));
        Assert.Equal(players[1].ToString(), state.GetProperty("cells")[Cell(3, 5)].GetString());
        Assert.Equal(players[0], second.CurrentTurnUserId);
        Assert.Null(second.DeadlineUtc);
    }

    [Fact]
    public void A_move_that_arrives_after_the_beat_lands_on_the_fresh_board()
    {
        var won = Play(Start(), StarterWins);
        var late = won.DeadlineUtc!.Value.AddSeconds(1);
        // The server plays the timeout for a move after the deadline, keeping its column: the starter's disc lands.
        var mine = State(engine.Apply(GameType.ConnectFour, won.StateJson, players[1], GameEngine.TimeoutAction, null, 4, late));
        Assert.Equal(1, mine.GetProperty("game").GetInt32());
        Assert.Equal(players[1].ToString(), mine.GetProperty("cells")[Cell(4, 5)].GetString());
        // Anyone else's late move only starts the next game.
        var theirs = State(engine.Apply(GameType.ConnectFour, won.StateJson, players[0], GameEngine.TimeoutAction, null, 4, late));
        Assert.Equal(1, theirs.GetProperty("game").GetInt32());
        Assert.All(theirs.GetProperty("cells").EnumerateArray(), cell => Assert.Equal(JsonValueKind.Null, cell.ValueKind));
    }

    [Fact]
    public void A_board_finished_before_the_beat_existed_is_cleared_by_the_next_starters_move()
    {
        // A match saved mid-way by an older server: game over, no next-game time, no deadline.
        var won = Play(Start(), StarterWins);
        var old = System.Text.Json.Nodes.JsonNode.Parse(won.StateJson)!.AsObject();
        old.Remove("nextGameAtUtc");
        var second = engine.Apply(GameType.ConnectFour, old.ToJsonString(), players[1], "drop", null, 3, now);
        var state = State(second);
        Assert.Equal(1, state.GetProperty("game").GetInt32());
        Assert.Equal(players[1].ToString(), state.GetProperty("cells")[Cell(3, 5)].GetString());
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.ConnectFour, old.ToJsonString(), players[0], "drop", null, 3, now));
    }

    [Fact]
    public void Two_wins_end_the_match_early()
    {
        var result = Play(Start(), StarterWins); // game 0: player one starts and wins
        result = Play(result, OtherWins);        // game 1: player two starts, player one wins
        Assert.True(result.Completed);
        Assert.Null(result.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Drop(result, 1, 3));

        var summary = engine.Summarise(GameType.ConnectFour, result.StateJson);
        Assert.Equal(2, summary.Scores[players[0]]);
        Assert.Equal(0, summary.Scores[players[1]]);
        Assert.Equal(2, summary.RoundsPlayed);
        Assert.Equal(players[0], summary.DeclaredWinnerUserId);
        Assert.Equal("matchOver", State(result).GetProperty("phase").GetString());
    }

    [Fact]
    public void Three_games_end_the_match_and_an_even_score_is_a_tie()
    {
        var result = Play(Start(), StarterWins); // game 0: player one
        result = Play(result, StarterWins);      // game 1: player two starts and wins
        Assert.False(result.Completed);
        result = Play(result, Draw);             // game 2: player one starts, a draw
        Assert.True(result.Completed);

        var summary = engine.Summarise(GameType.ConnectFour, result.StateJson);
        Assert.Equal(1, summary.Scores[players[0]]);
        Assert.Equal(1, summary.Scores[players[1]]);
        Assert.Equal(3, summary.RoundsPlayed);
        Assert.Null(summary.DeclaredWinnerUserId);
    }

    [Fact]
    public void Three_games_with_one_win_and_two_draws_go_to_the_winner()
    {
        var result = Play(Start(), Draw);   // game 0
        result = Play(result, OtherWins);   // game 1: player two starts, player one wins
        result = Play(result, Draw);        // game 2
        Assert.True(result.Completed);
        Assert.Equal(players[0], engine.Summarise(GameType.ConnectFour, result.StateJson).DeclaredWinnerUserId);
    }

    [Fact]
    public async Task Setup_is_just_the_region()
    {
        var setup = await new GameSetupFactory(null!).CreateAsync(GameType.ConnectFour, GameRegion.Global, null, CancellationToken.None);
        Assert.Equal("""{"region":"global"}""", setup);
    }
}
