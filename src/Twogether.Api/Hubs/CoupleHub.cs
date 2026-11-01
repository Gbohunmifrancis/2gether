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
using Twogether.Shared.Localization;

namespace Twogether.Api.Hubs;

[Authorize]
public sealed partial class CoupleHub(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, CouplePresenceTracker presence, GameLiveState liveState, HubConnectionRegistry connections, LocationSharingStopper locationStopper, LocationFixThrottle fixThrottle, GameInkGate inkGate, ExerciseLiveGate exerciseLive) : Hub
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
                var displayName = await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => item.DisplayName).SingleOrDefaultAsync() ?? Loc.T("Your partner");
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
    /// Stores the caller's latest fix, timed by the server. The web app's call; the phone sends
    /// <see cref="UpdateLocationAt"/> with the time the fix was taken.
    /// </summary>
    public Task<LocationDto?> UpdateLocation(double latitude, double longitude, double accuracyMeters)
        => RecordFixAsync(latitude, longitude, accuracyMeters, clock.UtcNow);

    /// <summary>
    /// Stores the caller's latest fix. Sharing is mutual: the partner receives it only while
    /// they share too. Starting is explicit (<c>POST api/location/start</c>): a fix on a stopped
    /// share is dropped, as is one sent during a pause, one older than the stored fix, and one
    /// sooner than a few seconds after the last. When the caller becomes visible again (the first
    /// fix, the end of a pause), they also receive the partner's current position. Returns null
    /// when the fix was dropped.
    /// </summary>
    public Task<LocationDto?> UpdateLocationAt(double latitude, double longitude, double accuracyMeters, long fixedAtUnixMs)
    {
        var now = clock.UtcNow;
        var fixedAt = LocationSharingRules.FixTime(fixedAtUnixMs) ?? now;
        return RecordFixAsync(latitude, longitude, accuracyMeters, fixedAt > now ? now : fixedAt);
    }

    private async Task<LocationDto?> RecordFixAsync(double latitude, double longitude, double accuracyMeters, DateTime fixedAtUtc)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) throw new HubException("A linked couple is required.");
        if (!LocationSharingRules.IsValidFix(latitude, longitude, accuracyMeters)) throw new HubException("Location is invalid.");
        var coupleId = currentUser.CoupleId.Value;
        var userId = currentUser.UserId.Value;
        var now = clock.UtcNow;
        if (!fixThrottle.TryAccept(userId, now)) return null;
        var location = await db.CoupleLocations.SingleOrDefaultAsync(item => item.CoupleId == coupleId && item.UserId == userId, Context.ConnectionAborted);
        if (location is null || !location.SharingEnabled)
        {
            // Still sending after the share stopped somewhere else (signed out or stopped on another phone, a
            // password reset): tell this phone sharing is off, so it ends its service and its notification.
            var name = await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => item.DisplayName).SingleOrDefaultAsync(Context.ConnectionAborted) ?? string.Empty;
            await Clients.Caller.SendAsync(LocationEvents.SharingChanged, LocationSharingRules.State(userId, name, location, now, withLastFix: false), Context.ConnectionAborted);
            return null;
        }
        var linked = await db.Couples.AsNoTracking().AnyAsync(item => item.Id == coupleId && item.Status == CoupleStatus.Active, Context.ConnectionAborted);
        if (!linked) throw new HubException("Link a partner before sharing location.");

        var becameVisible = !location.IsVisibleAt(now) || location.PausedUntilUtc is not null;
        var stored = location.TryRecordFix(latitude, longitude, accuracyMeters, fixedAtUtc, now);
        // Even a dropped fix shows the phone is still there, so the lease is saved either way.
        await db.SaveChangesAsync(Context.ConnectionAborted);
        if (!stored) return null;

        var user = await db.Users.AsNoTracking().SingleAsync(item => item.Id == userId, Context.ConnectionAborted);
        var result = LocationController.ToDto(location, user, true);
        var partnerId = await PartnerId(coupleId, userId);
        var theirs = partnerId is { } id
            ? await db.CoupleLocations.AsNoTracking().SingleOrDefaultAsync(item => item.CoupleId == coupleId && item.UserId == id, Context.ConnectionAborted)
            : null;
        var mutual = LocationSharingRules.CanSee(location, theirs, now);
        // The indicator first: the app shows points only while its own share reads "sharing".
        if (becameVisible)
            await LocationEvents.SharingChangedAsync(Clients, coupleId, LocationSharingRules.State(userId, user.DisplayName, location, now, withLastFix: mutual), Context.ConnectionAborted);
        if (mutual)
        {
            await Clients.Group(UserGroupName(theirs!.UserId)).SendAsync(LocationEvents.Updated, result with { IsCurrentUser = false }, Context.ConnectionAborted);
            if (becameVisible)
            {
                var partner = await db.Users.AsNoTracking().SingleAsync(item => item.Id == theirs.UserId, Context.ConnectionAborted);
                await Clients.Group(UserGroupName(userId)).SendAsync(LocationEvents.Updated, LocationController.ToDto(theirs, partner, false), Context.ConnectionAborted);
            }
        }
        return result;
    }

    /// <summary>Stops sharing and wipes the caller's point. The mutual rule means the caller stops seeing the partner too.</summary>
    public async Task StopLocationSharing()
    {
        if (!currentUser.UserId.HasValue) return;
        await locationStopper.StopAsync(currentUser.UserId.Value, Context.ConnectionAborted);
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
    /// Live ink (docs/games.md): the stroke the caller is drawing, as points that are fractions of the square (Tic-tac-toe,
    /// <paramref name="cell"/> the square) or of the canvas (Draw and guess, <paramref name="cell"/> the stroke's place in
    /// the drawing), relayed to their partner only (<c>GameInk</c>); -1 un-draws. Never stored. Relayed only for the
    /// caller's own couple's game in play, after its countdown, and in Draw and guess only from the round's drawer; a
    /// malformed message, one over the rate, or one for any other game is dropped. Returns whether it was relayed.
    /// </summary>
    public async Task<bool> Ink(Guid sessionId, int cell, int stroke, float[] points)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return false;
        if (!GameInkGate.IsValid(cell, stroke, points)) return false;
        var coupleId = currentUser.CoupleId.Value;
        var userId = currentUser.UserId.Value;
        var now = clock.UtcNow;
        if (!inkGate.TryTake(userId, now)) return false;
        var known = inkGate.Known(userId, sessionId, now);
        if (known is null)
        {
            var game = await db.GameSessions.AsNoTracking()
                .Where(session => session.Id == sessionId && session.CoupleId == coupleId && session.Status == GameSessionStatus.InProgress
                    && (session.CountdownEndsAtUtc == null || session.CountdownEndsAtUtc <= now))
                .Select(session => new { session.GameType, session.CurrentTurnUserId })
                .SingleOrDefaultAsync(Context.ConnectionAborted);
            if (game is null || !GameInkGate.Games.TryGetValue(game.GameType, out var rules)) return false;
            // Draw and guess: the drawer's turn is the whole round, so only they ink; the guesser never does.
            if (rules.DrawerOnly && game.CurrentTurnUserId != userId) return false;
            var partnerId = await PartnerId(coupleId, userId);
            if (partnerId is null) return false;
            inkGate.Allow(userId, sessionId, partnerId.Value, game.GameType, now);
            known = (partnerId.Value, game.GameType);
        }
        var (partner, type) = known.Value;
        if (!GameInkGate.Fits(type, cell)) return false;
        await Clients.Group(UserGroupName(partner)).SendAsync("GameInk", new GameInkDto(sessionId, userId, cell, stroke, points), Context.ConnectionAborted);
        return true;
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
