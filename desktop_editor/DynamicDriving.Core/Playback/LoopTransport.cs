namespace DynamicDriving.Core.Playback;

public readonly record struct LoopTransportState(
    double PositionMs,
    bool FirstPassPending,
    bool FinishingToEnd,
    int LoopCount);

/// <summary>
/// The song timeline: an optional first pass from the top of the file, the looping region, and the
/// post-loop tail played once on the way out.
///
/// This mirrors the Android transport exactly and is deliberately free of audio-device state so the
/// timeline can be stepped deterministically in tests.
/// </summary>
public sealed class LoopTransport
{
    private int _sampleRate = 44_100;
    private double _totalMs;
    private double _loopStartMs;
    private double _loopEndMs;
    private bool _loopStartsSong = true;

    private double _positionMs;
    private bool _firstPassPending;
    private bool _finishingToEnd;
    private int _loopCount;

    public LoopTransportState State =>
        new(_positionMs, _firstPassPending, _finishingToEnd, _loopCount);

    public double LoopStartMs => _loopStartMs;

    public double LoopEndMs => _loopEndMs;

    public double LoopDurationMs => Math.Max(1.0, _loopEndMs - _loopStartMs);

    public double TotalMs => _totalMs;

    public LoopTransportState Configure(
        int sampleRate,
        double totalMs,
        double loopStartMs,
        double loopEndMs,
        bool loopStartsSong)
    {
        _sampleRate = Math.Max(1, sampleRate);
        _totalMs = Math.Max(0.0, totalMs);
        _loopStartMs = Math.Clamp(loopStartMs, 0.0, Math.Max(0.0, _totalMs));
        _loopEndMs = Math.Clamp(loopEndMs, _loopStartMs + 1.0, Math.Max(_loopStartMs + 1.0, _totalMs));
        _loopStartsSong = loopStartsSong;

        _loopCount = 0;
        _finishingToEnd = false;
        _firstPassPending = !loopStartsSong;
        _positionMs = loopStartsSong ? _loopStartMs : 0.0;
        return State;
    }

    /// <summary>Advances the playhead by [deltaMs], wrapping at the loop boundary.</summary>
    public LoopTransportState Advance(double deltaMs)
    {
        if (deltaMs < 0)
        {
            return State;
        }

        var target = _positionMs + deltaMs;

        if (_finishingToEnd)
        {
            _positionMs = Math.Min(target, _totalMs);
            if (_positionMs >= _totalMs)
            {
                _positionMs = _totalMs;
                _finishingToEnd = false;
            }

            return State;
        }

        if (_firstPassPending)
        {
            if (target < _loopEndMs)
            {
                _positionMs = target;
                return State;
            }

            // The first pass has reached the loop end; fall into the loop from there.
            _firstPassPending = false;
            _loopCount++;
            _positionMs = WrapIntoLoop(target);
            return State;
        }

        if (target < _loopEndMs)
        {
            _positionMs = target;
            return State;
        }

        var wrapCount = (int)Math.Floor((target - _loopStartMs) / LoopDurationMs);
        _loopCount += Math.Max(1, wrapCount);
        _positionMs = WrapIntoLoop(target);
        return State;
    }

    /// <summary>
    /// Moves the playhead to [positionMs] on the song's own timeline.
    ///
    /// A position before the loop end re-enters the first pass when the song has one; anything at or
    /// past the loop end folds back into the loop.
    /// </summary>
    public LoopTransportState Seek(double positionMs)
    {
        var target = Math.Clamp(positionMs, 0.0, _totalMs);
        _finishingToEnd = false;

        if (!_loopStartsSong && target < _loopEndMs)
        {
            _firstPassPending = true;
            _positionMs = target;
            return State;
        }

        _firstPassPending = false;
        _positionMs = target < _loopEndMs && target >= _loopStartMs ? target : WrapIntoLoop(target);
        return State;
    }

    /// <summary>
    /// Plays the current pass out to the end of the file instead of looping again.
    ///
    /// Returns false when the song has no audio after the loop end, in which case the caller should
    /// move straight on to the next song.
    /// </summary>
    public bool PlayTailFromLoopEnd()
    {
        if (_totalMs <= _loopEndMs)
        {
            return false;
        }

        _finishingToEnd = true;
        _firstPassPending = false;
        _positionMs = _loopEndMs;
        return true;
    }

    /// <summary>Plays from wherever the playhead is out to the end of the file.</summary>
    public bool PlayCurrentLoopToSongEnd()
    {
        if (_totalMs <= _loopEndMs)
        {
            return false;
        }

        _finishingToEnd = true;
        _firstPassPending = false;
        return true;
    }

    public int FrameForPosition() => (int)Math.Round(_positionMs * _sampleRate / 1000.0);

    private double WrapIntoLoop(double positionMs)
    {
        if (positionMs < _loopEndMs)
        {
            return Math.Max(positionMs, _loopStartMs);
        }

        var distance = (positionMs - _loopEndMs) % LoopDurationMs;
        return _loopStartMs + distance;
    }
}
