using Twogether.Core.Enums;

namespace Twogether.Api.Realtime;

public sealed record NotificationDto(Guid Id, Guid UserId, NotificationType Type, string Title, string Body, string? DataJson, DateTime CreatedAtUtc, DateTime? ReadAtUtc);
public sealed record PartnerPresenceDto(Guid UserId, string DisplayName, bool Online, bool BothConnected);
public sealed record TypingDto(Guid UserId, string DisplayName, bool IsTyping, bool IsPrivate);
public sealed record LocationDto(Guid UserId, string DisplayName, string? AvatarUrl, string MapColor, double Latitude, double Longitude, double AccuracyMeters, DateTime UpdatedAtUtc, bool IsCurrentUser);
public sealed record GameInvitationDto(Guid SessionId, GameType GameType, Guid InvitedByUserId, string InvitedByName, DateTime CreatedAtUtc);
public sealed record GameCountdownDto(Guid SessionId, DateTime CountdownEndsAtUtc);
public sealed record GameEndedDto(Guid SessionId, Guid EndedByUserId, string EndedByName, DateTime EndedAtUtc);
public sealed record GameProgressDto(Guid SessionId, Guid UserId, int Round, int Filled, int Total);
public sealed record GameEndRequestedDto(Guid SessionId, Guid RequestedByUserId, string RequestedByName, DateTime RequestedAtUtc, DateTime AutoAcceptsAtUtc);
public sealed record CoupleEndedDto(Guid EndedByUserId, string EndedByName, DateTime EndedAtUtc);
