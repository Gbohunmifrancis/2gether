using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Twogether.Api.Common;

namespace Twogether.Api.Controllers;

public sealed class HealthController : ApiControllerBase
{
    [AllowAnonymous]
    [HttpGet]
    public IActionResult Get() => Ok(new { status = "ok", service = "twogether-api" });
}
