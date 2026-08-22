using DynamicDriving.Core.Audio;
using DynamicDriving.Core.Manifests;
using NAudio.Wave;

namespace DynamicDriving.Core.Playback;

/// <summary>A snapshot of one stem for the editor's live stem list.</summary>
public sealed record StemRuntimeState(
    string StemId,
    string DisplayName,
    bool Eligible,
    bool Active,
    double CurrentGain,
    double TargetGain,
    double GainMultiplier,
    double MuffleAmount,
    IReadOnlyList<string> ActiveEvents);

/// <summary>
/// Mixes a song's stems the way the Android engine does and hands the result to NAudio.
///
/// Everything that decides what is audible — speed rules, overlay scheduling, fades, muffling —
/// lives here rather than in the UI, and the provider can be pumped directly in a test without an
/// audio device.
/// </summary>
public sealed class StemMixingSampleProvider : ISampleProvider
{
    private readonly object _stateLock = new();
    private readonly List<LoadedStem> _stems = new();
    private readonly Dictionary<string, OverlayGroupState> _overlayGroups = new(StringComparer.Ordinal);
    private readonly Random _random;
    private readonly LoopTransport _transport = new();

    private SongManifest _manifest;
    private double _speedMph;
    private double _elapsedMs;
    private bool _isPlaying;

    public StemMixingSampleProvider(SongManifest manifest, IReadOnlyList<LoadedStem> stems, int? seed = null)
    {
        _manifest = manifest;
        _random = seed is { } value ? new Random(value) : new Random();
        _stems.AddRange(stems);

        var reference = _stems.FirstOrDefault();
        SampleRate = reference?.Audio.SampleRate ?? 44_100;
        ChannelCount = reference?.Audio.ChannelCount ?? 2;
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(SampleRate, ChannelCount);

        var totalMs = reference?.Audio.DurationMs ?? 0.0;
        _transport.Configure(
            SampleRate,
            totalMs,
            manifest.LoopRegion.StartMs,
            manifest.LoopRegion.EndMs,
            manifest.LoopRegion.LoopStartsSong);

        ApplyManifestToLoadedStems(manifest);
        RebuildOverlayGroups();
    }

    public WaveFormat WaveFormat { get; }

    public int SampleRate { get; }

    public int ChannelCount { get; }

    public double TotalMs => _transport.TotalMs;

    public double PositionMs
    {
        get
        {
            lock (_stateLock)
            {
                return _transport.State.PositionMs;
            }
        }
    }

    public int LoopCount
    {
        get
        {
            lock (_stateLock)
            {
                return _transport.State.LoopCount;
            }
        }
    }

    public bool IsPlaying
    {
        get
        {
            lock (_stateLock)
            {
                return _isPlaying;
            }
        }
    }

    public void Play()
    {
        lock (_stateLock)
        {
            _isPlaying = true;
        }
    }

    public void Pause()
    {
        lock (_stateLock)
        {
            _isPlaying = false;
        }
    }

    public void Seek(double positionMs)
    {
        lock (_stateLock)
        {
            _transport.Seek(positionMs);
            foreach (var stem in _stems)
            {
                stem.TailFrames.Clear();
                stem.ResetFilters();
            }
        }
    }

    public bool PlayTailFromLoopEnd()
    {
        lock (_stateLock)
        {
            foreach (var stem in _stems)
            {
                stem.TailFrames.Clear();
            }

            return _transport.PlayTailFromLoopEnd();
        }
    }

    public void SetSpeedMph(double mph)
    {
        lock (_stateLock)
        {
            _speedMph = Math.Max(0.0, mph);
        }
    }

    /// <summary>Mutes one loaded stem for preview without changing manifest membership.</summary>
    public void SetStemMuted(string stemId, bool muted)
    {
        lock (_stateLock)
        {
            foreach (var stem in _stems.Where(
                         candidate => string.Equals(candidate.Manifest.StemId, stemId, StringComparison.Ordinal)))
            {
                stem.Muted = muted;
                if (muted)
                {
                    stem.TailFrames.Clear();
                }
            }
        }
    }

