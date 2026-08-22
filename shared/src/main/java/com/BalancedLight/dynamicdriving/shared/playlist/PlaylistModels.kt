package com.BalancedLight.dynamicdriving.shared.playlist

import org.json.JSONArray
import org.json.JSONObject

/**
 * A device-local, user-ordered collection of songs.
 *
 * [songIds] is the durable record and keeps every song the user ever added, including songs whose
 * library is not currently mounted. Those entries stay hidden in the UI but return intact when the
 * library that owns them comes back.
 */
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList()
)

/** A playlist projected against the songs that are actually available right now. */
data class ResolvedPlaylist(
    val playlist: Playlist,
    val availableSongIds: List<String>,
    val missingSongCount: Int
) {
    val id: String get() = playlist.id
    val name: String get() = playlist.name
    val isEmpty: Boolean get() = availableSongIds.isEmpty()
}

/**
 * Pure, storage-independent playlist operations.
 *
 * Every function returns a new list; nothing mutates in place. Keeping these free of Android types
 * means the playlist rules can be unit-tested directly.
 */
object PlaylistOperations {
    const val MAX_NAME_LENGTH: Int = 60

    fun createPlaylist(
        playlists: List<Playlist>,
        name: String,
        idFactory: () -> String
    ): Pair<List<Playlist>, Playlist> {
        val created = Playlist(
            id = uniqueId(playlists, idFactory),
            name = normalizeName(name, playlists, excludingId = null)
        )
        return (playlists + created) to created
    }

    fun renamePlaylist(playlists: List<Playlist>, playlistId: String, name: String): List<Playlist> {
        if (playlists.none { it.id == playlistId }) {
            return playlists
        }
        val normalized = normalizeName(name, playlists, excludingId = playlistId)
        return playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(name = normalized) else playlist
        }
    }

    fun deletePlaylist(playlists: List<Playlist>, playlistId: String): List<Playlist> {
        return playlists.filterNot { it.id == playlistId }
    }

    fun movePlaylist(playlists: List<Playlist>, fromIndex: Int, toIndex: Int): List<Playlist> {
        return moveWithin(playlists, fromIndex, toIndex)
    }

    fun addSong(playlists: List<Playlist>, playlistId: String, songId: String): List<Playlist> {
        if (songId.isBlank()) {
            return playlists
        }
        return playlists.map { playlist ->
            when {
                playlist.id != playlistId -> playlist
                playlist.songIds.contains(songId) -> playlist
                else -> playlist.copy(songIds = playlist.songIds + songId)
            }
        }
    }

    fun removeSong(playlists: List<Playlist>, playlistId: String, songId: String): List<Playlist> {
        return playlists.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songIds = playlist.songIds.filterNot { it == songId })
            } else {
                playlist
            }
        }
    }

    /**
     * Reorders songs using indices into the *visible* (available) projection, then writes the new
     * order back onto the durable list so hidden songs keep their relative positions.
     */
    fun moveSong(
        playlists: List<Playlist>,
        playlistId: String,
        availableSongIds: Set<String>,
        fromVisibleIndex: Int,
        toVisibleIndex: Int
    ): List<Playlist> {
        return playlists.map { playlist ->
            if (playlist.id != playlistId) {
                return@map playlist
            }
            val visible = playlist.songIds.filter { availableSongIds.contains(it) }
            val reorderedVisible = moveWithin(visible, fromVisibleIndex, toVisibleIndex)
            if (reorderedVisible == visible) {
                return@map playlist
            }
            val visibleIterator = reorderedVisible.iterator()
            val rewritten = playlist.songIds.map { songId ->
                if (availableSongIds.contains(songId)) visibleIterator.next() else songId
            }
            playlist.copy(songIds = rewritten)
        }
    }

    fun resolve(playlist: Playlist, availableSongIds: Set<String>): ResolvedPlaylist {
        val available = playlist.songIds.filter { availableSongIds.contains(it) }
        return ResolvedPlaylist(
            playlist = playlist,
            availableSongIds = available,
            missingSongCount = playlist.songIds.size - available.size
        )
    }

    fun normalizeName(
        rawName: String,
        playlists: List<Playlist>,
        excludingId: String?
    ): String {
        val trimmed = rawName.trim().take(MAX_NAME_LENGTH).ifBlank { "Playlist" }
        val taken = playlists
            .filter { it.id != excludingId }
            .map { it.name.lowercase() }
            .toSet()
        if (!taken.contains(trimmed.lowercase())) {
            return trimmed
        }
        var suffix = 2
        while (taken.contains("$trimmed $suffix".lowercase())) {
            suffix += 1
        }
        return "$trimmed $suffix"
    }

    private fun uniqueId(playlists: List<Playlist>, idFactory: () -> String): String {
        val taken = playlists.map { it.id }.toSet()
        var candidate = idFactory()
        while (candidate.isBlank() || taken.contains(candidate)) {
            candidate = idFactory()
        }
        return candidate
    }

    private fun <T> moveWithin(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (items.isEmpty()) {
            return items
        }
        if (fromIndex !in items.indices) {
            return items
        }
        val clampedTo = toIndex.coerceIn(0, items.lastIndex)
        if (fromIndex == clampedTo) {
            return items
        }
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(clampedTo, moved)
        return mutable
    }
}

/** JSON round-tripping for the durable playlist record. */
object PlaylistSerialization {
    private const val KEY_VERSION = "version"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_SONG_IDS = "songIds"
    private const val CURRENT_VERSION = 1

    fun encode(playlists: List<Playlist>): String {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val songIds = JSONArray()
            playlist.songIds.forEach(songIds::put)
            array.put(
                JSONObject()
                    .put(KEY_ID, playlist.id)
                    .put(KEY_NAME, playlist.name)
                    .put(KEY_SONG_IDS, songIds)
            )
        }
        return JSONObject()
            .put(KEY_VERSION, CURRENT_VERSION)
            .put(KEY_PLAYLISTS, array)
            .toString()
    }

    fun decode(rawJson: String?): List<Playlist> {
        if (rawJson.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            val root = JSONObject(rawJson)
            val array = root.optJSONArray(KEY_PLAYLISTS) ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val id = entry.optString(KEY_ID).takeIf { it.isNotBlank() } ?: continue
                    val songIdsJson = entry.optJSONArray(KEY_SONG_IDS)
                    val songIds = buildList {
                        for (songIndex in 0 until (songIdsJson?.length() ?: 0)) {
                            val songId = songIdsJson?.optString(songIndex).orEmpty()
                            if (songId.isNotBlank() && !contains(songId)) {
                                add(songId)
                            }
                        }
                    }
                    add(
                        Playlist(
                            id = id,
                            name = entry.optString(KEY_NAME).ifBlank { "Playlist" },
                            songIds = songIds
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
