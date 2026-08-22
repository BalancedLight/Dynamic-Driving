package com.BalancedLight.dynamicdriving.shared.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.catalog.SongLibraryDiagnostic
import com.BalancedLight.dynamicdriving.shared.catalog.SongManifest
import com.BalancedLight.dynamicdriving.shared.playlist.ResolvedPlaylist
import com.BalancedLight.dynamicdriving.shared.ui.components.SongArtwork
import com.BalancedLight.dynamicdriving.shared.ui.headline
import com.BalancedLight.dynamicdriving.shared.ui.playlistSongCountLabel
import com.BalancedLight.dynamicdriving.shared.ui.songCountLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songs: List<SongManifest>,
    rootSummary: String,
    isRefreshing: Boolean,
    isChangingLibrary: Boolean,
    diagnostics: List<SongLibraryDiagnostic>,
    playlists: List<ResolvedPlaylist>,
    currentSongId: String?,
    onChooseFolder: () -> Unit,
    onUseBundledOnly: () -> Unit,
    onRefresh: () -> Unit,
    onPlaySong: (String) -> Unit,
    onAddSongToPlaylist: (playlistId: String, songId: String) -> Unit,
    onCreatePlaylistWithSong: (songId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var addToPlaylistSongId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LibraryHeaderCard(
                rootSummary = rootSummary,
                songCount = songs.size,
                isBusy = isRefreshing || isChangingLibrary,
                onChooseFolder = onChooseFolder,
                onUseBundledOnly = onUseBundledOnly,
                onRefresh = onRefresh
            )
        }

        if (diagnostics.isNotEmpty()) {
            item { DiagnosticsCard(diagnostics) }
        }

        if (songs.isEmpty() && !isRefreshing && !isChangingLibrary) {
            item { EmptyLibraryCard(onChooseFolder) }
        }

        items(songs, key = { it.songId }) { song ->
            SongCard(
                song = song,
                isCurrent = song.songId == currentSongId,
                onPlay = { onPlaySong(song.songId) },
                onAddToPlaylist = { addToPlaylistSongId = song.songId }
            )
        }
    }

    val pendingSongId = addToPlaylistSongId
    if (pendingSongId != null) {
        ModalBottomSheet(
            onDismissRequest = { addToPlaylistSongId = null },
            sheetState = sheetState
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.library_add_to_playlist),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                playlists.forEach { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        supportingContent = {
                            Text(playlistSongCountLabel(playlist.availableSongIds.size))
                        },
                        modifier = Modifier.clickableRow {
                            onAddSongToPlaylist(playlist.id, pendingSongId)
                            addToPlaylistSongId = null
                        }
                    )
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.playlists_create)) },
                    leadingContent = {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = null)
                    },
                    modifier = Modifier.clickableRow {
                        onCreatePlaylistWithSong(pendingSongId)
                        addToPlaylistSongId = null
                    }
                )
            }
        }
    }
}

@Composable
private fun LibraryHeaderCard(
    rootSummary: String,
    songCount: Int,
    isBusy: Boolean,
    onChooseFolder: () -> Unit,
    onUseBundledOnly: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.library_folder_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = rootSummary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = songCountLabel(songCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isBusy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.library_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChooseFolder, enabled = !isBusy) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.library_change_folder))
                }
                OutlinedButton(onClick = onRefresh, enabled = !isBusy) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.library_refresh))
                }
            }
            TextButton(onClick = onUseBundledOnly, enabled = !isBusy) {
                Text(stringResource(R.string.library_use_bundled))
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: List<SongLibraryDiagnostic>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.library_diagnostics_heading),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            diagnostics.take(6).forEach { diagnostic ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = diagnostic.kind.headline(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = diagnostic.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = diagnostic.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(onChooseFolder: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.library_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.library_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onChooseFolder) {
                Text(stringResource(R.string.library_choose_folder))
            }
        }
    }
}

@Composable
private fun SongCard(
    song: SongManifest,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRow(onPlay),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongArtwork(songId = song.songId, modifier = Modifier.size(64.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist ?: stringResource(R.string.now_playing_unknown_artist),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                song.album?.let { album ->
                    Text(
                        text = album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    imageVector = Icons.Rounded.PlaylistAdd,
                    contentDescription = stringResource(R.string.library_add_to_playlist)
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.library_play_song)
                )
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
