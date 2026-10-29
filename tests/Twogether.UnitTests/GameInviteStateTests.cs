using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

/// <summary>
/// A game that's invited but not yet started has the empty state "{}". Every game must show it (and summarise it)
/// without throwing, or the invite, the couple's game list and abandoning the invite all fail.
/// </summary>
public sealed class GameInviteStateTests
{
    private readonly GameEngine engine = new();

    public static TheoryData<GameType> Types()
    {
        var data = new TheoryData<GameType>();
        foreach (var type in Enum.GetValues<GameType>()) data.Add(type);
        return data;
    }

    [Theory]
    [MemberData(nameof(Types))]
    public void An_invite_that_has_not_started_shows_and_summarises(GameType type)
    {
        Assert.NotNull(engine.ToPublicState(type, "{}", Guid.NewGuid()));
        Assert.NotNull(engine.ToPublicState(type, "{}", null));
        Assert.NotNull(engine.Summarise(type, "{}"));
    }
}
