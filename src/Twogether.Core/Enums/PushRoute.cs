namespace Twogether.Core.Enums;

public enum PushRoute
{
    /// <summary>Sound and vibration as the channel allows.</summary>
    Normal,
    /// <summary>Delivered to the shade without sound or vibration.</summary>
    Quiet,
    /// <summary>Not pushed. The notification still appears in the app.</summary>
    Muted
}
