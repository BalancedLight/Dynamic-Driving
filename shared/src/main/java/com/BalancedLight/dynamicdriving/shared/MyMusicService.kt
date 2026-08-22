package com.BalancedLight.dynamicdriving.shared

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.BalancedLight.dynamicdriving.shared.catalog.MediaBrowseIds
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The MediaLibraryService that owns Dynamic Driving's session.
 *
 * It publishes the browse tree (All Songs and Playlists), answers search, and serves real
 * title/artist/album/artwork so notifications, the lock screen, Android Auto, AAOS, and any external
 * scrobbler all see the same metadata the manifests declare.
 */
@OptIn(UnstableApi::class)
class MyMusicService : MediaLibraryService() {
    companion object {
        /**
         * Content-style hints understood by Android Auto and AAOS.
         *
         * @see <a href="https://developer.android.com/training/cars/media/create-media-browser/content-styles">Content styles</a>
         */
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST_ITEM = 1
        private const val CONTENT_STYLE_GRID_ITEM = 2

        @Volatile
        @Suppress("DEPRECATION")
        private var latestCompatToken: MediaSessionCompat.Token? = null

        @Suppress("DEPRECATION")
        fun currentCompatSessionToken(): MediaSessionCompat.Token? = latestCompatToken

        fun ensureStarted(context: Context) {
            val serviceIntent = Intent(context.applicationContext, MyMusicService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
        }
    }

    private lateinit var session: MediaLibrarySession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val runtime by lazy { DynamicDrivingRuntime.require() }
    private val sessionPlayer by lazy {
        DynamicDrivingSessionPlayer(runtime.playbackController.transportPlayer, runtime.playbackController)
    }

    override fun onCreate() {
        super.onCreate()
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        val notificationProvider = DefaultMediaNotificationProvider(this)
        notificationProvider.setSmallIcon(R.drawable.ic_notification_dynamic_driving)
        setMediaNotificationProvider(notificationProvider)
        val builder = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setId("DynamicDrivingMediaLibrary")
        createSessionActivity()?.let(builder::setSessionActivity)
        session = builder.build()
        if (!isSessionAdded(session)) {
            addSession(session)
        }
        @Suppress("DEPRECATION")
        run { latestCompatToken = MediaSessionCompat.Token.fromToken(session.platformToken) }
        serviceScope.launch {
            runtime.catalogRepository.libraryState.collectLatest { state ->
                if (::session.isInitialized) {
                    session.notifyChildrenChanged(MediaBrowseIds.ALL_SONGS, state.songs.size, null)
                    session.notifyChildrenChanged(MediaBrowseIds.ROOT, 2, null)
                }
            }
        }
        serviceScope.launch {
            runtime.playlistRepository.playlists.collectLatest { playlists ->
                if (::session.isInitialized) {
                    session.notifyChildrenChanged(MediaBrowseIds.PLAYLISTS, playlists.size, null)
                    playlists.forEach { playlist ->
                        session.notifyChildrenChanged(
                            MediaBrowseIds.playlistNode(playlist.id),
                            playlist.songIds.size,
                            null
                        )
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing()) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        latestCompatToken = null
        if (::session.isInitialized) {
            if (isSessionAdded(session)) {
                removeSession(session)
            }
            session.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createSessionActivity(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Grants the connecting browser read access to the artwork URIs it is about to be handed.
     *
     * The artwork provider is not exported, so this per-URI grant is the only way a car host can
     * load cover art -- and no app that never connects to the session can read anything.
     */
    private fun grantArtworkAccess(browserPackage: String, items: List<MediaItem>) {
        if (browserPackage.isBlank() || browserPackage == packageName) {
            return
        }
        items.forEach { item ->
            val artworkUri = item.mediaMetadata.artworkUri ?: return@forEach
            runCatching {
                applicationContext.grantUriPermission(
                    browserPackage,
                    artworkUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun contentStyleParams(params: LibraryParams?): LibraryParams {
        val extras = Bundle(params?.extras ?: Bundle.EMPTY).apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM)
        }
        return LibraryParams.Builder()
            .setExtras(extras)
            .setRecent(params?.isRecent == true)
            .setOffline(params?.isOffline == true)
            .setSuggested(params?.isSuggested == true)
            .build()
    }

    @OptIn(UnstableApi::class)
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(runtime.mediaBrowseTree.rootItem(), contentStyleParams(params))
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = runtime.mediaBrowseTree.children(parentId)
            grantArtworkAccess(browser.packageName, children)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), contentStyleParams(params))
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = runtime.mediaBrowseTree.item(mediaId)
            item?.let { grantArtworkAccess(browser.packageName, listOf(it)) }
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val results = runtime.mediaBrowseTree.search(query)
            session.notifySearchResultChanged(browser, query, results.size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val results = runtime.mediaBrowseTree.search(query)
            grantArtworkAccess(browser.packageName, results)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(results), contentStyleParams(params))
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requested = mediaItems.getOrNull(
                startIndex.takeIf { it != C.INDEX_UNSET && it in mediaItems.indices } ?: 0
            ) ?: mediaItems.firstOrNull()

            val searchQuery = requested?.requestMetadata?.searchQuery
            val requestedMediaId = requested?.mediaId?.takeIf { it.isNotBlank() }
            val playRequest = when {
                !searchQuery.isNullOrBlank() -> runtime.mediaBrowseTree.resolveSearchRequest(searchQuery)
                requestedMediaId != null -> runtime.mediaBrowseTree.resolvePlayRequest(requestedMediaId)
                else -> null
            } ?: fallbackPlayRequest()

            if (playRequest == null) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, startPositionMs)
                )
            }

            runtime.playbackController.setActiveCollection(playRequest.collection)
            runtime.playbackController.selectSong(playRequest.songId)
            val resolvedItem = runtime.catalogRepository.getSongMediaItem(playRequest.songId)
            grantArtworkAccess(controller.packageName, listOf(resolvedItem))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(listOf(resolvedItem), 0, 0L)
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val settingsState = runtime.settings.state.value
            val collection = settingsState.activeCollection
            val resumeSongId = settingsState.lastSongId
                ?.takeIf { songId -> runtime.catalogRepository.findSong(songId) != null }
                ?: runtime.mediaBrowseTree.resolvePlayRequest(
                    when (collection) {
                        ActiveCollection.AllSongs -> MediaBrowseIds.ALL_SONGS
                        is ActiveCollection.Playlist -> MediaBrowseIds.playlistNode(collection.playlistId)
                    }
                )?.songId

            if (resumeSongId == null) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                )
            }
            runtime.playbackController.setActiveCollection(collection)
            runtime.playbackController.selectSong(resumeSongId)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(runtime.catalogRepository.getSongMediaItem(resumeSongId)),
                    0,
                    0L
                )
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolvedItems = mediaItems.mapNotNull { mediaItem ->
                val mediaId = mediaItem.mediaId
                if (mediaId.isBlank()) {
                    null
                } else {
                    runtime.catalogRepository.findSongMediaItem(mediaId)
                }
            }.toMutableList()
            return Futures.immediateFuture(resolvedItems)
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = getMediaButtonKeyEvent(intent) ?: return false
            if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                return true
            }
            val playbackController = runtime.playbackController
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> playbackController.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP -> playbackController.pause()

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> playbackController.togglePlayback()

                KeyEvent.KEYCODE_MEDIA_NEXT -> playbackController.skipToNextSong()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playbackController.skipToPreviousSong()
                else -> return false
            }
            return true
        }

        private fun fallbackPlayRequest() =
            runtime.mediaBrowseTree.resolvePlayRequest(MediaBrowseIds.ALL_SONGS)

        private fun getMediaButtonKeyEvent(intent: Intent): KeyEvent? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
        }
    }
}
