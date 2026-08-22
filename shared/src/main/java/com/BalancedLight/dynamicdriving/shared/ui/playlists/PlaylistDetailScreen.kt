package com.BalancedLight.dynamicdriving.shared.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.BalancedLight.dynamicdriving.shared.catalog.SongManifest
import com.BalancedLight.dynamicdriving.shared.playlist.ResolvedPlaylist
import com.BalancedLight.dynamicdriving.shared.ui.components.ReorderHandle
import com.BalancedLight.dynamicdriving.shared.ui.components.ReorderableRowHeight
import com.BalancedLight.dynamicdriving.shared.ui.components.SongArtwork
import com.BalancedLight.dynamicdriving.shared.ui.components.rememberReorderState
import com.BalancedLight.dynamicdriving.shared.ui.missingSongCountLabel
import com.BalancedLight.dynamicdriving.shared.ui.playlistSongCountLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: ResolvedPlaylist,
    songsById: Map<String, SongManifest>,
    allSongs: List<SongManifest>,
    currentSongId: String?,
    onPlaySong: (String) -> Unit,
    onRemoveSong: (String) -> Unit,
    onMoveSong: (Int, Int) -> Unit,
    onAddSong: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddSheet by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState()
    val reorderState = rememberReorderState(onMove = onMoveSong)
    val visibleSongs = playlist.availableSongIds.mapNotNull(songsById::get)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = playlist.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = playlistSongCountLabel(playlist.availableSongIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (playlist.missingSongCount > 0) {
                        Text(
                            text = missingSongCountLabel(playlist.missingSongCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { showAddSheet = true }) {
                        Text(stringResource(R.string.playlists_add_songs))
                    }
                }
            }
        }

        if (visibleSongs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.playlists_empty_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        itemsIndexed(visibleSongs, key = { _, song -> song.songId }) { index, song ->
            PlaylistSongRow(
                song = song,
                index = index,
                itemCount = visibleSongs.size,
                isCurrent = song.songId == currentSongId,
                isDragging = reorderState.draggingIndex == index,
                reorderHandle = {
                    ReorderHandle(
                        state = reorderState,
                        index = index,
                        itemCount = visibleSongs.size,
                        contentDescription = stringResource(R.string.playlists_move_up)
                    )
                },
                onPlay = { onPlaySong(song.songId) },
                onRemove = { onRemoveSong(song.songId) },
                onMoveUp = { onMoveSong(index, index - 1) },
                onMoveDown = { onMoveSong(index, index + 1) }
            )
        }
    }

    if (showAddSheet) {
        val candidates = allSongs.filterNot { playlist.playlist.songIds.contains(it.songId) }
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = addSheetState
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.playlists_add_songs_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(candidates, key = { it.songId }) { song ->
                        ListItem(
                            headlineContent = { Text(song.displayName) },
                            supportingContent = song.artist?.let { artist ->
                                { Text(artist) }
                            },
                            leadingContent = {
                                SongArtwork(songId = song.songId, modifier = Modifier.size(40.dp))
                            },
                            modifier = Modifier.clickable { onAddSong(song.songId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSongRow(
    song: SongManifest,
    index: Int,
    itemCount: Int,
    isCurrent: Boolean,
    isDragging: Boolean,
    reorderHandle: @Composable () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(ReorderableRowHeight)
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDragging -> MaterialTheme.colorScheme.tertiaryContainer
                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            reorderHandle()
            Spacer(Modifier.size(12.dp))
            SongArtwork(songId = song.songId, modifier = Modifier.size(44.dp))
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.playlists_remove_song)
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.action_more)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlists_move_up)) },
                        enabled = index > 0,
                        leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onMoveUp()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlists_move_down)) },
                        enabled = index < itemCount - 1,
                        leadingIcon = { Icon(Icons.Rounded.ArrowDownward, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onMoveDown()
                        }
                    )
                }
            }
        }
    }
}
