using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

/// <summary>Moves get the same dictionary checks whether they come over REST or the game hub.</summary>
public sealed class GameWordChecksTests
{
    private sealed class Dictionary(bool? isWord) : IGameWordValidator
    {
        public List<string> Asked { get; } = [];

        public Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(string stateJson, string answersJson, CancellationToken cancellationToken = default)
        {
            Asked.Add(answersJson);
            return Task.FromResult<IReadOnlyDictionary<string, bool?>>(new Dictionary<string, bool?> { ["Name"] = true });
        }

        public Task<bool?> IsWordAsync(string word, CancellationToken cancellationToken = default)
        {
            Asked.Add(word);
            return Task.FromResult(isWord);
        }
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    [InlineData(null)]
    public async Task A_word_dash_play_carries_the_dictionary_verdict(bool? isWord)
    {
        var dictionary = new Dictionary(isWord);
        var checks = await dictionary.ForMoveAsync(GameType.WordDash, "{}", "play", "mango", CancellationToken.None);

        Assert.Equal(["mango"], dictionary.Asked);
        Assert.Equal(isWord, checks![WordDashRules.WordKey]);
    }

    [Fact]
    public async Task I_call_on_answers_are_checked_on_submit()
    {
        var dictionary = new Dictionary(true);
        var checks = await dictionary.ForMoveAsync(GameType.ICallOn, "{}", "submit", """{"Name":"Ada"}""", CancellationToken.None);

        Assert.True(checks!["Name"]);
        Assert.Single(dictionary.Asked);
    }

    [Theory]
    [InlineData(GameType.WordDash, "timeout", "mango")]
    [InlineData(GameType.ICallOn, "vet", "{}")]
    [InlineData(GameType.Trivia, "answer", "1")]
    [InlineData(GameType.WordDash, "play", null)]
    public async Task Other_moves_ask_nothing(GameType gameType, string action, string? value)
    {
        var dictionary = new Dictionary(true);

        Assert.Null(await dictionary.ForMoveAsync(gameType, "{}", action, value, CancellationToken.None));
        Assert.Empty(dictionary.Asked);
    }

    /// <summary>
    /// End to end through the rules: a word the dictionary refuses is turned down, and one it can't judge stands. The
    /// starting letter is random, so the word is built from it.
    /// </summary>
    [Theory]
    [InlineData(false, false)]
    [InlineData(null, true)]
    public async Task The_verdict_decides_whether_the_word_stands(bool? isWord, bool stands)
    {
        var engine = new GameEngine();
        var (maya, theo) = (Guid.NewGuid(), Guid.NewGuid());
        var started = engine.Start(GameType.WordDash, [maya, theo], DateTime.UtcNow, """{"region":"global"}""");
        var letter = System.Text.Json.JsonDocument.Parse(engine.ToPublicState(GameType.WordDash, started.StateJson, maya)).RootElement.GetProperty("letter").GetString()!;
        var word = letter.ToLowerInvariant() + "zzqx";
        var checks = await new Dictionary(isWord).ForMoveAsync(GameType.WordDash, started.StateJson, "play", word, CancellationToken.None);

        var play = () => engine.Apply(GameType.WordDash, started.StateJson, started.CurrentTurnUserId!.Value, "play", word, null, DateTime.UtcNow, checks);
        if (stands) Assert.NotEqual(started.StateJson, play().StateJson);
        else Assert.Throws<InvalidOperationException>(play);
    }
}
