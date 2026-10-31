using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Controllers;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Memories;
using Twogether.Core.Users;
using Twogether.Infrastructure.Account;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Results;
using Twogether.UnitTests.Support;

namespace Twogether.UnitTests;

public sealed class AvatarImageTests
{
    internal static readonly byte[] Jpeg = [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46];
    internal static readonly byte[] Png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00];
    internal static readonly byte[] WebP = [.. "RIFF"u8, 0x24, 0x00, 0x00, 0x00, .. "WEBPVP8 "u8];

    [Fact]
    public void The_type_comes_from_the_first_bytes()
    {
        Assert.Equal("image/jpeg", AvatarImage.Detect(Jpeg));
        Assert.Equal("image/png", AvatarImage.Detect(Png));
        Assert.Equal("image/webp", AvatarImage.Detect(WebP));
    }

    [Theory]
    [InlineData("<html><script>alert(1)</script></html>")]
    [InlineData("GIF89a......")]
    [InlineData("RIFF....WAVEfmt ")]
    [InlineData("PK\u0003\u0004zip")]
    [InlineData("")]
    public void Anything_else_is_not_a_picture(string text) => Assert.Null(AvatarImage.Detect(Encoding.Latin1.GetBytes(text)));

    [Fact]
    public void Too_short_to_tell_is_not_a_picture()
    {
        Assert.Null(AvatarImage.Detect(Jpeg[..2]));
        Assert.Null(AvatarImage.Detect(Png[..7]));
        Assert.Null(AvatarImage.Detect(WebP[..11]));
    }
}

public sealed class AvatarRulesTests
{
    private static byte[] Padded(byte[] head, int length) => [.. head, .. new byte[length - head.Length]];

    [Theory]
    [InlineData("image/jpeg")]
    [InlineData("IMAGE/PNG")]
    [InlineData("image/webp")]
    [InlineData("image/png; charset=binary")]
    public void An_allowed_type_with_matching_bytes_passes(string contentType)
        => Assert.True(AvatarRules.Validate(contentType, AvatarImageTests.Png).IsSuccess);

    [Fact]
    public void The_stored_type_is_what_the_bytes_are_not_what_the_header_said()
        => Assert.Equal("image/jpeg", AvatarRules.Validate("image/png", AvatarImageTests.Jpeg).Value);

    [Theory]
    [InlineData("image/gif")]
    [InlineData("text/html")]
    [InlineData("application/json")]
    [InlineData("")]
    [InlineData(null)]
    public void Other_types_are_refused(string? contentType)
    {
        var result = AvatarRules.Validate(contentType, AvatarImageTests.Png);
        Assert.Equal("avatar.unsupported_type", result.Error.Code);
        Assert.Equal(ErrorType.Validation, result.Error.Type);
    }

    [Fact]
    public void A_page_sent_as_a_jpeg_is_refused()
    {
        var result = AvatarRules.Validate("image/jpeg", "<html><body>hi</body></html>"u8);
        Assert.Equal("avatar.not_an_image", result.Error.Code);
        Assert.Equal(ErrorType.Validation, result.Error.Type);
    }

    [Fact]
    public void Empty_and_over_400_KB_are_refused_and_exactly_400_KB_passes()
    {
        Assert.Equal("avatar.empty", AvatarRules.Validate("image/jpeg", []).Error.Code);
        Assert.Equal("avatar.too_large", AvatarRules.Validate("image/jpeg", Padded(AvatarImageTests.Jpeg, AvatarImage.MaxBytes + 1)).Error.Code);
        Assert.True(AvatarRules.Validate("image/jpeg", Padded(AvatarImageTests.Jpeg, AvatarImage.MaxBytes)).IsSuccess);
        Assert.Equal(400 * 1024, AvatarImage.MaxBytes);
    }
}

public sealed class AvatarDatabaseTests : IDisposable
{
    private static readonly JsonSerializerOptions Web = new(JsonSerializerDefaults.Web);
    private readonly TestDatabase database = new();
    private readonly FakeClock clock = new(TestCouple.Now);

    public void Dispose() => database.Dispose();

    private AvatarService Service(ApplicationDbContext db) => new(db, clock);

