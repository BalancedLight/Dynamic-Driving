using DynamicDriving.Core.Audio;
using DynamicDriving.Core.Manifests;
using DynamicDriving.Core.Playback;
using Xunit;

namespace DynamicDriving.Tests;

/// <summary>
/// Exercises the mixer against audio generated on the fly.
///
/// Nothing here touches an output device: the sample provider is pumped directly, so the same
/// assertions hold on a build agent with no sound card.
/// </summary>
public sealed class GeneratedAudioTests
{
    [Fact]
    public void A_wav_round_trips_through_the_reader_and_writer()
    {
        using var folder = new TempFolder();
        var samples = TestAudio.Tone(440, 250, amplitude: 0.75);
        var path = TestAudio.WriteWav(folder.Path, "tone.wav", samples);

        var audio = WavFile.Read(path);

        Assert.Equal(TestAudio.SampleRate, audio.SampleRate);
        Assert.Equal(1, audio.ChannelCount);
        Assert.Equal(samples.Length, audio.Samples.Length);
        Assert.Equal(samples, audio.Samples);
        Assert.Equal(250.0, audio.DurationMs, 1);
    }

    [Fact]
    public void A_stereo_wav_round_trips_with_both_channels_intact()
    {
        using var folder = new TempFolder();
        var samples = TestAudio.Tone(440, 100, amplitude: 0.5, channelCount: 2);
        var path = TestAudio.WriteWav(folder.Path, "stereo.wav", samples, channelCount: 2);

        var audio = WavFile.Read(path);

        Assert.Equal(2, audio.ChannelCount);
        Assert.Equal(samples, audio.Samples);
    }

    [Fact]
    public void A_non_pcm_file_is_rejected_with_a_readable_message()
    {
        using var folder = new TempFolder();
        var path = folder.File("broken.wav");
        File.WriteAllBytes(path, "definitely not a wav"u8.ToArray());

        var error = Assert.Throws<WavFormatException>(() => WavFile.Read(path));
        Assert.Contains("RIFF", error.Message);
    }

    [Fact]
    public void A_base_stem_above_its_speed_threshold_becomes_audible()
    {
        var provider = BuildProvider(out _);
        provider.SetSpeedMph(40);
        provider.Play();

        PumpSeconds(provider, 3.0);

        var lift = provider.SnapshotStems().Single(stem => stem.StemId == "lift");
        Assert.True(lift.Eligible);
        Assert.True(lift.CurrentGain > 0.5, $"Expected the lift stem to fade in, got {lift.CurrentGain:F3}.");
    }

    [Fact]
    public void A_base_stem_below_its_speed_threshold_stays_silent()
    {
        var provider = BuildProvider(out _);
        provider.SetSpeedMph(2);
        provider.Play();

        PumpSeconds(provider, 3.0);

        var lift = provider.SnapshotStems().Single(stem => stem.StemId == "lift");
        Assert.False(lift.Eligible);
        Assert.True(lift.CurrentGain < 0.01, $"Expected the lift stem to stay down, got {lift.CurrentGain:F3}.");
    }

    [Fact]
    public void Dropping_the_speed_fades_a_stem_back_out()
    {
        var provider = BuildProvider(out _);
        provider.SetSpeedMph(40);
        provider.Play();
        PumpSeconds(provider, 3.0);
        Assert.True(provider.SnapshotStems().Single(stem => stem.StemId == "lift").CurrentGain > 0.5);

        provider.SetSpeedMph(0);
        PumpSeconds(provider, 3.0);

        Assert.True(provider.SnapshotStems().Single(stem => stem.StemId == "lift").CurrentGain < 0.01);
    }

    [Fact]
    public void Mixing_produces_audible_output_and_never_clips_past_full_scale()
    {
        var provider = BuildProvider(out _);
        provider.SetSpeedMph(50);
        provider.Play();
        PumpSeconds(provider, 2.0);

        var buffer = new float[provider.SampleRate * provider.ChannelCount];
        provider.Read(buffer, 0, buffer.Length);

        Assert.Contains(buffer, sample => Math.Abs(sample) > 0.01f);
        Assert.All(buffer, sample => Assert.InRange(sample, -1f, 1f));
    }

