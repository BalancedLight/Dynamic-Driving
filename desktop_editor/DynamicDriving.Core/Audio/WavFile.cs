using System.Buffers.Binary;

namespace DynamicDriving.Core.Audio;

/// <summary>Raised when a file is not a 16-bit PCM WAV Dynamic Driving can play.</summary>
public sealed class WavFormatException : Exception
{
    public WavFormatException(string message) : base(message)
    {
    }
}

public sealed record WavHeader(
    int SampleRate,
    int ChannelCount,
    int BitsPerSample,
    int FrameCount)
{
    public double DurationMs => FrameCount * 1000.0 / SampleRate;
}

/// <summary>
/// Minimal 16-bit PCM WAV reader and writer.
///
/// Dynamic Driving plays stems by mixing raw PCM frame-for-frame, so the editor deliberately reads the
/// same narrow format the Android engine accepts rather than decoding anything wider and letting an
/// unplayable file look fine in the editor.
/// </summary>
public static class WavFile
{
    public static WavHeader ReadHeader(string path)
    {
        using var stream = File.OpenRead(path);
        return ReadHeader(stream, out _, out _);
    }

    public static WavAudio Read(string path)
    {
        using var stream = File.OpenRead(path);
        var header = ReadHeader(stream, out var dataOffset, out var dataSize);
        stream.Position = dataOffset;

        var sampleCount = dataSize / sizeof(short);
        var samples = new short[sampleCount];
        var buffer = new byte[Math.Min(dataSize, 1 << 16)];
        var sampleIndex = 0;
        var remaining = dataSize;

        while (remaining > 0)
        {
            var toRead = Math.Min(buffer.Length, remaining);
            ReadExactly(stream, buffer.AsSpan(0, toRead));

            for (var byteIndex = 0; byteIndex < toRead; byteIndex += 2)
            {
                samples[sampleIndex++] = BinaryPrimitives.ReadInt16LittleEndian(
                    buffer.AsSpan(byteIndex, 2));
            }

            remaining -= toRead;
        }

        var frameCount = samples.Length / header.ChannelCount;
        return new WavAudio(header with { FrameCount = frameCount }, samples);
    }

    public static void Write(string path, int sampleRate, int channelCount, short[] samples)
    {
        var dataSize = samples.Length * sizeof(short);
        using var stream = File.Create(path);
        using var writer = new BinaryWriter(stream);

        writer.Write("RIFF"u8.ToArray());
        writer.Write(36 + dataSize);
        writer.Write("WAVE"u8.ToArray());

        writer.Write("fmt "u8.ToArray());
        writer.Write(16);
        writer.Write((short)1); // PCM
        writer.Write((short)channelCount);
        writer.Write(sampleRate);
        writer.Write(sampleRate * channelCount * sizeof(short)); // byte rate
        writer.Write((short)(channelCount * sizeof(short)));     // block align
        writer.Write((short)16);                                 // bits per sample

        writer.Write("data"u8.ToArray());
        writer.Write(dataSize);
        foreach (var sample in samples)
        {
            writer.Write(sample);
        }
    }

    private static WavHeader ReadHeader(Stream stream, out long dataOffset, out int dataSize)
    {
        Span<byte> riff = stackalloc byte[12];
        ReadExactly(stream, riff);
        if (!riff[..4].SequenceEqual("RIFF"u8) || !riff[8..12].SequenceEqual("WAVE"u8))
        {
            throw new WavFormatException("Not a RIFF/WAVE file.");
        }

        var audioFormat = 0;
        var channelCount = 0;
        var sampleRate = 0;
        var bitsPerSample = 0;
        dataOffset = 0;
        dataSize = 0;

        Span<byte> chunkHeader = stackalloc byte[8];
        while (true)
        {
            var read = stream.Read(chunkHeader);
            if (read < 8)
            {
                break;
            }

            var chunkId = System.Text.Encoding.ASCII.GetString(chunkHeader[..4]);
            var chunkSize = BinaryPrimitives.ReadInt32LittleEndian(chunkHeader[4..8]);

            if (chunkId == "fmt ")
            {
                var fmt = new byte[chunkSize];
                ReadExactly(stream, fmt);
                audioFormat = BinaryPrimitives.ReadInt16LittleEndian(fmt.AsSpan(0, 2));
                channelCount = BinaryPrimitives.ReadInt16LittleEndian(fmt.AsSpan(2, 2));
                sampleRate = BinaryPrimitives.ReadInt32LittleEndian(fmt.AsSpan(4, 4));
                bitsPerSample = BinaryPrimitives.ReadInt16LittleEndian(fmt.AsSpan(14, 2));
            }
            else if (chunkId == "data")
            {
                dataOffset = stream.Position;
                dataSize = chunkSize;
                break;
            }
            else
            {
                stream.Seek(chunkSize + (chunkSize % 2), SeekOrigin.Current);
                continue;
            }

            if (chunkSize % 2 != 0)
            {
                stream.Seek(1, SeekOrigin.Current);
            }
        }

        if (audioFormat != 1)
        {
            throw new WavFormatException($"Only uncompressed PCM WAV is supported (found format {audioFormat}).");
        }

        if (bitsPerSample != 16)
        {
            throw new WavFormatException($"Only 16-bit PCM WAV is supported (found {bitsPerSample}-bit).");
        }

        if (channelCount is < 1 or > 2)
        {
            throw new WavFormatException($"Only mono or stereo WAV is supported (found {channelCount} channels).");
        }

        if (sampleRate <= 0 || dataSize <= 0)
        {
            throw new WavFormatException("The WAV file has no readable audio data.");
        }

        if (stream.CanSeek && (dataOffset > stream.Length || dataSize > stream.Length - dataOffset))
        {
            throw new WavFormatException("The WAV file ended before its declared audio data was complete.");
        }

        var frameCount = dataSize / sizeof(short) / channelCount;
        return new WavHeader(sampleRate, channelCount, bitsPerSample, frameCount);
    }

    private static void ReadExactly(Stream stream, Span<byte> buffer)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = stream.Read(buffer[offset..]);
            if (read <= 0)
            {
                throw new WavFormatException("The WAV file ended unexpectedly.");
            }

            offset += read;
        }
    }
}

/// <summary>Decoded 16-bit PCM audio: interleaved samples plus the format they came in.</summary>
public sealed class WavAudio
{
    public WavAudio(WavHeader header, short[] samples)
    {
        Header = header;
        Samples = samples;
    }

    public WavHeader Header { get; }

    public short[] Samples { get; }

    public int SampleRate => Header.SampleRate;

    public int ChannelCount => Header.ChannelCount;

    public int FrameCount => Header.FrameCount;

    public double DurationMs => Header.DurationMs;

    public int FrameForMs(double positionMs) =>
        Math.Clamp((int)Math.Round(positionMs * SampleRate / 1000.0), 0, Math.Max(0, FrameCount));

    public double MsForFrame(int frame) => frame * 1000.0 / SampleRate;
}
