package com.BalancedLight.dynamicdriving.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.catalog.SongLibraryDiagnosticKind
import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import com.BalancedLight.dynamicdriving.shared.speed.ResolvedSpeedSource
import java.util.Locale

@Composable
fun SpeedSourceSelection.label(): String = stringResource(
    when (this) {
        SpeedSourceSelection.AUTOMATIC -> R.string.speed_source_automatic
        SpeedSourceSelection.CAR -> R.string.speed_source_car
        SpeedSourceSelection.PHONE_GPS -> R.string.speed_source_phone_gps
        SpeedSourceSelection.MANUAL -> R.string.speed_source_manual
    }
)

@Composable
fun SpeedSourceSelection.description(): String = stringResource(
    when (this) {
        SpeedSourceSelection.AUTOMATIC -> R.string.speed_source_automatic_description
        SpeedSourceSelection.CAR -> R.string.speed_source_car_description
        SpeedSourceSelection.PHONE_GPS -> R.string.speed_source_phone_gps_description
        SpeedSourceSelection.MANUAL -> R.string.speed_source_manual_description
    }
)

@Composable
fun ResolvedSpeedSource.label(): String = stringResource(
    when (this) {
        ResolvedSpeedSource.CAR_NATIVE -> R.string.speed_resolved_car_native
        ResolvedSpeedSource.CAR_PROJECTED -> R.string.speed_resolved_car_projected
        ResolvedSpeedSource.PHONE_GPS -> R.string.speed_resolved_phone_gps
        ResolvedSpeedSource.MANUAL -> R.string.speed_resolved_manual
        ResolvedSpeedSource.NONE -> R.string.speed_resolved_none
    }
)

@Composable
fun PlaylistPlaybackPolicy.label(): String = stringResource(
    when (this) {
        PlaylistPlaybackPolicy.REPEAT_SONG -> R.string.playback_mode_repeat_song
        PlaylistPlaybackPolicy.SEQUENTIAL -> R.string.playback_mode_sequential
        PlaylistPlaybackPolicy.SHUFFLE -> R.string.playback_mode_shuffle
    }
)

@Composable
fun PlaylistPlaybackPolicy.description(): String = stringResource(
    when (this) {
        PlaylistPlaybackPolicy.REPEAT_SONG -> R.string.playback_mode_repeat_song_description
        PlaylistPlaybackPolicy.SEQUENTIAL -> R.string.playback_mode_sequential_description
        PlaylistPlaybackPolicy.SHUFFLE -> R.string.playback_mode_shuffle_description
    }
)

@Composable
fun SongLibraryDiagnosticKind.headline(): String = stringResource(
    when (this) {
        SongLibraryDiagnosticKind.EMPTY_FOLDER -> R.string.library_diagnostic_empty_folder
        SongLibraryDiagnosticKind.MALFORMED_SONG -> R.string.library_diagnostic_malformed
        SongLibraryDiagnosticKind.DUPLICATE_SONG -> R.string.library_diagnostic_duplicate
        SongLibraryDiagnosticKind.UNAVAILABLE_ROOT -> R.string.library_diagnostic_unavailable
    }
)

@Composable
fun songCountLabel(count: Int): String =
    pluralStringResource(R.plurals.library_song_count, count, count)

@Composable
fun playlistSongCountLabel(count: Int): String =
    pluralStringResource(R.plurals.playlists_song_summary, count, count)

@Composable
fun missingSongCountLabel(count: Int): String =
    pluralStringResource(R.plurals.playlists_missing_songs, count, count)

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
