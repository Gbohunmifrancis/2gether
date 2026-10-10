using System.Security.Cryptography;
using System.Text;

namespace Twogether.Core.Questions;

public static class DailyQuestionPicker
{
    /// <summary>
    /// The next question for a couple. Each couple gets its own stable order (a hash of couple id
    /// and key), so adding questions to the bank never reshuffles what came before. Unused
    /// questions come first; once all are used, the one used longest ago returns.
    /// </summary>
    public static string Pick(Guid coupleId, IEnumerable<string> catalogKeys, IReadOnlyDictionary<string, DateOnly> lastUsed)
    {
        var keys = catalogKeys.ToList();
        if (keys.Count == 0) throw new InvalidOperationException("The question bank is empty.");
        var unused = keys.Where(key => !lastUsed.ContainsKey(key)).ToList();
        if (unused.Count > 0) return unused.MinBy(key => Rank(coupleId, key))!;
        return keys.OrderBy(key => lastUsed[key]).ThenBy(key => Rank(coupleId, key)).First();
    }

    private static string Rank(Guid coupleId, string key)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{coupleId:N}:{key}")));
}