    /// <summary>Applies edited loop points without reloading the audio.</summary>
    public void UpdateLoopRegion(LoopRegion loopRegion)
    {
        lock (_stateLock)
        {
            var position = _transport.State.PositionMs;
            _transport.Configure(
                SampleRate,
                _transport.TotalMs,
                loopRegion.StartMs,
                loopRegion.EndMs,
                loopRegion.LoopStartsSong);
            _transport.Seek(position);
            foreach (var stem in _stems)
            {
                stem.TailFrames.Clear();
            }
        }
    }

    /// <summary>Applies edited stem rules and gains without reloading the audio.</summary>
    public void UpdateManifest(SongManifest manifest)
    {
        lock (_stateLock)
        {
            _manifest = manifest;
            ApplyManifestToLoadedStems(manifest);
            RebuildOverlayGroups();
        }
    }

    private void ApplyManifestToLoadedStems(SongManifest manifest)
    {
        foreach (var stem in _stems)
        {
            var updated = manifest.Stems.FirstOrDefault(candidate => candidate.StemId == stem.Manifest.StemId);
            stem.Included = updated is not null;
            if (updated is not null)
            {
                stem.Manifest = updated;
            }
        }
    }

    public IReadOnlyList<StemRuntimeState> SnapshotStems()
    {
        lock (_stateLock)
        {
            return _stems.Select(stem => new StemRuntimeState(
                stem.Manifest.StemId,
                stem.Manifest.Label,
                stem.Eligible,
                stem.CurrentGain > 0.001,
                stem.CurrentGain,
                stem.TargetGain,
                stem.GainMultiplier,
                stem.MuffleAmount,
                stem.ActiveEvents.ToList())).ToList();
        }
    }

