using System.Collections.Concurrent;

namespace Twogether.Api.Presence;

/// <summary>
/// In-flight, never-persisted state for a live game: what each player has typed but not
/// yet submitted. A 15-second round makes durability pointless, and keystrokes have no
/// business in the database — but the server does need the draft, because the first player
/// to submit commits their partner's answers too, and the timeout worker submits for a
/// player whose browser has gone quiet.
/// </summary>
public sealed class GameLiveState
{
    private readonly ConcurrentDictionary<(Guid SessionId, Guid UserId), Draft> _drafts = new();

    public void RecordDraft(Guid sessionId, Guid userId, int round, string answersJson)
        => _drafts[(sessionId, userId)] = new Draft(round, answersJson);

    /// <summary>
    /// Drafts belonging to the round in play. Keyed by round so a stale draft from an
    /// earlier round is never submitted against a fresh letter.
    /// </summary>
    public IReadOnlyDictionary<Guid, string> Drafts(Guid sessionId, int round)
        => _drafts
            .Where(item => item.Key.SessionId == sessionId && item.Value.Round == round)
            .ToDictionary(item => item.Key.UserId, item => item.Value.AnswersJson);

    public void ClearSession(Guid sessionId)
    {
        foreach (var key in _drafts.Keys.Where(key => key.SessionId == sessionId).ToList())
            _drafts.TryRemove(key, out _);
    }

    private sealed record Draft(int Round, string AnswersJson);
}
