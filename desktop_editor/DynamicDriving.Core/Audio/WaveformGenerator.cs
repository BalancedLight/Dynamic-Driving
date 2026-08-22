namespace DynamicDriving.Core.Audio;

/// <summary>A peak envelope, normalised to 0..1, ready to draw.</summary>
public sealed record Waveform(IReadOnlyList<float> Peaks, double DurationMs, int SampleRate, int ChannelCount)
{
    public static Waveform Empty { get; } = new(Array.Empty<float>(), 0.0, 44_100, 1);
}

public static class WaveformGenerator
{
    public const int DefaultBinCount = 1024;

    /// <summary>
    /// Reduces [audio] to [binCount] peak values.
    ///
    /// Peaks (rather than averages) are used because loop points are placed against transients, and
    /// an averaged envelope hides exactly the detail the user is aiming at.
    /// </summary>
    public static Waveform Generate(WavAudio audio, int binCount = DefaultBinCount)
    {
        if (audio.FrameCount <= 0 || binCount <= 0)
        {
            return Waveform.Empty with { SampleRate = audio.SampleRate, ChannelCount = audio.ChannelCount };
        }

        var peaks = new float[binCount];
        var framesPerBin = Math.Max(1, audio.FrameCount / binCount);
        var channelCount = audio.ChannelCount;
        var highest = 0f;

        for (var bin = 0; bin < binCount; bin++)
        {
            var startFrame = (int)((long)bin * audio.FrameCount / binCount);
            var endFrame = Math.Min(audio.FrameCount, startFrame + framesPerBin);
            var peak = 0f;

            for (var frame = startFrame; frame < endFrame; frame++)
            {
                for (var channel = 0; channel < channelCount; channel++)
                {
                    var index = frame * channelCount + channel;
                    if (index >= audio.Samples.Length)
                    {
                        break;
                    }

                    var magnitude = Math.Abs(audio.Samples[index] / 32768f);
                    if (magnitude > peak)
                    {
                        peak = magnitude;
                    }
                }
            }

            peaks[bin] = peak;
            if (peak > highest)
            {
                highest = peak;
            }
        }

        if (highest > 0f)
        {
            for (var bin = 0; bin < peaks.Length; bin++)
            {
                peaks[bin] /= highest;
            }
        }

        return new Waveform(peaks, audio.DurationMs, audio.SampleRate, audio.ChannelCount);
    }
}
