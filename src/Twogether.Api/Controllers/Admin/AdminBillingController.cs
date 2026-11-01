using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Twogether.Api.Admin;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Ops;

namespace Twogether.Api.Controllers.Admin;

/// <summary>
/// The billing check (docs/billing.md, "Why Plus isn't on sale"): what Play Console and Bachs actually hold for our
/// products, and what to fix. Store configuration only, nothing about any person. Asks Google and Bachs on every call,
/// so the dashboard runs it on a button, never on its own; every run is audited.
/// </summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin/billing")]
public sealed class AdminBillingController(BillingCheckService check, AdminAudit audit, IApplicationDbContext db) : ApiControllerBase
{
    /// <summary><paramref name="country"/>: the region whose availability the hints judge (two letters, default NG).</summary>
    [HttpGet("check")]
    public async Task<ActionResult<BillingCheckDto>> Check([FromQuery] string? country, CancellationToken ct)
    {
        var result = await check.RunAsync(country, ct);
        var problems = result.Hints.Count(hint => hint.Level == HintLevel.Problem);
        audit.Record(User, "billing.checked", "billing", null, $"{result.Country}: {problems} problem(s)");
        await db.SaveChangesAsync(ct);
        return Ok(result);
    }
}