    /// <summary>Convenience overload for tests and callers that already hold an array.</summary>
    public int Read(float[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public int Read(Span<float> buffer)
    {
        var offset = 0;
        var count = buffer.Length;
        buffer.Clear();

        lock (_stateLock)
        {
            if (_stems.Count == 0)
            {
                return count;
            }

            var frames = count / ChannelCount;
            if (!_isPlaying)
            {
                // Silence still counts as delivered audio: NAudio keeps the device open and the
                // playhead simply does not move.
                return count;
            }

            var frameMs = 1000.0 / SampleRate;

            for (var frame = 0; frame < frames; frame++)
            {
                _elapsedMs += frameMs;
                UpdateStemTargets(_elapsedMs);

                var transportFrame = _transport.FrameForPosition();
                var writeIndex = offset + frame * ChannelCount;

                foreach (var stem in _stems)
                {
                    AdvanceStemGain(stem, frameMs);
                    var gain = (float)(stem.CurrentGain * stem.GainMultiplier * stem.Manifest.Gain);
                    if (gain <= 0.0001f && stem.TailFrames.Count == 0)
                    {
                        continue;
                    }

                    for (var channel = 0; channel < ChannelCount; channel++)
                    {
                        var sample = stem.SampleAt(transportFrame, channel);

                        foreach (var tailFrame in stem.TailFrames)
                        {
                            sample += stem.SampleAt(tailFrame, channel);
                        }

                        sample = stem.ApplyMuffle(sample, channel, SampleRate);
                        buffer[writeIndex + channel] += sample * gain;
                    }

                    for (var tailIndex = stem.TailFrames.Count - 1; tailIndex >= 0; tailIndex--)
                    {
                        var nextFrame = stem.TailFrames[tailIndex] + 1;
                        if (nextFrame >= stem.Audio.FrameCount)
                        {
                            stem.TailFrames.RemoveAt(tailIndex);
                        }
                        else
                        {
                            stem.TailFrames[tailIndex] = nextFrame;
                        }
                    }
                }

                var previousLoopCount = _transport.State.LoopCount;
                _transport.Advance(frameMs);
                if (_transport.State.LoopCount != previousLoopCount)
                {
                    OnLoopWrapped();
                }
            }

            // A dense mix of many stems can exceed unity; a soft clip is friendlier than wrapping.
            for (var index = offset; index < offset + count; index++)
            {
                buffer[index] = Math.Clamp(buffer[index], -1f, 1f);
            }
        }

        return count;
    }

    private void OnLoopWrapped()
    {
        if (!_manifest.LoopRegion.PlayTailOverLoop)
        {
            foreach (var stem in _stems)
            {
                stem.TailFrames.Clear();
            }

            return;
        }

        var loopEndFrame = (int)Math.Round(_transport.LoopEndMs * SampleRate / 1000.0);
        foreach (var stem in _stems)
        {
            if (stem.Included && !stem.Muted && stem.Manifest.PlayTailOverLoop && loopEndFrame < stem.Audio.FrameCount)
            {
                // Each pass owns its own cursor. A cursor must survive audio-buffer boundaries,
                // and long tails may overlap a later pass without restarting the earlier tail.
                stem.TailFrames.Add(loopEndFrame);
            }
            else
            {
                stem.TailFrames.Clear();
            }
        }
    }

    private void UpdateStemTargets(double nowMs)
    {
        var idleEffect = PlaybackRules.CalculateIdleBaseEffect(_speedMph);
        var songMuffle = PlaybackRules.CalculateSongMuffleEffect(_manifest.Muffle, _speedMph);

        foreach (var stem in _stems)
        {
            stem.ActiveEvents.Clear();
            var gainMultiplier = 1.0;

            if (!stem.Included || stem.Muted)
            {
                stem.Eligible = false;
                stem.TargetGain = 0.0;
                stem.GainMultiplier = 1.0;
                stem.MuffleAmount = 0.0;
                stem.MuffleCutoffHz = PlaybackRules.ClearCutoffHz;
                continue;
            }

            foreach (var stemEvent in stem.Manifest.Events)
            {
                if (!PlaybackRules.EvaluateCondition(stemEvent.Condition, _speedMph))
                {
                    continue;
                }

                stem.ActiveEvents.Add(stemEvent.DisplayName ?? stemEvent.EventId ?? "event");
                foreach (var modifier in stemEvent.Modifiers.OfType<GainMultiplierModifier>())
                {
                    gainMultiplier *= modifier.Multiplier;
                }
            }

            if (stem.Manifest.Rule is not OverlayStemRule)
            {
                var eligible = PlaybackRules.ShouldActivateBaseStem(
                    stem.Manifest.Rule,
                    _speedMph,
                    stem.CurrentGain > 0.001);
                stem.Eligible = eligible;
                stem.TargetGain = eligible ? 1.0 : 0.0;

                if (idleEffect.IsActive)
                {
                    gainMultiplier *= idleEffect.GainMultiplier;
                    stem.ActiveEvents.Add("Idle gain");
                }
            }

            stem.GainMultiplier = gainMultiplier;
            stem.MuffleAmount = Math.Max(
                songMuffle.IsActive ? songMuffle.MuffleAmount : 0.0,
                stem.Manifest.Rule is OverlayStemRule || !idleEffect.IsActive ? 0.0 : idleEffect.MuffleAmount);
            stem.MuffleCutoffHz = stem.MuffleAmount > 0.0
                ? Math.Min(songMuffle.MuffleCutoffHz, idleEffect.MuffleCutoffHz)
                : PlaybackRules.ClearCutoffHz;

            if (songMuffle.IsActive)
            {
                stem.ActiveEvents.Add("Song muffle");
            }
        }

        UpdateOverlayGroups(nowMs);
    }

    private void UpdateOverlayGroups(double nowMs)
    {
        var overlayStems = _stems.Where(stem => stem.Included && stem.Manifest.Rule is OverlayStemRule).ToList();
        if (overlayStems.Count == 0)
        {
            return;
        }

        foreach (var group in overlayStems.GroupBy(stem => ((OverlayStemRule)stem.Manifest.Rule).GroupId))
        {
            var groupId = group.Key;
            var currentState = _overlayGroups.TryGetValue(groupId, out var existing)
                ? existing
                : new OverlayGroupState();

            var eligible = group
                .Where(stem => PlaybackRules.IsOverlayEligible(
                    (OverlayStemRule)stem.Manifest.Rule,
                    _speedMph,
                    stem.Manifest.StemId == currentState.ActiveStemId))
                .ToList();

            foreach (var stem in group)
            {
                stem.Eligible = eligible.Contains(stem);
            }

            var activeStem = group.FirstOrDefault(stem => stem.Manifest.StemId == currentState.ActiveStemId);
            var forceStop = activeStem is not null && !eligible.Contains(activeStem);

            var decision = OverlayGroupScheduler.Tick(
                currentState,
                (long)nowMs,
                eligible.Select(stem => stem.Manifest).ToList(),
                activeStem?.Manifest,
                forceStop,
                _random);

            _overlayGroups[groupId] = decision.State;

            switch (decision.Action)
            {
                case OverlayGroupAction.Start start:
                    foreach (var stem in group)
                    {
                        stem.TargetGain = stem.Manifest.StemId == start.Stem.StemId ? 1.0 : 0.0;
                    }

                    break;

                case OverlayGroupAction.Stop stop:
                    foreach (var stem in group.Where(stem => stem.Manifest.StemId == stop.StemId))
                    {
                        stem.TargetGain = 0.0;
                    }

                    break;

                case OverlayGroupAction.None:
                    foreach (var stem in group)
                    {
                        stem.TargetGain = stem.Manifest.StemId == decision.State.ActiveStemId ? 1.0 : 0.0;
                    }

                    break;
            }
        }
    }

    private static void AdvanceStemGain(LoadedStem stem, double frameMs)
    {
        var fadeMs = stem.TargetGain > stem.CurrentGain
            ? Math.Max(1, stem.Manifest.FadeInMs)
            : Math.Max(1, stem.Manifest.FadeOutMs);
        var step = frameMs / fadeMs;

        if (stem.CurrentGain < stem.TargetGain)
        {
            stem.CurrentGain = Math.Min(stem.TargetGain, stem.CurrentGain + step);
        }
        else if (stem.CurrentGain > stem.TargetGain)
        {
            stem.CurrentGain = Math.Max(stem.TargetGain, stem.CurrentGain - step);
        }
    }

    private void RebuildOverlayGroups()
    {
        _overlayGroups.Clear();
        foreach (var stem in _stems)
        {
            if (stem.Manifest.Rule is OverlayStemRule overlay && !string.IsNullOrWhiteSpace(overlay.GroupId))
            {
                _overlayGroups[overlay.GroupId] = new OverlayGroupState();
            }
        }
    }
}

/// <summary>One stem's audio plus the mixing state that belongs to it.</summary>
public sealed class LoadedStem
{
    private float[] _lowPassState = Array.Empty<float>();

    public LoadedStem(StemManifest manifest, WavAudio audio)
    {
        Manifest = manifest;
        Audio = audio;
        _lowPassState = new float[audio.ChannelCount];
    }

    public StemManifest Manifest { get; set; }

    public WavAudio Audio { get; }

    /// <summary>Whether the current editor manifest includes this preloaded browser stem.</summary>
    public bool Included { get; set; } = true;

    /// <summary>Preview-only mute; unlike <see cref="Included"/>, this is never serialized.</summary>
    public bool Muted { get; set; }

    public double CurrentGain { get; set; }

    public double TargetGain { get; set; }

    public double GainMultiplier { get; set; } = 1.0;

    public double MuffleAmount { get; set; }

    public double MuffleCutoffHz { get; set; } = PlaybackRules.ClearCutoffHz;

    public bool Eligible { get; set; }

    /// <summary>Current source frame for each post-loop tail voice still ringing out.</summary>
    public List<int> TailFrames { get; } = new();

    public List<string> ActiveEvents { get; } = new();

    public float SampleAt(int frame, int channel)
    {
        if (frame < 0 || frame >= Audio.FrameCount)
        {
            return 0f;
        }

        var index = frame * Audio.ChannelCount + channel;
        return index >= 0 && index < Audio.Samples.Length ? Audio.Samples[index] / 32768f : 0f;
    }

    /// <summary>One-pole low-pass, blended in by <see cref="MuffleAmount"/>.</summary>
    public float ApplyMuffle(float sample, int channel, int sampleRate)
    {
        if (MuffleAmount <= 0.001 || channel >= _lowPassState.Length)
        {
            return sample;
        }

        var cutoff = Math.Clamp(MuffleCutoffHz, 40.0, sampleRate / 2.0 - 1.0);
        var dt = 1.0 / sampleRate;
        var rc = 1.0 / (2.0 * Math.PI * cutoff);
        var alpha = (float)(dt / (rc + dt));

        _lowPassState[channel] += alpha * (sample - _lowPassState[channel]);
        var wet = _lowPassState[channel];
        return (float)((sample * (1.0 - MuffleAmount)) + (wet * MuffleAmount));
    }

    public void ResetFilters()
    {
        _lowPassState = new float[Audio.ChannelCount];
    }
}
