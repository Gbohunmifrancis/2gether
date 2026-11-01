using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Exercise;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Api.Hubs;

/// <summary>
/// Watching live (docs/couple-goals.md): while one partner works out on camera, their phone sends the stick figure
/// (<see cref="ExerciseLive"/>) and the other partner's live view sends <see cref="WatchExercise"/> and
/// <see cref="CheerExercise"/> back. Only the couple's own two phones take part, nothing here is stored or logged, and
/// each kind is capped per person (<see cref="ExerciseLiveGate"/>).
/// </summary>
public sealed partial class CoupleHub
{
    /// <summary>
    /// One frame of the caller's workout, relayed to their partner only (<c>ExerciseLive</c>). The caller must be the dare's
    /// assignee in their own active couple, with the dare in progress (the camera started it) and still open. A malformed
    /// frame, one over the rate, or one for any other dare is dropped. The first frame that is counting tells the partner,
    /// once per dare, with a push that opens the live view. Returns whether it was relayed.
    /// </summary>
    public async Task<bool> ExerciseLive(Guid occurrenceId, int count, string phase, string? cue, int aspect, int[] joints)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return false;
        if (!ExerciseLiveRules.IsValid(count, phase, cue, aspect, joints)) return false;
        var coupleId = currentUser.CoupleId.Value;
        var userId = currentUser.UserId.Value;
        var now = clock.UtcNow;
        if (!exerciseLive.TryFrame(userId, now)) return false;
        var pass = exerciseLive.Known(userId, occurrenceId, now);
        if (pass is null)
        {
            var dare = await db.ExerciseOccurrences.AsNoTracking()
                .Where(item => item.Id == occurrenceId && item.CoupleId == coupleId && item.UserId == userId && item.Status == ExerciseOccurrenceStatus.InProgress)
                .Select(item => new { item.ExerciseType, item.Target, item.CompleteByUtc })
                .SingleOrDefaultAsync(Context.ConnectionAborted);
            if (dare is null || !(dare.CompleteByUtc > now)) return false;
            var partnerId = await ActivePartnerId(coupleId, userId);
            if (partnerId is null) return false;
            pass = new LivePass(partnerId.Value, coupleId, dare.ExerciseType, dare.Target);
            exerciseLive.Allow(userId, occurrenceId, pass, now);
        }
        var frame = new ExerciseLiveDto(occurrenceId, userId, pass.Type, pass.Target, count, phase, string.IsNullOrEmpty(cue) ? null : cue, aspect, joints);
        await Clients.Group(UserGroupName(pass.PartnerId)).SendAsync(ExerciseEvents.Live, frame, Context.ConnectionAborted);
        if (phase == ExerciseLiveRules.Counting && !exerciseLive.WasAnnounced(occurrenceId)) await AnnounceCountingAsync(occurrenceId, userId, pass, now);
        return true;
    }

    /// <summary>
    /// The caller opened the live view of their partner's dare (and repeats it every few seconds while it stays open), or
    /// closed it: relayed to the partner only (<c>ExerciseWatching</c>), so their camera shows who's watching. Opening needs
    /// the dare ringing or in progress; closing is always passed on.
    /// </summary>
    public async Task<bool> WatchExercise(Guid occurrenceId, bool watching)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return false;
        var userId = currentUser.UserId.Value;
        var now = clock.UtcNow;
        if (!exerciseLive.TryWatchSignal(userId, now)) return false;
        var exerciser = await PartnersDareAsync(occurrenceId, requireCamera: false, open: watching);
        if (exerciser is null) return false;
        exerciseLive.Watching(occurrenceId, watching, now);
        var name = await DisplayNameAsync(userId);
        await Clients.Group(UserGroupName(exerciser.Value)).SendAsync(ExerciseEvents.Watching, new ExerciseWatchingDto(occurrenceId, userId, name, watching), Context.ConnectionAborted);
        return true;
    }

    /// <summary>
    /// A cheer from the live view (<c>heart</c>, <c>gotThis</c>, <c>almostThere</c>) for the partner on camera, relayed to them
    /// only (<c>ExerciseCheer</c>) while their dare is in progress; a few every ten seconds at most.
    /// </summary>
    public async Task<bool> CheerExercise(Guid occurrenceId, string cheer)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return false;
        if (cheer is null || !ExerciseLiveRules.Cheers.Contains(cheer)) return false;
        var userId = currentUser.UserId.Value;
        var now = clock.UtcNow;
        if (!exerciseLive.TryCheer(userId, now)) return false;
        var exerciser = await PartnersDareAsync(occurrenceId, requireCamera: true, open: true);
        if (exerciser is null) return false;
        var name = await DisplayNameAsync(userId);
        await Clients.Group(UserGroupName(exerciser.Value)).SendAsync(ExerciseEvents.Cheer, new ExerciseCheerDto(occurrenceId, userId, name, cheer), Context.ConnectionAborted);
        return true;
    }

    /// <summary>
    /// The partner whose dare this is, when it belongs to the caller's active couple and not to the caller; with [open], only
    /// while it rings or (with [requireCamera]) is in progress.
    /// </summary>
    private async Task<Guid?> PartnersDareAsync(Guid occurrenceId, bool requireCamera, bool open)
    {
        var coupleId = currentUser.CoupleId!.Value;
        var userId = currentUser.UserId!.Value;
        var dare = await db.ExerciseOccurrences.AsNoTracking()
            .Where(item => item.Id == occurrenceId && item.CoupleId == coupleId && item.UserId != userId)
            .Select(item => new { item.UserId, item.Status })
            .SingleOrDefaultAsync(Context.ConnectionAborted);
        if (dare is null) return null;
        if (open && !(requireCamera ? dare.Status == ExerciseOccurrenceStatus.InProgress : dare.Status is ExerciseOccurrenceStatus.Alarming or ExerciseOccurrenceStatus.InProgress)) return null;
        return await ActivePartnerId(coupleId, userId) == dare.UserId ? dare.UserId : null;
    }

    /// <summary>"Maya started their squats": once per dare, to the partner, unless they already have the live view open.</summary>
    private async Task AnnounceCountingAsync(Guid occurrenceId, Guid userId, LivePass pass, DateTime now)
    {
        // Marked first, so the next frames on this server don't look again while this one does.
        exerciseLive.Announced(occurrenceId, now);
        if (exerciseLive.IsWatched(occurrenceId, now)) return;
        var key = occurrenceId.ToString();
        var since = now.AddDays(-1);
        var told = await db.Notifications.AsNoTracking().AnyAsync(item => item.UserId == pass.PartnerId && item.Type == NotificationType.ExerciseStarted
            && item.CreatedAtUtc >= since && item.DataJson != null && item.DataJson.Contains(key), Context.ConnectionAborted);
        if (told) return;
        var title = await db.ExerciseOccurrences.AsNoTracking().Where(item => item.Id == occurrenceId).Select(item => item.Title).SingleAsync(Context.ConnectionAborted);
        var name = await DisplayNameAsync(userId);
        var data = JsonSerializer.Serialize(new { occurrenceId, exercise = JsonNamingPolicy.CamelCase.ConvertName(pass.Type.ToString()) });
        Notification notification;
        using (Loc.Use(await db.LanguageOfAsync(pass.PartnerId, Context.ConnectionAborted)))
            notification = Notification.Create(pass.PartnerId, pass.CoupleId, NotificationType.ExerciseStarted,
                Loc.T("{name} started their {exercise}", ("name", name), ("exercise", ExerciseText.InSentence(title, pass.Type))),
                Loc.T("Watch live and cheer them on."), data, now);
        db.Notifications.Add(notification);
        try { await db.SaveChangesAsync(Context.ConnectionAborted); }
        catch (DbUpdateException) { return; }
        await Clients.Group(UserGroupName(pass.PartnerId)).SendAsync(ExerciseEvents.NotificationCreated, NotificationController.ToDto(notification), Context.ConnectionAborted);
    }

    private async Task<Guid?> ActivePartnerId(Guid coupleId, Guid userId)
    {
        var couple = await db.Couples.AsNoTracking().Where(item => item.Id == coupleId && item.Status == CoupleStatus.Active)
            .Select(item => new { item.UserAId, item.UserBId }).SingleOrDefaultAsync(Context.ConnectionAborted);
        if (couple is null) return null;
        if (couple.UserAId == userId) return couple.UserBId;
        return couple.UserBId == userId ? couple.UserAId : null;
    }

    private async Task<string> DisplayNameAsync(Guid userId)
        => await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => item.DisplayName).SingleOrDefaultAsync(Context.ConnectionAborted) ?? Loc.T("Your partner");
}
