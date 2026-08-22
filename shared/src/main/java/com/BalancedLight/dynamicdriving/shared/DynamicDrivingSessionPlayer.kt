package com.BalancedLight.dynamicdriving.shared

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.BalancedLight.dynamicdriving.shared.playback.SpeedAdaptivePlaybackController

/**
 * The player published to MediaSession.
 *
 * The underlying transport holds exactly one clipped media item at a time — the looped region of
 * the current song — so its own next/previous commands would always be unavailable. Routing those
 * commands, and play/pause, through the playback controller is what makes skip buttons work on the
 * notification, the lock screen, a steering-wheel control, Android Auto, and AAOS while keeping the
 * stem engine, wake lock, and audio focus in step.
 */
@OptIn(UnstableApi::class)
class DynamicDrivingSessionPlayer(
    delegate: Player,
    private val controller: SpeedAdaptivePlaybackController
) : ForwardingPlayer(delegate) {

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands()
            .buildUpon()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true

        else -> super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean = true

    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() = controller.skipToNextSong()

    override fun seekToNextMediaItem() = controller.skipToNextSong()

    override fun seekToPrevious() = controller.skipToPreviousSong()

    override fun seekToPreviousMediaItem() = controller.skipToPreviousSong()

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) controller.play() else controller.pause()
    }
}
