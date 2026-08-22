package com.BalancedLight.dynamicdriving.shared.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOperationsTest {
    private var nextId = 0
    private val idFactory = { "playlist-${++nextId}" }

    @Test
    fun create_assigns_a_unique_id_and_keeps_insertion_order() {
        var playlists = emptyList<Playlist>()
        playlists = PlaylistOperations.createPlaylist(playlists, "Morning", idFactory).first
        playlists = PlaylistOperations.createPlaylist(playlists, "Evening", idFactory).first

        assertEquals(listOf("Morning", "Evening"), playlists.map { it.name })
        assertNotEquals(playlists[0].id, playlists[1].id)
    }

    @Test
    fun create_disambiguates_a_duplicate_name() {
        var playlists = emptyList<Playlist>()
        playlists = PlaylistOperations.createPlaylist(playlists, "Commute", idFactory).first
        playlists = PlaylistOperations.createPlaylist(playlists, "commute", idFactory).first

        assertEquals(listOf("Commute", "commute 2"), playlists.map { it.name })
    }

    @Test
    fun create_falls_back_to_a_default_name_when_blank() {
        val (playlists, created) = PlaylistOperations.createPlaylist(emptyList(), "   ", idFactory)

        assertEquals("Playlist", created.name)
        assertEquals(1, playlists.size)
    }

    @Test
    fun rename_leaves_other_playlists_alone() {
        val playlists = listOf(
            Playlist("a", "First"),
            Playlist("b", "Second")
        )

        val renamed = PlaylistOperations.renamePlaylist(playlists, "b", "Renamed")

        assertEquals(listOf("First", "Renamed"), renamed.map { it.name })
    }

    @Test
    fun rename_of_an_unknown_playlist_is_a_no_op() {
        val playlists = listOf(Playlist("a", "First"))

        assertEquals(playlists, PlaylistOperations.renamePlaylist(playlists, "missing", "Nope"))
    }

    @Test
    fun delete_removes_only_the_named_playlist() {
        val playlists = listOf(Playlist("a", "First"), Playlist("b", "Second"))

        assertEquals(listOf("b"), PlaylistOperations.deletePlaylist(playlists, "a").map { it.id })
    }

    @Test
    fun playlists_can_be_reordered_and_out_of_range_moves_are_ignored() {
        val playlists = listOf(
            Playlist("a", "A"),
            Playlist("b", "B"),
            Playlist("c", "C")
        )

        assertEquals(
            listOf("b", "c", "a"),
            PlaylistOperations.movePlaylist(playlists, 0, 2).map { it.id }
        )
        assertEquals(playlists, PlaylistOperations.movePlaylist(playlists, 7, 1))
        assertEquals(
            listOf("a", "b", "c"),
            PlaylistOperations.movePlaylist(playlists, 1, 1).map { it.id }
        )
        // Targets outside the list clamp to the ends rather than throwing.
        assertEquals(
            listOf("b", "c", "a"),
            PlaylistOperations.movePlaylist(playlists, 0, 99).map { it.id }
        )
    }

    @Test
    fun adding_a_song_appends_once_and_ignores_duplicates() {
        var playlists = listOf(Playlist("a", "A"))
        playlists = PlaylistOperations.addSong(playlists, "a", "song1")
        playlists = PlaylistOperations.addSong(playlists, "a", "song2")
        playlists = PlaylistOperations.addSong(playlists, "a", "song1")

        assertEquals(listOf("song1", "song2"), playlists.single().songIds)
    }

    @Test
    fun removing_a_song_keeps_the_rest_in_order() {
        val playlists = listOf(Playlist("a", "A", listOf("s1", "s2", "s3")))

        val updated = PlaylistOperations.removeSong(playlists, "a", "s2")

        assertEquals(listOf("s1", "s3"), updated.single().songIds)
    }

    @Test
    fun missing_songs_are_hidden_but_retained() {
        val playlist = Playlist("a", "A", listOf("present1", "gone", "present2"))

        val resolved = PlaylistOperations.resolve(playlist, setOf("present1", "present2"))

        assertEquals(listOf("present1", "present2"), resolved.availableSongIds)
        assertEquals(1, resolved.missingSongCount)
        // The durable record is untouched, so the song returns when its library comes back.
        assertEquals(listOf("present1", "gone", "present2"), resolved.playlist.songIds)
    }

    @Test
    fun a_playlist_whose_songs_are_all_missing_resolves_to_empty_without_losing_them() {
        val playlist = Playlist("a", "A", listOf("gone1", "gone2"))

        val resolved = PlaylistOperations.resolve(playlist, emptySet())

        assertTrue(resolved.isEmpty)
        assertEquals(2, resolved.missingSongCount)
        assertEquals(listOf("gone1", "gone2"), resolved.playlist.songIds)
    }

    @Test
    fun reordering_visible_songs_keeps_hidden_songs_in_their_slots() {
        val playlists = listOf(
            Playlist("a", "A", listOf("visible1", "hidden", "visible2", "visible3"))
        )
        val available = setOf("visible1", "visible2", "visible3")

        val updated = PlaylistOperations.moveSong(
            playlists = playlists,
            playlistId = "a",
            availableSongIds = available,
            fromVisibleIndex = 0,
            toVisibleIndex = 2
        )

        // The three visible songs become visible2, visible3, visible1; "hidden" stays in slot 1.
        assertEquals(
            listOf("visible2", "hidden", "visible3", "visible1"),
            updated.single().songIds
        )
    }

    @Test
    fun reordering_with_an_out_of_range_index_is_a_no_op() {
        val playlists = listOf(Playlist("a", "A", listOf("s1", "s2")))

        val updated = PlaylistOperations.moveSong(
            playlists = playlists,
            playlistId = "a",
            availableSongIds = setOf("s1", "s2"),
            fromVisibleIndex = 5,
            toVisibleIndex = 0
        )

        assertEquals(playlists, updated)
    }

    @Test
    fun serialization_round_trips_order_and_names() {
        val playlists = listOf(
            Playlist("a", "Morning", listOf("s1", "s2")),
            Playlist("b", "Evening", listOf("s3"))
        )

        val decoded = PlaylistSerialization.decode(PlaylistSerialization.encode(playlists))

        assertEquals(playlists, decoded)
    }

    @Test
    fun decoding_junk_yields_an_empty_library_rather_than_throwing() {
        assertTrue(PlaylistSerialization.decode(null).isEmpty())
        assertTrue(PlaylistSerialization.decode("").isEmpty())
        assertTrue(PlaylistSerialization.decode("not json").isEmpty())
        assertTrue(PlaylistSerialization.decode("""{"playlists":"wrong type"}""").isEmpty())
    }

    @Test
    fun decoding_drops_entries_without_an_id_and_deduplicates_song_ids() {
        val decoded = PlaylistSerialization.decode(
            """
            {
              "version": 1,
              "playlists": [
                { "name": "No id", "songIds": ["s1"] },
                { "id": "b", "name": "Good", "songIds": ["s1", "s1", "s2"] }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, decoded.size)
        assertEquals("b", decoded.single().id)
        assertEquals(listOf("s1", "s2"), decoded.single().songIds)
        assertFalse(decoded.any { it.name == "No id" })
    }
}
