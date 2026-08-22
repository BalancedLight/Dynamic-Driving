package com.BalancedLight.dynamicdriving.shared.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.BalancedLight.dynamicdriving.shared.playlist.ResolvedPlaylist
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection
import com.BalancedLight.dynamicdriving.shared.ui.components.ReorderHandle
import com.BalancedLight.dynamicdriving.shared.ui.components.ReorderableRowHeight
import com.BalancedLight.dynamicdriving.shared.ui.components.rememberReorderState
import com.BalancedLight.dynamicdriving.shared.ui.missingSongCountLabel
import com.BalancedLight.dynamicdriving.shared.ui.playlistSongCountLabel

@Composable
fun PlaylistsScreen(
    playlists: List<ResolvedPlaylist>,
    activeCollection: ActiveCollection,
    onOpenPlaylist: (String) -> Unit,
    onPlayPlaylist: (String) -> Unit,
    onSetActiveCollection: (ActiveCollection) -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onMovePlaylist: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val reorderState = rememberReorderState(onMove = onMovePlaylist)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            AllSongsCard(
                isActive = activeCollection == ActiveCollection.AllSongs,
                onSelect = { onSetActiveCollection(ActiveCollection.AllSongs) }
            )
        }

        if (playlists.isEmpty()) {
            item { EmptyPlaylistsCard() }
        }

        itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
            PlaylistRow(
                playlist = playlist,
                index = index,
                itemCount = playlists.size,
                isActive = (activeCollection as? ActiveCollection.Playlist)?.playlistId == playlist.id,
                isDragging = reorderState.draggingIndex == index,
                reorderHandle = {
                    ReorderHandle(
                        state = reorderState,
                        index = index,
                        itemCount = playlists.size,
                        contentDescription = stringResource(R.string.playlists_move_up)
                    )
                },
                onOpen = { onOpenPlaylist(playlist.id) },
                onPlay = { onPlayPlaylist(playlist.id) },
                onSetActive = { onSetActiveCollection(ActiveCollection.Playlist(playlist.id)) },
                onRename = { onRenamePlaylist(playlist.id) },
                onDelete = { onDeletePlaylist(playlist.id) },
                onMoveUp = { onMovePlaylist(index, index - 1) },
                onMoveDown = { onMovePlaylist(index, index + 1) }
            )
        }
    }
}

@Composable
private fun AllSongsCard(isActive: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.LibraryMusic, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.playlists_all_songs),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (isActive) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.playlists_active)
                )
            }
        }
    }
}

@Composable
private fun EmptyPlaylistsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.playlists_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.playlists_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: ResolvedPlaylist,
    index: Int,
    itemCount: Int,
    isActive: Boolean,
    isDragging: Boolean,
    reorderHandle: @Composable () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onSetActive: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(ReorderableRowHeight)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDragging -> MaterialTheme.colorScheme.tertiaryContainer
                isActive -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            reorderHandle()
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(playlistSongCountLabel(playlist.availableSongIds.size))
                        if (playlist.missingSongCount > 0) {
                            append(" · ")
                            append(missingSongCountLabel(playlist.missingSongCount))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPlay, enabled = !playlist.isEmpty) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.playlists_play)
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
                        text = { Text(stringResource(R.string.playlists_set_active)) },
                        onClick = {
                            menuExpanded = false
                            onSetActive()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
