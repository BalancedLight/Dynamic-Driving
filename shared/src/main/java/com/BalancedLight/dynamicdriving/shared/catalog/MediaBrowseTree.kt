package com.BalancedLight.dynamicdriving.shared.catalog

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.BalancedLight.dynamicdriving.shared.playlist.PlaylistRepository
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection

/** Stable identifiers for the browsable media hierarchy. */
object MediaBrowseIds {
    const val ROOT = "dl_root"
    const val ALL_SONGS = "dl_all_songs"
    const val PLAYLISTS = "dl_playlists"
    const val PLAYLIST_PREFIX = "dl_playlist:"
    private const val PLAYLIST_SONG_PREFIX = "dl_playlist_song:"

    fun playlistNode(playlistId: String): String = "$PLAYLIST_PREFIX$playlistId"

    fun playlistIdFrom(nodeId: String): String? =
        nodeId.takeIf { it.startsWith(PLAYLIST_PREFIX) }
            ?.removePrefix(PLAYLIST_PREFIX)
            ?.takeIf { it.isNotBlank() }

    /** Encodes both IDs without reserving any characters from user-created playlist IDs. */
    fun playlistSong(playlistId: String, songId: String): String {
        require(playlistId.isNotBlank()) { "Playlist ID cannot be blank." }
        require(songId.isNotBlank()) { "Song ID cannot be blank." }
        return "$PLAYLIST_SONG_PREFIX${playlistId.length}:$playlistId$songId"
    }

    fun playlistSongIdsFrom(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(PLAYLIST_SONG_PREFIX)) return null
        val payload = mediaId.removePrefix(PLAYLIST_SONG_PREFIX)
        val lengthSeparator = payload.indexOf(':')
        if (lengthSeparator <= 0) return null
        val playlistIdLength = payload.substring(0, lengthSeparator).toIntOrNull() ?: return null
        val playlistIdStart = lengthSeparator + 1
        val songIdStart = playlistIdStart + playlistIdLength
        if (playlistIdLength <= 0 || songIdStart >= payload.length) return null
        val playlistId = payload.substring(playlistIdStart, songIdStart)
        val songId = payload.substring(songIdStart)
        return if (playlistId.isBlank() || songId.isBlank()) null else playlistId to songId
    }
}

/** A resolved instruction to start playback, produced from a media ID or a voice/search query. */
data class MediaPlayRequest(
    val collection: ActiveCollection,
    val songId: String
)

/**
 * Projects the library and the user's playlists into the browse tree that Android Auto, AAOS, and
 * any other MediaBrowser client sees:
 *
 * ```
 * root
 *  ├── All Songs        → every song
 *  └── Playlists        → each playlist in the user's order → its songs in the user's order
 * ```
 */
