package com.BalancedLight.dynamicdriving.shared.playback

import com.BalancedLight.dynamicdriving.shared.catalog.SongLibraryDiagnostic
import com.BalancedLight.dynamicdriving.shared.catalog.SongOption
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection
import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import com.BalancedLight.dynamicdriving.shared.speed.EffectiveSpeedState

/**
 * Per-stem internals.
 *
 * Only populated in debug builds; [PlaybackUiState.diagnostics] is null in release so no stem
 * control, gain figure, or mixer counter can reach a shipped screen.
 */
data class PlaybackStemDiagnostics(
    val stemId: String,
    val displayName: String,
    val stateLabel: String,
    val eligible: Boolean,
    val currentGain: Float,
    val targetGain: Float,
    val gainMultiplier: Float,
    val reverbWetMix: Float,
    val activeEvents: List<String> = emptyList(),
    val activeRemainingMs: Long? = null,
    val cooldownRemainingMs: Long? = null
)

/** Audio-engine internals. Debug builds only. */
data class PlaybackDiagnostics(
    val audioLoadDurationMs: Long = 0L,
    val audioLoadSourceSummary: String = "No stems loaded",
    val audioUnderrunCount: Int = 0,
    val activeAudioStemCount: Int = 0,
    val lastMixerRenderMs: Float = 0f,
    val maxMixerRenderMs: Float = 0f,
    val lastAudioWriteMs: Float = 0f,
    val maxAudioWriteMs: Float = 0f,
    val speedSampleAgeMs: Long = 0L,
    val stems: List<PlaybackStemDiagnostics> = emptyList()
)

/** Everything the app UI and the car surfaces render. */
data class PlaybackUiState(
    val currentSongId: String? = null,
    val currentSongTitle: String? = null,
    val currentSongArtist: String? = null,
    val currentSongAlbum: String? = null,
    val isPlaying: Boolean = false,
    val activeCollection: ActiveCollection = ActiveCollection.AllSongs,
    val activeCollectionName: String = "All Songs",
    val playbackPolicy: PlaylistPlaybackPolicy = PlaylistPlaybackPolicy.REPEAT_SONG,
    val loopsBeforeAdvance: Int = 2,
    val completedLoopCount: Int = 0,
    val queuedSongTitle: String? = null,
    val playingOutCurrentSong: Boolean = false,
    val speed: EffectiveSpeedState = EffectiveSpeedState(),
    val smoothedSpeedMph: Double = 0.0,
    val currentPositionMs: Long = 0L,
    val loopStartMs: Long = 0L,
    val loopEndMs: Long = 0L,
    val nextLoopInMs: Long = 0L,
    val libraryRootSummary: String = "Loading song library...",
    val libraryRefreshing: Boolean = true,
    val libraryDiagnostics: List<SongLibraryDiagnostic> = emptyList(),
    val audioLoading: Boolean = false,
    val audioLoadError: String? = null,
    val collectionSongOptions: List<SongOption> = emptyList(),
    val diagnostics: PlaybackDiagnostics? = null
) {
    val hasSong: Boolean get() = currentSongId != null
}
