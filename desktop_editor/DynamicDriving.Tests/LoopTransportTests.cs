using DynamicDriving.Core.Playback;
using Xunit;

namespace DynamicDriving.Tests;

/// <summary>
/// The transport parity suite.
///
/// These are the same steps the previous Python editor asserted, kept intact through the port so
/// the desktop timeline still matches the Android one: an intro pass, the fall into the loop, seeks
/// on either side of the loop end, and the tail played out once on the way to the next song.
/// </summary>
public sealed class LoopTransportTests
{
    [Fact]
    public void Intro_then_loop_seek_and_tail_transport()
    {
        var transport = new LoopTransport();
        var state = transport.Configure(
            sampleRate: 1_000,
            totalMs: 5_000,
            loopStartMs: 1_000,
            loopEndMs: 3_000,
            loopStartsSong: false);

        Assert.Equal(0.0, state.PositionMs);
        Assert.True(state.FirstPassPending);

        state = transport.Advance(2_500);
        Assert.Equal(2_500.0, state.PositionMs);
        Assert.True(state.FirstPassPending);

        state = transport.Advance(600);
        Assert.Equal(1_100.0, state.PositionMs);
        Assert.False(state.FirstPassPending);
        Assert.Equal(1, state.LoopCount);

        state = transport.Seek(500);
        Assert.Equal(500.0, state.PositionMs);
        Assert.True(state.FirstPassPending);

        state = transport.Seek(3_200);
        Assert.Equal(1_200.0, state.PositionMs);
        Assert.False(state.FirstPassPending);

        Assert.True(transport.PlayTailFromLoopEnd());

        state = transport.Advance(1_500);
        Assert.Equal(4_500.0, state.PositionMs);
        Assert.True(state.FinishingToEnd);

        state = transport.Advance(1_000);
        Assert.Equal(5_000.0, state.PositionMs);
        Assert.False(state.FinishingToEnd);
    }

    [Fact]
    public void A_song_whose_loop_starts_it_begins_at_the_loop_start()
    {
        var transport = new LoopTransport();

        var state = transport.Configure(1_000, 5_000, 1_000, 3_000, loopStartsSong: true);

        Assert.Equal(1_000.0, state.PositionMs);
        Assert.False(state.FirstPassPending);
    }

    [Fact]
    public void Looping_wraps_repeatedly_and_counts_every_pass()
    {
        var transport = new LoopTransport();
        transport.Configure(1_000, 5_000, 1_000, 3_000, loopStartsSong: true);

        transport.Advance(2_000); // one full loop
        Assert.Equal(1, transport.State.LoopCount);
        Assert.Equal(1_000.0, transport.State.PositionMs);

        transport.Advance(4_000); // two more
        Assert.Equal(3, transport.State.LoopCount);
        Assert.Equal(1_000.0, transport.State.PositionMs);
    }

    [Fact]
    public void Seeking_past_the_end_clamps_to_the_song_length()
    {
        var transport = new LoopTransport();
        transport.Configure(1_000, 5_000, 1_000, 3_000, loopStartsSong: true);

        var state = transport.Seek(99_000);

        Assert.InRange(state.PositionMs, 1_000.0, 3_000.0);
    }

    [Fact]
    public void A_song_with_no_audio_after_the_loop_cannot_play_a_tail()
    {
        var transport = new LoopTransport();
        transport.Configure(1_000, 3_000, 1_000, 3_000, loopStartsSong: true);

        Assert.False(transport.PlayTailFromLoopEnd());
        Assert.False(transport.PlayCurrentLoopToSongEnd());
    }

    [Fact]
    public void Playing_the_current_loop_out_keeps_the_playhead_where_it_is()
    {
        var transport = new LoopTransport();
        transport.Configure(1_000, 5_000, 1_000, 3_000, loopStartsSong: true);
        transport.Advance(1_200);

        Assert.True(transport.PlayCurrentLoopToSongEnd());
        Assert.Equal(2_200.0, transport.State.PositionMs);
        Assert.True(transport.State.FinishingToEnd);

        var state = transport.Advance(3_000);
        Assert.Equal(5_000.0, state.PositionMs);
        Assert.False(state.FinishingToEnd);
    }

    [Fact]
    public void Seeking_cancels_a_tail_playout()
    {
        var transport = new LoopTransport();
        transport.Configure(1_000, 5_000, 1_000, 3_000, loopStartsSong: true);
        Assert.True(transport.PlayTailFromLoopEnd());

        var state = transport.Seek(1_500);

        Assert.False(state.FinishingToEnd);
        Assert.Equal(1_500.0, state.PositionMs);
    }
}
