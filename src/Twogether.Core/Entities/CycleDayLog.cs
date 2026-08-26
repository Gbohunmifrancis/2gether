using Twogether.Core.Enums;
using Twogether.Core.Seedwork;
using System.Text.Json;

namespace Twogether.Core.Entities;

public sealed class CycleDayLog : BaseEntity
{
    private CycleDayLog() { }

    public Guid UserId { get; private set; }
    public DateOnly LogDate { get; private set; }
    public FlowLevel FlowLevel { get; private set; }
    public IReadOnlyList<string> Symptoms => JsonSerializer.Deserialize<string[]>(SymptomsJson) ?? [];
    public string SymptomsJson { get; private set; } = string.Empty;
    public Mood? Mood { get; private set; }
    public bool? HadSex { get; private set; }
    public bool? ProtectionUsed { get; private set; }
    public string? Notes { get; private set; }

    public static CycleDayLog Create(Guid userId, DateOnly logDate, FlowLevel flowLevel, IEnumerable<string>? symptoms, Mood? mood, bool? hadSex, bool? protectionUsed, string? notes, DateTime utcNow) => new()
    {
        UserId = userId,
        LogDate = logDate,
        FlowLevel = flowLevel,
        SymptomsJson = JsonSerializer.Serialize(symptoms ?? []),
        Mood = mood,
        HadSex = hadSex,
        ProtectionUsed = protectionUsed,
        Notes = notes,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Update(FlowLevel flowLevel, IEnumerable<string>? symptoms, Mood? mood, bool? hadSex, bool? protectionUsed, string? notes, DateTime utcNow)
    {
        FlowLevel = flowLevel;
        SymptomsJson = JsonSerializer.Serialize(symptoms ?? []);
        Mood = mood;
        HadSex = hadSex;
        ProtectionUsed = protectionUsed;
        Notes = notes;
        UpdatedAtUtc = utcNow;
    }
}