class MediaBrowseTree(
    private val catalogRepository: SongCatalogRepository,
    private val playlistRepository: PlaylistRepository
) {
    fun rootItem(): MediaItem = browsableItem(
        mediaId = MediaBrowseIds.ROOT,
        title = "Dynamic Driving",
        subtitle = catalogRepository.libraryState.value.rootSummary,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    )

    fun children(parentId: String): List<MediaItem> {
        return when {
            parentId == MediaBrowseIds.ROOT -> listOf(
                browsableItem(
                    mediaId = MediaBrowseIds.ALL_SONGS,
                    title = "All Songs",
                    subtitle = songCountLabel(catalogRepository.getSongs().size),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                ),
                browsableItem(
                    mediaId = MediaBrowseIds.PLAYLISTS,
                    title = "Playlists",
                    subtitle = playlistCountLabel(playlistRepository.playlists.value.size),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

            parentId == MediaBrowseIds.ALL_SONGS ->
                catalogRepository.getSongs().map(MediaItemFactory::browsableSongItem)

            parentId == MediaBrowseIds.PLAYLISTS -> {
                val availableIds = availableSongIds()
                playlistRepository.resolveAll(availableIds).map { resolved ->
                    browsableItem(
                        mediaId = MediaBrowseIds.playlistNode(resolved.id),
                        title = resolved.name,
                        subtitle = songCountLabel(resolved.availableSongIds.size),
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
                    )
                }
            }

            else -> {
                val playlistId = MediaBrowseIds.playlistIdFrom(parentId) ?: return emptyList()
                val resolved = playlistRepository.resolve(playlistId, availableSongIds())
                    ?: return emptyList()
                resolved.availableSongIds.mapNotNull { songId ->
                    catalogRepository.findSong(songId)?.let { song ->
                        MediaItemFactory.browsableSongItem(
                            song,
                            MediaBrowseIds.playlistSong(resolved.id, songId)
                        )
                    }
                }
            }
        }
    }

    fun item(mediaId: String): MediaItem? {
        return when {
            mediaId == MediaBrowseIds.ROOT -> rootItem()

            mediaId == MediaBrowseIds.ALL_SONGS -> browsableItem(
                mediaId = MediaBrowseIds.ALL_SONGS,
                title = "All Songs",
                subtitle = songCountLabel(catalogRepository.getSongs().size),
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )

            mediaId == MediaBrowseIds.PLAYLISTS -> browsableItem(
                mediaId = MediaBrowseIds.PLAYLISTS,
                title = "Playlists",
                subtitle = playlistCountLabel(playlistRepository.playlists.value.size),
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            )

            MediaBrowseIds.playlistSongIdsFrom(mediaId) != null -> {
                val (playlistId, songId) = MediaBrowseIds.playlistSongIdsFrom(mediaId)!!
                val resolved = playlistRepository.resolve(playlistId, availableSongIds())
                    ?: return null
                if (songId !in resolved.availableSongIds) return null
                catalogRepository.findSong(songId)?.let { song ->
                    MediaItemFactory.browsableSongItem(song, mediaId)
                }
            }

            MediaBrowseIds.playlistIdFrom(mediaId) != null -> {
                val playlistId = MediaBrowseIds.playlistIdFrom(mediaId)!!
                playlistRepository.resolve(playlistId, availableSongIds())?.let { resolved ->
                    browsableItem(
                        mediaId = mediaId,
                        title = resolved.name,
                        subtitle = songCountLabel(resolved.availableSongIds.size),
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
                    )
                }
            }

            else -> catalogRepository.findSong(mediaId)?.let(MediaItemFactory::browsableSongItem)
        }
    }

    /** Matches a free-text query against song titles, artists, albums, and playlist names. */
    fun search(query: String): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return catalogRepository.getSongs().map(MediaItemFactory::browsableSongItem)
        }
        val availableIds = availableSongIds()
        val playlistMatches = playlistRepository.resolveAll(availableIds)
            .filter { it.name.lowercase().contains(needle) }
            .flatMap { it.availableSongIds }
        val songMatches = catalogRepository.getSongs().filter { song -> song.matches(needle) }
        val orderedIds = LinkedHashSet<String>()
        songMatches.forEach { orderedIds.add(it.songId) }
        playlistMatches.forEach(orderedIds::add)
        return orderedIds.mapNotNull { songId ->
            catalogRepository.findSong(songId)?.let(MediaItemFactory::browsableSongItem)
        }
    }

    /** Resolves a browse/voice selection into the collection and song playback should switch to. */
    fun resolvePlayRequest(mediaId: String): MediaPlayRequest? {
        MediaBrowseIds.playlistSongIdsFrom(mediaId)?.let { (playlistId, songId) ->
            val resolved = playlistRepository.resolve(playlistId, availableSongIds()) ?: return null
            if (songId !in resolved.availableSongIds) return null
            return MediaPlayRequest(ActiveCollection.Playlist(playlistId), songId)
        }
        MediaBrowseIds.playlistIdFrom(mediaId)?.let { playlistId ->
            val resolved = playlistRepository.resolve(playlistId, availableSongIds()) ?: return null
            val firstSongId = resolved.availableSongIds.firstOrNull() ?: return null
            return MediaPlayRequest(ActiveCollection.Playlist(playlistId), firstSongId)
        }
        if (mediaId == MediaBrowseIds.ALL_SONGS || mediaId == MediaBrowseIds.ROOT) {
            val firstSongId = catalogRepository.getSongs().firstOrNull()?.songId ?: return null
            return MediaPlayRequest(ActiveCollection.AllSongs, firstSongId)
        }
        val song = catalogRepository.findSong(mediaId) ?: return null
        return MediaPlayRequest(ActiveCollection.AllSongs, song.songId)
    }

    /** Resolves a spoken or typed search into something playable. */
    fun resolveSearchRequest(query: String): MediaPlayRequest? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return catalogRepository.getSongs().firstOrNull()
                ?.let { MediaPlayRequest(ActiveCollection.AllSongs, it.songId) }
        }
        val availableIds = availableSongIds()
        playlistRepository.resolveAll(availableIds)
            .firstOrNull { it.name.lowercase().contains(needle) && it.availableSongIds.isNotEmpty() }
            ?.let { resolved ->
                return MediaPlayRequest(
                    ActiveCollection.Playlist(resolved.id),
                    resolved.availableSongIds.first()
                )
            }
        return catalogRepository.getSongs().firstOrNull { it.matches(needle) }
            ?.let { MediaPlayRequest(ActiveCollection.AllSongs, it.songId) }
    }

    private fun availableSongIds(): Set<String> =
        catalogRepository.getSongs().map { it.songId }.toSet()

    private fun SongManifest.matches(lowercaseNeedle: String): Boolean {
        return displayName.lowercase().contains(lowercaseNeedle) ||
            artist?.lowercase()?.contains(lowercaseNeedle) == true ||
            album?.lowercase()?.contains(lowercaseNeedle) == true
    }

    private fun songCountLabel(count: Int): String =
        if (count == 1) "1 song" else "$count songs"

    private fun playlistCountLabel(count: Int): String =
        if (count == 1) "1 playlist" else "$count playlists"

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String,
        mediaType: Int
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setMediaType(mediaType)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()
}
