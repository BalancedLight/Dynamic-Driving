package com.BalancedLight.dynamicdriving.shared.playback

import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackQueueRulesTest {
    private val collection = listOf("s1", "s2", "s3")

    @Test
    fun repeat_song_never_advances_no_matter_how_many_loops_elapse() {
        repeat(20) { completedLoops ->
            assertFalse(
                PlaybackQueueRules.shouldAdvanceAfterLoop(
                    policy = PlaylistPlaybackPolicy.REPEAT_SONG,
                    completedLoopCount = completedLoops,
                    loopsBeforeAdvance = 1
                )
            )
        }
    }

    @Test
    fun sequential_advances_only_once_the_loop_threshold_is_reached() {
        val policy = PlaylistPlaybackPolicy.SEQUENTIAL

        assertFalse(PlaybackQueueRules.shouldAdvanceAfterLoop(policy, 2, 3))
        assertTrue(PlaybackQueueRules.shouldAdvanceAfterLoop(policy, 3, 3))
        assertTrue(PlaybackQueueRules.shouldAdvanceAfterLoop(policy, 4, 3))
    }

    @Test
    fun shuffle_uses_the_same_loop_threshold_as_sequential() {
        val policy = PlaylistPlaybackPolicy.SHUFFLE

        assertFalse(PlaybackQueueRules.shouldAdvanceAfterLoop(policy, 7, 8))
        assertTrue(PlaybackQueueRules.shouldAdvanceAfterLoop(policy, 8, 8))
    }

    @Test
    fun a_loop_threshold_below_one_still_advances_after_a_single_loop() {
        assertTrue(
            PlaybackQueueRules.shouldAdvanceAfterLoop(
                policy = PlaylistPlaybackPolicy.SEQUENTIAL,
                completedLoopCount = 1,
                loopsBeforeAdvance = 0
            )
        )
    }

    @Test
    fun sequential_advancement_walks_the_collection_and_wraps() {
        val random = Random(1)

        assertEquals(
            "s2",
            PlaybackQueueRules.nextAutomaticSongId(
                collection,
                "s1",
                PlaylistPlaybackPolicy.SEQUENTIAL,
                random
            )
        )
        assertEquals(
            "s1",
            PlaybackQueueRules.nextAutomaticSongId(
                collection,
                "s3",
                PlaylistPlaybackPolicy.SEQUENTIAL,
                random
            )
        )
    }

    @Test
    fun shuffle_advancement_never_picks_the_current_song() {
        repeat(50) { seed ->
            val next = PlaybackQueueRules.nextAutomaticSongId(
                collection,
                "s2",
                PlaylistPlaybackPolicy.SHUFFLE,
                Random(seed)
            )
            assertNotEquals("s2", next)
            assertTrue(next in collection)
        }
    }

    @Test
    fun shuffle_advancement_stops_when_the_collection_holds_only_the_current_song() {
        assertNull(
            PlaybackQueueRules.nextAutomaticSongId(
                listOf("only"),
                "only",
                PlaylistPlaybackPolicy.SHUFFLE,
                Random(0)
            )
        )
    }

    @Test
    fun repeat_song_has_no_automatic_next_song() {
        assertNull(
            PlaybackQueueRules.nextAutomaticSongId(
                collection,
                "s1",
                PlaylistPlaybackPolicy.REPEAT_SONG,
                Random(0)
            )
        )
    }

    @Test
    fun automatic_advancement_on_an_empty_collection_yields_nothing() {
        assertNull(
            PlaybackQueueRules.nextAutomaticSongId(
                emptyList(),
                null,
                PlaylistPlaybackPolicy.SEQUENTIAL,
                Random(0)
            )
        )
    }

    @Test
    fun manual_next_and_previous_wrap_predictably_in_both_directions() {
        assertEquals("s2", PlaybackQueueRules.adjacentSongId(collection, "s1", 1))
        assertEquals("s1", PlaybackQueueRules.adjacentSongId(collection, "s3", 1))
        assertEquals("s3", PlaybackQueueRules.adjacentSongId(collection, "s1", -1))
        assertEquals("s2", PlaybackQueueRules.adjacentSongId(collection, "s3", -1))
    }

    @Test
    fun manual_next_uses_shuffle_instead_of_playlist_order() {
        val selectedSongs = (0 until 50).map { seed ->
            PlaybackQueueRules.manualSkipSongId(
                collectionSongIds = collection,
                currentSongId = "s1",
                offset = 1,
                policy = PlaylistPlaybackPolicy.SHUFFLE,
                random = Random(seed)
            )
        }.toSet()

        assertEquals(setOf("s2", "s3"), selectedSongs)
        assertFalse("s1" in selectedSongs)
    }

    @Test
    fun repeat_song_still_allows_an_explicit_next_or_previous_skip() {
        assertEquals(
            "s2",
            PlaybackQueueRules.manualSkipSongId(
                collection,
                "s1",
                1,
                PlaylistPlaybackPolicy.REPEAT_SONG,
                Random(0)
            )
        )
        assertEquals(
            "s3",
            PlaybackQueueRules.manualSkipSongId(
                collection,
                "s1",
                -1,
                PlaylistPlaybackPolicy.REPEAT_SONG,
                Random(0)
            )
        )
    }

    @Test
    fun manual_next_from_a_song_outside_the_collection_starts_from_the_top() {
        assertEquals("s2", PlaybackQueueRules.adjacentSongId(collection, "not-in-collection", 1))
        assertEquals("s3", PlaybackQueueRules.adjacentSongId(collection, null, -1))
    }

    @Test
    fun manual_next_on_a_single_song_collection_stays_put() {
        assertEquals("only", PlaybackQueueRules.adjacentSongId(listOf("only"), "only", 1))
        assertEquals("only", PlaybackQueueRules.adjacentSongId(listOf("only"), "only", -1))
    }

    @Test
    fun manual_next_on_an_empty_collection_yields_nothing() {
        assertNull(PlaybackQueueRules.adjacentSongId(emptyList(), "s1", 1))
    }
}
