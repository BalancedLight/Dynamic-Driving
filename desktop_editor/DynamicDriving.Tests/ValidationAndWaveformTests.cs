using DynamicDriving.Core.Audio;
using DynamicDriving.Core.Manifests;
using Xunit;

namespace DynamicDriving.Tests;

public sealed class ValidationAndWaveformTests
{
    [Fact]
    public void A_well_formed_song_validates_cleanly()
    {
        using var folder = new TempFolder();
        var manifest = TwoStemSong(folder.Path, sampleRateMatches: true);

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.True(report.IsValid, string.Join("; ", report.Errors.Select(issue => issue.Message)));
    }

    [Fact]
    public void Stems_of_different_lengths_are_rejected()
    {
        using var folder = new TempFolder();
        TestAudio.WriteWav(folder.Path, "bed.wav", TestAudio.Tone(220, 2_000));
        TestAudio.WriteWav(folder.Path, "lift.wav", TestAudio.Tone(440, 1_000));
        var manifest = SongFolder.CreateDefaultManifest(folder.Path, new[] { "bed.wav", "lift.wav" });
        manifest.LoopRegion.EndMs = 900;

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.False(report.IsValid);
        Assert.Contains(report.Errors, issue => issue.Message.Contains("does not match"));
    }

    [Fact]
    public void A_negative_loop_start_is_rejected()
    {
        using var folder = new TempFolder();
        var manifest = TwoStemSong(folder.Path, sampleRateMatches: true);
        manifest.LoopRegion.StartMs = -100;

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.Contains(report.Errors, issue => issue.Message.Contains("non-negative"));
    }

    [Theory]
    [InlineData("../outside.wav")]
    [InlineData("sub/../outside.wav")]
    [InlineData("./bed.wav")]
    [InlineData("C:\\outside.wav")]
    [InlineData("/outside.wav")]
    [InlineData("sub//bed.wav")]
    public void Asset_paths_must_stay_inside_the_song_folder(string assetPath)
    {
        using var folder = new TempFolder();
        var manifest = TwoStemSong(folder.Path, sampleRateMatches: true);
        manifest.Stems[0].AssetPath = assetPath;

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.Contains(report.Errors, issue =>
            issue.StemId == manifest.Stems[0].StemId && issue.Message.Contains("relative path"));
    }

    [Fact]
    public void A_wav_whose_declared_data_extends_past_eof_is_rejected()
    {
        using var folder = new TempFolder();
        var path = TestAudio.WriteWav(folder.Path, "bed.wav", TestAudio.Tone(220, 2_000));
        using (var stream = File.Open(path, FileMode.Open, FileAccess.Write, FileShare.None))
        using (var writer = new BinaryWriter(stream))
        {
            stream.Position = 40;
            writer.Write(100_000);
        }
        var manifest = SongFolder.CreateDefaultManifest(folder.Path, new[] { "bed.wav" });
        manifest.LoopRegion.EndMs = 1_000;

        var readError = Assert.Throws<WavFormatException>(() => WavFile.Read(path));
        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.Contains("declared audio data", readError.Message);
        Assert.Contains(report.Errors, issue => issue.Message.Contains("declared audio data"));
    }

    [Fact]
    public void A_missing_stem_file_is_reported_against_the_stem()
    {
        using var folder = new TempFolder();
        TestAudio.WriteWav(folder.Path, "bed.wav", TestAudio.Tone(220, 2_000));
        var manifest = SongFolder.CreateDefaultManifest(folder.Path, new[] { "bed.wav" });
        manifest.Stems.Add(new StemManifest
        {
            StemId = "ghost",
            DisplayName = "Ghost",
            AssetPath = "ghost.wav",
            Rule = new BaseStemRule()
        });
        manifest.LoopRegion.EndMs = 1_000;

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.False(report.IsValid);
        Assert.Contains(report.Errors, issue => issue.StemId == "ghost" && issue.Message.Contains("was not found"));
    }

