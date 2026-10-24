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

    /// <summary>Drops discs in turn, starting with whoever's turn it is.</summary>
    private GameEngineResult Play(GameEngineResult from, params int[] columns)
    {
        foreach (var column in columns) from = Drop(from, Array.IndexOf(players, from.CurrentTurnUserId!.Value), column);
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
        Assert.False(engine.IsTimed(GameType.ConnectFour));
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
    public void Players_take_turns_starting_each_game()
    {
        var won = Play(Start(), StarterWins);
        Assert.Equal(players[1], won.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Drop(won, 0, 3));

        var second = Drop(won, 1, 3);
        var state = State(second);
        Assert.Equal(1, state.GetProperty("game").GetInt32());
        Assert.Equal(1, state.GetProperty("cells").EnumerateArray().Count(cell => cell.ValueKind != JsonValueKind.Null));
        Assert.Equal(players[1].ToString(), state.GetProperty("cells")[Cell(3, 5)].GetString());
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
