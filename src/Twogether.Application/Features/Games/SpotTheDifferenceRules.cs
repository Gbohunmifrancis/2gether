using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Spot the difference (docs/games.md): three pictures, seven differences each, both partners looking at the same
/// picture at the same time. The phones draw the pictures from the scene's kind and seed (the app's
/// <c>SpotScenes</c>), so both see the same two pictures and the same differences. Whoever spots a difference first
/// sends <c>spot</c> with its <c>index</c> and gets the point; a later claim of the same one is refused. A player's
/// spots must be at least <see cref="MinSpotGap"/> apart (server time), so tapping everywhere doesn't pay. A picture
/// ends when all seven are found or after <see cref="SceneSeconds"/>, when the rest are revealed; a short beat later
/// (<see cref="BeatSeconds"/>) the next picture starts. After the last one, more spots wins; equal is a tie.
/// </summary>
public sealed class SpotTheDifferenceRules : IGameRules
{
    public const int Scenes = 3;
    public const int Differences = 7;
    public const int SceneSeconds = 90;
    public const int BeatSeconds = 3;
    public static readonly TimeSpan MinSpotGap = TimeSpan.FromMilliseconds(300);

    private const string SpottingPhase = "spotting";
    private const string BetweenPhase = "between";
    private const string CompletedPhase = "completed";

    /// <summary>The kinds of picture, by the app's <c>SceneKind</c> names: they travel as is.</summary>
    public static readonly IReadOnlyList<string> Kinds = ["Picnic", "Bedroom", "Kitchen", "Beach", "Garden", "NightSky", "Cafe", "LivingRoom"];

    private static readonly JsonSerializerOptions SetupJson = new(JsonSerializerDefaults.Web);
    private static readonly JsonSerializerOptions PublicJson = new(JsonSerializerDefaults.Web);

    public GameType Type => GameType.SpotTheDifference;

    public bool AnyoneMayAct => true;

    public bool Timed => true;

    /// <summary>The setup for a new game: its region, a seed, and the three pictures that seed draws.</summary>
    public static string CreateSetup(string region, int? seed = null)
    {
        var gameSeed = seed ?? Random.Shared.Next();
        return JsonSerializer.Serialize(new { region, seed = gameSeed, scenes = Plan(gameSeed) }, SetupJson);
    }

    /// <summary>Three pictures from a seed: three different kinds in a shuffled order, each with its own seed.</summary>
    public static List<Scene> Plan(int seed)
    {
        var random = new Random(seed);
        return Kinds.OrderBy(_ => random.Next()).Take(Scenes).Select(kind => new Scene { Kind = kind, Seed = random.Next() }).ToList();
    }

