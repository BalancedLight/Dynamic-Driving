package com.BalancedLight.dynamicdriving.shared.playlist

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Durable, device-local playlist storage.
 *
 * The repository owns persistence and identity generation; every ordering rule lives in
 * [PlaylistOperations] so it can be tested without Android.
 */
class PlaylistRepository(
    context: Context,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    companion object {
        private const val PREFERENCES_NAME = "dynamic_driving_playlists"
        private const val KEY_PLAYLISTS_JSON = "playlists_json"
    }

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _playlists = MutableStateFlow(
        PlaylistSerialization.decode(preferences.getString(KEY_PLAYLISTS_JSON, null))
    )

    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun findPlaylist(playlistId: String): Playlist? =
        _playlists.value.firstOrNull { it.id == playlistId }

    fun createPlaylist(name: String): Playlist {
        val (updated, created) = PlaylistOperations.createPlaylist(_playlists.value, name, idFactory)
        commit(updated)
        return created
    }

    fun renamePlaylist(playlistId: String, name: String) {
        commit(PlaylistOperations.renamePlaylist(_playlists.value, playlistId, name))
    }

    fun deletePlaylist(playlistId: String) {
        commit(PlaylistOperations.deletePlaylist(_playlists.value, playlistId))
    }

    fun movePlaylist(fromIndex: Int, toIndex: Int) {
        commit(PlaylistOperations.movePlaylist(_playlists.value, fromIndex, toIndex))
    }

    fun addSong(playlistId: String, songId: String) {
        commit(PlaylistOperations.addSong(_playlists.value, playlistId, songId))
    }

    fun removeSong(playlistId: String, songId: String) {
        commit(PlaylistOperations.removeSong(_playlists.value, playlistId, songId))
    }

    fun moveSong(
        playlistId: String,
        availableSongIds: Set<String>,
        fromVisibleIndex: Int,
        toVisibleIndex: Int
    ) {
        commit(
            PlaylistOperations.moveSong(
                playlists = _playlists.value,
                playlistId = playlistId,
                availableSongIds = availableSongIds,
                fromVisibleIndex = fromVisibleIndex,
                toVisibleIndex = toVisibleIndex
            )
        )
    }

    fun resolveAll(availableSongIds: Set<String>): List<ResolvedPlaylist> =
        _playlists.value.map { PlaylistOperations.resolve(it, availableSongIds) }

    fun resolve(playlistId: String, availableSongIds: Set<String>): ResolvedPlaylist? =
        findPlaylist(playlistId)?.let { PlaylistOperations.resolve(it, availableSongIds) }

    private fun commit(updated: List<Playlist>) {
        if (updated == _playlists.value) {
            return
        }
        preferences.edit { putString(KEY_PLAYLISTS_JSON, PlaylistSerialization.encode(updated)) }
        _playlists.value = updated
    }
}
