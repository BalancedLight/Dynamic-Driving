using DynamicDriving.Core.Audio;

namespace DynamicDriving.Tests;

/// <summary>
/// Procedurally generated audio for tests.
///
/// The repository ships no test audio: every fixture is synthesized into a temporary folder at run
/// time, so the suite carries no binary blobs and no path that only exists on one machine.
/// </summary>
internal static class TestAudio
{
    public const int SampleRate = 8_000;

    /// <summary>A sine tone. Deterministic, so a mixed result can be asserted exactly.</summary>
    public static short[] Tone(double frequencyHz, double durationMs, double amplitude = 0.5, int channelCount = 1)
    {
        var frameCount = (int)Math.Round(durationMs * SampleRate / 1000.0);
        var samples = new short[frameCount * channelCount];
        for (var frame = 0; frame < frameCount; frame++)
        {
            var value = Math.Sin(2 * Math.PI * frequencyHz * frame / SampleRate) * amplitude;
            for (var channel = 0; channel < channelCount; channel++)
            {
                samples[frame * channelCount + channel] = (short)Math.Clamp(value * short.MaxValue, short.MinValue, short.MaxValue);
            }
        }

        return samples;
    }

    /// <summary>Constant full-scale-ish audio; useful when a test needs a stem it can hear immediately.</summary>
    public static short[] Constant(double durationMs, double amplitude = 0.5, int channelCount = 1)
    {
        var frameCount = (int)Math.Round(durationMs * SampleRate / 1000.0);
        var samples = new short[frameCount * channelCount];
        var value = (short)Math.Clamp(amplitude * short.MaxValue, short.MinValue, short.MaxValue);
        Array.Fill(samples, value);
        return samples;
    }

    public static string WriteWav(string folderPath, string fileName, short[] samples, int channelCount = 1)
    {
        Directory.CreateDirectory(folderPath);
        var path = Path.Combine(folderPath, fileName);
        WavFile.Write(path, SampleRate, channelCount, samples);
        return path;
    }
}

/// <summary>A temporary directory that deletes itself at the end of the test.</summary>
internal sealed class TempFolder : IDisposable
{
    public TempFolder()
    {
        Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "dynamicdriving-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path);
    }

    public string Path { get; }

    public string File(string name) => System.IO.Path.Combine(Path, name);

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(Path))
            {
                Directory.Delete(Path, recursive: true);
            }
        }
        catch (IOException)
        {
            // A file still held open by the OS is not a test failure.
        }
    }
}
