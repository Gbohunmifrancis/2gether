using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class TicTacToeRulesTests
{
    private readonly GameEngine engine = new();
    private readonly Guid[] players = [Guid.NewGuid(), Guid.NewGuid()];
    private readonly DateTime now = DateTime.UtcNow;

    private GameEngineResult Start() => engine.Start(GameType.TicTacToe, players, now, """{"region":"global"}""");

    private GameEngineResult Place(GameEngineResult from, int player, int cell)
        => engine.Apply(GameType.TicTacToe, from.StateJson, players[player], "place", null, cell, now);

    /// <summary>Plays moves in turn, starting with whoever's turn it is.</summary>
    private GameEngineResult Play(GameEngineResult from, params int[] cells)
    {
        foreach (var cell in cells) from = Place(from, Array.IndexOf(players, from.CurrentTurnUserId!.Value), cell);
        return from;
    }

    private JsonElement State(GameEngineResult result) => JsonDocument.Parse(engine.ToPublicState(GameType.TicTacToe, result.StateJson, players[0])).RootElement;

    private static int[] Line(JsonElement state) => state.GetProperty("line").EnumerateArray().Select(cell => cell.GetInt32()).ToArray();

    // The starter wins the top row; the other player wins the middle row; nobody wins.
    private static readonly int[] StarterWins = [0, 3, 1, 4, 2];
    private static readonly int[] OtherWins = [0, 3, 1, 4, 8, 5];
    private static readonly int[] Draw = [0, 1, 2, 4, 3, 5, 7, 6, 8];

    [Fact]
    public void Starts_empty_with_player_one_to_move_and_no_clock()
    {
        var start = Start();
        var state = State(start);
        Assert.Equal(players[0], start.CurrentTurnUserId);
        Assert.Null(start.DeadlineUtc);
        Assert.False(engine.IsTimed(GameType.TicTacToe));
        Assert.False(engine.AnyoneMayAct(GameType.TicTacToe));
        Assert.Equal(9, state.GetProperty("cells").GetArrayLength());
        Assert.All(state.GetProperty("cells").EnumerateArray(), cell => Assert.Equal(JsonValueKind.Null, cell.ValueKind));
        Assert.Equal("playing", state.GetProperty("phase").GetString());
        Assert.Equal(5, state.GetProperty("totalGames").GetInt32());
    }

    [Theory]
    [InlineData(new[] { 0, 3, 1, 4, 2 }, new[] { 0, 1, 2 })] // row
    [InlineData(new[] { 1, 0, 4, 2, 7 }, new[] { 1, 4, 7 })] // column
    [InlineData(new[] { 0, 1, 4, 2, 8 }, new[] { 0, 4, 8 })] // diagonal
    [InlineData(new[] { 2, 0, 4, 1, 6 }, new[] { 2, 4, 6 })] // other diagonal
    public void Three_in_a_row_wins_the_game_and_marks_the_line(int[] moves, int[] line)
    {
        var won = Play(Start(), moves);
        var state = State(won);
        Assert.False(won.Completed);
        Assert.Equal("gameOver", state.GetProperty("phase").GetString());
        Assert.Equal(line, Line(state));
        Assert.Equal(1, state.GetProperty("scores").GetProperty(players[0].ToString()).GetInt32());
        Assert.Equal(players[0], state.GetProperty("results")[0].GetGuid());
    }

    [Fact]
    public void Two_lines_made_at_once_are_both_marked()
    {
        var won = Play(Start(), 1, 4, 2, 5, 3, 8, 6, 7, 0);
        Assert.Equal([0, 1, 2, 3, 6], Line(State(won)));
    }

    [Fact]
    public void A_full_board_without_a_line_is_a_draw_that_scores_nobody()
    {
        var drawn = Play(Start(), Draw);
        var state = State(drawn);
        Assert.Equal("gameOver", state.GetProperty("phase").GetString());
        Assert.Equal(JsonValueKind.Null, state.GetProperty("line").ValueKind);
        Assert.Equal(JsonValueKind.Null, state.GetProperty("results")[0].ValueKind);
        Assert.Equal(1, state.GetProperty("draws").GetInt32());
        Assert.All(players, player => Assert.Equal(0, state.GetProperty("scores").GetProperty(player.ToString()).GetInt32()));
    }

    [Fact]
    public void Refuses_moves_out_of_turn_on_a_taken_square_off_the_board_or_of_another_kind()
    {
        var first = Place(Start(), 0, 4);
        Assert.Equal(players[1], first.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Place(first, 0, 0));
        Assert.Throws<InvalidOperationException>(() => Place(first, 1, 4));
        Assert.Throws<InvalidOperationException>(() => Place(first, 1, 9));
        Assert.Throws<InvalidOperationException>(() => Place(first, 1, -1));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.TicTacToe, first.StateJson, players[1], "place", null, null, now));
        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.TicTacToe, first.StateJson, players[1], "drop", null, 0, now));
    }

    [Fact]
    public void Players_take_turns_starting_and_the_next_starters_move_clears_the_board()
    {
        var won = Play(Start(), StarterWins);
        Assert.Equal(players[1], won.CurrentTurnUserId);
        Assert.Throws<InvalidOperationException>(() => Place(won, 0, 4));

        var second = Place(won, 1, 8);
        var state = State(second);
        Assert.Equal(1, state.GetProperty("game").GetInt32());
        Assert.Equal("playing", state.GetProperty("phase").GetString());
        Assert.Equal(JsonValueKind.Null, state.GetProperty("line").ValueKind);
        Assert.Equal(1, state.GetProperty("cells").EnumerateArray().Count(cell => cell.ValueKind != JsonValueKind.Null));
        Assert.Equal(players[1].ToString(), state.GetProperty("cells")[8].GetString());
        Assert.Equal(players[0], second.CurrentTurnUserId);

        // Game 2 is player one's again.
        var third = Play(second, 0, 3, 1, 4, 2);
        Assert.Equal(players[0], third.CurrentTurnUserId);
    }

    [Fact]
    public void Three_wins_end_the_match_early()
    {
        var result = Play(Start(), StarterWins);   // game 0: player one starts and wins
        result = Play(result, OtherWins);          // game 1: player two starts, player one wins
        Assert.False(result.Completed);
        result = Play(result, StarterWins);        // game 2: player one starts and wins
        Assert.True(result.Completed);
        Assert.Null(result.CurrentTurnUserId);

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
        var result = Play(Start(), StarterWins); // game 0: player one
        result = Play(result, StarterWins);      // game 1: player two starts and wins
        result = Play(result, Draw);             // game 2
        result = Play(result, Draw);             // game 3
        Assert.False(result.Completed);
        result = Play(result, StarterWins);      // game 4: player one
        Assert.True(result.Completed);

        var summary = engine.Summarise(GameType.TicTacToe, result.StateJson);
        Assert.Equal(2, summary.Scores[players[0]]);
        Assert.Equal(1, summary.Scores[players[1]]);
        Assert.Equal(5, summary.RoundsPlayed);
        Assert.Equal(players[0], summary.DeclaredWinnerUserId);
        Assert.Equal(2, State(result).GetProperty("draws").GetInt32());
    }

    [Fact]
    public void Five_games_without_a_leader_are_a_tied_match()
    {
        var result = Start();
        for (var game = 0; game < 5; game++) result = Play(result, Draw);
        Assert.True(result.Completed);
        var summary = engine.Summarise(GameType.TicTacToe, result.StateJson);
        Assert.Null(summary.DeclaredWinnerUserId);
        Assert.Equal(5, summary.RoundsPlayed);
        Assert.Equal(JsonValueKind.Null, State(result).GetProperty("winnerUserId").ValueKind);
    }

    [Fact]
    public async Task Setup_is_just_the_region()
    {
        var setup = await new GameSetupFactory(null!).CreateAsync(GameType.TicTacToe, GameRegion.Nigeria, null, CancellationToken.None);
        Assert.Equal("""{"region":"ng"}""", setup);
    }
}
