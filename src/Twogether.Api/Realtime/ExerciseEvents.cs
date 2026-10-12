using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Controllers;
using Twogether.Api.Hubs;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;

namespace Twogether.Api.Realtime;

/// <summary>
/// Fan-out for the exercise feature. The controller, the hub and the alarm worker all end up
/// sending the same events, and every one of them must only do so after a successful save.
/// </summary>
public static class ExerciseEvents
{
    public const string AlarmStarted = "ExerciseAlarmStarted";
    public const string Started = "ExerciseStarted";
    public const string Progress = "ExerciseProgress";
    public const string Completed = "ExerciseCompleted";
    public const string Missed = "ExerciseMissed";
    public const string Cancelled = "ExerciseCancelled";
    public const string CallOffAsked = "ExerciseCallOffAsked";
    public const string GoalsChanged = "ExerciseGoalsChanged";
    public const string NotificationCreated = "NotificationCreated";

    public static Task OccurrenceAsync(IHubContext<CoupleHub> hub, string eventName, ExerciseOccurrenceDto occurrence, CancellationToken cancellationToken)
        => hub.Clients.Group(CoupleHub.GroupName(occurrence.CoupleId)).SendAsync(eventName, occurrence, cancellationToken);

    public static Task GoalsChangedAsync(IHubContext<CoupleHub> hub, Guid coupleId, IReadOnlyList<ExerciseGoalDto> activeGoals, CancellationToken cancellationToken)
        => hub.Clients.Group(CoupleHub.GroupName(coupleId)).SendAsync(GoalsChanged, activeGoals, cancellationToken);

    public static Task NotificationAsync(IHubContext<CoupleHub> hub, Notification notification, CancellationToken cancellationToken)
        => hub.Clients.Group(CoupleHub.UserGroupName(notification.UserId)).SendAsync(NotificationCreated, NotificationController.ToDto(notification), cancellationToken);
}
