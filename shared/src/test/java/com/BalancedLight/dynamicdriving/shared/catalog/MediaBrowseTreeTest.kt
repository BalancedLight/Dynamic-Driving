package com.BalancedLight.dynamicdriving.shared.catalog

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.BalancedLight.dynamicdriving.shared.playlist.PlaylistRepository
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaBrowseTreeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var catalogRepository: SongCatalogRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var tree: MediaBrowseTree

    @Before
    fun setUp() {
        listOf("dynamic_driving_song_library", "dynamic_driving_playlists").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }
        catalogRepository = SongCatalogRepository(context)
        playlistRepository = PlaylistRepository(context)
        tree = MediaBrowseTree(catalogRepository, playlistRepository)
        awaitLibrary()
    }

    @Test
    fun root_offers_all_songs_and_playlists() {
        val children = tree.children(MediaBrowseIds.ROOT)

        assertEquals(
            listOf(MediaBrowseIds.ALL_SONGS, MediaBrowseIds.PLAYLISTS),
            children.map { it.mediaId }
        )
        assertTrue(children.all { it.mediaMetadata.isBrowsable == true })
        assertTrue(children.none { it.mediaMetadata.isPlayable == true })
    }

    @Test
    fun all_songs_lists_playable_items_with_real_metadata_and_no_invented_values() {
        val songs = tree.children(MediaBrowseIds.ALL_SONGS)

        assertTrue(songs.isNotEmpty())
        val demo = songs.first { it.mediaId == "open_road_demo" }
        assertEquals("Open Road", demo.mediaMetadata.title)
        assertEquals("Dynamic Driving Demo", demo.mediaMetadata.artist)
        assertEquals("Getting Started", demo.mediaMetadata.albumTitle)
        assertEquals(true, demo.mediaMetadata.isPlayable)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, demo.mediaMetadata.mediaType)
    }

    @Test
    fun a_song_without_declared_metadata_publishes_nothing_rather_than_a_placeholder() {
        val song = catalogRepository.getSongs().first().copy(
            songId = "no_metadata",
            artist = null,
            album = null
        )

        val metadata = MediaItemFactory.browsableSongItem(song).mediaMetadata

        assertNull(metadata.artist)
        assertNull(metadata.albumTitle)
        assertNotNull(metadata.title)
    }

    @Test
    fun playlists_appear_in_user_order_with_their_songs() {
        val evening = playlistRepository.createPlaylist("Evening Drive")
        val morning = playlistRepository.createPlaylist("Morning Run")
        val songId = catalogRepository.getSongs().first().songId
        playlistRepository.addSong(evening.id, songId)

        val playlists = tree.children(MediaBrowseIds.PLAYLISTS)

        assertEquals(
            listOf(
                MediaBrowseIds.playlistNode(evening.id),
                MediaBrowseIds.playlistNode(morning.id)
            ),
            playlists.map { it.mediaId }
        )

        val eveningSongs = tree.children(MediaBrowseIds.playlistNode(evening.id))
        assertEquals(
            listOf(MediaBrowseIds.playlistSong(evening.id, songId)),
            eveningSongs.map { it.mediaId }
        )
        assertTrue(tree.children(MediaBrowseIds.playlistNode(morning.id)).isEmpty())
    }

    @Test
    fun playlist_song_ids_round_trip_without_reserving_id_characters() {
        val mediaId = MediaBrowseIds.playlistSong("mix:night/drive", "song:one/two")

        assertEquals("mix:night/drive" to "song:one/two", MediaBrowseIds.playlistSongIdsFrom(mediaId))
    }

    @Test
    fun search_matches_title_artist_and_album() {
        assertTrue(tree.search("open road").any { it.mediaId == "open_road_demo" })
        assertTrue(tree.search("dynamic driving demo").any { it.mediaId == "open_road_demo" })
        assertTrue(tree.search("getting started").any { it.mediaId == "open_road_demo" })
        assertTrue(tree.search("no such song").isEmpty())
    }

    @Test
    fun search_matches_a_playlist_name_and_returns_its_songs() {
        val playlist = playlistRepository.createPlaylist("Night Shift")
        val songId = catalogRepository.getSongs().first().songId
        playlistRepository.addSong(playlist.id, songId)

        val results = tree.search("night shift")

        assertEquals(listOf(songId), results.map { it.mediaId })
    }

    @Test
    fun play_by_id_resolves_a_song_a_playlist_and_the_all_songs_node() {
        val songId = catalogRepository.getSongs().first().songId
        val playlist = playlistRepository.createPlaylist("Commute")
        playlistRepository.addSong(playlist.id, songId)

        assertEquals(
            MediaPlayRequest(ActiveCollection.AllSongs, songId),
            tree.resolvePlayRequest(songId)
        )
        assertEquals(
            MediaPlayRequest(ActiveCollection.Playlist(playlist.id), songId),
            tree.resolvePlayRequest(MediaBrowseIds.playlistNode(playlist.id))
        )
        val playlistSongId = MediaBrowseIds.playlistSong(playlist.id, songId)
        assertEquals(
            MediaPlayRequest(ActiveCollection.Playlist(playlist.id), songId),
            tree.resolvePlayRequest(playlistSongId)
        )
        assertEquals(playlistSongId, tree.item(playlistSongId)?.mediaId)
        assertEquals(
            MediaPlayRequest(ActiveCollection.AllSongs, songId),
            tree.resolvePlayRequest(MediaBrowseIds.ALL_SONGS)
        )
        assertNull(tree.resolvePlayRequest("not-a-real-id"))
    }

    @Test
    fun a_voice_search_prefers_a_matching_playlist_then_falls_back_to_a_song() {
        val songId = catalogRepository.getSongs().first().songId
        val playlist = playlistRepository.createPlaylist("Sunset Loop")
        playlistRepository.addSong(playlist.id, songId)

        assertEquals(
            MediaPlayRequest(ActiveCollection.Playlist(playlist.id), songId),
            tree.resolveSearchRequest("sunset")
        )
        assertEquals(
            MediaPlayRequest(ActiveCollection.AllSongs, "open_road_demo"),
            tree.resolveSearchRequest("open road")
        )
        assertNull(tree.resolveSearchRequest("nothing at all matches this"))
    }

    @Test
    fun an_empty_playlist_cannot_be_used_to_start_playback() {
        val playlist = playlistRepository.createPlaylist("Empty")

        assertNull(tree.resolvePlayRequest(MediaBrowseIds.playlistNode(playlist.id)))
    }

    private fun awaitLibrary(timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = catalogRepository.libraryState.value
            if (!state.isRefreshing && state.songs.isNotEmpty()) {
                return
            }
            Thread.sleep(20L)
        }
        throw AssertionError("Library never finished loading")
    }
}