    public GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson)
    {
        var state = new State
        {
            Players = players.ToList(),
            Scores = players.ToDictionary(player => player, _ => 0),
            Scenes = SetupScenes(setupJson) ?? Plan(Random.Shared.Next()),
            DeadlineUtc = utcNow.AddSeconds(SceneSeconds),
        };
        return Result(state);
    }

    public GameEngineResult Apply(GameMove move)
    {
        var state = Read(move.StateJson);
        if (state.Phase == CompletedPhase) throw new InvalidOperationException(Loc.T("This game is over."));
        if (string.Equals(move.Action, GameEngine.TimeoutAction, StringComparison.OrdinalIgnoreCase)) return TimeOut(state, move.UtcNow);
        if (!string.Equals(move.Action, "spot", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(Loc.T("Spot the difference expects a spot."));
        if (!state.Players.Contains(move.Actor)) throw new InvalidOperationException(Loc.T("You are not a player in this game."));
        if (state.Phase == BetweenPhase) throw new InvalidOperationException(Loc.T("The next picture is on its way."));
        if (move.Index is not { } index || index < 0 || index >= Differences)
            throw new InvalidOperationException(Loc.T("That isn't one of the differences."));
        var scene = state.Scenes[state.Scene];
        if (scene.Found.FirstOrDefault(find => find.Index == index) is { } found)
            throw new InvalidOperationException(found.By == move.Actor ? Loc.T("You already spotted that one.") : Loc.T("Your partner spotted that one first."));
        if (state.LastSpotUtc.TryGetValue(move.Actor, out var last) && move.UtcNow - last < MinSpotGap)
            throw new InvalidOperationException(Loc.T("Not so fast: one spot at a time."));

        scene.Found.Add(new Find { Index = index, By = move.Actor, AtUtc = move.UtcNow });
        state.Scores[move.Actor] = state.Scores.GetValueOrDefault(move.Actor) + 1;
        state.LastSpotUtc[move.Actor] = move.UtcNow;
        if (scene.Found.Count >= Differences) EndScene(state, move.UtcNow);
        return Result(state);
    }

    public string ToPublicState(string stateJson, Guid? viewer)
    {
        var state = Read(stateJson);
        var completed = state.Phase == CompletedPhase;
        var current = state.Scenes.Count == 0 ? null : state.Scenes[Math.Min(state.Scene, state.Scenes.Count - 1)];
        var winner = completed ? Winner(state) : null;
        return JsonSerializer.Serialize(new
        {
            state.Players,
            state.Phase,
            scene = state.Scene,
            totalScenes = state.Scenes.Count,
            differences = Differences,
            sceneSeconds = SceneSeconds,
            beatSeconds = BeatSeconds,
            kind = current?.Kind,
            seed = current?.Seed,
            found = (current?.Found ?? []).Select(find => new { find.Index, find.By }),
            // The pictures so far (not the ones still to come), each with who found what.
            scenes = state.Scenes.Take(state.Scene + 1).Select((scene, index) => new
            {
                index,
                scene.Kind,
                scene.Seed,
                found = scene.Found.Select(find => new { find.Index, find.By }),
                scene.TimedOut,
            }),
            state.Scores,
            winner,
            tie = completed && winner is null,
        }, PublicJson);
    }

    /// <summary>Each player's spots, the pictures finished, and the winner of a finished game (none for a tie).</summary>
    public GameScoreSummary Summarise(string stateJson)
    {
        var state = Read(stateJson);
        var completed = state.Phase == CompletedPhase;
        var played = completed ? state.Scenes.Count : state.Phase == BetweenPhase ? state.Scene + 1 : state.Scene;
        return new GameScoreSummary(state.Scores, played, completed ? Winner(state) : null);
    }

    /// <summary>
    /// The clock: a picture that runs out reveals what's left and ends (the next one follows after the beat); the
    /// beat between pictures ends with the next picture.
    /// </summary>
    private static GameEngineResult TimeOut(State state, DateTime utcNow)
    {
        if (state.Phase == BetweenPhase)
        {
            state.Scene++;
            state.Phase = SpottingPhase;
            state.DeadlineUtc = utcNow.AddSeconds(SceneSeconds);
            return Result(state);
        }
        state.Scenes[state.Scene].TimedOut = true;
        EndScene(state, utcNow);
        return Result(state);
    }

    /// <summary>A picture is done: the game ends after the last one, otherwise a short beat before the next.</summary>
    private static void EndScene(State state, DateTime utcNow)
    {
        if (state.Scene >= state.Scenes.Count - 1)
        {
            state.Phase = CompletedPhase;
            state.DeadlineUtc = null;
            return;
        }
        state.Phase = BetweenPhase;
        state.DeadlineUtc = utcNow.AddSeconds(BeatSeconds);
    }

    /// <summary>Both may spot at any time: the turn is only a name the session needs, so it stays with the first player.</summary>
    private static GameEngineResult Result(State state)
    {
        var completed = state.Phase == CompletedPhase;
        return new GameEngineResult(JsonSerializer.Serialize(state), completed ? null : state.Players[0], completed ? null : state.DeadlineUtc, completed);
    }

    private static Guid? Winner(State state)
    {
        if (state.Scores.Count == 0) return null;
        var best = state.Scores.Values.Max();
        var leaders = state.Scores.Where(item => item.Value == best).Select(item => item.Key).ToList();
        return leaders.Count == 1 ? leaders[0] : null;
    }

    private static List<Scene>? SetupScenes(string? setupJson)
    {
        if (string.IsNullOrWhiteSpace(setupJson)) return null;
        try
        {
            var scenes = JsonSerializer.Deserialize<Setup>(setupJson, SetupJson)?.Scenes?.Where(scene => Kinds.Contains(scene.Kind)).ToList();
            return scenes is { Count: Scenes } ? scenes : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static State Read(string json) => JsonSerializer.Deserialize<State>(json) ?? throw new InvalidOperationException(Loc.T("This game session is corrupted. End it and start a new game."));

    private sealed class Setup
    {
        public List<Scene>? Scenes { get; set; }
    }

    /// <summary>One picture: its kind and the seed both phones draw it from, and who has found which difference.</summary>
    public sealed class Scene
    {
        public string Kind { get; set; } = string.Empty;
        public int Seed { get; set; }
        public List<Find> Found { get; set; } = [];
        /// <summary>The clock ran out on this picture: what nobody found is shown.</summary>
        public bool TimedOut { get; set; }
    }

    public sealed class Find
    {
        public int Index { get; set; }
        public Guid By { get; set; }
        public DateTime AtUtc { get; set; }
    }

    private sealed class State
    {
        public List<Guid> Players { get; set; } = [];
        public Dictionary<Guid, int> Scores { get; set; } = [];
        public List<Scene> Scenes { get; set; } = [];
        /// <summary>The picture being played, from 0.</summary>
        public int Scene { get; set; }
        public string Phase { get; set; } = SpottingPhase;
        /// <summary>When this picture runs out, or, between pictures, when the next one starts.</summary>
        public DateTime? DeadlineUtc { get; set; }
        /// <summary>Each player's last spot, for the gap between spots.</summary>
        public Dictionary<Guid, DateTime> LastSpotUtc { get; set; } = [];
    }
}
