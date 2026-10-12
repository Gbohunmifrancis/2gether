using Microsoft.AspNetCore.Mvc;
using Twogether.Application.Common.Security;
using Twogether.Shared.Results;

namespace Twogether.Api.Common;

[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
public abstract class ApiControllerBase : ControllerBase
{
    /// <summary>The caller's address; behind a proxy this needs ForwardedHeaders:Enabled.</summary>
    protected string ClientIp => HttpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown";

    /// <summary>The longest wait among the budgets that are used up, or null when every one has room.</summary>
    protected static TimeSpan? Blocked(AttemptLimiter limiter, params (AttemptRule Rule, string Key)[] budgets)
        => budgets.Select(budget => limiter.RetryAfter(budget.Rule, budget.Key)).Where(wait => wait.HasValue).Max();

    protected ObjectResult TooManyAttempts(TimeSpan retryAfter, string message)
    {
        var seconds = (int)Math.Ceiling(retryAfter.TotalSeconds);
        Response.Headers.RetryAfter = seconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
        return StatusCode(StatusCodes.Status429TooManyRequests, new ApiError("rate_limited", message));
    }

    protected IActionResult HandleResult<T>(Result<T> result)
        => result.IsSuccess ? Ok(result.Value) : Problem(result.Error);

    protected IActionResult HandleNoContent(Result result)
        => result.IsSuccess ? NoContent() : Problem(result.Error);

    /// <summary>An expected failure as its HTTP answer: validation 422, not found 404, conflict 409...</summary>
    protected IActionResult FromError(Error error) => Problem(error);

    private IActionResult Problem(Error error)
    {
        var status = error.Type switch
        {
            ErrorType.Validation => StatusCodes.Status422UnprocessableEntity,
            ErrorType.NotFound => StatusCodes.Status404NotFound,
            ErrorType.Conflict => StatusCodes.Status409Conflict,
            ErrorType.Unauthorized => StatusCodes.Status401Unauthorized,
            ErrorType.Forbidden => StatusCodes.Status403Forbidden,
            ErrorType.Network => StatusCodes.Status502BadGateway,
            _ => StatusCodes.Status400BadRequest
        };

        return StatusCode(status, new ApiError(error.Code, error.Message));
    }
}
