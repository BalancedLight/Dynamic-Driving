package com.BalancedLight.dynamicdriving.shared.catalog

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaMetadata
import com.BalancedLight.dynamicdriving.shared.artwork.SongArtworkStore

/**
 * Builds the [MediaItem]s published to MediaSession, the notification, the lock screen, and car
 * hosts.
 *
 * Artist and album are written only when the manifest actually declares them. Anything that reads
 * this session — including external scrobblers — therefore sees real metadata or nothing at all,
 * never an invented artist or album. "Unknown artist" is a UI-only label and never leaves the app.
 */
object MediaItemFactory {
    @Volatile
    private var artworkStore: SongArtworkStore? = null

    fun attachArtworkStore(store: SongArtworkStore) {
        artworkStore = store
    }

    fun loopingMediaItem(song: SongManifest): MediaItem =
        buildMediaItem(
            song = song,
            clipStartMs = song.loopRegion.startMs,
            clipEndMs = song.loopRegion.endMs
        )

    fun tailPlaybackMediaItem(song: SongManifest): MediaItem =
        buildMediaItem(song = song, clipStartMs = song.loopRegion.endMs, clipEndMs = null)

    fun playOutMediaItem(song: SongManifest): MediaItem =
        buildMediaItem(song = song, clipStartMs = song.loopRegion.startMs, clipEndMs = null)

    fun firstPassMediaItem(song: SongManifest): MediaItem =
        buildMediaItem(song = song, clipStartMs = 0L, clipEndMs = song.loopRegion.endMs)

    /** A browsable-tree entry: identical metadata, no clipping, so hosts can render it as a card. */
    fun browsableSongItem(song: SongManifest): MediaItem =
        browsableSongItem(song, song.songId)

    /** A browsable-tree entry whose ID can retain the collection it was reached through. */
    fun browsableSongItem(song: SongManifest, mediaId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(songMetadata(song).build())
            .build()

    fun songMetadata(song: SongManifest): MediaMetadata.Builder {
        val builder = MediaMetadata.Builder()
            .setTitle(song.displayName)
            .setDisplayTitle(song.displayName)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        song.artist?.let(builder::setArtist)
        song.album?.let(builder::setAlbumTitle)
        song.artist?.let(builder::setAlbumArtist)
        artworkStore?.artworkUri(song.songId)?.let(builder::setArtworkUri)
        return builder
    }

    private fun buildMediaItem(
        song: SongManifest,
        clipStartMs: Long,
        clipEndMs: Long?
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.songId)
            .setUri(song.transportStem.audioFile.uri)
            .setMediaMetadata(songMetadata(song).build())
            .setClippingConfiguration(
                ClippingConfiguration.Builder().apply {
                    setStartPositionMs(clipStartMs)
                    clipEndMs?.let(::setEndPositionMs)
                }.build()
            )
            .build()
    }
}
