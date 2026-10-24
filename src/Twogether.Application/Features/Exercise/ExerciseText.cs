using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Exercise;

/// <summary>Dare wording in the reader's language (docs/translation.md).</summary>
public static class ExerciseText
{
    /// <summary>A dare's title: the catalog name in the reader's language, or the title someone typed, as written.</summary>
    public static string Title(string title, ExerciseType type)
        => title == ExerciseCatalog.Get(type).DisplayName ? Loc.T(title) : title;

    /// <summary>"Today's sit-ups": a catalog name reads lowercased mid-sentence; a typed title stays as written.</summary>
    public static string InSentence(string title, ExerciseType type)
        => title == ExerciseCatalog.Get(type).DisplayName ? Loc.T(title).ToLowerInvariant() : title;

    /// <summary>"15 reps", or "30 s" for a hold.</summary>
    public static string Target(int target, ExerciseType type)
        => ExerciseCatalog.MeasureOf(type) == ExerciseMeasure.Seconds ? Loc.T("{count} s", ("count", target)) : Loc.Plural(target, "{count} rep", "{count} reps");
}