    private AvatarController Controller(ApplicationDbContext db, Guid userId, Guid? coupleId, HttpContext? http = null)
        => new(Service(db), new FakeCurrentUser(userId, coupleId)) { ControllerContext = new ControllerContext { HttpContext = http ?? new DefaultHttpContext() } };

    private static DefaultHttpContext Upload(byte[] body, string? contentType, bool declareLength = true)
    {
        var http = new DefaultHttpContext();
        http.Request.Body = new MemoryStream(body);
        http.Request.ContentType = contentType;
        if (declareLength) http.Request.ContentLength = body.Length;
        return http;
    }

    [Fact]
    public async Task Uploading_stores_the_picture_and_each_change_raises_the_version()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var service = Service(db);

        var first = await service.SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        var second = await service.SetAsync(couple.MayaId, "image/jpeg", AvatarImageTests.Jpeg, default);

        Assert.Equal(1, first.Value.AvatarVersion);
        Assert.Equal(2, second.Value.AvatarVersion);
        await using var check = database.CreateContext();
        var maya = await check.Users.AsNoTracking().SingleAsync(item => item.Id == couple.MayaId);
        Assert.Equal(AvatarImageTests.Jpeg, maya.AvatarBytes);
        Assert.Equal(2, maya.PublicAvatarVersion);
        // The partner's row is untouched.
        Assert.Equal(0, (await check.Users.AsNoTracking().SingleAsync(item => item.Id == couple.TheoId)).PublicAvatarVersion);
    }

    [Fact]
    public async Task A_refused_upload_changes_nothing()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);

        var result = await Service(db).SetAsync(couple.MayaId, "image/jpeg", "not a picture"u8.ToArray(), default);

        Assert.True(result.IsFailure);
        await using var check = database.CreateContext();
        var maya = await check.Users.AsNoTracking().SingleAsync(item => item.Id == couple.MayaId);
        Assert.Null(maya.AvatarBytes);
        Assert.Equal(0, maya.AvatarVersion);
    }

    [Fact]
    public async Task Removing_clears_the_picture_reports_zero_and_keeps_versions_from_repeating()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var service = Service(db);
        await service.SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);

        Assert.True(await service.ClearAsync(couple.MayaId, default));
        Assert.True(await service.ClearAsync(couple.MayaId, default));

        await using var check = database.CreateContext();
        var maya = await check.Users.AsNoTracking().SingleAsync(item => item.Id == couple.MayaId);
        Assert.Null(maya.AvatarBytes);
        Assert.Equal(0, maya.PublicAvatarVersion);
        // Set (1), removed (2); the second removal changes nothing, and the next upload cannot reuse an old ETag.
        Assert.Equal(2, maya.AvatarVersion);
        Assert.Equal(3, (await service.SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default)).Value.AvatarVersion);
        Assert.False(await service.ClearAsync(Guid.NewGuid(), default));
    }

    [Fact]
    public async Task Only_the_owner_and_the_current_partner_can_see_a_picture()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var stranger = await TestCouple.AddAsync(db);
        var service = Service(db);
        await service.SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);

        Assert.Equal(1, await service.VisibleVersionAsync(couple.MayaId, couple.MayaId, default));
        Assert.Equal(1, await service.VisibleVersionAsync(couple.TheoId, couple.MayaId, default));
        Assert.Equal(AvatarImageTests.Png, (await service.LoadAsync(couple.TheoId, couple.MayaId, default))!.Bytes);
        Assert.Null(await service.VisibleVersionAsync(stranger.MayaId, couple.MayaId, default));
        Assert.Null(await service.LoadAsync(stranger.TheoId, couple.MayaId, default));
        Assert.Null(await service.VisibleVersionAsync(Guid.NewGuid(), couple.MayaId, default));
        // Nothing to see for a partner without a picture.
        Assert.Null(await service.VisibleVersionAsync(couple.MayaId, couple.TheoId, default));
    }

    [Fact]
    public async Task After_a_breakup_the_old_partner_gets_nothing_even_though_the_picture_is_still_there()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var service = Service(db);
        await service.SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        await db.Database.ExecuteSqlRawAsync("UPDATE users SET \"CoupleId\" = NULL");
        await db.Database.ExecuteSqlRawAsync("UPDATE couples SET \"Status\" = 'Dissolved'");

        Assert.Null(await service.VisibleVersionAsync(couple.TheoId, couple.MayaId, default));
        Assert.Equal(1, await service.VisibleVersionAsync(couple.MayaId, couple.MayaId, default));
    }

    [Fact]
    public async Task The_endpoint_serves_bytes_with_type_etag_and_private_cache_headers()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await Service(db).SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        var http = new DefaultHttpContext();

        var result = await Controller(db, couple.TheoId, couple.CoupleId, http).Get(couple.MayaId, default);

        var file = Assert.IsType<FileContentResult>(result);
        Assert.Equal("image/png", file.ContentType);
        Assert.Equal(AvatarImageTests.Png, file.FileContents);
        Assert.Equal("\"1\"", http.Response.Headers.ETag.ToString());
        Assert.Equal("private, max-age=86400", http.Response.Headers.CacheControl.ToString());
        Assert.Equal("nosniff", http.Response.Headers.XContentTypeOptions.ToString());
    }

    [Theory]
    [InlineData("\"1\"")]
    [InlineData("W/\"1\"")]
    [InlineData("\"7\", \"1\"")]
    [InlineData("*")]
    public async Task A_matching_if_none_match_is_answered_304_without_the_picture(string ifNoneMatch)
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await Service(db).SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        var http = new DefaultHttpContext();
        http.Request.Headers.IfNoneMatch = ifNoneMatch;

        var result = await Controller(db, couple.MayaId, couple.CoupleId, http).Get(couple.MayaId, default);

        Assert.Equal(StatusCodes.Status304NotModified, Assert.IsType<StatusCodeResult>(result).StatusCode);
        Assert.Equal("\"1\"", http.Response.Headers.ETag.ToString());
        Assert.Equal("private, max-age=86400", http.Response.Headers.CacheControl.ToString());
    }

    [Fact]
    public async Task A_stale_etag_gets_the_new_picture()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await Service(db).SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        await Service(db).SetAsync(couple.MayaId, "image/jpeg", AvatarImageTests.Jpeg, default);
        var http = new DefaultHttpContext();
        http.Request.Headers.IfNoneMatch = "\"1\"";

        var result = await Controller(db, couple.TheoId, couple.CoupleId, http).Get(couple.MayaId, default);

        Assert.Equal("image/jpeg", Assert.IsType<FileContentResult>(result).ContentType);
        Assert.Equal("\"2\"", http.Response.Headers.ETag.ToString());
    }

    [Fact]
    public async Task A_stranger_and_a_missing_picture_are_both_404_and_leave_no_cache_headers()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        var stranger = await TestCouple.AddAsync(db);
        await Service(db).SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        var http = new DefaultHttpContext();

        Assert.IsType<NotFoundResult>(await Controller(db, stranger.MayaId, stranger.CoupleId, http).Get(couple.MayaId, default));
        Assert.IsType<NotFoundResult>(await Controller(db, couple.MayaId, couple.CoupleId, http).Get(couple.TheoId, default));
        Assert.IsType<NotFoundResult>(await Controller(db, couple.MayaId, couple.CoupleId, http).Get(Guid.NewGuid(), default));
        Assert.False(http.Response.Headers.ContainsKey("ETag"));
        Assert.False(http.Response.Headers.ContainsKey("Cache-Control"));
    }

    [Fact]
    public async Task Putting_a_picture_answers_the_new_version()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);

        var result = await Controller(db, couple.MayaId, couple.CoupleId, Upload(AvatarImageTests.Jpeg, "image/jpeg")).Put(default);

        var dto = Assert.IsType<AvatarDto>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.Equal(1, dto.AvatarVersion);
        Assert.Equal("{\"avatarVersion\":1}", JsonSerializer.Serialize(dto, Web));
    }

    [Fact]
    public async Task Putting_the_wrong_thing_is_a_422_like_other_validation_errors()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);

        async Task<(int Status, string Code)> PutAsync(HttpContext http)
        {
            var result = Assert.IsType<ObjectResult>(await Controller(db, couple.MayaId, couple.CoupleId, http).Put(default));
            return (result.StatusCode!.Value, Assert.IsType<ApiError>(result.Value).Code);
        }

        Assert.Equal((422, "avatar.unsupported_type"), await PutAsync(Upload(AvatarImageTests.Png, "image/gif")));
        Assert.Equal((422, "avatar.not_an_image"), await PutAsync(Upload("<script>alert(1)</script>"u8.ToArray(), "image/png")));
        Assert.Equal((422, "avatar.empty"), await PutAsync(Upload([], "image/png")));
        // A declared length over the limit is refused before anything is read.
        var declared = Upload(AvatarImageTests.Png, "image/png");
        declared.Request.ContentLength = AvatarImage.MaxBytes + 1;
        Assert.Equal((422, "avatar.too_large"), await PutAsync(declared));
        // No declared length (chunked) and more than the limit: stopped after the limit.
        var oversized = Upload([.. AvatarImageTests.Png, .. new byte[AvatarImage.MaxBytes]], "image/png", declareLength: false);
        Assert.Equal((422, "avatar.too_large"), await PutAsync(oversized));
        await using var check = database.CreateContext();
        Assert.Null((await check.Users.AsNoTracking().SingleAsync(item => item.Id == couple.MayaId)).AvatarBytes);
    }

    [Fact]
    public async Task Delete_removes_the_callers_own_picture_only()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await Service(db).SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        await Service(db).SetAsync(couple.TheoId, "image/png", AvatarImageTests.Png, default);

        Assert.IsType<NoContentResult>(await Controller(db, couple.MayaId, couple.CoupleId).Delete(default));

        await using var check = database.CreateContext();
        Assert.Null((await check.Users.AsNoTracking().SingleAsync(item => item.Id == couple.MayaId)).AvatarBytes);
        Assert.NotNull((await check.Users.AsNoTracking().SingleAsync(item => item.Id == couple.TheoId)).AvatarBytes);
    }

    [Fact]
    public async Task Members_and_the_signed_in_user_carry_avatarVersion_zero_without_a_picture()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await Service(db).SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        await Service(db).SetAsync(couple.TheoId, "image/png", AvatarImageTests.Png, default);
        await Service(db).SetAsync(couple.TheoId, "image/png", AvatarImageTests.Png, default);
        await Service(db).ClearAsync(couple.MayaId, default);

        var coupleController = new CoupleController(db, new FakeCurrentUser(couple.MayaId, couple.CoupleId), clock, null!, null!, null!, null!, null!);
        var dto = Assert.IsType<CoupleDto>(Assert.IsType<OkObjectResult>((await coupleController.Get(default)).Result).Value);

        Assert.Equal([0L, 2L], dto.Members.Select(member => member.AvatarVersion));
        Assert.Contains("\"avatarVersion\":2", JsonSerializer.Serialize(dto.Members[1], Web));

        // A fresh context: the one above still tracks the users as they were created.
        await using var fresh = database.CreateContext();
        var auth = new AuthController(fresh, null!, null!, new FakeCurrentUser(couple.TheoId, couple.CoupleId), clock, null!, null!, null!, null!, null!, null!);
        var me = Assert.IsType<AuthUserDto>(Assert.IsType<OkObjectResult>((await auth.Me(default)).Result).Value);
        Assert.Equal(2, me.AvatarVersion);
        Assert.Contains("\"avatarVersion\":2", JsonSerializer.Serialize(me, Web));
    }

    [Fact]
    public async Task The_export_holds_the_picture_and_deleting_the_account_takes_it_away()
    {
        await using var db = database.CreateContext();
        var couple = await TestCouple.AddAsync(db);
        await Service(db).SetAsync(couple.MayaId, "image/png", AvatarImageTests.Png, default);
        var exporter = new AccountExportService(db, new FakeMediaStorage(), new MediaSettings(), clock);

        var profile = JsonSerializer.Serialize((await exporter.ExportAsync(couple.MayaId, default))!.Profile, Web);

        Assert.Contains("\"profilePicture\":{\"contentType\":\"image/png\"", profile);
        Assert.Contains(Convert.ToBase64String(AvatarImageTests.Png), profile);
        Assert.Contains("\"profilePicture\":null", JsonSerializer.Serialize((await exporter.ExportAsync(couple.TheoId, default))!.Profile, Web));

        await new AccountEraser(db, clock).EraseAsync(couple.MayaId, default);

        await using var check = database.CreateContext();
        Assert.False(await check.Users.AnyAsync(item => item.Id == couple.MayaId));
        Assert.Null(await Service(check).VisibleVersionAsync(couple.TheoId, couple.MayaId, default));
    }
}
