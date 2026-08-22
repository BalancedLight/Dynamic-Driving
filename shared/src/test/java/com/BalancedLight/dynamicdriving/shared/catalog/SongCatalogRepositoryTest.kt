package com.BalancedLight.dynamicdriving.shared.catalog

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the transactional folder switch.
 *
 * The behaviour that matters here is that a failed switch is inert: the previously loaded library
 * is still exactly what it was, the app has not died, and another attempt can follow immediately.
 */
@RunWith(RobolectricTestRunner::class)
class SongCatalogRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun bundled_demo_loads_with_its_declared_artist_and_album() {
        val repository = newRepository()

        val song = repository.awaitSongs().first { it.songId == "open_road_demo" }

        assertEquals("Open Road", song.displayName)
        assertEquals("Dynamic Driving Demo", song.artist)
        assertEquals("Getting Started", song.album)
        assertNotNull(song.artworkFile)
        assertTrue(song.stems.size >= 2)
    }

    @Test
    fun bundled_demo_can_be_removed_from_and_restored_to_the_playable_catalog() {
        val repository = newRepository(bundledDemoEnabled = false)

        val disabledState = repository.awaitLibrary()

        assertTrue(disabledState.songs.none { it.libraryRoot is SongLibraryRoot.BundledAssets })
        assertTrue(disabledState.roots.none { it is SongLibraryRoot.BundledAssets })

        repository.setBundledDemoEnabled(true)
        assertTrue(repository.awaitSongs().any { it.songId == "open_road_demo" })
    }

    @Test
    fun switching_to_an_unusable_folder_fails_without_losing_the_current_library() = runBlocking {
        val repository = newRepository()
        val before = repository.awaitSongs()

        val result = repository.changeLibraryRoot(Uri.parse("content://com.example.absent/tree/nope"))

        assertTrue("Expected a failure, got $result", result is LibraryChangeResult.Failed)
        assertEquals(before.map { it.songId }, repository.getSongs().map { it.songId })
        assertFalse(repository.libraryState.value.isRefreshing)
    }

    @Test
    fun a_malformed_uri_is_reported_rather_than_thrown() = runBlocking {
        val repository = newRepository()
        repository.awaitSongs()

        val result = repository.changeLibraryRoot(Uri.parse("not-even-a-content-uri"))

        assertTrue(result is LibraryChangeResult.Failed)
        assertTrue(repository.getSongs().isNotEmpty())
    }

    @Test
    fun repeated_failed_switches_leave_the_library_intact_every_time() = runBlocking {
        val repository = newRepository()
        val before = repository.awaitSongs().map { it.songId }

        repeat(8) { attempt ->
            val result = repository.changeLibraryRoot(
                Uri.parse("content://com.example.absent$attempt/tree/nope")
            )
            assertTrue(result is LibraryChangeResult.Failed)
            assertEquals(before, repository.getSongs().map { it.songId })
        }
    }

    @Test
    fun rapid_concurrent_switches_settle_on_one_library_without_dropping_songs() = runBlocking {
        val repository = newRepository()
        val before = repository.awaitSongs().map { it.songId }

        val results = (0 until 6).map { attempt ->
            async {
                if (attempt % 2 == 0) {
                    repository.changeLibraryRoot(null)
                } else {
                    repository.changeLibraryRoot(
                        Uri.parse("content://com.example.absent$attempt/tree/nope")
                    )
                }
            }
        }.awaitAll()

        // Every call returns a definite outcome; none of them may leave the library empty.
        assertTrue(results.all { it is LibraryChangeResult.Success || it is LibraryChangeResult.Failed || it == LibraryChangeResult.Superseded })
        assertEquals(before, repository.getSongs().map { it.songId })
        assertFalse(repository.libraryState.value.isRefreshing)
    }

    @Test
    fun falling_back_to_the_bundled_demo_succeeds() = runBlocking {
        val repository = newRepository()
        repository.awaitSongs()

        val result = repository.changeLibraryRoot(null)

        assertTrue(result is LibraryChangeResult.Success)
        val success = result as LibraryChangeResult.Success
        assertTrue(success.songCount >= 1)
        assertTrue(repository.getSongs().any { it.songId == "open_road_demo" })
    }

    @Test
    fun playback_is_told_about_an_adopted_library_but_not_about_a_failed_switch() = runBlocking {
        val repository = newRepository()
        repository.awaitSongs()

        var adoptions = 0
        repository.setTransitionHandler(object : LibraryTransitionHandler {
            override suspend fun onLibraryAdopted(newState: SongLibraryState) {
                adoptions += 1
            }
        })

        repository.changeLibraryRoot(null)
        val adoptionsAfterSuccess = adoptions
        assertTrue(adoptionsAfterSuccess >= 1)

        repository.changeLibraryRoot(Uri.parse("content://com.example.absent/tree/nope"))

        assertEquals(adoptionsAfterSuccess, adoptions)
    }

    private fun newRepository(bundledDemoEnabled: Boolean = true): SongCatalogRepository {
        context.getSharedPreferences("dynamic_driving_song_library", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        return SongCatalogRepository(context, bundledDemoEnabled)
    }

    private fun SongCatalogRepository.awaitLibrary(timeoutMs: Long = 10_000L): SongLibraryState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = libraryState.value
            if (!state.isRefreshing) {
                return state
            }
            Thread.sleep(20L)
        }
        throw AssertionError("Library never finished loading: ${libraryState.value}")
    }

    private fun SongCatalogRepository.awaitSongs(timeoutMs: Long = 10_000L): List<SongManifest> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = libraryState.value
            if (!state.isRefreshing && state.songs.isNotEmpty()) {
                return state.songs
            }
            Thread.sleep(20L)
        }
        throw AssertionError("Library never finished loading: ${libraryState.value}")
    }
}
