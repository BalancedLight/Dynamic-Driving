using System.Text.Json.Nodes;
using DynamicDriving.Core.Manifests;
using Xunit;

namespace DynamicDriving.Tests;

/// <summary>
/// Parses the shared corpus in <c>contracts/fixtures</c> and asserts the projection in
/// <c>expected.json</c>.
///
/// The Kotlin suite (<c>SongManifestContractTest</c>) runs the identical assertions against the
/// identical files. If one parser drifts, one of the two suites fails.
/// </summary>
public sealed class SongManifestContractTests
{
    private static readonly JsonObject Expected = LoadExpected();

    public static TheoryData<string> FixtureNames()
    {
        var data = new TheoryData<string>();
        foreach (var fixtureName in Expected.Select(entry => entry.Key))
        {
            data.Add(fixtureName);
        }

        return data;
    }

    [Theory]
    [MemberData(nameof(FixtureNames))]
    public void Fixture_matches_the_shared_expected_projection(string fixtureName)
    {
        var manifest = ParseFixture(fixtureName);
        var expectation = Expected[fixtureName]!.AsObject();

        Assert.Equal(expectation["songId"]!.GetValue<string>(), manifest.SongId);
        Assert.Equal(expectation["displayName"]!.GetValue<string>(), manifest.DisplayName);
        Assert.Equal(OptionalString(expectation, "artist"), manifest.Artist);
        Assert.Equal(OptionalString(expectation, "album"), manifest.Album);
        Assert.Equal(expectation["transportStemId"]!.GetValue<string>(), manifest.TransportStemId);

        var expectedLoop = expectation["loop"]!.AsObject();
        Assert.Equal(expectedLoop["startMs"]!.GetValue<long>(), manifest.LoopRegion.StartMs);
        Assert.Equal(expectedLoop["endMs"]!.GetValue<long>(), manifest.LoopRegion.EndMs);
        Assert.Equal(expectedLoop["playTailOverLoop"]!.GetValue<bool>(), manifest.LoopRegion.PlayTailOverLoop);
        Assert.Equal(expectedLoop["loopStartsSong"]!.GetValue<bool>(), manifest.LoopRegion.LoopStartsSong);

        AssertMuffle(expectation, manifest.Muffle);
        AssertStems(expectation, manifest);
    }

    [Fact]
    public void Missing_artist_and_album_stay_null_rather_than_becoming_placeholders()
    {
        var manifest = ParseFixture("minimal.json");

        Assert.Null(manifest.Artist);
        Assert.Null(manifest.Album);
    }

    [Fact]
    public void Gain_multiplier_above_unity_survives_parse_and_round_trip()
    {
        var manifest = ParseFixture("events.json");
        var modifier = Assert.IsType<GainMultiplierModifier>(
            manifest.Stems.Single().Events[0].Modifiers[0]);
        Assert.Equal(1.75, modifier.Multiplier, 6);

        var reparsed = SongManifestSerializer.Deserialize(SongManifestSerializer.Serialize(manifest));
        var reparsedModifier = Assert.IsType<GainMultiplierModifier>(
            reparsed.Stems.Single().Events[0].Modifiers[0]);
        Assert.Equal(1.75, reparsedModifier.Multiplier, 6);
    }

    [Fact]
    public void Blank_artist_and_album_are_treated_as_absent()
    {
        var manifest = SongManifestSerializer.Deserialize(
            """
            {
              "songId": "blank_metadata",
              "displayName": "Blank Metadata",
              "artist": "   ",
              "album": "",
              "transportStemId": "main",
              "loopRegion": { "startMs": 0, "endMs": 1000 },
              "stems": [
                { "stemId": "main", "displayName": "Main", "assetPath": "main.wav", "rule": { "type": "base" } }
              ]
            }
            """);

        Assert.Null(manifest.Artist);
        Assert.Null(manifest.Album);
        Assert.DoesNotContain("\"artist\"", SongManifestSerializer.Serialize(manifest));
        Assert.DoesNotContain("\"album\"", SongManifestSerializer.Serialize(manifest));
    }

    [Theory]
    [MemberData(nameof(FixtureNames))]
    public void Fixture_survives_a_serialize_parse_round_trip(string fixtureName)
    {
        var original = ParseFixture(fixtureName);
        var reparsed = SongManifestSerializer.Deserialize(SongManifestSerializer.Serialize(original));

        Assert.Equal(original.SongId, reparsed.SongId);
        Assert.Equal(original.DisplayName, reparsed.DisplayName);
        Assert.Equal(original.Artist, reparsed.Artist);
        Assert.Equal(original.Album, reparsed.Album);
        Assert.Equal(original.TransportStemId, reparsed.TransportStemId);
        Assert.Equal(original.LoopRegion.StartMs, reparsed.LoopRegion.StartMs);
        Assert.Equal(original.LoopRegion.EndMs, reparsed.LoopRegion.EndMs);
        Assert.Equal(original.LoopRegion.PlayTailOverLoop, reparsed.LoopRegion.PlayTailOverLoop);
        Assert.Equal(original.LoopRegion.LoopStartsSong, reparsed.LoopRegion.LoopStartsSong);
        Assert.Equal(original.Stems.Count, reparsed.Stems.Count);

        for (var index = 0; index < original.Stems.Count; index++)
        {
            var before = original.Stems[index];
            var after = reparsed.Stems[index];
            Assert.Equal(before.StemId, after.StemId);
            Assert.Equal(before.AssetPath, after.AssetPath);
            Assert.Equal(before.Gain, after.Gain, 6);
            Assert.Equal(before.FadeInMs, after.FadeInMs);
            Assert.Equal(before.FadeOutMs, after.FadeOutMs);
            Assert.Equal(before.PlayTailOverLoop, after.PlayTailOverLoop);
            Assert.Equal(before.Rule.GetType(), after.Rule.GetType());
            Assert.Equal(before.Events.Count, after.Events.Count);
        }
    }

