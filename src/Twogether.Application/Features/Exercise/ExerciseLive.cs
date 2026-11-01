using System.Text.RegularExpressions;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Exercise;

/// <summary>
/// One moment of a workout for the partner's live view (docs/couple-goals.md, Watching live): the stick figure's joints
/// as whole numbers (x then y, thousandths of the picture, -1 for a joint not seen), the count, the coach's phase and a
/// cue code the partner's phone turns into words. Never video, never stored, never logged.
/// </summary>
public sealed record ExerciseLiveDto(Guid OccurrenceId, Guid UserId, ExerciseType ExerciseType, int Target, int Count, string Phase, string? Cue, int Aspect, int[] Joints);

/// <summary>The partner opened the live view (or closed it), for "{name} is watching" on the camera.</summary>
public sealed record ExerciseWatchingDto(Guid OccurrenceId, Guid UserId, string UserName, bool Watching);

/// <summary>A cheer from the live view: <c>heart</c>, <c>gotThis</c> or <c>almostThere</c>.</summary>
public sealed record ExerciseCheerDto(Guid OccurrenceId, Guid UserId, string UserName, string Cheer);

/// <summary>What a live frame may hold; anything else is dropped before it is relayed.</summary>
public static partial class ExerciseLiveRules
{
    /// <summary>The figure's joints: nose, shoulders, elbows, wrists, hips, knees, ankles.</summary>
    public const int Joints = 13;
    /// <summary>Numbers in a frame's joints: x and y for each, or none when nobody is in the picture.</summary>
    public const int Numbers = Joints * 2;
    public const int Scale = 1000;
    public const int MaxCount = 10_000;
    public const int MaxCueLength = 48;
    /// <summary>The picture's width over its height, in thousandths.</summary>
    public const int MinAspect = 200, MaxAspect = 5000;

    public static readonly IReadOnlySet<string> Phases = new HashSet<string>(StringComparer.Ordinal) { "setup", "countdown", "counting", "done" };
    public static readonly IReadOnlySet<string> Cheers = new HashSet<string>(StringComparer.Ordinal) { "heart", "gotThis", "almostThere" };

    /// <summary>The phase in which the partner hears that counting started.</summary>
    public const string Counting = "counting";

    /// <summary>A frame's shape: a count in range, a known phase, a short cue code, a picture's shape, and the joints.</summary>
    public static bool IsValid(int count, string? phase, string? cue, int aspect, int[]? joints)
    {
        if (count < 0 || count > MaxCount || phase is null || !Phases.Contains(phase)) return false;
        if (aspect < MinAspect || aspect > MaxAspect) return false;
        if (!string.IsNullOrEmpty(cue) && (cue.Length > MaxCueLength || !CueCode().IsMatch(cue))) return false;
        if (joints is null || (joints.Length != 0 && joints.Length != Numbers)) return false;
        for (var i = 0; i < joints.Length; i += 2)
        {
            var (x, y) = (joints[i], joints[i + 1]);
            // A joint is both coordinates in the picture, or both -1.
            if (x == -1 && y == -1) continue;
            if (x < 0 || x > Scale || y < 0 || y > Scale) return false;
        }
        return true;
    }

    /// <summary>A cue code: letters, digits and dots ("setup.TurnSideways", "count.2", "tip.Shallow"); never free text.</summary>
    [GeneratedRegex("^[A-Za-z0-9.]+$")]
    private static partial Regex CueCode();
}