    [Fact]
    public void A_transport_stem_that_is_not_in_the_song_is_rejected()
    {
        using var folder = new TempFolder();
        var manifest = TwoStemSong(folder.Path, sampleRateMatches: true);
        manifest.TransportStemId = "nope";

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.Contains(report.Errors, issue => issue.Message.Contains("Transport stem"));
    }

    [Fact]
    public void Duplicate_stem_ids_are_rejected()
    {
        using var folder = new TempFolder();
        var manifest = TwoStemSong(folder.Path, sampleRateMatches: true);
        manifest.Stems[1].StemId = manifest.Stems[0].StemId;

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.Contains(report.Errors, issue => issue.Message.Contains("used more than once"));
    }

    [Fact]
    public void An_event_with_no_modifiers_is_rejected()
    {
        using var folder = new TempFolder();
        var manifest = TwoStemSong(folder.Path, sampleRateMatches: true);
        manifest.Stems[0].Events.Add(new StemEvent { EventId = "empty" });

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.Contains(report.Errors, issue => issue.Message.Contains("no modifiers"));
    }

    [Fact]
    public void An_inverted_overlay_cooldown_is_a_warning_rather_than_an_error()
    {
        using var folder = new TempFolder();
        var manifest = TwoStemSong(folder.Path, sampleRateMatches: true);
        manifest.Stems[1].Rule = new OverlayStemRule
        {
            MinMph = 10,
            GroupId = "lead",
            CooldownMinMs = 5_000,
            CooldownMaxMs = 1_000
        };

        var report = SongManifestValidator.Validate(manifest, folder.Path);

        Assert.True(report.IsValid);
        Assert.Contains(report.Warnings, issue => issue.Message.Contains("cooldown"));
    }

    [Fact]
    public void A_waveform_has_the_requested_resolution_and_a_normalised_peak()
    {
        var audio = new WavAudio(
            new WavHeader(TestAudio.SampleRate, 1, 16, TestAudio.SampleRate),
            TestAudio.Tone(220, 1_000, amplitude: 0.25));

        var waveform = WaveformGenerator.Generate(audio, binCount: 256);

        Assert.Equal(256, waveform.Peaks.Count);
        Assert.Equal(1_000.0, waveform.DurationMs, 1);
        Assert.Equal(1.0f, waveform.Peaks.Max(), 2);
        Assert.All(waveform.Peaks, peak => Assert.InRange(peak, 0f, 1f));
    }

    [Fact]
    public void A_waveform_of_silence_is_all_zeroes_rather_than_a_divide_by_zero()
    {
        var audio = new WavAudio(
            new WavHeader(TestAudio.SampleRate, 1, 16, TestAudio.SampleRate),
            new short[TestAudio.SampleRate]);

        var waveform = WaveformGenerator.Generate(audio, binCount: 64);

        Assert.Equal(64, waveform.Peaks.Count);
        Assert.All(waveform.Peaks, peak => Assert.Equal(0f, peak));
    }

    [Fact]
    public void An_empty_waveform_request_returns_the_empty_waveform()
    {
        var audio = new WavAudio(new WavHeader(TestAudio.SampleRate, 1, 16, 0), Array.Empty<short>());

        var waveform = WaveformGenerator.Generate(audio);

        Assert.Empty(waveform.Peaks);
    }

    private static SongManifest TwoStemSong(string folderPath, bool sampleRateMatches)
    {
        TestAudio.WriteWav(folderPath, "bed.wav", TestAudio.Tone(220, 2_000));
        TestAudio.WriteWav(folderPath, "lift.wav", TestAudio.Tone(440, 2_000));
        var manifest = SongFolder.CreateDefaultManifest(folderPath, new[] { "bed.wav", "lift.wav" });
        manifest.LoopRegion.EndMs = 1_800;
        return manifest;
    }
}
