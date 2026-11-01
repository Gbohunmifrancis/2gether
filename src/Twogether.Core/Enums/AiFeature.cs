namespace Twogether.Core.Enums;

/// <summary>The features that ask Claude for words (docs/ai.md). Each has its own admin switch and daily cap.</summary>
public enum AiFeature
{
    /// <summary>Three dare suggestions on the New dare screen.</summary>
    DareIdeas,
    /// <summary>A short note after a finished dare, once per occurrence.</summary>
    Coach,
    /// <summary>Sunday evening's recap of the couple's week, once per couple per week.</summary>
    WeeklyRecap,
    /// <summary>Plan something: three date ideas and two games for tonight.</summary>
    Plan
}
