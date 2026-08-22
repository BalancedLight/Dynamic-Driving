package com.BalancedLight.dynamicdriving

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the shipped Compose UI on a device.
 *
 * Onboarding is marked complete before the activity launches, because these tests are about the four
 * destinations rather than the first-run flow.
 */
@RunWith(AndroidJUnit4::class)
class DynamicDrivingAppUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val runtime: DynamicDrivingRuntime
        get() = DynamicDrivingRuntime.require()

    @Before
    fun completeOnboarding() {
        DynamicDrivingRuntime.initialize(ApplicationProvider.getApplicationContext<Context>())
        DynamicDrivingRuntime.require().settings.setOnboardingCompleted(true)
    }

    @Test
    fun now_playing_shows_the_bundled_demo_with_its_real_metadata() {
        awaitText("Open Road")

        composeRule.onNodeWithText("Open Road").assertIsDisplayed()
        composeRule.onNodeWithText("Dynamic Driving Demo").assertIsDisplayed()
        composeRule.onNodeWithText("Getting Started").assertIsDisplayed()
    }

    @Test
    fun every_destination_is_reachable_from_the_navigation_bar() {
        composeRule.onNodeWithText("Library").performClick()
        awaitText("Music folder")

        composeRule.onNodeWithText("Playlists").performClick()
        awaitText("All Songs")

        composeRule.onNodeWithText("Settings").performClick()
        awaitText("Speed source")

        composeRule.onNodeWithText("Now Playing").performClick()
        awaitText("Open Road")
    }

    @Test
    fun the_manual_speed_slider_appears_only_once_manual_is_selected() {
        composeRule.onNodeWithText("Settings").performClick()
        awaitText("Speed source")

        // Automatic is the default, so the manual control must not be on screen yet.
        assertEquals(
            SpeedSourceSelection.AUTOMATIC,
            runtime.settings.state.value.speedSourceSelection
        )
        assertEquals(0, countNodesWithText("Manual speed"))

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.waitForIdle()

        assertEquals(SpeedSourceSelection.MANUAL, runtime.settings.state.value.speedSourceSelection)
        awaitText("Manual speed")
    }

    @Test
    fun choosing_a_playback_mode_reveals_the_loop_count_and_persists_it() {
        composeRule.onNodeWithText("Settings").performClick()
        awaitText("Speed source")

        composeRule.onNodeWithText("Play in order").performClick()
        composeRule.waitForIdle()

        awaitText("Loops before moving on")
        assertEquals(PlaylistPlaybackPolicy.SEQUENTIAL, runtime.settings.state.value.playbackPolicy)
    }

    @Test
    fun creating_a_playlist_shows_it_in_the_playlists_destination() {
        val name = "Instrumented Test Playlist"
        runtime.playlistRepository.createPlaylist(name)

        composeRule.onNodeWithText("Playlists").performClick()

        awaitText(name)
    }

    private fun awaitText(text: String, timeoutMillis: Long = 20_000L) {
        composeRule.waitUntil(timeoutMillis) { countNodesWithText(text) > 0 }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun countNodesWithText(text: String): Int =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size
}
