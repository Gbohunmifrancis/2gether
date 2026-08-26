using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

public sealed class GameSession : BaseEntity
{
    private GameSession() { }

    public Guid CoupleId { get; private set; }
    public GameType GameType { get; private set; }
    public GameSessionStatus Status { get; private set; } = GameSessionStatus.WaitingForPartner;
    public DateTime? StartedAtUtc { get; private set; }
    public DateTime? EndedAtUtc { get; private set; }
    public Guid? CurrentTurnUserId { get; private set; }
    public DateTime? DeadlineUtc { get; private set; }
    public Guid? InvitedByUserId { get; private set; }
    public DateTime? InviteAcceptedAtUtc { get; private set; }
    public DateTime? CountdownEndsAtUtc { get; private set; }
    public Guid? EndedByUserId { get; private set; }
    public Guid? EndRequestedByUserId { get; private set; }
    public DateTime? EndRequestedAtUtc { get; private set; }
    public long StateVersion { get; private set; }
    public string StateJson { get; private set; } = "{}";
    public string SetupJson { get; private set; } = "{}";

    public static GameSession Create(Guid coupleId, GameType gameType, Guid invitedByUserId, DateTime utcNow, string? setupJson = null) => new()
    {
        CoupleId = coupleId,
        GameType = gameType,
        InvitedByUserId = invitedByUserId,
        SetupJson = setupJson ?? "{}",
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void AcceptInvite(DateTime countdownEndsAtUtc, DateTime utcNow)
    {
        if (Status != GameSessionStatus.WaitingForPartner) throw new InvalidOperationException("Only waiting games can be accepted.");
        InviteAcceptedAtUtc = utcNow;
        CountdownEndsAtUtc = countdownEndsAtUtc;
        UpdatedAtUtc = utcNow;
    }

    public void Start(Guid currentTurnUserId, DateTime? deadlineUtc, string stateJson, DateTime utcNow)
    {
        if (Status != GameSessionStatus.WaitingForPartner) throw new InvalidOperationException("Only waiting games can be started.");
        Status = GameSessionStatus.InProgress;
        StartedAtUtc ??= utcNow;
        CurrentTurnUserId = currentTurnUserId;
        DeadlineUtc = deadlineUtc;
        StateJson = stateJson;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    public void UpdateState(Guid currentTurnUserId, DateTime? deadlineUtc, string stateJson, DateTime utcNow)
    {
        if (Status != GameSessionStatus.InProgress) throw new InvalidOperationException("Only active games can be updated.");
        CurrentTurnUserId = currentTurnUserId;
        DeadlineUtc = deadlineUtc;
        StateJson = stateJson;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    public void Complete(string stateJson, DateTime utcNow)
    {
        if (Status != GameSessionStatus.InProgress) throw new InvalidOperationException("Only active games can be completed.");
        Status = GameSessionStatus.Completed;
        EndedAtUtc = utcNow;
        CurrentTurnUserId = null;
        DeadlineUtc = null;
        StateJson = stateJson;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    public void Abandon(DateTime utcNow)
    {
        if (Status is not (GameSessionStatus.WaitingForPartner or GameSessionStatus.InProgress))
            throw new InvalidOperationException("Only waiting or active games can be abandoned.");
        Status = GameSessionStatus.Abandoned;
        EndedAtUtc = utcNow;
        CurrentTurnUserId = null;
        DeadlineUtc = null;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    public void EndBy(Guid userId, DateTime utcNow)
    {
        Abandon(utcNow);
        EndedByUserId = userId;
    }

    /// <summary>
    /// Asks the partner to agree to end the game. Paused freezes play, and because the
    /// timeout worker only scans InProgress sessions it freezes the round timer too.
    /// </summary>
    public void RequestEnd(Guid userId, DateTime utcNow)
    {
        if (Status != GameSessionStatus.InProgress) throw new InvalidOperationException("Only an active game can be asked to end.");
        Status = GameSessionStatus.Paused;
        EndRequestedByUserId = userId;
        EndRequestedAtUtc = utcNow;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>The partner agreed: the game ends and the score decides the winner.</summary>
    public void AcceptEnd(Guid endedByUserId, DateTime utcNow)
    {
        if (Status != GameSessionStatus.Paused || !EndRequestedByUserId.HasValue)
            throw new InvalidOperationException("No end request is waiting for an answer.");
        Status = GameSessionStatus.Completed;
        EndedAtUtc = utcNow;
        EndedByUserId = endedByUserId;
        CurrentTurnUserId = null;
        DeadlineUtc = null;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>
    /// The partner refused: the game still ends, but whoever asked to leave loses it.
    /// </summary>
    public void RejectEnd(DateTime utcNow)
    {
        if (Status != GameSessionStatus.Paused || !EndRequestedByUserId.HasValue)
            throw new InvalidOperationException("No end request is waiting for an answer.");
        Status = GameSessionStatus.Completed;
        EndedAtUtc = utcNow;
        EndedByUserId = EndRequestedByUserId;
        CurrentTurnUserId = null;
        DeadlineUtc = null;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>
    /// Forfeit decided by the server (a partner walked out of a live game). The winner is
    /// recorded on the GameResult; the session only needs to know who caused the end.
    /// </summary>
    public void Forfeit(Guid forfeitedByUserId, DateTime utcNow)
    {
        if (Status is not (GameSessionStatus.InProgress or GameSessionStatus.Paused))
            throw new InvalidOperationException("Only a live game can be forfeited.");
        Status = GameSessionStatus.Completed;
        EndedAtUtc = utcNow;
        EndedByUserId = forfeitedByUserId;
        CurrentTurnUserId = null;
        DeadlineUtc = null;
        StateVersion++;
        UpdatedAtUtc = utcNow;
    }
}
