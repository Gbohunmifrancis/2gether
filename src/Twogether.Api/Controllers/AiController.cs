using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Ai;
using Twogether.Infrastructure.Ai;
using Twogether.Shared.Results;

namespace Twogether.Api.Controllers;

/// <summary>
/// The AI features (docs/ai.md). Each is behind its admin switch and the server's key; a feature that is off answers
/// <c>404 ai.off</c>, and <c>status</c> says which are on so the app can hide the rest. The couple is the caller's own.
/// </summary>
[Authorize]
public sealed class AiController(AiAssistant assistant, ICurrentUser currentUser) : ApiControllerBase
{
    [HttpGet("status")]
    public async Task<ActionResult<AiStatusDto>> Status(CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId) return Unauthorized();
        return Ok(await assistant.StatusAsync(userId, cancellationToken));
    }

    /// <summary>Three dare ideas for the person a dare is being set for. At most <c>Ai:DareIdeasPerDay</c> (10) a day per person.</summary>
    [HttpPost("dare-ideas")]
    public async Task<ActionResult<DareIdeasDto>> DareIdeas(DareIdeasRequest request, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId) return Unauthorized();
        return Answer(await assistant.DareIdeasAsync(userId, currentUser.CoupleId, request.AssignedUserId, cancellationToken));
    }

    /// <summary>Three date ideas and two games for tonight. At most <c>Ai:PlansPerDay</c> (5) a day per person.</summary>
    [HttpPost("plan")]
    public async Task<ActionResult<PlanDto>> Plan(PlanRequest request, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId) return Unauthorized();
        return Answer(await assistant.PlanAsync(userId, currentUser.CoupleId, request, cancellationToken));
    }

    /// <summary>The coach's note on a finished dare; the person who did it and their partner may read it. Written on first read.</summary>
    [HttpGet("coach/{occurrenceId:guid}")]
    public async Task<ActionResult<CoachNoteDto>> Coach(Guid occurrenceId, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId) return Unauthorized();
        return Answer(await assistant.CoachAsync(userId, currentUser.CoupleId, occurrenceId, cancellationToken));
    }

    /// <summary>The couple's latest weekly recap, written for the caller; 204 when there is none this week.</summary>
    [HttpGet("recap")]
    public async Task<ActionResult<WeeklyRecapDto>> Recap(CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId) return Unauthorized();
        var recap = await assistant.LatestRecapAsync(userId, currentUser.CoupleId, cancellationToken);
        return recap is null ? NoContent() : Ok(recap);
    }

    private ActionResult Answer<T>(Result<T> result)
    {
        if (result.IsSuccess) return Ok(result.Value);
        var error = new ApiError(result.Error.Code, result.Error.Message);
        if (result.Error.Code == "ai.limit") return StatusCode(StatusCodes.Status429TooManyRequests, error);
        return result.Error.Type switch
        {
            ErrorType.Validation when result.Error.Code == "ai.no_couple" => BadRequest(error),
            ErrorType.Validation => UnprocessableEntity(error),
            ErrorType.NotFound => NotFound(error),
            ErrorType.Conflict => Conflict(error),
            ErrorType.Forbidden => StatusCode(StatusCodes.Status403Forbidden, error),
            ErrorType.Network => StatusCode(StatusCodes.Status502BadGateway, error),
            // The model's answer didn't pass the checks, or the call failed for a reason that isn't the network.
            _ => StatusCode(StatusCodes.Status502BadGateway, error)
        };
    }
}
