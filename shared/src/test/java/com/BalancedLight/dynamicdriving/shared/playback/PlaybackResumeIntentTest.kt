package com.BalancedLight.dynamicdriving.shared.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumeIntentTest {
    @Test
    fun preserves_existing_resume_request_even_if_transport_is_temporarily_paused() {
        assertTrue(
            PlaybackResumeIntent.resolve(
                pendingResumeRequest = true,
                playWhenReady = false,
                isPlaying = false
            )
        )
    }

    @Test
    fun returns_true_when_transport_is_already_active() {
        assertTrue(
            PlaybackResumeIntent.resolve(
                pendingResumeRequest = false,
                playWhenReady = true,
                isPlaying = false
            )
        )
        assertTrue(
            PlaybackResumeIntent.resolve(
                pendingResumeRequest = false,
                playWhenReady = false,
                isPlaying = true
            )
        )
    }

    @Test
    fun stays_false_when_no_resume_has_been_requested() {
        assertFalse(
            PlaybackResumeIntent.resolve(
                pendingResumeRequest = false,
                playWhenReady = false,
                isPlaying = false
            )
        )
    }
}
