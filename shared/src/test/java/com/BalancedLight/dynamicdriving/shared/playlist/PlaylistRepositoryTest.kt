package com.BalancedLight.dynamicdriving.shared.playlist

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var idCounter = 0

    @Before
    fun setUp() {
        context.getSharedPreferences("dynamic_driving_playlists", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        idCounter = 0
    }

    @Test
    fun playlists_survive_a_restart_with_order_and_songs_intact() {
        val repository = newRepository()
        val first = repository.createPlaylist("Morning")
        val second = repository.createPlaylist("Evening")
        repository.addSong(first.id, "song-a")
        repository.addSong(first.id, "song-b")
        repository.addSong(second.id, "song-c")

        val reloaded = newRepository()

        assertEquals(listOf("Morning", "Evening"), reloaded.playlists.value.map { it.name })
        assertEquals(listOf("song-a", "song-b"), reloaded.findPlaylist(first.id)?.songIds)
        assertEquals(listOf("song-c"), reloaded.findPlaylist(second.id)?.songIds)
    }

    @Test
    fun deleting_a_playlist_removes_it_permanently() {
        val repository = newRepository()
        val playlist = repository.createPlaylist("Temporary")

        repository.deletePlaylist(playlist.id)

        assertNull(repository.findPlaylist(playlist.id))
        assertTrue(newRepository().playlists.value.isEmpty())
    }

    @Test
    fun renaming_persists_and_disambiguates() {
        val repository = newRepository()
        repository.createPlaylist("Roadtrip")
        val second = repository.createPlaylist("Other")

        repository.renamePlaylist(second.id, "Roadtrip")

        assertEquals("Roadtrip 2", newRepository().findPlaylist(second.id)?.name)
    }

    @Test
    fun reordering_playlists_and_songs_persists() {
        val repository = newRepository()
        val a = repository.createPlaylist("A")
        val b = repository.createPlaylist("B")
        repository.addSong(a.id, "s1")
        repository.addSong(a.id, "s2")
        repository.addSong(a.id, "s3")

        repository.movePlaylist(0, 1)
        repository.moveSong(a.id, setOf("s1", "s2", "s3"), fromVisibleIndex = 2, toVisibleIndex = 0)

        val reloaded = newRepository()
        assertEquals(listOf(b.id, a.id), reloaded.playlists.value.map { it.id })
        assertEquals(listOf("s3", "s1", "s2"), reloaded.findPlaylist(a.id)?.songIds)
    }

    @Test
    fun a_song_whose_library_is_gone_stays_in_the_record_and_returns_later() {
        val repository = newRepository()
        val playlist = repository.createPlaylist("Mixed")
        repository.addSong(playlist.id, "present")
        repository.addSong(playlist.id, "absent")

        val whileMissing = repository.resolve(playlist.id, setOf("present"))
        assertEquals(listOf("present"), whileMissing?.availableSongIds)
        assertEquals(1, whileMissing?.missingSongCount)

        val whenRestored = repository.resolve(playlist.id, setOf("present", "absent"))
        assertEquals(listOf("present", "absent"), whenRestored?.availableSongIds)
        assertEquals(0, whenRestored?.missingSongCount)
    }

    @Test
    fun resolving_an_unknown_playlist_returns_nothing() {
        assertNull(newRepository().resolve("missing", emptySet()))
    }

    private fun newRepository(): PlaylistRepository =
        PlaylistRepository(context) { "playlist-${++idCounter}" }
}