    [Fact]
    public void Unknown_properties_survive_a_round_trip_verbatim()
    {
        var fixturePath = Path.Combine(ContractFixtures.Directory, "unknown-properties.json");
        var manifest = SongManifestSerializer.Deserialize(File.ReadAllText(fixturePath));

        var rewritten = JsonNode.Parse(SongManifestSerializer.Serialize(manifest))!.AsObject();

        Assert.True(rewritten.ContainsKey("futureTopLevelField"));
        Assert.True(rewritten["futureTopLevelField"]!.AsObject()["reserved"]!.GetValue<bool>());
        Assert.Equal(42, rewritten["loopRegion"]!.AsObject()["futureLoopField"]!.GetValue<int>());

        var stem = rewritten["stems"]!.AsArray()[0]!.AsObject();
        Assert.Equal("kept verbatim", stem["futureStemField"]!.GetValue<string>());
        Assert.Equal(1.5, stem["rule"]!.AsObject()["futureRuleField"]!.GetValue<double>(), 6);
    }

    private static SongManifest ParseFixture(string fixtureName) =>
        SongManifestSerializer.Deserialize(
            File.ReadAllText(Path.Combine(ContractFixtures.Directory, fixtureName)));

    private static void AssertMuffle(JsonObject expectation, MuffleSettings? actual)
    {
        if (expectation["muffle"] is not JsonObject expectedMuffle)
        {
            Assert.Null(actual);
            return;
        }

        Assert.NotNull(actual);
        Assert.Equal(expectedMuffle["releaseMph"]!.GetValue<double>(), actual!.ReleaseMph, 6);
        Assert.Equal(expectedMuffle["wetMix"]!.GetValue<double>(), actual.WetMix, 6);
        Assert.Equal(expectedMuffle["cutoffHz"]!.GetValue<double>(), actual.CutoffHz, 6);
        Assert.Equal(expectedMuffle["fadeMs"]!.GetValue<long>(), actual.FadeMs);
    }

    private static void AssertStems(JsonObject expectation, SongManifest manifest)
    {
        var expectedStems = expectation["stems"]!.AsArray();
        Assert.Equal(expectedStems.Count, manifest.Stems.Count);

        for (var index = 0; index < expectedStems.Count; index++)
        {
            var expectedStem = expectedStems[index]!.AsObject();
            var stem = manifest.Stems[index];

            Assert.Equal(expectedStem["stemId"]!.GetValue<string>(), stem.StemId);
            Assert.Equal(expectedStem["displayName"]!.GetValue<string>(), stem.DisplayName);
            Assert.Equal(expectedStem["assetPath"]!.GetValue<string>(), stem.AssetPath);
            Assert.Equal(expectedStem["playTailOverLoop"]!.GetValue<bool>(), stem.PlayTailOverLoop);
            Assert.Equal(expectedStem["gain"]!.GetValue<double>(), stem.Gain, 6);
            Assert.Equal(expectedStem["fadeInMs"]!.GetValue<long>(), stem.FadeInMs);
            Assert.Equal(expectedStem["fadeOutMs"]!.GetValue<long>(), stem.FadeOutMs);
            Assert.Equal(expectedStem["eventCount"]!.GetValue<int>(), stem.Events.Count);

            switch (expectedStem["ruleType"]!.GetValue<string>())
            {
                case "base":
                    Assert.IsType<BaseStemRule>(stem.Rule);
                    Assert.Equal(OptionalDouble(expectedStem, "minMph"), stem.Rule.MinMph);
                    Assert.Equal(OptionalDouble(expectedStem, "maxMphExclusive"), stem.Rule.MaxMphExclusive);
                    Assert.Null(OptionalString(expectedStem, "groupId"));
                    break;

                case "overlay":
                    var overlay = Assert.IsType<OverlayStemRule>(stem.Rule);
                    Assert.Equal(expectedStem["minMph"]!.GetValue<double>(), overlay.MinMph!.Value, 6);
                    Assert.Equal(OptionalDouble(expectedStem, "maxMphExclusive"), overlay.MaxMphExclusive);
                    Assert.Equal(expectedStem["groupId"]!.GetValue<string>(), overlay.GroupId);
                    break;

                default:
                    throw new InvalidOperationException("Unsupported rule type in the expected projection.");
            }
        }
    }

    private static string? OptionalString(JsonObject parent, string key) =>
        parent[key] is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;

    private static double? OptionalDouble(JsonObject parent, string key) =>
        parent[key] is JsonValue value && value.TryGetValue<double>(out var number) ? number : null;

    private static JsonObject LoadExpected()
    {
        var path = Path.Combine(ContractFixtures.Directory, "expected.json");
        var root = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
        return root["fixtures"]!.AsObject();
    }
}

/// <summary>Finds <c>contracts/fixtures</c> from wherever the test host started.</summary>
internal static class ContractFixtures
{
    public static string Directory { get; } = Locate();

    private static string Locate()
    {
        var candidate = new DirectoryInfo(AppContext.BaseDirectory);
        while (candidate is not null)
        {
            var fixtures = Path.Combine(candidate.FullName, "contracts", "fixtures");
            if (System.IO.Directory.Exists(fixtures))
            {
                return fixtures;
            }

            candidate = candidate.Parent;
        }

        throw new DirectoryNotFoundException(
            $"Could not locate contracts/fixtures from {AppContext.BaseDirectory}");
    }
}
