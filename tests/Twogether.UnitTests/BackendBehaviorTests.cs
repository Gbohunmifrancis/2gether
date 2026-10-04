using System.Text.Json;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Infrastructure.Security;

namespace Twogether.UnitTests;

public sealed class BackendBehaviorTests
{
    [Fact]
    public void PasswordHasher_round_trips_and_rejects_wrong_password()
    {
        var hasher = new PasswordHasher();
        var hash = hasher.Hash("correct horse battery staple");

        Assert.True(hasher.Verify("correct horse battery staple", hash));
        Assert.False(hasher.Verify("wrong password", hash));
        Assert.NotEqual(hash, hasher.Hash("correct horse battery staple"));
    }

    [Fact]
    public void Quiz_public_state_does_not_expose_answers()
    {
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var engine = new GameEngine();
        var started = engine.Start(GameType.CoupleQuiz, [first, second], DateTime.UtcNow);

        var publicState = engine.ToPublicState(GameType.CoupleQuiz, started.StateJson);

        Assert.DoesNotContain("Answers", publicState, StringComparison.Ordinal);
        Assert.Contains("answerCount", publicState, StringComparison.Ordinal);
    }

    [Fact]
    public void Quiz_requires_current_turn_and_advances_after_each_answer()
    {
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var engine = new GameEngine();
        var now = DateTime.UtcNow;
        var started = engine.Start(GameType.CoupleQuiz, [first, second], now);

        Assert.Throws<InvalidOperationException>(() => engine.Apply(GameType.CoupleQuiz, started.StateJson, second, "answer", "no", null, now));
        var next = engine.Apply(GameType.CoupleQuiz, started.StateJson, first, "answer", "yes", null, now);

        Assert.Equal(second, next.CurrentTurnUserId);
        Assert.False(next.Completed);
        Assert.Contains("answerCount", engine.ToPublicState(GameType.CoupleQuiz, next.StateJson), StringComparison.Ordinal);
    }

    [Fact]
    public void Memory_public_state_masks_unrevealed_cards()
    {
        var engine = new GameEngine();
        var started = engine.Start(GameType.MemoryMatch, [Guid.NewGuid(), Guid.NewGuid()], DateTime.UtcNow);
        using var state = JsonDocument.Parse(engine.ToPublicState(GameType.MemoryMatch, started.StateJson));

        var cards = state.RootElement.GetProperty("cards");
        Assert.Equal(12, cards.GetArrayLength());
        Assert.All(cards.EnumerateArray(), card => Assert.Equal(JsonValueKind.Null, card.GetProperty("value").ValueKind));
    }

    [Fact]
    public void Game_session_rejects_double_start_and_tracks_terminal_state()
    {
        var now = DateTime.UtcNow;
        var session = GameSession.Create(Guid.NewGuid(), GameType.CoupleQuiz, Guid.NewGuid(), now);
        session.Start(Guid.NewGuid(), now.AddMinutes(2), "{}", now);

        Assert.Throws<InvalidOperationException>(() => session.Start(Guid.NewGuid(), now.AddMinutes(2), "{}", now));
        session.Complete("{\"done\":true}", now.AddMinutes(1));

        Assert.Equal(GameSessionStatus.Completed, session.Status);
        Assert.Equal(2, session.StateVersion);
        Assert.Null(session.CurrentTurnUserId);
    }

    [Fact]
    public void Game_invitation_tracks_countdown_and_who_ended_it()
    {
        var now = DateTime.UtcNow;
        var inviter = Guid.NewGuid();
        var endingPartner = Guid.NewGuid();
        var session = GameSession.Create(Guid.NewGuid(), GameType.GuessMyAnswer, inviter, now);

        session.AcceptInvite(now.AddSeconds(5), now.AddSeconds(1));
        session.Start(endingPartner, now.AddMinutes(2), "{}", now.AddSeconds(1));
        session.EndBy(endingPartner, now.AddSeconds(3));

        Assert.Equal(inviter, session.InvitedByUserId);
        Assert.Equal(now.AddSeconds(1), session.InviteAcceptedAtUtc);
        Assert.Equal(now.AddSeconds(5), session.CountdownEndsAtUtc);
        Assert.Equal(endingPartner, session.EndedByUserId);
        Assert.Equal(GameSessionStatus.Abandoned, session.Status);
    }

    [Fact]
    public void On_game_waits_for_both_private_submissions_before_scoring()
    {
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var engine = new GameEngine();
        var now = DateTime.UtcNow;
        var started = engine.Start(GameType.ICallOn, [first, second], now);
        const string answers = "{\"Name\":\"Alice\",\"Place\":\"Lagos\",\"Thing\":\"Lamp\",\"Food\":\"Apple\"}";

        var waiting = engine.Apply(GameType.ICallOn, started.StateJson, first, "submit", answers, null, now);
        using (var waitingState = JsonDocument.Parse(engine.ToPublicState(GameType.ICallOn, waiting.StateJson)))
            Assert.Single(waitingState.RootElement.GetProperty("submitted").EnumerateArray());

        var completedRound = engine.Apply(GameType.ICallOn, waiting.StateJson, second, "submit", answers, null, now);
        using var publicState = JsonDocument.Parse(engine.ToPublicState(GameType.ICallOn, completedRound.StateJson));
        Assert.Equal(1, publicState.RootElement.GetProperty("round").GetInt32());
        Assert.Empty(publicState.RootElement.GetProperty("submitted").EnumerateArray());
    }

    [Fact]
    public void Private_messages_and_location_sharing_preserve_privacy_state()
    {
        var now = DateTime.UtcNow;
        var coupleId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var message = Message.Create(coupleId, userId, "just us", true, now);
        var location = CoupleLocation.Create(coupleId, userId, 6.5244, 3.3792, 12, now);

        location.StopSharing(now.AddMinutes(1));

        Assert.True(message.IsPrivate);
        Assert.False(location.SharingEnabled);
    }
}
