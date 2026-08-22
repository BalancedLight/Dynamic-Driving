package com.BalancedLight.dynamicdriving.projected

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Header
import androidx.car.app.model.Section
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import com.BalancedLight.dynamicdriving.shared.artwork.SongArtworkStore
import com.BalancedLight.dynamicdriving.shared.catalog.SongManifest
import com.BalancedLight.dynamicdriving.shared.playlist.ResolvedPlaylist
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection

/** Cover art shipped to the host is decoded small: templates carry icons over a binder budget. */
private const val CAR_ARTWORK_EDGE_PX = 128

/**
 * The distraction-safe selector Android Auto renders for Dynamic Driving.
 *
 * Playlists and songs are offered as titled grid sections with cover art and names. The host owns
 * the rendering — the app never draws a free-form browser — and selecting anything hands playback
 * to the media session, whose own UI the host then shows.
 */
class CarBrowseScreen(carContext: CarContext) : Screen(carContext) {
    private val runtime by lazy { DynamicDrivingRuntime.require() }
    private val artworkStore by lazy { SongArtworkStore.get(carContext) }

    override fun onGetTemplate(): Template {
        val libraryState = runtime.catalogRepository.libraryState.value
        val availableIds = libraryState.songs.map { it.songId }.toSet()
        val playlists = runtime.playlistRepository.resolveAll(availableIds)
            .filter { it.availableSongIds.isNotEmpty() }
        val contentLimit = carContext.gridContentLimit()

        val sections = mutableListOf<Section<*>>()
        if (playlists.isNotEmpty()) {
            sections += playlistSection(playlists.take(contentLimit))
        }
        sections += songSection(libraryState.songs.take(contentLimit))

        return SectionedItemTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(com.BalancedLight.dynamicdriving.shared.R.string.app_name))
                    .build()
            )
            .setSections(sections)
            .setLoading(libraryState.isRefreshing && libraryState.songs.isEmpty())
            .setAlphabeticalIndexingAllowed(true)
            .build()
    }

    private fun playlistSection(playlists: List<ResolvedPlaylist>): GridSection {
        val builder = GridSection.Builder()
            .setTitle(carContext.getString(R.string.car_section_playlists))
            .setItemSize(GridSection.ITEM_SIZE_LARGE)
            .setNoItemsMessage(carContext.getString(R.string.car_section_playlists_empty))
        playlists.forEach { playlist ->
            val coverSongId = playlist.availableSongIds.firstOrNull()
            builder.addItem(
                GridItem.Builder()
                    .setTitle(playlist.name)
                    .setText(songCountText(playlist.availableSongIds.size))
                    .apply { artworkIcon(coverSongId)?.let { setImage(it, GridItem.IMAGE_TYPE_LARGE) } }
                    .setOnClickListener { playPlaylist(playlist) }
                    .build()
            )
        }
        return builder.build()
    }

    private fun songSection(songs: List<SongManifest>): GridSection {
        val builder = GridSection.Builder()
            .setTitle(carContext.getString(R.string.car_section_library))
            .setItemSize(GridSection.ITEM_SIZE_LARGE)
            .setNoItemsMessage(carContext.getString(R.string.car_section_library_empty))
        songs.forEach { song ->
            builder.addItem(
                GridItem.Builder()
                    .setTitle(song.displayName)
                    .apply {
                        song.artist?.let(::setText)
                        artworkIcon(song.songId)?.let { setImage(it, GridItem.IMAGE_TYPE_LARGE) }
                    }
                    .setOnClickListener { playSong(song.songId) }
                    .build()
            )
        }
        return builder.build()
    }

    private fun artworkIcon(songId: String?): CarIcon? {
        val bitmap = songId?.let { artworkStore.loadBitmapScaled(it, CAR_ARTWORK_EDGE_PX) }
            ?: return null
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun songCountText(count: Int): String =
        carContext.resources.getQuantityString(R.plurals.car_song_count, count, count)

    private fun playPlaylist(playlist: ResolvedPlaylist) {
        val songId = playlist.availableSongIds.firstOrNull() ?: return
        runtime.playbackController.playFromCollection(
            ActiveCollection.Playlist(playlist.id),
            songId
        )
        invalidate()
    }

    private fun playSong(songId: String) {
        runtime.playbackController.playFromCollection(ActiveCollection.AllSongs, songId)
        invalidate()
    }
}
