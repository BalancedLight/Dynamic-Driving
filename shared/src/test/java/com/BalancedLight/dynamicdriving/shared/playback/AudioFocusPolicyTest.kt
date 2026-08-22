package com.BalancedLight.dynamicdriving.shared.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the audio-focus rules.
 *
 * These exist because of a real failure: the transport ExoPlayer was configured to manage audio
 * focus while this controller also owned a focus request, so the two revoked each other and every
 * revocation arrived as `AUDIOFOCUS_LOSS`. On a Pixel 4 running Android 10 that produced 65 losses
 * in 55 seconds and playback that started and stopped again the instant it was pressed.
 */
class AudioFocusPolicyTest {

    @Test
    fun a_permanent_loss_stops_playback_and_forgets_the_users_intent() {
        assertEquals(
            AudioFocusAction.PAUSE_AND_CLEAR_INTENT,
            AudioFocusPolicy.resolve(AudioManager.AUDIOFOCUS_LOSS)
        )
    }

    @Test
    fun a_transient_loss_stops_the_sound_but_keeps_the_users_intent() {
        assertEquals(
            AudioFocusAction.PAUSE_KEEPING_INTENT,
            AudioFocusPolicy.resolve(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
        assertEquals(
            AudioFocusAction.PAUSE_KEEPING_INTENT,
            AudioFocusPolicy.resolve(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        )
    }

    @Test
    fun regaining_focus_resumes_only_when_something_was_pending() {
        listOf(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        ).forEach { focusChange ->
            assertEquals(
                "focus change $focusChange should resume",
                AudioFocusAction.RESUME_IF_PENDING,
                AudioFocusPolicy.resolve(focusChange)
            )
        }
    }

    @Test
    fun an_unrecognised_focus_change_does_nothing_rather_than_guessing() {
        assertEquals(AudioFocusAction.NONE, AudioFocusPolicy.resolve(Int.MIN_VALUE))
        assertEquals(AudioFocusAction.NONE, AudioFocusPolicy.resolve(9_999))
    }

    @Test
    fun an_interruption_followed_by_its_end_returns_to_playing() {
        // A phone call is the everyday case: transient loss, then gain. The user should not have to
        // press play again afterwards.
        assertEquals(
            AudioFocusAction.PAUSE_KEEPING_INTENT,
            AudioFocusPolicy.resolve(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
        assertEquals(
            AudioFocusAction.RESUME_IF_PENDING,
            AudioFocusPolicy.resolve(AudioManager.AUDIOFOCUS_GAIN)
        )
    }
}
