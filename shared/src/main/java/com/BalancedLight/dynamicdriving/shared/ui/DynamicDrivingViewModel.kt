package com.BalancedLight.dynamicdriving.shared.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import com.BalancedLight.dynamicdriving.shared.catalog.LibraryChangeResult
import com.BalancedLight.dynamicdriving.shared.catalog.SongLibraryState
import com.BalancedLight.dynamicdriving.shared.playback.PlaybackUiState
import com.BalancedLight.dynamicdriving.shared.playlist.PlaylistOperations
import com.BalancedLight.dynamicdriving.shared.playlist.ResolvedPlaylist
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection
import com.BalancedLight.dynamicdriving.shared.settings.DynamicDrivingSettingsState
import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A one-shot result of a library-folder change, surfaced once as a snackbar. */
sealed interface LibraryFeedback {
    val id: Long

    data class Changed(
        override val id: Long,
        val rootName: String,
        val songCount: Int
    ) : LibraryFeedback

    data class Failed(
        override val id: Long,
        val message: String
    ) : LibraryFeedback
}

data class DynamicDrivingUiModel(
    val playback: PlaybackUiState = PlaybackUiState(),
    val library: SongLibraryState = SongLibraryState(isRefreshing = true),
    val playlists: List<ResolvedPlaylist> = emptyList(),
    val settings: DynamicDrivingSettingsState = DynamicDrivingSettingsState(),
    val isChangingLibrary: Boolean = false
)

/**
 * Single view model behind every app screen.
 *
 * The object graph already lives in [DynamicDrivingRuntime] (the media service needs it whether or not
 * an activity exists), so this class exists to project that state into Compose and to keep
 * screen-level concerns like the folder-change snackbar out of the domain layer.
 */
class DynamicDrivingViewModel : ViewModel() {
    private val runtime = DynamicDrivingRuntime.require()

    private val _isChangingLibrary = MutableStateFlow(false)
    private val _feedback = MutableStateFlow<LibraryFeedback?>(null)
    private var feedbackCounter = 0L

    val feedback: StateFlow<LibraryFeedback?> = _feedback

    val uiModel: StateFlow<DynamicDrivingUiModel> = combine(
        runtime.playbackController.uiState,
        runtime.catalogRepository.libraryState,
        runtime.playlistRepository.playlists,
        runtime.settings.state,
        _isChangingLibrary
    ) { playback, library, playlists, settings, isChangingLibrary ->
        val availableIds = library.songs.map { it.songId }.toSet()
        DynamicDrivingUiModel(
            playback = playback,
            library = library,
            playlists = playlists.map { playlist -> PlaylistOperations.resolve(playlist, availableIds) },
            settings = settings,
            isChangingLibrary = isChangingLibrary
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DynamicDrivingUiModel()
    )

    // ------------------------------------------------------------------ playback

    fun togglePlayback() = runtime.playbackController.togglePlayback()

    fun skipToNext() = runtime.playbackController.skipToNextSong()

    fun skipToPrevious() = runtime.playbackController.skipToPreviousSong()

    fun playSong(songId: String, collection: ActiveCollection = ActiveCollection.AllSongs) {
        runtime.playbackController.playFromCollection(collection, songId)
    }

    fun setActiveCollection(collection: ActiveCollection) {
        runtime.playbackController.setActiveCollection(collection)
    }

    // ------------------------------------------------------------------ settings

    fun setSpeedSourceSelection(selection: SpeedSourceSelection) =
        runtime.playbackController.setSpeedSourceSelection(selection)

    fun setManualSpeedMph(mph: Double) = runtime.playbackController.setManualSpeedMph(mph)

    fun setPlaybackPolicy(policy: PlaylistPlaybackPolicy) =
        runtime.playbackController.setPlaybackPolicy(policy)

    fun setLoopsBeforeAdvance(loops: Int) = runtime.playbackController.setLoopsBeforeAdvance(loops)

    fun setBundledDemoEnabled(enabled: Boolean) = runtime.setBundledDemoEnabled(enabled)

    fun setOnboardingCompleted(completed: Boolean) = runtime.settings.setOnboardingCompleted(completed)

    fun setForegroundMonitoringActive(active: Boolean) =
        runtime.setForegroundMonitoringActive(active)

    fun refreshLiveInputs() = runtime.refreshLiveInputs()

    // ------------------------------------------------------------------ library

    fun refreshLibrary() = runtime.catalogRepository.refreshLibrary()

    /**
     * Switches the music folder.
     *
     * The repository keeps the previous library intact unless the new folder scans cleanly, so a
     * failure here is purely a message: nothing the user had is lost.
     */
    fun changeLibraryRoot(treeUri: Uri?) {
        viewModelScope.launch {
            _isChangingLibrary.value = true
            try {
                when (val result = runtime.catalogRepository.changeLibraryRoot(treeUri)) {
                    is LibraryChangeResult.Success -> {
                        _feedback.value = LibraryFeedback.Changed(
                            id = nextFeedbackId(),
                            rootName = result.root?.displayName.orEmpty(),
                            songCount = result.songCount
                        )
                    }

                    is LibraryChangeResult.Failed -> {
                        _feedback.value = LibraryFeedback.Failed(
                            id = nextFeedbackId(),
                            message = result.message
                        )
                    }

                    LibraryChangeResult.Superseded -> Unit
                }
            } finally {
                _isChangingLibrary.value = false
            }
        }
    }

    fun consumeFeedback(id: Long) {
        if (_feedback.value?.id == id) {
            _feedback.value = null
        }
    }

    // ------------------------------------------------------------------ playlists

    fun createPlaylist(name: String) {
        runtime.playlistRepository.createPlaylist(name)
    }

    /** Creates a playlist and returns its id so the caller can seed it with a song. */
    fun createPlaylistReturning(name: String): String =
        runtime.playlistRepository.createPlaylist(name).id

    fun renamePlaylist(playlistId: String, name: String) =
        runtime.playlistRepository.renamePlaylist(playlistId, name)

    fun deletePlaylist(playlistId: String) {
        val activeCollection = runtime.settings.state.value.activeCollection
        runtime.playlistRepository.deletePlaylist(playlistId)
        if (activeCollection is ActiveCollection.Playlist && activeCollection.playlistId == playlistId) {
            // Deleting the collection currently feeding playback falls back to All Songs.
            runtime.playbackController.setActiveCollection(ActiveCollection.AllSongs)
        }
    }

    fun movePlaylist(fromIndex: Int, toIndex: Int) =
        runtime.playlistRepository.movePlaylist(fromIndex, toIndex)

    fun addSongToPlaylist(playlistId: String, songId: String) =
        runtime.playlistRepository.addSong(playlistId, songId)

    fun removeSongFromPlaylist(playlistId: String, songId: String) =
        runtime.playlistRepository.removeSong(playlistId, songId)

    fun moveSongInPlaylist(playlistId: String, fromVisibleIndex: Int, toVisibleIndex: Int) {
        val availableIds = runtime.catalogRepository.getSongs().map { it.songId }.toSet()
        runtime.playlistRepository.moveSong(
            playlistId = playlistId,
            availableSongIds = availableIds,
            fromVisibleIndex = fromVisibleIndex,
            toVisibleIndex = toVisibleIndex
        )
    }

    fun artworkFor(songId: String?) = runtime.catalogRepository.getSongArtworkBitmap(songId)

    private fun nextFeedbackId(): Long {
        feedbackCounter += 1
        return feedbackCounter
    }
}
