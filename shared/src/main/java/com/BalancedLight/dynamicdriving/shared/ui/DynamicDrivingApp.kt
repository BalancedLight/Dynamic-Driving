package com.BalancedLight.dynamicdriving.shared.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection
import com.BalancedLight.dynamicdriving.shared.ui.diagnostics.DiagnosticsSheet
import com.BalancedLight.dynamicdriving.shared.ui.library.LibraryScreen
import com.BalancedLight.dynamicdriving.shared.ui.nowplaying.NowPlayingScreen
import com.BalancedLight.dynamicdriving.shared.ui.onboarding.OnboardingScreen
import com.BalancedLight.dynamicdriving.shared.ui.permissions.AppPermissionRequest
import com.BalancedLight.dynamicdriving.shared.ui.playlists.PlaylistDetailScreen
import com.BalancedLight.dynamicdriving.shared.ui.playlists.PlaylistsScreen
import com.BalancedLight.dynamicdriving.shared.ui.settings.SettingsScreen
import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection

/** Per-flavour differences between the phone app and the AAOS app. */
data class DynamicDrivingAppConfig(
    val versionName: String,
    val permissions: List<AppPermissionRequest> = emptyList(),
    val supportsFolderPicker: Boolean = true
)

private enum class Destination(
    val titleRes: Int,
    val icon: ImageVector
) {
    NOW_PLAYING(R.string.nav_now_playing, Icons.Rounded.PlayCircle),
    LIBRARY(R.string.nav_library, Icons.Rounded.LibraryMusic),
    PLAYLISTS(R.string.nav_playlists, Icons.Rounded.QueueMusic),
    SETTINGS(R.string.nav_settings, Icons.Rounded.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicDrivingApp(
    config: DynamicDrivingAppConfig,
    viewModel: DynamicDrivingViewModel = viewModel()
) {
    val uiModel by viewModel.uiModel.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var destination by rememberSaveable { mutableStateOf(Destination.NOW_PLAYING) }
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistDialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(viewModel::changeLibraryRoot)
    }

    // Live speed is only read while a screen that shows it is on top; the media foreground service
    // covers the playback case. That is what keeps background location out of the manifest.
    LaunchedEffect(Unit) {
        viewModel.setForegroundMonitoringActive(true)
    }

    val changedTemplate = stringResource(R.string.library_change_succeeded)
    val failedTemplate = stringResource(R.string.library_change_failed)
    LaunchedEffect(feedback) {
        val current = feedback ?: return@LaunchedEffect
        val message = when (current) {
            is LibraryFeedback.Changed -> changedTemplate.format(current.rootName)
            is LibraryFeedback.Failed -> failedTemplate.format(current.message)
        }
        snackbarHostState.showSnackbar(message)
        viewModel.consumeFeedback(current.id)
    }

    if (!uiModel.settings.onboardingCompleted) {
        OnboardingScreen(
            onChooseFolder = {
                if (config.supportsFolderPicker) {
                    folderPicker.launch(null)
                }
            },
            onFinish = { viewModel.setOnboardingCompleted(true) }
        )
        return
    }

    val activePlaylist = openPlaylistId?.let { id -> uiModel.playlists.firstOrNull { it.id == id } }
    BackHandler(enabled = openPlaylistId != null) { openPlaylistId = null }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(activePlaylist?.name ?: stringResource(destination.titleRes))
                },
                navigationIcon = {
                    if (activePlaylist != null) {
                        androidx.compose.material3.IconButton(onClick = { openPlaylistId = null }) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry && activePlaylist == null,
                        onClick = {
                            openPlaylistId = null
                            destination = entry
                        },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.titleRes)) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (destination == Destination.PLAYLISTS && activePlaylist == null) {
                FloatingActionButton(onClick = { playlistDialog = PlaylistDialog.Create() }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.playlists_create)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            when {
                activePlaylist != null -> PlaylistDetailScreen(
                    playlist = activePlaylist,
                    songsById = uiModel.library.songs.associateBy { it.songId },
                    allSongs = uiModel.library.songs,
                    currentSongId = uiModel.playback.currentSongId,
                    onPlaySong = { songId ->
                        viewModel.playSong(songId, ActiveCollection.Playlist(activePlaylist.id))
                    },
                    onRemoveSong = { songId ->
                        viewModel.removeSongFromPlaylist(activePlaylist.id, songId)
                    },
                    onMoveSong = { from, to ->
                        viewModel.moveSongInPlaylist(activePlaylist.id, from, to)
                    },
                    onAddSong = { songId -> viewModel.addSongToPlaylist(activePlaylist.id, songId) }
                )

                destination == Destination.NOW_PLAYING -> NowPlayingScreen(
                    state = uiModel.playback,
                    onTogglePlayback = viewModel::togglePlayback,
                    onSkipPrevious = viewModel::skipToPrevious,
                    onSkipNext = viewModel::skipToNext,
                    onOpenPlaylists = { destination = Destination.PLAYLISTS },
                    onOpenSettings = { destination = Destination.SETTINGS },
                    onUseAutomaticSpeedSource = {
                        viewModel.setSpeedSourceSelection(SpeedSourceSelection.AUTOMATIC)
                    }
                )

                destination == Destination.LIBRARY -> LibraryScreen(
                    songs = uiModel.library.songs,
                    rootSummary = uiModel.library.rootSummary,
                    isRefreshing = uiModel.library.isRefreshing,
                    isChangingLibrary = uiModel.isChangingLibrary,
                    diagnostics = uiModel.library.diagnostics,
                    playlists = uiModel.playlists,
                    currentSongId = uiModel.playback.currentSongId,
                    onChooseFolder = {
                        if (config.supportsFolderPicker) {
                            folderPicker.launch(null)
                        }
                    },
                    onUseBundledOnly = { viewModel.changeLibraryRoot(null) },
                    onRefresh = viewModel::refreshLibrary,
                    onPlaySong = { songId -> viewModel.playSong(songId) },
                    onAddSongToPlaylist = viewModel::addSongToPlaylist,
                    onCreatePlaylistWithSong = { songId ->
                        playlistDialog = PlaylistDialog.Create(seedSongId = songId)
                    }
                )

                destination == Destination.PLAYLISTS -> PlaylistsScreen(
                    playlists = uiModel.playlists,
                    activeCollection = uiModel.playback.activeCollection,
                    onOpenPlaylist = { openPlaylistId = it },
                    onPlayPlaylist = { playlistId ->
                        uiModel.playlists.firstOrNull { it.id == playlistId }
                            ?.availableSongIds
                            ?.firstOrNull()
                            ?.let { songId ->
                                viewModel.playSong(songId, ActiveCollection.Playlist(playlistId))
                            }
                    },
                    onSetActiveCollection = viewModel::setActiveCollection,
                    onRenamePlaylist = { playlistId ->
                        val playlist = uiModel.playlists.firstOrNull { it.id == playlistId }
                        playlistDialog = PlaylistDialog.Rename(
                            playlistId = playlistId,
                            currentName = playlist?.name.orEmpty()
                        )
                    },
                    onDeletePlaylist = { playlistId ->
                        val playlist = uiModel.playlists.firstOrNull { it.id == playlistId }
                        playlistDialog = PlaylistDialog.Delete(
                            playlistId = playlistId,
                            currentName = playlist?.name.orEmpty()
                        )
                    },
                    onMovePlaylist = viewModel::movePlaylist
                )

                destination == Destination.SETTINGS -> SettingsScreen(
                    settings = uiModel.settings,
                    permissions = config.permissions,
                    versionName = config.versionName,
                    onSpeedSourceSelected = viewModel::setSpeedSourceSelection,
                    onManualSpeedChanged = viewModel::setManualSpeedMph,
                    onPlaybackPolicySelected = viewModel::setPlaybackPolicy,
                    onLoopsBeforeAdvanceChanged = viewModel::setLoopsBeforeAdvance,
                    onBundledDemoEnabledChanged = viewModel::setBundledDemoEnabled,
                    onReplayOnboarding = { viewModel.setOnboardingCompleted(false) }
                )
            }

            // Present only in debug builds: the controller leaves `diagnostics` null in release,
            // so no stem control or performance counter can reach a shipped screen.
            uiModel.playback.diagnostics?.let { diagnostics ->
                DiagnosticsSheet(
                    diagnostics = diagnostics,
                    playback = uiModel.playback,
                    expanded = showDiagnostics,
                    onExpandedChange = { showDiagnostics = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    PlaylistDialogHost(
        dialog = playlistDialog,
        onDismiss = { playlistDialog = null },
        onCreate = { name, seedSongId ->
            val created = viewModel.createPlaylistReturning(name)
            seedSongId?.let { viewModel.addSongToPlaylist(created, it) }
            playlistDialog = null
        },
        onRename = { playlistId, name ->
            viewModel.renamePlaylist(playlistId, name)
            playlistDialog = null
        },
        onDelete = { playlistId ->
            viewModel.deletePlaylist(playlistId)
            if (openPlaylistId == playlistId) {
                openPlaylistId = null
            }
            playlistDialog = null
        }
    )
}
