namespace Twogether.Core.Enums;

/// <summary>How a game in the history ended (docs/games.md). Stored by name, so values may be added.</summary>
public enum GameOutcome
{
    /// <summary>Played to the end by the rules.</summary>
    Completed,
    EndedByAgreement,
    EndRejected,
    Forfeited,
    PartnerLeft,
    /// <summary>Left alone for a week: the server ended it. No winner.</summary>
    TimedOut,
    /// <summary>Ended part way by a partner (or by the couple ending), or its state couldn't be read. No winner.</summary>
    Abandoned
}