    [Fact]
    public void Active_event_can_amplify_a_stem_above_unity()
    {
        var provider = BuildProvider(out var manifest);
        var boost = new StemEvent
        {
            EventId = "boost",
            Condition = new MphCondition
            {
                Operator = ComparisonOperator.GreaterThanOrEqual,
                Value = 0.0
            }
        };
        boost.Modifiers.Add(new GainMultiplierModifier { Multiplier = 1.75, FadeMs = 1 });
        manifest.Stems.Single(stem => stem.StemId == "bed").Events.Add(boost);
        provider.UpdateManifest(manifest);
        provider.SetSpeedMph(10);
        provider.Play();

        PumpSeconds(provider, 0.1);

        var bed = provider.SnapshotStems().Single(stem => stem.StemId == "bed");
        Assert.Equal(1.75, bed.GainMultiplier, 3);
    }

    [Fact]
    public void A_paused_provider_returns_silence_and_does_not_move_the_playhead()
    {
        var provider = BuildProvider(out _);
        provider.SetSpeedMph(50);

        var buffer = new float[provider.SampleRate * provider.ChannelCount];
        provider.Read(buffer, 0, buffer.Length);

        Assert.All(buffer, sample => Assert.Equal(0f, sample));
        Assert.Equal(0.0, provider.PositionMs, 3);
    }

    [Fact]
    public void Playback_wraps_at_the_loop_boundary()
    {
        var provider = BuildProvider(out _);
        provider.Play();
        provider.SetSpeedMph(30);

        PumpSeconds(provider, 2.5);

        Assert.True(provider.LoopCount >= 1, "Expected at least one loop within 2.5 s of a 2 s loop.");
        Assert.InRange(provider.PositionMs, 0.0, 2_000.0);
    }

    [Fact]
    public void Seeking_moves_the_playhead_without_playing()
    {
        var provider = BuildProvider(out _);

        provider.Seek(1_500);

        Assert.Equal(1_500.0, provider.PositionMs, 3);
    }

    [Fact]
    public void Editing_a_stem_rule_takes_effect_without_reloading_the_audio()
    {
        var provider = BuildProvider(out var manifest);
        provider.SetSpeedMph(10);
        provider.Play();
        PumpSeconds(provider, 2.0);
        Assert.False(provider.SnapshotStems().Single(stem => stem.StemId == "lift").Eligible);

        ((BaseStemRule)manifest.Stems.Single(stem => stem.StemId == "lift").Rule).MinMph = 5.0;
        provider.UpdateManifest(manifest);
        PumpSeconds(provider, 2.0);

        Assert.True(provider.SnapshotStems().Single(stem => stem.StemId == "lift").Eligible);
    }

    [Fact]
    public void Removing_and_readding_a_preloaded_stem_fades_it_out_and_back_in()
    {
        var provider = BuildProvider(out var manifest);
        var liftManifest = manifest.Stems.Single(stem => stem.StemId == "lift");
        provider.SetSpeedMph(40);
        provider.Play();
        PumpSeconds(provider, 1.0);
        Assert.True(provider.SnapshotStems().Single(stem => stem.StemId == "lift").CurrentGain > 0.9);

        manifest.Stems.Remove(liftManifest);
        provider.UpdateManifest(manifest);
        PumpSeconds(provider, 0.5);
        var fadedOut = provider.SnapshotStems().Single(stem => stem.StemId == "lift");
        Assert.False(fadedOut.Eligible);
        Assert.True(fadedOut.CurrentGain < 0.01, $"Expected the unchecked stem to fade out, got {fadedOut.CurrentGain:F3}.");

        manifest.Stems.Add(liftManifest);
        provider.UpdateManifest(manifest);
        PumpSeconds(provider, 0.5);
        var fadedIn = provider.SnapshotStems().Single(stem => stem.StemId == "lift");
        Assert.True(fadedIn.Eligible);
        Assert.True(fadedIn.CurrentGain > 0.9, $"Expected the checked stem to fade in, got {fadedIn.CurrentGain:F3}.");
    }

    [Fact]
    public void Preview_mute_fades_a_stem_without_removing_it_from_the_manifest()
    {
        var provider = BuildProvider(out var manifest);
        provider.SetSpeedMph(40);
        provider.Play();
        PumpSeconds(provider, 1.0);

        provider.SetStemMuted("lift", muted: true);
        PumpSeconds(provider, 0.5);
        var muted = provider.SnapshotStems().Single(stem => stem.StemId == "lift");
        Assert.False(muted.Eligible);
        Assert.True(muted.CurrentGain < 0.01, $"Expected the muted stem to fade out, got {muted.CurrentGain:F3}.");
        Assert.Contains(manifest.Stems, stem => stem.StemId == "lift");

        provider.SetStemMuted("lift", muted: false);
        PumpSeconds(provider, 0.5);
        var unmuted = provider.SnapshotStems().Single(stem => stem.StemId == "lift");
        Assert.True(unmuted.Eligible);
        Assert.True(unmuted.CurrentGain > 0.9, $"Expected the unmuted stem to fade in, got {unmuted.CurrentGain:F3}.");
    }

