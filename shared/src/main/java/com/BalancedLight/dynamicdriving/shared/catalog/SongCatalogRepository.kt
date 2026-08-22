package com.BalancedLight.dynamicdriving.shared.catalog

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import com.BalancedLight.dynamicdriving.shared.artwork.SongArtworkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Notified when a newly scanned library is about to become the active one.
 *
 * The playback controller implements this to cancel in-flight audio loads and settle on a song
 * before the repository lets go of the previous document-tree permission.
 */
interface LibraryTransitionHandler {
    suspend fun onLibraryAdopted(newState: SongLibraryState)
}

/**
 * Owns the song library and the persisted document-tree selection.
 *
 * Changing the library root is transactional: the candidate folder is permission-checked and fully
 * scanned before anything persisted changes, and the previous root's URI permission is only released
 * after playback has acknowledged the new library. A failed switch leaves the previous library
 * exactly as it was.
 */
class SongCatalogRepository(
    context: Context,
    bundledDemoEnabled: Boolean = true
) {
    companion object {
        private const val PREFERENCES_NAME = "dynamic_driving_song_library"
        private const val PREF_EXTERNAL_TREE_URI = "external_tree_uri"
        private const val TREE_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private val appContext = context.applicationContext
    private val scanner = SongLibraryScanner(appContext)
    private val artworkStore = SongArtworkStore.get(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val libraryMutex = Mutex()
    private val generationCounter = AtomicLong(0L)

    @Volatile
    private var bundledDemoEnabled = bundledDemoEnabled

    private val _libraryState = MutableStateFlow(
        SongLibraryState(
            isRefreshing = true,
            selectedExternalTreeUri = getPersistedExternalTreeUri()
        )
    )

    val libraryState: StateFlow<SongLibraryState> = _libraryState.asStateFlow()

    @Volatile
    private var transitionHandler: LibraryTransitionHandler? = null

    init {
        refreshLibrary()
    }

    fun setTransitionHandler(handler: LibraryTransitionHandler?) {
        transitionHandler = handler
    }

    /** Includes or excludes bundled songs from every public catalog and playback surface. */
    fun setBundledDemoEnabled(enabled: Boolean) {
        if (bundledDemoEnabled == enabled) return
        bundledDemoEnabled = enabled
        refreshLibrary()
    }

    /** Rescans the currently selected root. Safe to call repeatedly; stale scans are discarded. */
    fun refreshLibrary() {
        val generation = generationCounter.incrementAndGet()
        _libraryState.value = _libraryState.value.copy(isRefreshing = true)
        scope.launch {
            libraryMutex.withLock {
                if (generation != generationCounter.get()) {
                    return@withLock
                }
                val selectedTreeUri = getPersistedExternalTreeUri()
                val scanned = runCatching { scanner.scan(selectedTreeUri) }
                    .recoverCatching { error ->
                        // A previously good root went away. Fall back to the bundled demo and say so.
                        val bundled = scanner.scanBundledAssets()
                        SongLibraryState(
                            songs = bundled.songs,
                            diagnostics = bundled.diagnostics + SongLibraryDiagnostic(
                                source = selectedTreeUri?.toString().orEmpty(),
                                message = error.message ?: "The selected music folder is unavailable.",
                                kind = SongLibraryDiagnosticKind.UNAVAILABLE_ROOT
                            ),
                            roots = listOfNotNull(bundled.root),
                            selectedExternalTreeUri = selectedTreeUri,
                            lastRefreshTimestampMs = System.currentTimeMillis()
                        )
                    }
                    .getOrElse { return@withLock }
                if (generation != generationCounter.get()) {
                    return@withLock
                }
                publish(scanned.copy(generation = generation))
                transitionHandler?.onLibraryAdopted(_libraryState.value)
            }
        }
    }

    /**
     * Adopts [treeUri] as the external library root, or falls back to the bundled demo when null.
     *
     * Nothing persisted changes unless the candidate root is readable and scannable.
     */
    suspend fun changeLibraryRoot(treeUri: Uri?): LibraryChangeResult {
        val generation = generationCounter.incrementAndGet()
        return libraryMutex.withLock {
            if (generation != generationCounter.get()) {
                return@withLock LibraryChangeResult.Superseded
            }
            val previousUri = getPersistedExternalTreeUri()
            _libraryState.value = _libraryState.value.copy(isRefreshing = true)

            var tookNewPermission = false
            try {
                if (treeUri != null && treeUri != previousUri) {
                    if (!takePersistableTreePermission(treeUri)) {
                        publish(_libraryState.value.copy(isRefreshing = false))
                        return@withLock LibraryChangeResult.Failed(
                            reason = LibraryChangeFailure.PERMISSION_DENIED,
                            message = "Dynamic Driving was not granted lasting access to that folder. Pick it again and allow access."
                        )
                    }
                    tookNewPermission = true
                }

                val scanned = withContext(Dispatchers.IO) {
                    runCatching { scanner.scan(treeUri) }
                }
                val scannedState = scanned.getOrElse { error ->
                    if (tookNewPermission && treeUri != null) {
                        releasePersistableTreePermission(treeUri)
                    }
                    publish(_libraryState.value.copy(isRefreshing = false))
                    return@withLock LibraryChangeResult.Failed(
                        reason = when (error) {
                            is LibraryRootUnavailableException -> LibraryChangeFailure.UNAVAILABLE
                            is SecurityException -> LibraryChangeFailure.PERMISSION_DENIED
                            else -> LibraryChangeFailure.SCAN_FAILED
                        },
                        message = error.message ?: "That folder could not be read."
                    )
                }

                if (generation != generationCounter.get()) {
                    if (tookNewPermission && treeUri != null) {
                        releasePersistableTreePermission(treeUri)
                    }
                    return@withLock LibraryChangeResult.Superseded
                }

                // The candidate is good. Publish it, let playback settle, and only then let go of
                // the old permission so in-flight reads never lose their grant mid-load.
                publish(scannedState.copy(generation = generation))
                transitionHandler?.onLibraryAdopted(_libraryState.value)

                persistExternalTreeUri(treeUri)
                if (previousUri != null && previousUri != treeUri) {
                    releasePersistableTreePermission(previousUri)
                }

                LibraryChangeResult.Success(
                    root = _libraryState.value.externalRoot ?: _libraryState.value.roots.firstOrNull(),
                    songCount = _libraryState.value.songs.size,
                    diagnostics = _libraryState.value.diagnostics
                )
            } catch (error: Throwable) {
                if (tookNewPermission && treeUri != null) {
                    releasePersistableTreePermission(treeUri)
                }
                publish(_libraryState.value.copy(isRefreshing = false))
                LibraryChangeResult.Failed(
                    reason = LibraryChangeFailure.SCAN_FAILED,
                    message = error.message ?: "That folder could not be read."
                )
            }
        }
    }

    fun getSongs(): List<SongManifest> = libraryState.value.songs

    fun getSongOptions(): List<SongOption> = libraryState.value.songOptions

    fun findSong(songId: String): SongManifest? =
        libraryState.value.songs.firstOrNull { it.songId == songId }

    fun requireSong(songId: String): SongManifest =
        findSong(songId) ?: error("Song '$songId' was not found in the current library.")

    fun getSongArtworkBitmap(songId: String?): Bitmap? {
        val resolvedSongId = songId ?: return null
        return artworkStore.loadBitmap(resolvedSongId)
    }

    fun getSongMediaItems(): List<MediaItem> =
        libraryState.value.songs.map(MediaItemFactory::loopingMediaItem)

    fun findSongMediaItem(songId: String): MediaItem? =
        findSong(songId)?.let(MediaItemFactory::loopingMediaItem)

    fun getSongMediaItem(songId: String): MediaItem =
        MediaItemFactory.loopingMediaItem(requireSong(songId))

    fun getSongTailPlaybackMediaItem(songId: String): MediaItem =
        MediaItemFactory.tailPlaybackMediaItem(requireSong(songId))

    fun getSongPlayOutMediaItem(songId: String): MediaItem =
        MediaItemFactory.playOutMediaItem(requireSong(songId))

    fun getSongFirstPassMediaItem(songId: String): MediaItem =
        MediaItemFactory.firstPassMediaItem(requireSong(songId))

    private fun publish(state: SongLibraryState) {
        val visibleState = if (bundledDemoEnabled) {
            state
        } else {
            state.copy(
                songs = state.songs.filterNot { it.libraryRoot is SongLibraryRoot.BundledAssets },
                roots = state.roots.filterNot { it is SongLibraryRoot.BundledAssets }
            )
        }
        artworkStore.setSources(visibleState.songs.associate { it.songId to it.artworkFile })
        MediaItemFactory.attachArtworkStore(artworkStore)
        _libraryState.value = visibleState.copy(isRefreshing = false)
    }

    private fun getPersistedExternalTreeUri(): Uri? =
        preferences.getString(PREF_EXTERNAL_TREE_URI, null)?.let(Uri::parse)

    private fun persistExternalTreeUri(treeUri: Uri?) {
        preferences.edit {
            if (treeUri == null) {
                remove(PREF_EXTERNAL_TREE_URI)
            } else {
                putString(PREF_EXTERNAL_TREE_URI, treeUri.toString())
            }
        }
    }

    private fun takePersistableTreePermission(treeUri: Uri): Boolean {
        val alreadyHeld = appContext.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
        if (alreadyHeld) {
            return true
        }
        return runCatching {
            appContext.contentResolver.takePersistableUriPermission(treeUri, TREE_PERMISSION_FLAGS)
        }.recoverCatching {
            // Some providers only offer read access; retry without the write flag before giving up.
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
    }

    private fun releasePersistableTreePermission(treeUri: Uri) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(treeUri, TREE_PERMISSION_FLAGS)
        }.recoverCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
