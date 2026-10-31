namespace Twogether.Application.Features.Billing;

/// <summary>
/// The plan's cap on active daily dares, per partner (Free: one each). Pure, so the rules are tested on their own.
/// </summary>
public static class DareLimits
{
    /// <summary>Whether one more active dare fits for someone who already has <paramref name="active"/>.</summary>
    public static bool Fits(int? limit, int active) => limit is not { } max || active < max;

    /// <summary>
    /// Over the cap (Plus ended with several dares running): the oldest keep ringing, the rest are paused until Plus
    /// comes back or the person picks which to resume.
    /// </summary>
    public static IReadOnlyList<Guid> ToPause(int? limit, IEnumerable<(Guid Id, DateTime CreatedAtUtc)> active)
        => limit is not { } max ? [] : active.OrderBy(item => item.CreatedAtUtc).ThenBy(item => item.Id).Skip(max).Select(item => item.Id).ToList();
}
