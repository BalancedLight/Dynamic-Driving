using DynamicDriving.Core.Audio;
using DynamicDriving.Core.Manifests;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace DynamicDriving.Core.Playback;

/// <summary>
/// Owns the audio device and the loaded song.
///
/// All the mixing lives in <see cref="StemMixingSampleProvider"/>; this class exists so the UI has
/// one object to talk to and so failing to open an output device degrades to "no preview" rather
/// than taking the editor down.
/// </summary>
public sealed class StemPlaybackEngine : IDisposable
{
    private readonly object _deviceLock = new();
    private IWavePlayer? _output;
    private StemMixingSampleProvider? _provider;

    public bool HasSong => _provider is not null;

    public bool IsPlaying => _provider?.IsPlaying ?? false;

    public double PositionMs => _provider?.PositionMs ?? 0.0;

    public double TotalMs => _provider?.TotalMs ?? 0.0;

    public int LoopCount => _provider?.LoopCount ?? 0;

    /// <summary>Set when the last load could not open an output device.</summary>
    public string? DeviceError { get; private set; }

    /// <summary>
    /// Loads every stem of [manifest] from [folderPath] and prepares playback.
    /// </summary>
    /// <returns>The stems that failed to load, keyed by stem ID.</returns>
    public IReadOnlyDictionary<string, string> Load(
        SongManifest manifest,
        string folderPath,
        int? seed = null,
        IReadOnlyList<StemManifest>? browserStems = null)
    {
        var failures = new Dictionary<string, string>(StringComparer.Ordinal);
        var loaded = new List<LoadedStem>();

        foreach (var stem in browserStems ?? manifest.Stems)
        {
            var stemPath = Path.Combine(folderPath, stem.AssetPath.Replace('/', Path.DirectorySeparatorChar));
            try
            {
                loaded.Add(new LoadedStem(stem, WavFile.Read(stemPath)));
            }
            catch (Exception error) when (error is WavFormatException or IOException)
            {
                failures[stem.StemId] = error.Message;
            }
        }

        lock (_deviceLock)
        {
            StopInternal();
            _provider = loaded.Count > 0 ? new StemMixingSampleProvider(manifest, loaded, seed) : null;
            DeviceError = null;
        }

        return failures;
    }

    public void UpdateManifest(SongManifest manifest)
    {
        _provider?.UpdateManifest(manifest);
        _provider?.UpdateLoopRegion(manifest.LoopRegion);
    }

    public void SetSpeedMph(double mph) => _provider?.SetSpeedMph(mph);

    public void SetStemMuted(string stemId, bool muted) => _provider?.SetStemMuted(stemId, muted);

    public void Seek(double positionMs) => _provider?.Seek(positionMs);

    public bool PlayTailFromLoopEnd() => _provider?.PlayTailFromLoopEnd() ?? false;

    public IReadOnlyList<StemRuntimeState> SnapshotStems() =>
        _provider?.SnapshotStems() ?? Array.Empty<StemRuntimeState>();

    public void Play()
    {
        lock (_deviceLock)
        {
            var provider = _provider;
            if (provider is null)
            {
                return;
            }

            if (_output is null)
            {
                try
                {
                    var output = new WaveOut { BufferMilliseconds = 60, NumberOfBuffers = 3 };
                    output.Init(new SampleToWaveProvider(provider));
                    _output = output;
                    DeviceError = null;
                }
                catch (Exception error)
                {
                    // A machine with no audio device should still be able to edit a song.
                    DeviceError = error.Message;
                    return;
                }
            }

            provider.Play();
            _output.Play();
        }
    }

    public void Pause()
    {
        lock (_deviceLock)
        {
            _provider?.Pause();
            _output?.Pause();
        }
    }

    public void Stop()
    {
        lock (_deviceLock)
        {
            _provider?.Pause();
            _provider?.Seek(0);
            _output?.Stop();
        }
    }

    public void Dispose()
    {
        lock (_deviceLock)
        {
            StopInternal();
            _provider = null;
        }
    }

    private void StopInternal()
    {
        if (_output is null)
        {
            return;
        }

        try
        {
            _output.Stop();
            _output.Dispose();
        }
        catch (Exception)
        {
            // Disposing an already-failed device must not stop the editor from loading a new song.
        }

        _output = null;
    }
}
