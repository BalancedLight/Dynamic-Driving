package com.BalancedLight.dynamicdriving.shared.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.playback.PlaybackUiState
import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import com.BalancedLight.dynamicdriving.shared.ui.components.SongArtwork
import com.BalancedLight.dynamicdriving.shared.ui.label

@Composable
fun NowPlayingScreen(
    state: PlaybackUiState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseAutomaticSpeedSource: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SongArtwork(
            songId = state.currentSongId,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 20.dp
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = state.currentSongTitle ?: stringResource(R.string.now_playing_no_song),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.currentSongArtist ?: stringResource(R.string.now_playing_unknown_artist),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = state.currentSongAlbum ?: stringResource(R.string.now_playing_unknown_album),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (!state.hasSong) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.now_playing_no_song_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))

        TransportControls(
            isPlaying = state.isPlaying,
            enabled = state.hasSong,
            onTogglePlayback = onTogglePlayback,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext
        )

        if (state.audioLoading) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.now_playing_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.audioLoadError?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.now_playing_audio_error, error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            AssistChip(
                onClick = onOpenPlaylists,
                label = {
                    Text(
                        text = state.activeCollectionName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            )
            AssistChip(
                onClick = onOpenSettings,
                label = { Text(state.playbackPolicy.label(), maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = when (state.playbackPolicy) {
                            PlaylistPlaybackPolicy.REPEAT_SONG -> Icons.Rounded.RepeatOne
                            PlaylistPlaybackPolicy.SEQUENTIAL -> Icons.Rounded.Repeat
                            PlaylistPlaybackPolicy.SHUFFLE -> Icons.Rounded.Shuffle
                        },
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        SpeedCard(
            state = state,
            onUseAutomaticSpeedSource = onUseAutomaticSpeedSource,
            onOpenSettings = onOpenSettings
        )
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    enabled: Boolean,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        IconButton(
            onClick = onSkipPrevious,
            enabled = enabled,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.now_playing_previous),
                modifier = Modifier.size(40.dp)
            )
        }

        FilledIconButton(
            onClick = onTogglePlayback,
            enabled = enabled,
            modifier = Modifier.size(84.dp),
            colors = IconButtonDefaults.filledIconButtonColors()
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.now_playing_pause else R.string.now_playing_play
                ),
                modifier = Modifier.size(48.dp)
            )
        }

        IconButton(
            onClick = onSkipNext,
            enabled = enabled,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.now_playing_next),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun SpeedCard(
    state: PlaybackUiState,
    onUseAutomaticSpeedSource: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val explanation = state.speed.strictUnavailableExplanation
    val speedText = if (explanation != null) {
        stringResource(R.string.now_playing_speed_unavailable, state.speed.selection.label())
    } else {
        stringResource(
            R.string.now_playing_speed,
            state.smoothedSpeedMph,
            state.speed.resolvedSource.label()
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = speedText },
        colors = CardDefaults.cardColors(
            containerColor = if (explanation != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.size(12.dp))
                Text(text = speedText, style = MaterialTheme.typography.titleMedium)
            }
            if (explanation != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.speed.selection != SpeedSourceSelection.AUTOMATIC) {
                        TextButton(onClick = onUseAutomaticSpeedSource) {
                            Text(stringResource(R.string.now_playing_use_automatic))
                        }
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.nav_settings))
                    }
                }
            }
        }
    }
    Box(Modifier.height(24.dp))
}
