using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Api.Presence;
using Twogether.Api.Controllers;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Exercise;
using Twogether.Application.Features.Location;
using Twogether.Core.Enums;

namespace Twogether.Api.Hubs;

[Authorize]
public sealed class CoupleHub(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, CouplePresenceTracker presence, GameLiveState liveState, HubConnectionRegistry connections) : Hub
{
    public static string GroupName(Guid coupleId) => $"couple-{coupleId:N}";
    public static string UserGroupName(Guid userId) => $"user-{userId:N}";

    public override async Task OnConnectedAsync()
    {
        if (currentUser.UserId.HasValue) connections.Add(currentUser.UserId.Value, Context);
        if (currentUser.CoupleId.HasValue && currentUser.UserId.HasValue)
        {
            var coupleId = currentUser.CoupleId.Value;
            var userId = currentUser.UserId.Value;
            presence.Connected(coupleId, userId);
            await Groups.AddToGroupAsync(Context.ConnectionId, GroupName(coupleId));
            await Groups.AddToGroupAsync(Context.ConnectionId, UserGroupName(userId));
            var displayName = await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => item.DisplayName).SingleAsync(Context.ConnectionAborted);
            var partnerId = await PartnerId(coupleId, userId);
            var partnerOnline = partnerId.HasValue && presence.IsOnline(coupleId, partnerId.Value);
            var bothConnected = presence.AreBothOnline(coupleId);
            await Clients.Caller.SendAsync("PresenceChanged", partnerOnline, Context.ConnectionAborted);
            await Clients.OthersInGroup(GroupName(coupleId)).SendAsync("PresenceChanged", true, Context.ConnectionAborted);
            await Clients.OthersInGroup(GroupName(coupleId)).SendAsync("PartnerPresenceChanged", new PartnerPresenceDto(userId, displayName, true, bothConnected), Context.ConnectionAborted);
            if (bothConnected) await Clients.Group(GroupName(coupleId)).SendAsync("CoupleTogether", new { connectedAtUtc = clock.UtcNow }, Context.ConnectionAborted);
        }
        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        if (currentUser.UserId.HasValue) connections.Remove(currentUser.UserId.Value, Context.ConnectionId);
        if (currentUser.CoupleId.HasValue && currentUser.UserId.HasValue)
        {
            var coupleId = currentUser.CoupleId.Value;
            var userId = currentUser.UserId.Value;
            presence.Disconnected(coupleId, userId);
            var stillOnline = presence.IsOnline(coupleId, userId);
            if (!stillOnline)
            {
                var displayName = await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => item.DisplayName).SingleOrDefaultAsync() ?? "Your partner";
                await Clients.OthersInGroup(GroupName(coupleId)).SendAsync("PresenceChanged", false);
                await Clients.OthersInGroup(GroupName(coupleId)).SendAsync("PartnerPresenceChanged", new PartnerPresenceDto(userId, displayName, false, false));
            }
        }
        await base.OnDisconnectedAsync(exception);
    }

    public async Task<MessageDto> SendMessage(string body, bool isPrivate = false)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) throw new HubException("A linked couple is required.");
        var linkedCouple = await db.Couples.AsNoTracking().AnyAsync(couple => couple.Id == currentUser.CoupleId.Value && couple.Status == Twogether.Core.Enums.CoupleStatus.Active, Context.ConnectionAborted);
        if (!linkedCouple) throw new HubException("Link a partner before sending messages.");
        if (string.IsNullOrWhiteSpace(body) || body.Length > 4000) throw new HubException("Message must be between 1 and 4000 characters.");
        var message = Message.Create(currentUser.CoupleId.Value, currentUser.UserId.Value, body.Trim(), isPrivate, clock.UtcNow);
        db.Messages.Add(message);
        await db.SaveChangesAsync(Context.ConnectionAborted);
        var result = new MessageDto(message.Id, message.CoupleId, message.SenderUserId, message.Body, message.IsPrivate, message.CreatedAtUtc, message.EditedAtUtc);
        await Clients.Group(GroupName(message.CoupleId)).SendAsync("MessageCreated", result, Context.ConnectionAborted);
        return result;
    }

    public async Task SetTyping(bool isTyping, bool isPrivate)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return;
        var displayName = await db.Users.AsNoTracking().Where(item => item.Id == currentUser.UserId.Value).Select(item => item.DisplayName).SingleAsync(Context.ConnectionAborted);
        await Clients.OthersInGroup(GroupName(currentUser.CoupleId.Value)).SendAsync("TypingChanged", new TypingDto(currentUser.UserId.Value, displayName, isTyping, isPrivate), Context.ConnectionAborted);
    }

    /// <summary>
    /// Stores the caller's latest fix. Sharing is mutual: the partner receives it only while
    /// they share too. A fix sent during a pause is dropped, never stored. When the caller
    /// starts or comes back from a pause, they also receive the partner's current position.
    /// </summary>
    public async Task<LocationDto> UpdateLocation(double latitude, double longitude, double accuracyMeters)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) throw new HubException("A linked couple is required.");
        if (latitude is < -90 or > 90 || longitude is < -180 or > 180 || accuracyMeters is < 0 or > 100000) throw new HubException("Location is invalid.");
        var coupleId = currentUser.CoupleId.Value;
        var userId = currentUser.UserId.Value;
        var linked = await db.Couples.AsNoTracking().AnyAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, Context.ConnectionAborted);
        if (!linked) throw new HubException("Link a partner before sharing location.");
        var now = clock.UtcNow;
        var user = await db.Users.AsNoTracking().SingleAsync(item => item.Id == userId, Context.ConnectionAborted);
        var location = await db.CoupleLocations.SingleOrDefaultAsync(item => item.CoupleId == coupleId && item.UserId == userId, Context.ConnectionAborted);
        if (location is not null && location.IsPausedAt(now)) return LocationController.ToDto(location, user, true);

        var startedOrResumed = location is null || !location.SharingEnabled || location.PausedUntilUtc is not null;
        if (location is null)
        {
            location = CoupleLocation.Create(coupleId, userId, latitude, longitude, accuracyMeters, now);
            db.CoupleLocations.Add(location);
        }
        else location.Update(latitude, longitude, accuracyMeters, now);
        await db.SaveChangesAsync(Context.ConnectionAborted);

        var result = LocationController.ToDto(location, user, true);
        var partnerId = await PartnerId(coupleId, userId);
        var theirs = partnerId is { } id
            ? await db.CoupleLocations.AsNoTracking().SingleOrDefaultAsync(item => item.CoupleId == coupleId && item.UserId == id, Context.ConnectionAborted)
            : null;
        if (LocationSharingRules.CanSee(location, theirs, now))
        {
            await Clients.OthersInGroup(GroupName(coupleId)).SendAsync(LocationEvents.Updated, result with { IsCurrentUser = false }, Context.ConnectionAborted);
            if (startedOrResumed)
            {
                var partner = await db.Users.AsNoTracking().SingleAsync(item => item.Id == theirs!.UserId, Context.ConnectionAborted);
                await Clients.Caller.SendAsync(LocationEvents.Updated, LocationController.ToDto(theirs!, partner, false), Context.ConnectionAborted);
            }
        }
        if (startedOrResumed)
            await Clients.Group(GroupName(coupleId)).SendAsync(LocationEvents.SharingChanged, LocationSharingRules.State(userId, user.DisplayName, location, now), Context.ConnectionAborted);
        return result;
    }

    /// <summary>Stops sharing. The mutual rule means the caller stops seeing the partner too.</summary>
    public async Task StopLocationSharing()
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return;
        var coupleId = currentUser.CoupleId.Value;
        var userId = currentUser.UserId.Value;
        var location = await db.CoupleLocations.SingleOrDefaultAsync(item => item.CoupleId == coupleId && item.UserId == userId, Context.ConnectionAborted);
        if (location is null) return;
        var now = clock.UtcNow;
        location.StopSharing(now);
        await db.SaveChangesAsync(Context.ConnectionAborted);
        await Clients.OthersInGroup(GroupName(coupleId)).SendAsync(LocationEvents.Stopped, userId, Context.ConnectionAborted);
        if (await PartnerId(coupleId, userId) is { } partnerId)
            await Clients.Caller.SendAsync(LocationEvents.Stopped, partnerId, Context.ConnectionAborted);
        var name = await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => item.DisplayName).SingleAsync(Context.ConnectionAborted);
        await Clients.Group(GroupName(coupleId)).SendAsync(LocationEvents.SharingChanged, LocationSharingRules.State(userId, name, location, now), Context.ConnectionAborted);
    }

    /// <summary>
    /// Reports what the caller has typed so far in an "I call on" round. The draft is what
    /// gets submitted on their behalf if their partner finishes first or the clock runs out,
    /// and the partner sees the caller's progress as a percentage so the race is felt.
    /// </summary>
    public async Task ReportDraft(Guid sessionId, int round, string answersJson)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return;
        var coupleId = currentUser.CoupleId.Value;
        var userId = currentUser.UserId.Value;
        if (string.IsNullOrWhiteSpace(answersJson) || answersJson.Length > 2000) return;
        var owned = await db.GameSessions.AsNoTracking().AnyAsync(session => session.Id == sessionId && session.CoupleId == coupleId && session.Status == GameSessionStatus.InProgress, Context.ConnectionAborted);
        if (!owned) return;
        Dictionary<string, string>? answers;
        try { answers = JsonSerializer.Deserialize<Dictionary<string, string>>(answersJson); }
        catch (JsonException) { return; }
        if (answers is null || answers.Count == 0) return;

        liveState.RecordDraft(sessionId, userId, round, answersJson);
        var filled = answers.Values.Count(answer => !string.IsNullOrWhiteSpace(answer));
        await Clients.OthersInGroup(GroupName(coupleId)).SendAsync("GameProgress", new GameProgressDto(sessionId, userId, round, filled, answers.Count), Context.ConnectionAborted);
    }

    /// <summary>
    /// Live rep count from the assigned partner's camera session. Advisory only: it lets the
    /// other partner watch, and it never decides completion. Silently ignored when the caller
    /// is not the assigned user or the occurrence is not in progress, like ReportDraft.
    /// </summary>
    public async Task ReportExerciseProgress(Guid occurrenceId, int achievedCount, double confidence)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return;
        var coupleId = currentUser.CoupleId.Value;
        var userId = currentUser.UserId.Value;
        var occurrence = await db.ExerciseOccurrences.SingleOrDefaultAsync(item => item.Id == occurrenceId && item.CoupleId == coupleId && item.UserId == userId, Context.ConnectionAborted);
        if (occurrence is null || occurrence.Status != ExerciseOccurrenceStatus.InProgress) return;
        occurrence.RecordProgress(Math.Clamp(achievedCount, 0, 10000), Math.Clamp(confidence, 0, 1), clock.UtcNow);
        try { await db.SaveChangesAsync(Context.ConnectionAborted); }
        catch (DbUpdateConcurrencyException) { return; }
        await Clients.OthersInGroup(GroupName(coupleId)).SendAsync(ExerciseEvents.Progress,
            new ExerciseProgressDto(occurrence.Id, userId, occurrence.AchievedCount, occurrence.Target, occurrence.Confidence), Context.ConnectionAborted);
    }

    private async Task<Guid?> PartnerId(Guid coupleId, Guid userId)
    {
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == coupleId, Context.ConnectionAborted);
        return couple is null ? null : couple.UserAId == userId ? couple.UserBId : couple.UserAId;
    }
}

public sealed record MessageDto(Guid Id, Guid CoupleId, Guid SenderUserId, string Body, bool IsPrivate, DateTime CreatedAtUtc, DateTime? EditedAtUtc);
