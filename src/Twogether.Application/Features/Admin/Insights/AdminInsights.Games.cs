using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Insights;

public sealed partial class AdminInsights
{
    /// <summary>
    /// Games begun in the window, by type: how many were finished or abandoned, and the median length of a finished
    /// one. Skills are the Heart to heart rounds of duels begun in the window that weren't abandoned (finished or still
    /// going): each score played counts as a round. Duels are the finished ones: ties (level on hearts and on points) and
    /// the median gap in hearts.
    /// </summary>
    public async Task<GameInsightsDto> GamesAsync(int days, CancellationToken ct)
    {
        days = InsightsMath.ClampDays(days);
        var now = clock.UtcNow;
        var fromUtc = InsightsMath.WindowStart(now, days);
        var coupleIds = measured.CoupleIds;
        var sessions = db.GameSessions.Where(item => item.CreatedAtUtc >= fromUtc && coupleIds.Contains(item.CoupleId));

        var byType = await sessions.GroupBy(item => item.GameType).Select(group => new
        {
            group.Key,
            Started = group.Count(),
            Finished = group.Count(item => item.Status == GameSessionStatus.Completed),
            Abandoned = group.Count(item => item.Status == GameSessionStatus.Abandoned),
        }).ToListAsync(ct);
        var lengths = (await sessions.Where(item => item.Status == GameSessionStatus.Completed && item.StartedAtUtc != null && item.EndedAtUtc != null)
                .OrderByDescending(item => item.CreatedAtUtc).Take(MedianSample)
                .Select(item => new { item.GameType, StartedAtUtc = item.StartedAtUtc!.Value, EndedAtUtc = item.EndedAtUtc!.Value })
                .ToListAsync(ct))
            .ToLookup(item => item.GameType, item => Math.Max(0, (item.EndedAtUtc - item.StartedAtUtc).TotalMinutes));
        var types = byType.OrderByDescending(item => item.Started)
            .Select(item => new GameTypeDto(InsightsMath.Camel(item.Key.ToString()), item.Started, item.Finished, item.Abandoned, InsightsMath.Round(InsightsMath.Median(lengths[item.Key]))))
            .ToList();

        // Scores only: a duel's state holds the players, the rounds' kinds and seeds, and the points.
        var duels = await sessions.Where(item => item.GameType == GameType.SkillDuel && item.Status != GameSessionStatus.Abandoned && item.Status != GameSessionStatus.WaitingForPartner)
            .OrderByDescending(item => item.CreatedAtUtc).Take(MedianSample)
            .Select(item => new { item.Status, item.StateJson })
            .ToListAsync(ct);
        var scores = SkillDuelRules.Kinds.ToDictionary(kind => kind, _ => new List<int>());
        var margins = new List<double>();
        var finished = 0;
        var ties = 0;
        foreach (var duel in duels)
        {
            SkillDuelTally tally;
            try
            {
                tally = SkillDuelRules.Tally(duel.StateJson);
            }
            catch (Exception exception) when (exception is JsonException or InvalidOperationException)
            {
                continue; // A corrupted state says nothing; the game itself tells its players.
            }
            foreach (var (kind, score) in tally.Scores)
                if (scores.TryGetValue(kind, out var list)) list.Add(score);
            if (duel.Status != GameSessionStatus.Completed) continue;
            finished++;
            if (tally.Tied) ties++;
            if (tally.Margin is { } margin) margins.Add(margin);
        }
        var skills = scores.Select(item =>
        {
            var buckets = new int[10];
            foreach (var score in item.Value) buckets[InsightsMath.ScoreBucket(score)]++;
            return new SkillDto(item.Key, item.Value.Count, item.Value.Count == 0 ? null : Math.Round(item.Value.Average(), 1), buckets);
        }).ToList();
        return new GameInsightsDto(days, types, skills, new DuelDto(finished, ties, InsightsMath.Round(InsightsMath.Median(margins))));
    }
}
