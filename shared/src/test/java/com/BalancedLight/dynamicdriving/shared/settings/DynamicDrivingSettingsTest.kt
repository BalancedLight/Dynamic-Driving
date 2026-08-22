package com.BalancedLight.dynamicdriving.shared.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DynamicDrivingSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun defaults_match_the_documented_behaviour() {
        val settings = DynamicDrivingSettings(context)
        val state = settings.state.value

        assertEquals(SpeedSourceSelection.AUTOMATIC, state.speedSourceSelection)
        assertEquals(PlaylistPlaybackPolicy.REPEAT_SONG, state.playbackPolicy)
        assertEquals(2, state.loopsBeforeAdvance)
        assertTrue(state.bundledDemoEnabled)
        assertFalse(state.onboardingCompleted)
        assertEquals(ActiveCollection.AllSongs, state.activeCollection)
    }

    @Test
    fun speed_source_and_manual_speed_survive_a_restart() {
        DynamicDrivingSettings(context).apply {
            setSpeedSourceSelection(SpeedSourceSelection.MANUAL)
            setManualSpeedMph(48.0)
        }

        val reloaded = DynamicDrivingSettings(context).state.value

        assertEquals(SpeedSourceSelection.MANUAL, reloaded.speedSourceSelection)
        assertEquals(48.0, reloaded.manualSpeedMph, 0.001)
    }

    @Test
    fun manual_speed_is_clamped_to_the_supported_range() {
        val settings = DynamicDrivingSettings(context)

        settings.setManualSpeedMph(-20.0)
        assertEquals(DynamicDrivingSettings.MIN_MANUAL_SPEED_MPH, settings.state.value.manualSpeedMph, 0.001)

        settings.setManualSpeedMph(9_000.0)
        assertEquals(DynamicDrivingSettings.MAX_MANUAL_SPEED_MPH, settings.state.value.manualSpeedMph, 0.001)
    }

    @Test
    fun loops_before_advance_is_clamped_to_one_through_eight() {
        val settings = DynamicDrivingSettings(context)

        settings.setLoopsBeforeAdvance(0)
        assertEquals(DynamicDrivingSettings.MIN_LOOPS_BEFORE_ADVANCE, settings.state.value.loopsBeforeAdvance)

        settings.setLoopsBeforeAdvance(99)
        assertEquals(DynamicDrivingSettings.MAX_LOOPS_BEFORE_ADVANCE, settings.state.value.loopsBeforeAdvance)

        settings.setLoopsBeforeAdvance(5)
        assertEquals(5, settings.state.value.loopsBeforeAdvance)
        assertEquals(5, DynamicDrivingSettings(context).state.value.loopsBeforeAdvance)
    }

    @Test
    fun playback_policy_and_active_collection_persist() {
        DynamicDrivingSettings(context).apply {
            setPlaybackPolicy(PlaylistPlaybackPolicy.SHUFFLE)
            setActiveCollection(ActiveCollection.Playlist("abc"))
            setLastSongId("song-9")
            setOnboardingCompleted(true)
        }

        val reloaded = DynamicDrivingSettings(context).state.value

        assertEquals(PlaylistPlaybackPolicy.SHUFFLE, reloaded.playbackPolicy)
        assertEquals(ActiveCollection.Playlist("abc"), reloaded.activeCollection)
        assertEquals("song-9", reloaded.lastSongId)
        assertTrue(reloaded.onboardingCompleted)
    }

    @Test
    fun the_bundled_demo_playback_option_survives_a_restart() {
        DynamicDrivingSettings(context).setBundledDemoEnabled(false)

        val reloaded = DynamicDrivingSettings(context).state.value

        assertFalse(reloaded.bundledDemoEnabled)
    }

    @Test
    fun an_unrecognised_stored_value_falls_back_to_the_default() {
        context.getSharedPreferences("dynamic_driving_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("speed_source_selection", "SOMETHING_ELSE")
            .putString("playback_policy", "NONSENSE")
            .apply()

        val state = DynamicDrivingSettings(context).state.value

        assertEquals(SpeedSourceSelection.AUTOMATIC, state.speedSourceSelection)
        assertEquals(PlaylistPlaybackPolicy.REPEAT_SONG, state.playbackPolicy)
    }

    @Test
    fun collection_ids_round_trip_through_their_string_form() {
        assertEquals(ActiveCollection.AllSongs, ActiveCollection.fromId(ActiveCollection.AllSongs.id))
        assertEquals(
            ActiveCollection.Playlist("xyz"),
            ActiveCollection.fromId(ActiveCollection.Playlist("xyz").id)
        )
        assertEquals(ActiveCollection.AllSongs, ActiveCollection.fromId(null))
        assertEquals(ActiveCollection.AllSongs, ActiveCollection.fromId("playlist:"))
        assertEquals(ActiveCollection.AllSongs, ActiveCollection.fromId("garbage"))
    }
}
