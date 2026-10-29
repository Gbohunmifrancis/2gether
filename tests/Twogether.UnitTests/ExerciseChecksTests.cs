using System.Text.Json;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Infrastructure.Exercise;

namespace Twogether.UnitTests;

public sealed class ExerciseChecksTests
{
    private static ExerciseOccurrence InProgress(DateTime utcNow)
    {
        var goal = ExerciseGoal.Create(Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid(), ExerciseType.Squat, null, 15, new TimeOnly(7, 0), "Africa/Lagos", 30, utcNow.AddDays(-1));
        var occurrence = ExerciseOccurrence.Open(goal, DateOnly.FromDateTime(utcNow), utcNow, utcNow);
        occurrence.Start(utcNow);
        return occurrence;
    }

    [Fact]
    public void A_clean_session_has_no_flags_and_form_faults_alone_are_not_flags()
    {
        var checks = new ExerciseCheckCounts(FormFaults: 6, FastReps: 2, ChallengesPassed: 2, ChallengesMissed: 0, PhoneMovedReps: 1, OutOfFrameSeconds: 19);
        Assert.Empty(checks.Flags(ExerciseIntegrity.Passed));
        Assert.Empty(checks.Flags(ExerciseIntegrity.NotChecked));
        Assert.Null(checks.Summary(ExerciseIntegrity.Passed));
    }

    [Fact]
    public void Suspicious_sessions_are_flagged_and_summarised_for_the_partner()
    {
        var checks = new ExerciseCheckCounts(0, 0, 1, 2, 3, 25);
        Assert.Equal([ExerciseFlag.MissedCheckIn, ExerciseFlag.PhoneMoved, ExerciseFlag.LeftFrame, ExerciseFlag.UnverifiedApp], checks.Flags(ExerciseIntegrity.Failed));
        Assert.Equal("Missed 2 camera check-ins, the phone was moving during 3 reps, out of view for 25 seconds, the app couldn't be verified.", checks.Summary(ExerciseIntegrity.Failed));
        Assert.Equal("Missed a camera check-in.", new ExerciseCheckCounts(0, 0, 0, 1, 0, 0).Summary(ExerciseIntegrity.NotChecked));
        Assert.Contains(ExerciseFlag.UnverifiedApp, default(ExerciseCheckCounts).Flags(ExerciseIntegrity.Missing));
    }

    [Fact]
    public void Completion_stores_clamped_checks_and_a_new_session_clears_them()
    {
        var now = DateTime.UtcNow;
        var occurrence = InProgress(now);
        occurrence.Complete(15, 0.9, now.AddMinutes(1), new ExerciseCheckCounts(-3, 4, 1, 1, 50_000, int.MaxValue), ExerciseIntegrity.Passed);
        Assert.Equal(new ExerciseCheckCounts(0, 4, 1, 1, ExerciseCheckCounts.MaxCount, ExerciseCheckCounts.MaxSeconds), occurrence.Checks);
        Assert.Equal(ExerciseIntegrity.Passed, occurrence.Integrity);

        var again = InProgress(now);
        again.Complete(15, 0.9, now.AddMinutes(1), new ExerciseCheckCounts(1, 1, 1, 1, 1, 1), ExerciseIntegrity.Failed);
        var fresh = InProgress(now);
        Assert.Equal(default, fresh.Checks);
        Assert.Equal(ExerciseIntegrity.NotChecked, fresh.Integrity);
    }

    [Fact]
    public void The_dto_carries_the_checks_and_their_flags()
    {
        var now = DateTime.UtcNow;
        var occurrence = InProgress(now);
        occurrence.Complete(15, 0.9, now.AddMinutes(1), new ExerciseCheckCounts(2, 0, 0, 1, 0, 0), ExerciseIntegrity.Passed);
        var dto = ExerciseMapper.ToDto(occurrence, "Maya");
        Assert.NotNull(dto.Checks);
        Assert.Equal(2, dto.Checks.FormFaults);
        Assert.Equal([ExerciseFlag.MissedCheckIn], dto.Checks.Flags);
    }

    private static readonly DateTime Now = new(2026, 10, 23, 12, 0, 0, DateTimeKind.Utc);
    private const string Package = "app.heartpact";

    private static IntegrityPayload Good(string hash) => new(Package, hash, new DateTimeOffset(Now.AddMinutes(-1)).ToUnixTimeMilliseconds(), "PLAY_RECOGNIZED", ["MEETS_DEVICE_INTEGRITY"]);

    /// <summary>Pinned so the Android side's test can check it computes the same value.</summary>
    private const string KnownHash = "9ab43e1d6c898e55542d5ee870522a80eed659a67120a09dc2ce9da4a4c9e7ca";
    private const string KnownHashWithChecks = "b23443d94197b9521b5e553d54e7e8ec6f8a8e77a33df3d6d56d81295f3d9a87";

