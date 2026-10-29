using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Twogether.Api.Admin;
using Twogether.Api.Common;
using Twogether.Application.Features.Admin.Insights;

namespace Twogether.Api.Controllers.Admin;

/// <summary>The insight pages (docs/admin/contract-v2.md, "Insights"). Aggregates of measured people only.</summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin/analytics")]
public sealed class AdminInsightsController(AdminInsights insights) : ApiControllerBase
{
    [HttpGet("funnel")]
    public async Task<ActionResult<FunnelDto>> Funnel([FromQuery] int days = 30, CancellationToken ct = default) => Ok(await insights.FunnelAsync(days, ct));

    [HttpGet("retention")]
    public async Task<ActionResult<RetentionDto>> Retention([FromQuery] int weeks = 12, CancellationToken ct = default) => Ok(await insights.RetentionAsync(weeks, ct));

    [HttpGet("rhythm")]
    public async Task<ActionResult<RhythmDto>> Rhythm([FromQuery] int days = 30, CancellationToken ct = default) => Ok(await insights.RhythmAsync(days, ct));

    [HttpGet("dares")]
    public async Task<ActionResult<DareInsightsDto>> Dares([FromQuery] int days = 30, CancellationToken ct = default) => Ok(await insights.DaresAsync(days, ct));

    [HttpGet("games")]
    public async Task<ActionResult<GameInsightsDto>> Games([FromQuery] int days = 30, CancellationToken ct = default) => Ok(await insights.GamesAsync(days, ct));

    [HttpGet("growth")]
    public async Task<ActionResult<GrowthDto>> Growth([FromQuery] int days = 90, CancellationToken ct = default) => Ok(await insights.GrowthAsync(days, ct));

    [HttpGet("world")]
    public async Task<ActionResult<WorldDto>> World(CancellationToken ct = default) => Ok(await insights.WorldAsync(ct));

    [HttpGet("couples/top")]
    public async Task<ActionResult<IReadOnlyList<TopCoupleDto>>> TopCouples([FromQuery] int days = 30, [FromQuery] int limit = 12, CancellationToken ct = default)
        => Ok(await insights.TopCouplesAsync(days, limit, ct));
}
