using Microsoft.AspNetCore.Mvc;
using Twogether.Shared.Results;

namespace Twogether.Api.Common;

[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
public abstract class ApiControllerBase : ControllerBase
{
    protected IActionResult HandleResult<T>(Result<T> result)
        => result.IsSuccess ? Ok(result.Value) : Problem(result.Error);

    protected IActionResult HandleNoContent(Result result)
        => result.IsSuccess ? NoContent() : Problem(result.Error);

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
