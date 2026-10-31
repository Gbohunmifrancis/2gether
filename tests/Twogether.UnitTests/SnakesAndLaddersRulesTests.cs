using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class SnakesAndLaddersRulesTests
{
    private static readonly JsonSerializerOptions Web = new(JsonSerializerDefaults.Web);
    private readonly Guid maya = Guid.NewGuid();
    private readonly Guid theo = Guid.NewGuid();
    private readonly DateTime start = new(2026, 10, 31, 18, 0, 0, DateTimeKind.Utc);

    /// <summary>The server's randomness, scripted: each call takes the next number (a die's 1 to 6, a coin's 0 or 1).</summary>
    private sealed class Script(params int[] numbers)
    {
        private readonly Queue<int> queue = new(numbers);

        public int Next(int n)
        {
            var value = queue.Dequeue();
            // A die (n = 6) is scripted as its face, 1 to 6.
            return n == 6 ? value - 1 : value;
        }

        public int Left => queue.Count;
    }

    /// <summary>
    /// A small board to test on: ladders 3→22, 14→35, 50→91; snakes 17→4, 62→38, 99→80; hearts: a hug shield on 6,
    /// kiss &amp; swap on 8 and 45, a love boost on 10 (to 13), a heartbreak on 30 (to 25).
    /// </summary>
    private static SnakesAndLaddersRules.Board TestBoard() => new()
    {
        Ladders = [new() { From = 3, To = 22 }, new() { From = 14, To = 35 }, new() { From = 50, To = 91 }],
        Snakes = [new() { From = 17, To = 4 }, new() { From = 62, To = 38 }, new() { From = 99, To = 80 }],
        Hearts =
        [
            new() { Square = 6, Kind = SnakesAndLaddersRules.Shield }, new() { Square = 8, Kind = SnakesAndLaddersRules.Swap },
            new() { Square = 45, Kind = SnakesAndLaddersRules.Swap }, new() { Square = 10, Kind = SnakesAndLaddersRules.Boost },
            new() { Square = 30, Kind = SnakesAndLaddersRules.Heartbreak },
        ],
    };

    private static string Setup(SnakesAndLaddersRules.Board? board = null, int seed = 1)
        => JsonSerializer.Serialize(new { region = GameRegion.Global, seed, board = board ?? TestBoard() }, Web);

    /// <summary>A game in play on the test board, written by hand the way the server stores it (only what matters given).</summary>
    private string State(int mayaAt, int theoAt, Guid? turn = null, bool mayaShield = false)
        => JsonSerializer.Serialize(new
        {
            Players = new[] { maya, theo },
            First = maya,
            Turn = turn ?? maya,
            Board = TestBoard(),
            Positions = new Dictionary<Guid, int> { [maya] = mayaAt, [theo] = theoAt },
            Shields = new Dictionary<Guid, bool> { [maya] = mayaShield, [theo] = false },
        });

    private static GameEngineResult Roll(SnakesAndLaddersRules rules, string stateJson, Guid actor, DateTime at)
        => rules.Apply(new GameMove(stateJson, actor, "roll", null, null, at));

    private GameEngineResult Roll(string stateJson, Guid actor, params int[] dice) => Roll(new SnakesAndLaddersRules(new Script(dice).Next), stateJson, actor, start);

    private static JsonElement View(SnakesAndLaddersRules rules, GameEngineResult result, Guid? viewer = null)
    {
        using var document = JsonDocument.Parse(rules.ToPublicState(result.StateJson, viewer));
        return document.RootElement.Clone();
    }

    private static JsonElement View(GameEngineResult result) => View(new SnakesAndLaddersRules(), result);

    private int At(JsonElement view, Guid player) => view.GetProperty("positions").GetProperty(player.ToString()).GetInt32();

    private static List<(string Kind, int From, int To)> Events(JsonElement view)
        => view.GetProperty("last").GetProperty("events").EnumerateArray()
            .Select(item => (item.GetProperty("kind").GetString()!, item.GetProperty("from").GetInt32(), item.GetProperty("to").GetInt32())).ToList();

    private static List<int> Steps(JsonElement view) => view.GetProperty("last").GetProperty("steps").EnumerateArray().Select(item => item.GetInt32()).ToList();

    [Fact]
    public void Is_registered_turn_by_turn_with_no_clock()
    {
        var engine = new GameEngine();
        Assert.True(GameEngine.RulesFor(GameType.SnakesAndLadders) is SnakesAndLaddersRules);
        Assert.False(engine.AnyoneMayAct(GameType.SnakesAndLadders));
        Assert.False(engine.IsTimed(GameType.SnakesAndLadders));
        Assert.False(engine.MovesMayCross(GameType.SnakesAndLadders));
        // The engine's own start works with the real dice and a seeded setup.
        var started = engine.Start(GameType.SnakesAndLadders, [maya, theo], start, SnakesAndLaddersRules.CreateSetup(GameRegion.Global, 5));
        Assert.Contains(started.CurrentTurnUserId!.Value, new[] { maya, theo });
        Assert.Null(started.DeadlineUtc);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    public void A_coin_flip_picks_who_rolls_first_and_both_start_off_the_board(int coin)
    {
        var rules = new SnakesAndLaddersRules(new Script(coin).Next);
        var started = rules.Start([maya, theo], start, Setup());
        var first = coin == 0 ? maya : theo;
        var view = View(rules, started, maya);

        Assert.Equal(first, started.CurrentTurnUserId);
        Assert.Null(started.DeadlineUtc);
        Assert.False(started.Completed);
        Assert.Equal("playing", view.GetProperty("phase").GetString());
        Assert.Equal(100, view.GetProperty("goal").GetInt32());
        Assert.Equal(first.ToString(), view.GetProperty("first").GetString());
        Assert.Equal(first.ToString(), view.GetProperty("turn").GetString());
        Assert.Equal(0, At(view, maya));
        Assert.Equal(0, At(view, theo));
        Assert.False(view.GetProperty("shields").GetProperty(maya.ToString()).GetBoolean());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("last").ValueKind);
        Assert.Equal(JsonValueKind.Null, view.GetProperty("winner").ValueKind);
        Assert.Equal(0, view.GetProperty("moves").GetInt32());
        // The board from the setup, for both to see.
        var board = view.GetProperty("board");
        Assert.Equal(3, board.GetProperty("ladders").GetArrayLength());
        Assert.Equal(22, board.GetProperty("ladders")[0].GetProperty("to").GetInt32());
        Assert.Equal(17, board.GetProperty("snakes")[0].GetProperty("from").GetInt32());
        Assert.Equal("shield", board.GetProperty("hearts")[0].GetProperty("kind").GetString());
        Assert.Equal(6, board.GetProperty("hearts")[0].GetProperty("square").GetInt32());
    }

    [Fact]
    public void The_server_rolls_and_moves_square_by_square_then_the_turn_passes()
    {
        var rolled = Roll(State(0, 0), maya, 2);
        var view = View(rolled);

        Assert.Equal(theo, rolled.CurrentTurnUserId);
        Assert.Equal(2, At(view, maya));
        var last = view.GetProperty("last");
        Assert.Equal(1, last.GetProperty("move").GetInt32());
        Assert.Equal(maya.ToString(), last.GetProperty("player").GetString());
        Assert.Equal(2, last.GetProperty("roll").GetInt32());
        Assert.Equal(0, last.GetProperty("from").GetInt32());
        Assert.Equal([1, 2], Steps(view));
        Assert.Empty(Events(view));
        Assert.Equal(2, last.GetProperty("to").GetInt32());
        Assert.False(last.GetProperty("extraTurn").GetBoolean());
        Assert.Equal(1, view.GetProperty("stats").GetProperty(maya.ToString()).GetProperty("rolls").GetInt32());
        Assert.Equal(1, view.GetProperty("moves").GetInt32());
        Assert.Equal(theo.ToString(), view.GetProperty("turn").GetString());

        // Theo's roll follows, and it's Maya's again.
        var next = Roll(rolled.StateJson, theo, 4);
        Assert.Equal(maya, next.CurrentTurnUserId);
        Assert.Equal(2, View(next).GetProperty("last").GetProperty("move").GetInt32());
    }

    [Fact]
    public void A_ladder_climbs()
    {
        var view = View(Roll(State(0, 0), maya, 3));
        Assert.Equal([1, 2, 3], Steps(view));
        Assert.Equal([("ladder", 3, 22)], Events(view));
        Assert.Equal(22, At(view, maya));
        Assert.Equal(1, view.GetProperty("stats").GetProperty(maya.ToString()).GetProperty("ladders").GetInt32());
    }

    [Fact]
    public void A_snake_slides_you_down()
    {
        var view = View(Roll(State(15, 0), maya, 2));
        Assert.Equal([16, 17], Steps(view));
        Assert.Equal([("snake", 17, 4)], Events(view));
        Assert.Equal(4, At(view, maya));
        Assert.Equal(1, view.GetProperty("stats").GetProperty(maya.ToString()).GetProperty("snakes").GetInt32());
    }

    [Fact]
    public void A_hug_shield_cancels_your_next_snake_once()
    {
        // Land on the hug: the shield shows on your token.
        var hugged = Roll(State(4, 0), maya, 2);
        var view = View(hugged);
        Assert.Equal([("hug", 6, 6)], Events(view));
        Assert.True(view.GetProperty("shields").GetProperty(maya.ToString()).GetBoolean());
        Assert.Equal(6, At(view, maya));

        // The next snake doesn't bite; the shield is used up.
        var saved = View(Roll(State(15, 0, mayaShield: true), maya, 2));
        Assert.Equal([("shield", 17, 17)], Events(saved));
        Assert.Equal(17, At(saved, maya));
        Assert.False(saved.GetProperty("shields").GetProperty(maya.ToString()).GetBoolean());
        Assert.Equal(1, saved.GetProperty("stats").GetProperty(maya.ToString()).GetProperty("saves").GetInt32());

        // A shield doesn't stop a ladder from being a ladder.
        var climbed = View(Roll(State(1, 0, mayaShield: true), maya, 2));
        Assert.Equal([("ladder", 3, 22)], Events(climbed));
        Assert.True(climbed.GetProperty("shields").GetProperty(maya.ToString()).GetBoolean());
    }

    [Fact]
    public void A_love_boost_moves_three_more_and_a_heartbreak_five_back()
    {
        var boosted = View(Roll(State(7, 0), maya, 3));
        Assert.Equal([("boost", 10, 13)], Events(boosted));
        Assert.Equal(13, At(boosted, maya));

        var broken = View(Roll(State(27, 0), maya, 3));
        Assert.Equal([("heartbreak", 30, 25)], Events(broken));
        Assert.Equal(25, At(broken, maya));
    }

    [Fact]
    public void Kiss_and_swap_trades_places_only_with_a_partner_who_is_ahead()
    {
        var swapped = View(Roll(State(5, 40), maya, 3));
        Assert.Equal([("swap", 8, 40)], Events(swapped));
        Assert.Equal(40, At(swapped, maya));
        Assert.Equal(8, At(swapped, theo));
        Assert.Equal(1, swapped.GetProperty("stats").GetProperty(maya.ToString()).GetProperty("swaps").GetInt32());

        // Ahead (or level), it's just a kiss: nobody moves.
        var kissed = View(Roll(State(5, 2), maya, 3));
        Assert.Equal([("kiss", 8, 8)], Events(kissed));
        Assert.Equal(8, At(kissed, maya));
        Assert.Equal(2, At(kissed, theo));
    }

    [Fact]
    public void You_must_land_on_100_exactly_an_overshoot_bounces_back()
    {
        var bounced = Roll(State(97, 20), maya, 5);
        var view = View(bounced);
        Assert.Equal([98, 99, 100, 99, 98], Steps(view));
        Assert.Equal([("bounce", 100, 98)], Events(view));
        Assert.Equal(98, At(view, maya));
        Assert.False(bounced.Completed);
        Assert.Equal(theo, bounced.CurrentTurnUserId);

        // Bouncing back onto a snake's head still slides.
        var bitten = View(Roll(State(98, 20), maya, 3));
        Assert.Equal([99, 100, 99], Steps(bitten));
        Assert.Equal([("bounce", 100, 99), ("snake", 99, 80)], Events(bitten));
        Assert.Equal(80, At(bitten, maya));
    }

    [Fact]
    public void Landing_on_100_wins_even_with_a_six()
    {
        var won = Roll(State(94, 60), maya, 6);
        var view = View(won);
        Assert.True(won.Completed);
        Assert.Null(won.CurrentTurnUserId);
        Assert.Equal(100, At(view, maya));
        Assert.Equal([("win", 100, 100)], Events(view));
        Assert.False(view.GetProperty("last").GetProperty("extraTurn").GetBoolean());
        Assert.Equal("completed", view.GetProperty("phase").GetString());
        Assert.Equal(maya.ToString(), view.GetProperty("winner").GetString());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("turn").ValueKind);
        Assert.Throws<InvalidOperationException>(() => Roll(won.StateJson, theo, 3));

        var summary = new SnakesAndLaddersRules().Summarise(won.StateJson);
        Assert.Equal(100, summary.Scores[maya]);
        Assert.Equal(60, summary.Scores[theo]);
        Assert.Equal(1, summary.RoundsPlayed);
        Assert.Equal(maya, summary.DeclaredWinnerUserId);
    }

    [Fact]
    public void A_six_rolls_again_and_a_third_six_in_a_row_goes_back_to_where_the_turn_began()
    {
        var first = Roll(State(40, 0), maya, 6);
        var view = View(first);
        Assert.Equal(maya, first.CurrentTurnUserId);
        Assert.True(view.GetProperty("last").GetProperty("extraTurn").GetBoolean());
        Assert.Equal(1, view.GetProperty("last").GetProperty("sixes").GetInt32());
        Assert.Equal(46, At(view, maya));

        var second = Roll(first.StateJson, maya, 6);
        Assert.Equal(maya, second.CurrentTurnUserId);
        Assert.Equal(52, At(View(second), maya));
        Assert.Equal(2, View(second).GetProperty("sixes").GetInt32());

        var third = Roll(second.StateJson, maya, 6);
        var thirdView = View(third);
        Assert.Equal(theo, third.CurrentTurnUserId);
        Assert.Equal(40, At(thirdView, maya));
        Assert.Equal([("threeSixes", 52, 40)], Events(thirdView));
        Assert.Empty(Steps(thirdView));
        Assert.False(thirdView.GetProperty("last").GetProperty("extraTurn").GetBoolean());
        Assert.Equal(3, thirdView.GetProperty("last").GetProperty("sixes").GetInt32());
        Assert.Equal(0, thirdView.GetProperty("sixes").GetInt32());
        Assert.Equal(3, thirdView.GetProperty("stats").GetProperty(maya.ToString()).GetProperty("sixes").GetInt32());

        // A six, then anything else, ends the turn there and starts the count afresh.
        var sixThenTwo = Roll(Roll(State(40, 0), maya, 6).StateJson, maya, 2);
        Assert.Equal(theo, sixThenTwo.CurrentTurnUserId);
        Assert.Equal(48, At(View(sixThenTwo), maya));
        Assert.Equal(0, View(sixThenTwo).GetProperty("sixes").GetInt32());
    }

    [Fact]
    public void Only_the_player_whose_roll_it_is_may_roll_and_only_roll()
    {
        var script = new Script(3);
        var rules = new SnakesAndLaddersRules(script.Next);
        Assert.Throws<InvalidOperationException>(() => Roll(rules, State(0, 0, turn: maya), theo, start));
        Assert.Throws<InvalidOperationException>(() => Roll(rules, State(0, 0), Guid.NewGuid(), start));
        Assert.Throws<InvalidOperationException>(() => rules.Apply(new GameMove(State(0, 0), maya, "move", "100", 99, start)));
        // Nothing was rolled for the refused moves.
        Assert.Equal(1, script.Left);
    }

    [Fact]
    public void A_whole_game_plays_out_turn_by_turn_and_summarises()
    {
        var random = new Random(11);
        var rules = new SnakesAndLaddersRules(random.Next);
        var state = rules.Start([maya, theo], start, SnakesAndLaddersRules.CreateSetup(GameRegion.Global, 11));
        var moves = 0;
        while (!state.Completed && moves < 2_000)
        {
            var before = View(rules, state);
            var actor = state.CurrentTurnUserId!.Value;
            state = Roll(rules, state.StateJson, actor, start.AddMinutes(moves));
            moves++;
            var after = View(rules, state);
            var last = after.GetProperty("last");
            Assert.Equal(moves, last.GetProperty("move").GetInt32());
            Assert.Equal(At(before, actor), last.GetProperty("from").GetInt32());
            Assert.Equal(At(after, actor), last.GetProperty("to").GetInt32());
            Assert.Equal(last.GetProperty("roll").GetInt32() == 6 && !state.Completed && last.GetProperty("sixes").GetInt32() < 3, state.CurrentTurnUserId == actor);
        }
        Assert.True(state.Completed);
        var summary = rules.Summarise(state.StateJson);
        Assert.Equal(moves, summary.RoundsPlayed);
        Assert.Equal(100, summary.Scores[summary.DeclaredWinnerUserId!.Value]);
        var stats = View(rules, state).GetProperty("stats");
        Assert.Equal(moves, stats.GetProperty(maya.ToString()).GetProperty("rolls").GetInt32() + stats.GetProperty(theo.ToString()).GetProperty("rolls").GetInt32());
    }

    [Fact]
    public void Every_seed_draws_its_own_valid_varied_and_fair_board()
    {
        var boards = Enumerable.Range(0, 120).Select(SnakesAndLaddersRules.Layout).ToList();
        foreach (var (board, seed) in boards.Select((board, seed) => (board, seed)))
        {
            Assert.True(SnakesAndLaddersRules.Valid(board));
            Assert.InRange(board.Ladders.Count, 7, 9);
            Assert.InRange(board.Snakes.Count, 7, 9);
            Assert.Equal(7, board.Hearts.Count);
            Assert.Equal(2, board.Hearts.Count(heart => heart.Kind == SnakesAndLaddersRules.Swap));
            Assert.Equal(2, board.Hearts.Count(heart => heart.Kind == SnakesAndLaddersRules.Boost));
            Assert.Equal(2, board.Hearts.Count(heart => heart.Kind == SnakesAndLaddersRules.Shield));
            Assert.Equal(1, board.Hearts.Count(heart => heart.Kind == SnakesAndLaddersRules.Heartbreak));
            Assert.DoesNotContain(board.Ladders, ladder => ladder.From == 1);
            Assert.DoesNotContain(board.Snakes, snake => snake.From == 100);
            // Never chained: no square is both an end and a start, and no two things start on one square.
            var squares = board.Ladders.SelectMany(link => new[] { link.From, link.To }).Concat(board.Snakes.SelectMany(link => new[] { link.From, link.To }))
                .Concat(board.Hearts.Select(heart => heart.Square)).ToList();
            Assert.Equal(squares.Count, squares.Distinct().Count());
            Assert.All(squares, square => Assert.InRange(square, 2, 99));
            // Mixed lengths: a short and a long one of each.
            Assert.Contains(board.Ladders, ladder => SnakesAndLaddersRules.Row(ladder.To) - SnakesAndLaddersRules.Row(ladder.From) == 1);
            Assert.Contains(board.Ladders, ladder => SnakesAndLaddersRules.Row(ladder.To) - SnakesAndLaddersRules.Row(ladder.From) >= 3);
            Assert.Contains(board.Snakes, snake => SnakesAndLaddersRules.Row(snake.From) - SnakesAndLaddersRules.Row(snake.To) == 1);
            Assert.Contains(board.Snakes, snake => SnakesAndLaddersRules.Row(snake.From) - SnakesAndLaddersRules.Row(snake.To) >= 3);
            // The finish has a snake to dodge.
            Assert.Contains(board.Snakes, snake => snake.From > 90);
            // Fair: neither a sprint nor a slog, judged on fresh dice too.
            Assert.InRange(SnakesAndLaddersRules.AverageRolls(board, seed + 1_000_000), SnakesAndLaddersRules.MinAverageRolls - 4, SnakesAndLaddersRules.MaxAverageRolls + 4);
        }
        // The same seed always draws the same board; different seeds draw different ones.
        Assert.Equal(JsonSerializer.Serialize(SnakesAndLaddersRules.Layout(7)), JsonSerializer.Serialize(SnakesAndLaddersRules.Layout(7)));
        Assert.True(boards.Select(board => JsonSerializer.Serialize(board)).Distinct().Count() > 110);
        Assert.Equal(SnakesAndLaddersRules.CreateSetup("global", 3), SnakesAndLaddersRules.CreateSetup("global", 3));
        // The fallback board is fair too.
        Assert.True(SnakesAndLaddersRules.Valid(SnakesAndLaddersRules.Classic()));
        Assert.InRange(SnakesAndLaddersRules.AverageRolls(SnakesAndLaddersRules.Classic(), 1), SnakesAndLaddersRules.MinAverageRolls, SnakesAndLaddersRules.MaxAverageRolls);
    }

    [Fact]
    public void Going_first_is_no_big_advantage()
    {
        var random = new Random(2026);
        var firstWins = 0;
        const int games = 300;
        for (var game = 0; game < games; game++)
        {
            var board = SnakesAndLaddersRules.Layout(game);
            var rules = new SnakesAndLaddersRules(random.Next);
            var state = rules.Start([maya, theo], start, Setup(board, game));
            var first = state.CurrentTurnUserId!.Value;
            var moves = 0;
            while (!state.Completed && moves++ < 3_000) state = Roll(rules, state.StateJson, state.CurrentTurnUserId!.Value, start);
            Assert.True(state.Completed);
            if (rules.Summarise(state.StateJson).DeclaredWinnerUserId == first) firstWins++;
        }
        Assert.InRange(firstWins / (double)games, 0.42, 0.64);
    }

    [Fact]
    public void Valid_refuses_boards_that_chain_loop_or_break_the_rules()
    {
        SnakesAndLaddersRules.Board With(Action<SnakesAndLaddersRules.Board> change)
        {
            var board = TestBoard();
            change(board);
            return board;
        }
        Assert.True(SnakesAndLaddersRules.Valid(TestBoard()));
        // A ladder ending on a snake's head (a chain), or a snake ending at a ladder's foot (a loop).
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Ladders.Add(new() { From = 41, To = 62 }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Snakes.Add(new() { From = 73, To = 50 }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Snakes.Add(new() { From = 100, To = 70 }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Ladders.Add(new() { From = 1, To = 25 }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Ladders.Add(new() { From = 70, To = 100 }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Ladders.Add(new() { From = 17, To = 40 }))));
        // A ladder must go up a row at least, a snake down one.
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Ladders.Add(new() { From = 41, To = 44 }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Snakes.Add(new() { From = 77, To = 72 }))));
        // A boost onto a snake's head, a heart on a ladder, an unknown heart.
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Hearts.Add(new() { Square = 59, Kind = SnakesAndLaddersRules.Boost }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Hearts.Add(new() { Square = 3, Kind = SnakesAndLaddersRules.Swap }))));
        Assert.False(SnakesAndLaddersRules.Valid(With(board => board.Hearts.Add(new() { Square = 70, Kind = "teleport" }))));
    }

    [Fact]
    public void The_setup_board_is_used_and_a_bad_setup_still_starts_on_a_good_board()
    {
        var fromSetup = View(new SnakesAndLaddersRules(new Script(0).Next).Start([maya, theo], start, Setup()));
        Assert.Equal(3, fromSetup.GetProperty("board").GetProperty("snakes").GetArrayLength());

        var seeded = SnakesAndLaddersRules.Layout(9);
        var broken = JsonSerializer.Serialize(new { region = "global", seed = 9, board = new { ladders = new[] { new { from = 1, to = 100 } } } }, Web);
        var fromSeed = View(new SnakesAndLaddersRules(new Script(0).Next).Start([maya, theo], start, broken));
        Assert.Equal(seeded.Ladders.Count, fromSeed.GetProperty("board").GetProperty("ladders").GetArrayLength());
        Assert.Equal(seeded.Ladders[0].From, fromSeed.GetProperty("board").GetProperty("ladders")[0].GetProperty("from").GetInt32());

        foreach (var setup in new[] { null, "", "not json", "{\"region\":\"global\"}" })
        {
            var started = new SnakesAndLaddersRules(new Script(1).Next).Start([maya, theo], start, setup);
            Assert.Equal(theo, started.CurrentTurnUserId);
            Assert.True(View(started).GetProperty("board").GetProperty("ladders").GetArrayLength() >= 7);
        }
    }

    [Fact]
    public void Old_or_unknown_state_json_reads_and_bad_json_is_refused_cleanly()
    {
        var engine = new GameEngine();
        // Not started yet.
        Assert.Equal("{}", engine.ToPublicState(GameType.SnakesAndLadders, "{}", maya));
        Assert.Equal(0, engine.Summarise(GameType.SnakesAndLadders, "{}").RoundsPlayed);

        // A bare state with unknown fields and nothing but the players: plays on, on the fallback board, from 0.
        var bare = JsonSerializer.Serialize(new { Players = new[] { maya, theo }, Turn = maya, Mystery = "future field", Weather = new { rain = true } });
        var rolled = Roll(bare, maya, 2);
        var view = View(rolled);
        Assert.Equal(2, At(view, maya));
        Assert.Equal(0, At(view, theo));
        Assert.Equal(theo, rolled.CurrentTurnUserId);
        Assert.NotNull(engine.Summarise(GameType.SnakesAndLadders, bare));

        Assert.Throws<InvalidOperationException>(() => Roll("null", maya, 2));
        Assert.Throws<InvalidOperationException>(() => Roll("{\"Players\":[]}", maya, 2));
        Assert.Throws<JsonException>(() => Roll("{not json", maya, 2));
        Assert.Equal(0, engine.Summarise(GameType.SnakesAndLadders, "{not json").RoundsPlayed);
    }

    [Fact]
    public void Nothing_is_secret_both_partners_see_the_same_state()
    {
        var rules = new SnakesAndLaddersRules(new Script(0, 4).Next);
        var started = rules.Start([maya, theo], start, Setup());
        var rolled = Roll(rules, started.StateJson, maya, start);
        Assert.Equal(rules.ToPublicState(rolled.StateJson, maya), rules.ToPublicState(rolled.StateJson, theo));
        Assert.Equal(rules.ToPublicState(rolled.StateJson, maya), rules.ToPublicState(rolled.StateJson, null));
    }
}
