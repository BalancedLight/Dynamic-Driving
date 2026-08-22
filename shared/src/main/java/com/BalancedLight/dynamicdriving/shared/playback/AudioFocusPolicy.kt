package com.BalancedLight.dynamicdriving.shared.playback

import android.media.AudioManager

/** What the playback controller should do in response to an audio-focus change. */
enum class AudioFocusAction {
    /** Nothing to do. */
    NONE,

    /**
     * Stop and forget the user's intent to play. Another app has taken over for good, so pressing
     * play again should be a deliberate act.
     */
    PAUSE_AND_CLEAR_INTENT,

    /**
     * Stop the sound but remember that the user wants to be playing, so focus coming back resumes
     * without them touching anything.
     */
    PAUSE_KEEPING_INTENT,

    /** Focus is back; resume if the user still wants to play and the audio is ready. */
    RESUME_IF_PENDING
}

/**
 * Maps audio-focus changes onto playback actions.
 *
 * This is a pure function so the policy can be tested directly. The rule it encodes is that a
 * permanent loss clears the user's intent while a transient one preserves it — an interruption
 * should hand playback back afterwards, a takeover should not.
 *
 * The controller is the app's **only** audio-focus owner. The transport ExoPlayer is muted and must
 * never request focus of its own; two owners in one process revoke each other in a loop, which
 * presents as playback starting and immediately stopping.
 */
object AudioFocusPolicy {
    fun resolve(focusChange: Int): AudioFocusAction = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> AudioFocusAction.RESUME_IF_PENDING

        AudioManager.AUDIOFOCUS_LOSS -> AudioFocusAction.PAUSE_AND_CLEAR_INTENT

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusAction.PAUSE_KEEPING_INTENT

        else -> AudioFocusAction.NONE
    }
}