    [Fact]
    public void Post_loop_tails_advance_across_buffers_and_overlap_without_restarting()
    {
        const int loopFrames = TestAudio.SampleRate / 10;
        var samples = new short[loopFrames * 4];
        Array.Fill(samples, (short)(short.MaxValue * 0.1), loopFrames, loopFrames);
        Array.Fill(samples, (short)(short.MaxValue * 0.2), loopFrames * 2, loopFrames);
        Array.Fill(samples, (short)(short.MaxValue * 0.3), loopFrames * 3, loopFrames);

        var manifest = new SongManifest
        {
            SongId = "tail_cursor",
            DisplayName = "Tail cursor",
            TransportStemId = "main",
            LoopRegion = new LoopRegion
            {
                StartMs = 0,
                EndMs = 100,
                LoopStartsSong = true,
                PlayTailOverLoop = true
            }
        };
        var stem = new StemManifest
        {
            StemId = "main",
            DisplayName = "Main",
            AssetPath = "main.wav",
            FadeInMs = 1,
            FadeOutMs = 1,
            PlayTailOverLoop = true,
            Rule = new BaseStemRule()
        };
        manifest.Stems.Add(stem);

        var audio = new WavAudio(
            new WavHeader(TestAudio.SampleRate, 1, 16, samples.Length),
            samples);
        var provider = new StemMixingSampleProvider(
            manifest,
            new[] { new LoadedStem(stem, audio) },
            seed: 7);
        provider.SetSpeedMph(10);
        provider.Play();

        var output = new float[loopFrames * 4];
        var read = 0;
        while (read < output.Length)
        {
            var chunk = Math.Min(73, output.Length - read);
            provider.Read(output, read, chunk);
            read += chunk;
        }

        Assert.InRange(output[loopFrames + loopFrames / 2], 0.08f, 0.12f);
        Assert.InRange(output[loopFrames * 2 + loopFrames / 2], 0.27f, 0.33f);
        Assert.InRange(output[loopFrames * 3 + loopFrames / 2], 0.56f, 0.64f);
    }

    private static void PumpSeconds(StemMixingSampleProvider provider, double seconds)
    {
        var totalFrames = (int)(provider.SampleRate * seconds);
        var buffer = new float[1024 * provider.ChannelCount];
        var pumped = 0;
        while (pumped < totalFrames)
        {
            var frames = Math.Min(1024, totalFrames - pumped);
            provider.Read(buffer, 0, frames * provider.ChannelCount);
            pumped += frames;
        }
    }

    /// <summary>A two-stem song: a bed that is always on and a lift that needs 25 mph.</summary>
    private static StemMixingSampleProvider BuildProvider(out SongManifest manifest)
    {
        var bedAudio = new WavAudio(
            new WavHeader(TestAudio.SampleRate, 1, 16, TestAudio.SampleRate * 3),
            TestAudio.Tone(220, 3_000, amplitude: 0.4));
        var liftAudio = new WavAudio(
            new WavHeader(TestAudio.SampleRate, 1, 16, TestAudio.SampleRate * 3),
            TestAudio.Tone(440, 3_000, amplitude: 0.4));

        manifest = new SongManifest
        {
            SongId = "generated",
            DisplayName = "Generated",
            TransportStemId = "bed",
            LoopRegion = new LoopRegion { StartMs = 0, EndMs = 2_000, LoopStartsSong = true }
        };

        var bed = new StemManifest
        {
            StemId = "bed",
            DisplayName = "Bed",
            AssetPath = "bed.wav",
            FadeInMs = 200,
            FadeOutMs = 200,
            Rule = new BaseStemRule()
        };
        var lift = new StemManifest
        {
            StemId = "lift",
            DisplayName = "Lift",
            AssetPath = "lift.wav",
            FadeInMs = 200,
            FadeOutMs = 200,
            Rule = new BaseStemRule { MinMph = 25.0 }
        };
        manifest.Stems.Add(bed);
        manifest.Stems.Add(lift);

        return new StemMixingSampleProvider(
            manifest,
            new[] { new LoadedStem(bed, bedAudio), new LoadedStem(lift, liftAudio) },
            seed: 7);
    }
}