    [Fact]
    public void Request_hash_binds_the_token_to_one_occurrence_and_count()
    {
        var id = Guid.Parse("6f1c2a52-6a3c-4c76-9b1e-2f0a0d5b9c11");
        var hash = PlayIntegrityRules.RequestHash(id, 15);
        Assert.Equal(64, hash.Length);
        Assert.Equal(hash, PlayIntegrityRules.RequestHash(id, 15));
        Assert.NotEqual(hash, PlayIntegrityRules.RequestHash(id, 16));
        Assert.NotEqual(hash, PlayIntegrityRules.RequestHash(Guid.NewGuid(), 15));
        // The app computes the same: SHA-256 of "heartpact:exercise:<id>:<count>:<six check numbers>", lower-case hex.
        Assert.Equal(KnownHash, hash);
        var checks = new ExerciseCheckCounts(2, 1, 1, 0, 3, 4);
        Assert.Equal(KnownHashWithChecks, PlayIntegrityRules.RequestHash(id, 15, checks));
        // Made-up checks don't match a token requested for the real ones.
        Assert.NotEqual(KnownHashWithChecks, PlayIntegrityRules.RequestHash(id, 15, checks with { ChallengesMissed = 0, PhoneMovedReps = 0 }));
    }

    [Fact]
    public void Integrity_passes_only_for_a_recent_token_for_this_request_from_a_genuine_app_and_device()
    {
        var hash = PlayIntegrityRules.RequestHash(Guid.NewGuid(), 15);
        Assert.Equal(ExerciseIntegrity.Passed, PlayIntegrityRules.Evaluate(Good(hash), Package, hash, Now));
        Assert.Equal(ExerciseIntegrity.Passed, PlayIntegrityRules.Evaluate(Good(hash) with { DeviceRecognitionVerdict = ["MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY"] }, Package, hash, Now));

        Assert.Equal(ExerciseIntegrity.Failed, PlayIntegrityRules.Evaluate(Good(hash), Package, PlayIntegrityRules.RequestHash(Guid.NewGuid(), 15), Now));
        Assert.Equal(ExerciseIntegrity.Failed, PlayIntegrityRules.Evaluate(Good(hash) with { RequestPackageName = "com.copycat" }, Package, hash, Now));
        Assert.Equal(ExerciseIntegrity.Failed, PlayIntegrityRules.Evaluate(Good(hash) with { AppRecognitionVerdict = "UNRECOGNIZED_VERSION" }, Package, hash, Now));
        Assert.Equal(ExerciseIntegrity.Failed, PlayIntegrityRules.Evaluate(Good(hash) with { DeviceRecognitionVerdict = ["MEETS_BASIC_INTEGRITY"] }, Package, hash, Now));
        Assert.Equal(ExerciseIntegrity.Failed, PlayIntegrityRules.Evaluate(Good(hash) with { DeviceRecognitionVerdict = [] }, Package, hash, Now));
        Assert.Equal(ExerciseIntegrity.Failed, PlayIntegrityRules.Evaluate(Good(hash) with { TimestampMillis = new DateTimeOffset(Now.AddMinutes(-11)).ToUnixTimeMilliseconds() }, Package, hash, Now));
        Assert.Equal(ExerciseIntegrity.Failed, PlayIntegrityRules.Evaluate(Good(hash) with { TimestampMillis = null }, Package, hash, Now));
    }

    [Fact]
    public void Google_s_decoded_payload_is_read_including_string_timestamps()
    {
        const string json = """
            {"tokenPayloadExternal":{
              "requestDetails":{"requestPackageName":"app.heartpact","requestHash":"abc","timestampMillis":"1792929600000"},
              "appIntegrity":{"appRecognitionVerdict":"PLAY_RECOGNIZED","packageName":"app.heartpact"},
              "deviceIntegrity":{"deviceRecognitionVerdict":["MEETS_DEVICE_INTEGRITY"]},
              "accountDetails":{"appLicensingVerdict":"LICENSED"}}}
            """;
        using var document = JsonDocument.Parse(json);
        var payload = PlayIntegrityVerifier.Read(document.RootElement);
        Assert.Equal("app.heartpact", payload.RequestPackageName);
        Assert.Equal("abc", payload.RequestHash);
        Assert.Equal(1792929600000L, payload.TimestampMillis);
        Assert.Equal("PLAY_RECOGNIZED", payload.AppRecognitionVerdict);
        Assert.Equal(["MEETS_DEVICE_INTEGRITY"], payload.DeviceRecognitionVerdict);

        using var empty = JsonDocument.Parse("{}");
        var nothing = PlayIntegrityVerifier.Read(empty.RootElement);
        Assert.Null(nothing.RequestHash);
        Assert.Empty(nothing.DeviceRecognitionVerdict);
    }

    [Fact]
    public async Task Fake_and_disabled_verifiers_behave_as_documented()
    {
        var fake = new FakePlayIntegrityVerifier();
        Assert.Equal(ExerciseIntegrity.Passed, await fake.VerifyAsync(FakePlayIntegrityVerifier.PassToken, "h", default));
        Assert.Equal(ExerciseIntegrity.Failed, await fake.VerifyAsync("forged", "h", default));
        Assert.Equal(ExerciseIntegrity.Missing, await fake.VerifyAsync(null, "h", default));
        Assert.Equal(ExerciseIntegrity.NotChecked, await new DisabledPlayIntegrityVerifier().VerifyAsync("anything", "h", default));
    }
}
