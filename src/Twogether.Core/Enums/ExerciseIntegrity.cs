namespace Twogether.Core.Enums;

/// <summary>What Google Play Integrity said about the app that sent a completion.</summary>
public enum ExerciseIntegrity
{
    /// <summary>The check isn't set up on this server, or Google couldn't be reached.</summary>
    NotChecked,
    /// <summary>Google confirmed the request came from our app, installed from Play, on a genuine device.</summary>
    Passed,
    /// <summary>The check is set up but the app sent no token.</summary>
    Missing,
    /// <summary>Google refused it: a modified app, a tampered device, or a token for another request.</summary>
    Failed
}
