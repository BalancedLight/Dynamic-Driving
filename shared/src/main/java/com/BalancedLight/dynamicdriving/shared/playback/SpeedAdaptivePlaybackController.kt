package com.BalancedLight.dynamicdriving.shared.playback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes as PlatformAudioAttributes
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.BalancedLight.dynamicdriving.shared.BuildConfig
import com.BalancedLight.dynamicdriving.shared.MyMusicService
import com.BalancedLight.dynamicdriving.shared.catalog.BaseStemRule
import com.BalancedLight.dynamicdriving.shared.catalog.LibraryTransitionHandler
import com.BalancedLight.dynamicdriving.shared.catalog.OverlayStemRule
import com.BalancedLight.dynamicdriving.shared.catalog.SongCatalogRepository
import com.BalancedLight.dynamicdriving.shared.catalog.SongLibraryState
import com.BalancedLight.dynamicdriving.shared.catalog.SongMuffleManifest
import com.BalancedLight.dynamicdriving.shared.catalog.SongManifest
import com.BalancedLight.dynamicdriving.shared.catalog.StemEventManifest
import com.BalancedLight.dynamicdriving.shared.catalog.StemManifest
import com.BalancedLight.dynamicdriving.shared.catalog.StemModifierManifest
import com.BalancedLight.dynamicdriving.shared.playlist.PlaylistRepository
import com.BalancedLight.dynamicdriving.shared.settings.ActiveCollection
import com.BalancedLight.dynamicdriving.shared.settings.DynamicDrivingSettings
import com.BalancedLight.dynamicdriving.shared.settings.DynamicDrivingSettingsState
import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import com.BalancedLight.dynamicdriving.shared.speed.EffectiveSpeedState
import com.BalancedLight.dynamicdriving.shared.speed.ManualSpeedSource
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSourceRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.random.Random

/**
 * Decides whether playback should resume after an interruption that momentarily pauses the
 * transport (a song switch, a library swap, a transient focus loss).
 *
 * Reading only `playWhenReady`/`isPlaying` loses the user's intent because those are already false
 * by the time the reload runs; keeping the pending request in the equation preserves it.
 */
internal object PlaybackResumeIntent {
    fun resolve(
        pendingResumeRequest: Boolean,
        playWhenReady: Boolean,
        isPlaying: Boolean
    ): Boolean {
        return pendingResumeRequest || playWhenReady || isPlaying
    }
}

class SpeedAdaptivePlaybackController(
    context: Context,
    private val catalogRepository: SongCatalogRepository,
    private val playlistRepository: PlaylistRepository,
    private val settings: DynamicDrivingSettings,
    private val manualSpeedSource: ManualSpeedSource,
    private val speedRouter: SpeedSourceRouter
) : LibraryTransitionHandler {
    companion object {
        private const val SPEED_SMOOTHING_WINDOW_MS = 1_000.0
        private const val HYSTERESIS_MPH = 2.0
        private const val TICK_INTERVAL_MS = 100L
        private const val AUDIO_DRIFT_TOLERANCE_MS = 1_000L

        /**
         * Whether the transport ExoPlayer requests audio focus for itself. It must not.
         *
         * This controller already owns a focus request on behalf of the stem engine, which is what
         * actually makes sound. Letting the transport request focus as well puts two owners in one
         * process: each request revokes the other's, every revocation arrives as `AUDIOFOCUS_LOSS`,
         * and playback starts and stops again immediately in a tight loop.
         */
        private const val TRANSPORT_MANAGES_AUDIO_FOCUS = false
        private const val DEFAULT_REVERB_DELAY_MS = 140f
        private const val MAX_SAFE_REVERB_WET_MIX = 0.65f
        private const val MAX_SAFE_REVERB_FEEDBACK = 0.72f
        private const val MAX_SAFE_REVERB_DAMPING = 0.92f
        internal const val CLEAR_MUFFLE_CUTOFF_HZ = 18_000f
        internal const val IDLE_BASE_MUFFLED_CUTOFF_HZ = 300f
        const val IDLE_BASE_EFFECT_RELEASE_MPH = 6.0
        const val IDLE_BASE_MIN_GAIN_MULTIPLIER = 0.5f
        const val IDLE_BASE_MAX_MUFFLE_AMOUNT = 0.6f
    }

    private val appContext = context.applicationContext
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val random = Random(System.currentTimeMillis())
    private val audioLoader = SongAudioLoader(appContext)
    private val audioEngine = StemMixingAudioEngine()
    private val playbackWakeLock by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DynamicDriving:StemPlayback")
            .apply { setReferenceCounted(false) }
    }
    private val audioManager by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSystemService(AudioManager::class.java)
    }
    private val audioFocusRequest by lazy(LazyThreadSafetyMode.NONE) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(::handleAudioFocusChange)
            .build()
    }

    private val _uiState = MutableStateFlow(PlaybackUiState())

    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var currentSong: SongManifest? = null
    private var currentLibraryState: SongLibraryState = catalogRepository.libraryState.value
    private var lastAppliedLibraryState: SongLibraryState? = null
    private var settingsState: DynamicDrivingSettingsState = settings.state.value
    private val stemRuntimes = linkedMapOf<String, StemRuntime>()
    private val overlayGroupStates = linkedMapOf<String, OverlayGroupState>()
    private var latestSpeedState: EffectiveSpeedState = speedRouter.effectiveState.value
    private var smoothedSpeedMph: Double = latestSpeedState.mph
    private var lastSmoothingElapsedMs: Long = SystemClock.elapsedRealtime()
    private var songLoadJob: Job? = null
    private var currentSongTotalDurationMs: Long = 0L
    private var lastAudioLoadDiagnostics = SongAudioLoadDiagnostics()
    private var audioLoadInProgress = false
    private var audioReadySongId: String? = null
    private var audioLoadError: String? = null
    private var playAfterAudioLoad = false
    private var completedLoopCount = 0
    private var queuedNextSongId: String? = null
    private var preloadedSongId: String? = null
    private var preloadedSongAudio: LoadedSongAudio? = null
    private var preloadSongJob: Job? = null
    private var playingOutCurrentSong = false
    private var transportUsesLoopClip = true
    private var inFirstPassIntro = false
    private var suppressNextPlayOutSeek = false
    private var hasAudioFocus = false

    private val transportListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newSongId = mediaItem?.mediaId ?: return
            val song = catalogRepository.findSong(newSongId) ?: return
            if (currentSong?.songId == newSongId && currentSong === song) {
                return
            }
            currentSong = song
            audioReadySongId = null
            audioLoadError = null
            playAfterAudioLoad = PlaybackResumeIntent.resolve(
                pendingResumeRequest = playAfterAudioLoad,
                playWhenReady = transportPlayer.playWhenReady,
                isPlaying = transportPlayer.isPlaying
            )
            transportPlayer.pause()
            audioEngine.pause()
            lastAudioLoadDiagnostics = SongAudioLoadDiagnostics()
            completedLoopCount = 0
            playingOutCurrentSong = false
            transportUsesLoopClip = true
            inFirstPassIntro = false
            refreshQueuedNextSong()
            rebuildOverlayGroups(song)
            initializeStemRuntimes(song)
            loadSongAudio(song)
            settings.setLastSongId(song.songId)
            if (playAfterAudioLoad) {
                acquirePlaybackWakeLock()
                speedRouter.setPlaybackDemandActive(true)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncAudioEnginePlayback()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (inFirstPassIntro) {
                    handleFirstPassIntroComplete()
                } else {
                    handleSongFinishedPlayingOut()
                }
            }
            syncAudioEnginePlayback()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (
                reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                if (suppressNextPlayOutSeek && playingOutCurrentSong) {
                    suppressNextPlayOutSeek = false
                    return
                }
                if (transportUsesLoopClip) {
                    audioEngine.seekTo(newPosition.positionMs)
                } else if (inFirstPassIntro) {
                    audioEngine.seekFirstPass(newPosition.positionMs)
                } else if (playingOutCurrentSong) {
                    audioEngine.seekPlayOut(newPosition.positionMs)
                }
                return
            }
            if (
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                transportUsesLoopClip &&
                newPosition.positionMs < oldPosition.positionMs
            ) {
                handleLoopBoundaryReached()
            }
        }
    }

    /**
     * The silent clock the stem engine follows.
     *
     * It is muted on purpose: every audible sample comes from [StemMixingAudioEngine]'s AudioTrack,
     * and this player exists only to give that engine a timeline, a loop boundary, and a position to
     * stay in sync with.
     *
     * Because it makes no sound, it must not manage audio focus — see
     * [TRANSPORT_MANAGES_AUDIO_FOCUS].
     */
    val transportPlayer: ExoPlayer by lazy(LazyThreadSafetyMode.NONE) {
        ExoPlayer.Builder(appContext)
            .build()
            .apply {
                setAudioAttributes(audioAttributes, TRANSPORT_MANAGES_AUDIO_FOCUS)
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                addListener(transportListener)
            }
    }

    init {
        manualSpeedSource.setMph(settingsState.manualSpeedMph)
        speedRouter.setSelection(settingsState.speedSourceSelection)
        catalogRepository.setTransitionHandler(this)
        scope.launch {
            catalogRepository.libraryState.collectLatest { libraryState ->
                currentLibraryState = libraryState
                applyLibraryState(libraryState)
            }
        }
        scope.launch {
            settings.state.collectLatest(::applySettings)
        }
        scope.launch {
            playlistRepository.playlists.collectLatest {
                refreshQueuedNextSong()
                publishState(SystemClock.elapsedRealtime())
            }
        }
        scope.launch {
            speedRouter.effectiveState.collectLatest { state ->
                latestSpeedState = state
            }
        }
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    // ---------------------------------------------------------------- transport

    fun togglePlayback() {
        if (transportPlayer.isPlaying || transportPlayer.playWhenReady || playAfterAudioLoad) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        ensurePlaybackServiceStarted()
        ensureSongSelected()
        if (currentSong == null) {
            publishState(SystemClock.elapsedRealtime())
            return
        }
        playAfterAudioLoad = true
        if (isCurrentAudioReady()) {
            startTransportAndAudioEngine()
        } else {
            transportPlayer.playWhenReady = false
            transportPlayer.pause()
            audioEngine.pause()
            acquirePlaybackWakeLock()
            speedRouter.setPlaybackDemandActive(true)
        }
        publishState(SystemClock.elapsedRealtime())
    }

    fun pause() {
        playAfterAudioLoad = false
        transportPlayer.pause()
        syncAudioEnginePlayback()
        abandonAudioFocus()
        publishState(SystemClock.elapsedRealtime())
    }

    fun selectSong(songId: String) {
        val song = catalogRepository.findSong(songId) ?: return
        if (
            currentSong === song &&
            transportPlayer.currentMediaItem?.mediaId == song.songId &&
            (transportUsesLoopClip || inFirstPassIntro) &&
            stemRuntimes.isNotEmpty()
        ) {
            return
        }
        val shouldContinuePlaying = PlaybackResumeIntent.resolve(
            pendingResumeRequest = playAfterAudioLoad,
            playWhenReady = transportPlayer.playWhenReady,
            isPlaying = transportPlayer.isPlaying
        )
        transportPlayer.pause()
        audioEngine.pause()
        speedRouter.setPlaybackDemandActive(false)
        playingOutCurrentSong = false
        suppressNextPlayOutSeek = false
        completedLoopCount = 0
        currentSong = song
        lastAudioLoadDiagnostics = SongAudioLoadDiagnostics()
        audioReadySongId = null
        audioLoadError = null
        playAfterAudioLoad = shouldContinuePlaying
        refreshQueuedNextSong()
        if (song.loopRegion.loopStartsSong) {
            transportUsesLoopClip = true
            inFirstPassIntro = false
            transportPlayer.repeatMode = Player.REPEAT_MODE_ONE
            transportPlayer.setMediaItems(buildLoopingMediaQueue(song.songId), 0, 0L)
        } else {
            transportUsesLoopClip = false
            inFirstPassIntro = true
            transportPlayer.repeatMode = Player.REPEAT_MODE_OFF
            transportPlayer.setMediaItems(
                listOf(catalogRepository.getSongFirstPassMediaItem(song.songId)),
                0,
                0L
            )
        }
        transportPlayer.prepare()
        transportPlayer.playWhenReady = false
        rebuildOverlayGroups(song)
        initializeStemRuntimes(song)
        loadSongAudio(song)
        settings.setLastSongId(song.songId)
        if (playAfterAudioLoad) {
            acquirePlaybackWakeLock()
            speedRouter.setPlaybackDemandActive(true)
        }
        publishState(SystemClock.elapsedRealtime())
    }

    fun warmUpPlaybackStack() {
        ensurePlaybackServiceStarted()
        ensureSongSelected()
    }

    fun skipToNextSong() {
        skipToAdjacentSong(offset = 1)
    }

    fun skipToPreviousSong() {
        skipToAdjacentSong(offset = -1)
    }

    // ---------------------------------------------------------------- collections

    /** Switches the collection playback draws from, keeping the current song when it belongs. */
    fun setActiveCollection(collection: ActiveCollection) {
        settings.setActiveCollection(collection)
        val songIds = collectionSongIds(collection)
        val currentSongId = currentSong?.songId
        if (currentSongId != null && songIds.contains(currentSongId)) {
            refreshQueuedNextSong()
            publishState(SystemClock.elapsedRealtime())
            return
        }
        val firstSongId = songIds.firstOrNull()
        if (firstSongId == null) {
            publishState(SystemClock.elapsedRealtime())
            return
        }
        selectWithoutAutoplay(firstSongId)
    }

    /** Switches collection and starts the requested song. Used by the app UI and car hosts. */
    fun playFromCollection(collection: ActiveCollection, songId: String) {
        settings.setActiveCollection(collection)
        selectSong(songId)
        play()
    }

    // ---------------------------------------------------------------- settings

    fun setSpeedSourceSelection(selection: SpeedSourceSelection) {
        settings.setSpeedSourceSelection(selection)
    }

    fun setManualSpeedMph(mph: Double) {
        settings.setManualSpeedMph(mph)
    }

    fun setPlaybackPolicy(policy: PlaylistPlaybackPolicy) {
        settings.setPlaybackPolicy(policy)
    }

    fun setLoopsBeforeAdvance(loops: Int) {
        settings.setLoopsBeforeAdvance(loops)
    }

    private fun applySettings(state: DynamicDrivingSettingsState) {
        val previous = settingsState
        settingsState = state
        if (previous.speedSourceSelection != state.speedSourceSelection) {
            speedRouter.setSelection(state.speedSourceSelection)
        }
        if (previous.manualSpeedMph != state.manualSpeedMph) {
            manualSpeedSource.setMph(state.manualSpeedMph)
        }
        if (
            previous.playbackPolicy != state.playbackPolicy ||
            previous.activeCollectionId != state.activeCollectionId
        ) {
            completedLoopCount = 0
            refreshQueuedNextSong()
        }
        publishState(SystemClock.elapsedRealtime())
    }

    // ---------------------------------------------------------------- library transitions

    override suspend fun onLibraryAdopted(newState: SongLibraryState) {
        withContext(Dispatchers.Main.immediate) {
            currentLibraryState = newState
            applyLibraryState(newState)
        }
    }

    /**
     * Settles playback onto a freshly scanned library.
     *
     * Cancels every in-flight audio load first so no load can finish against the old root, then
     * either keeps the current song (preserving the play/pause intent) or pauses cleanly and parks
     * on the first available song without starting playback.
     */
    private fun applyLibraryState(libraryState: SongLibraryState) {
        if (lastAppliedLibraryState === libraryState) {
            return
        }
        lastAppliedLibraryState = libraryState
        val now = SystemClock.elapsedRealtime()
        val activeSongId = currentSong?.songId
        val matchingSong = activeSongId?.let { songId ->
            libraryState.songs.firstOrNull { it.songId == songId }
        }

        when {
            libraryState.songs.isEmpty() -> {
                cancelInFlightAudioWork()
                clearCurrentSong()
            }

            activeSongId == null -> {
                cancelInFlightAudioWork()
                selectWithoutAutoplay(firstSongIdForActiveCollection(libraryState))
            }

            matchingSong == null -> {
                // The song that was playing no longer exists in the new library.
                cancelInFlightAudioWork()
                selectWithoutAutoplay(firstSongIdForActiveCollection(libraryState))
            }

            currentSong !== matchingSong -> {
                // Same song ID, new manifest instance (different root or rescanned file).
                cancelInFlightAudioWork()
                currentSong = null
                selectSong(matchingSong.songId)
            }

            else -> {
                refreshQueuedNextSong()
                publishState(now)
            }
        }
    }

    private fun firstSongIdForActiveCollection(libraryState: SongLibraryState): String? {
        val collectionIds = collectionSongIds(settingsState.activeCollection, libraryState)
        return collectionIds.firstOrNull() ?: libraryState.songs.firstOrNull()?.songId
    }

    private fun selectWithoutAutoplay(songId: String?) {
        playAfterAudioLoad = false
        transportPlayer.playWhenReady = false
        transportPlayer.pause()
        audioEngine.pause()
        abandonAudioFocus()
        if (songId == null) {
            clearCurrentSong()
            return
        }
        currentSong = null
        selectSong(songId)
    }

    private fun cancelInFlightAudioWork() {
        songLoadJob?.cancel()
        songLoadJob = null
        audioLoadInProgress = false
        audioReadySongId = null
        clearQueuedSongPreload()
    }

    // ---------------------------------------------------------------- tick

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        updateSmoothedSpeed(now)
        val song = currentSong
        if (song != null) {
            synchronizeBaseStems(song, now)
            synchronizeOverlayStems(song, now)
            updateStemMixing(now)
            correctAudioEngineDriftIfNeeded(song)
        }
        publishState(now)
    }

    private fun correctAudioEngineDriftIfNeeded(song: SongManifest) {
        if (!isCurrentAudioReady()) {
            return
        }
        if (!transportPlayer.playWhenReady && !transportPlayer.isPlaying) {
            return
        }
        val audioDiagnostics = audioEngine.diagnostics()
        if (audioDiagnostics.currentSongId != song.songId) {
            return
        }
        val transportPositionMs = transportPlayer.currentPosition.coerceAtLeast(0L)
        val shouldCorrect = if (transportUsesLoopClip) {
            PlaybackRules.shouldCorrectLoopDrift(
                referencePositionMs = transportPositionMs,
                stemPositionMs = audioDiagnostics.currentPositionMs,
                loopDurationMs = song.loopRegion.durationMs,
                toleranceMs = AUDIO_DRIFT_TOLERANCE_MS
            )
        } else {
            PlaybackRules.shouldCorrectDrift(
                referencePositionMs = transportPositionMs,
                stemPositionMs = audioDiagnostics.currentPositionMs,
                toleranceMs = AUDIO_DRIFT_TOLERANCE_MS
            )
        }
        if (!shouldCorrect) {
            return
        }
        when {
            transportUsesLoopClip -> audioEngine.seekTo(transportPositionMs)
            inFirstPassIntro -> audioEngine.seekFirstPass(transportPositionMs)
            playingOutCurrentSong -> audioEngine.seekPlayOut(transportPositionMs)
        }
    }

    private fun clearCurrentSong() {
        currentSong = null
        currentSongTotalDurationMs = 0L
        queuedNextSongId = null
        clearQueuedSongPreload()
        completedLoopCount = 0
        playingOutCurrentSong = false
        suppressNextPlayOutSeek = false
        stemRuntimes.clear()
        overlayGroupStates.clear()
        songLoadJob?.cancel()
        transportUsesLoopClip = true
        inFirstPassIntro = false
        transportPlayer.pause()
        transportPlayer.clearMediaItems()
        audioEngine.pause()
        releasePlaybackWakeLock()
        abandonAudioFocus()
        audioEngine.setStemMixStates(emptyArray<StemAudioMixState>())
        publishState(SystemClock.elapsedRealtime())
    }

    private fun updateSmoothedSpeed(now: Long) {
        val dt = max(0L, now - lastSmoothingElapsedMs).toDouble()
        lastSmoothingElapsedMs = now
        val targetMph = latestSpeedState.mph
        if (dt <= 0.0) {
            smoothedSpeedMph = targetMph
            return
        }
        val alpha = (dt / SPEED_SMOOTHING_WINDOW_MS).coerceIn(0.0, 1.0)
        smoothedSpeedMph += (targetMph - smoothedSpeedMph) * alpha
    }

    private fun synchronizeBaseStems(song: SongManifest, now: Long) {
        song.stems.forEach { stem ->
            val baseRule = stem.rule as? BaseStemRule ?: return@forEach
            val currentlyActive = isStemActive(stem.stemId)
            val shouldBeActive = PlaybackRules.shouldActivateBaseStem(
                rule = baseRule,
                mph = smoothedSpeedMph,
                currentlyActive = currentlyActive,
                hysteresisMph = HYSTERESIS_MPH
            )
            if (shouldBeActive) {
                activateStem(stem, now, overlayExpiresAtMs = null)
            } else {
                fadeOutStem(stem.stemId, now)
            }
        }
    }

    private fun synchronizeOverlayStems(song: SongManifest, now: Long) {
        val overlayStems = song.stems.filter { it.rule is OverlayStemRule }
        val groupedOverlays = overlayStems.groupBy { (it.rule as OverlayStemRule).overlayGroup.groupId }
        groupedOverlays.forEach { (groupId, stems) ->
            val currentState = overlayGroupStates[groupId] ?: OverlayGroupState()
            val activeStem = stems.firstOrNull { it.stemId == currentState.activeStemId }
            val eligibleStems = stems.filter { stem ->
                PlaybackRules.isOverlayEligible(
                    rule = stem.rule as OverlayStemRule,
                    mph = smoothedSpeedMph,
                    currentlyActive = stem.stemId == currentState.activeStemId,
                    hysteresisMph = HYSTERESIS_MPH
                )
            }
            val forceStopActive = activeStem != null && eligibleStems.none { it.stemId == activeStem.stemId }
            val decision = OverlayGroupScheduler.tick(
                currentState = currentState,
                nowMs = now,
                eligibleCandidates = eligibleStems,
                activeStem = activeStem,
                forceStopActive = forceStopActive,
                random = random
            )
            overlayGroupStates[groupId] = decision.state
            when (val action = decision.action) {
                is OverlayGroupAction.Start -> activateStem(action.stem, now, action.activeUntilMs)
                is OverlayGroupAction.Stop -> fadeOutStem(action.stemId, now)
                OverlayGroupAction.None -> {
                    activeStem?.let { stem ->
                        stemRuntimes[stem.stemId]?.overlayExpiresAtMs = currentState.activeUntilMs
                    }
                }
            }
        }
    }

    private fun activateStem(stem: StemManifest, now: Long, overlayExpiresAtMs: Long?) {
        val runtime = stemRuntimes[stem.stemId] ?: return
        runtime.overlayExpiresAtMs = overlayExpiresAtMs
        val isAlreadyFadingInToTarget = runtime.lifecycleState == StemLifecycleState.FADING_IN &&
            runtime.fadeToGain == stem.gain
        val isAlreadyActiveAtTarget = runtime.lifecycleState == StemLifecycleState.ACTIVE &&
            runtime.fadeToGain == stem.gain
        if (isAlreadyFadingInToTarget || isAlreadyActiveAtTarget) {
            return
        }
        beginGainFade(
            runtime = runtime,
            now = now,
            targetGain = stem.gain,
            fadeDurationMs = stem.fadeInMs,
            targetState = StemLifecycleState.ACTIVE
        )
    }

    private fun fadeOutStem(stemId: String, now: Long) {
        val runtime = stemRuntimes[stemId] ?: return
        if (runtime.lifecycleState == StemLifecycleState.INACTIVE && runtime.activationGain == 0f) {
            runtime.overlayExpiresAtMs = null
            return
        }
        beginGainFade(
            runtime = runtime,
            now = now,
            targetGain = 0f,
            fadeDurationMs = runtime.manifest.fadeOutMs,
            targetState = StemLifecycleState.FADING_OUT
        )
        runtime.overlayExpiresAtMs = null
    }

    private fun beginGainFade(
        runtime: StemRuntime,
        now: Long,
        targetGain: Float,
        fadeDurationMs: Long,
        targetState: StemLifecycleState
    ) {
        val normalizedTargetGain = targetGain.coerceAtLeast(0f)
        if (runtime.lifecycleState == targetState && runtime.fadeToGain == normalizedTargetGain) {
            return
        }
        runtime.lifecycleState = when {
            targetState == StemLifecycleState.FADING_OUT || normalizedTargetGain == 0f -> StemLifecycleState.FADING_OUT
            else -> StemLifecycleState.FADING_IN
        }
        runtime.fadeStartElapsedMs = now
        runtime.fadeDurationMs = fadeDurationMs.coerceAtLeast(1L)
        runtime.fadeFromGain = runtime.activationGain
        runtime.fadeToGain = normalizedTargetGain
    }

    private fun updateStemMixing(now: Long) {
        stemRuntimes.values.forEach { runtime ->
            updateActivationGain(runtime, now)
            updateEventStates(runtime, now)
            aggregateStemModifiers(runtime)
        }
        pushCurrentStemMixToAudioEngine()
    }

    private fun updateActivationGain(runtime: StemRuntime, now: Long) {
        val fadeDuration = runtime.fadeDurationMs.coerceAtLeast(1L)
        val progress = ((now - runtime.fadeStartElapsedMs).toFloat() / fadeDuration.toFloat()).coerceIn(0f, 1f)
        runtime.activationGain = runtime.fadeFromGain + ((runtime.fadeToGain - runtime.fadeFromGain) * progress)
        if (progress >= 1f) {
            runtime.lifecycleState = if (runtime.fadeToGain == 0f) {
                runtime.activationGain = 0f
                StemLifecycleState.INACTIVE
            } else {
                StemLifecycleState.ACTIVE
            }
        }
    }

    private fun updateEventStates(runtime: StemRuntime, now: Long) {
        runtime.eventRuntimes.forEach { eventRuntime ->
            val shouldBeActive = PlaybackRules.evaluateEventCondition(
                condition = eventRuntime.manifest.condition,
                mph = smoothedSpeedMph
            )
            if (shouldBeActive != eventRuntime.active) {
                eventRuntime.active = shouldBeActive
                eventRuntime.gainModifiers.forEach { modifierRuntime ->
                    beginModifierFade(
                        runtime = modifierRuntime,
                        now = now,
                        targetValue = if (shouldBeActive) {
                            modifierRuntime.manifest.multiplier
                        } else {
                            1f
                        }
                    )
                }
                eventRuntime.reverbModifiers.forEach { modifierRuntime ->
                    beginReverbFade(
                        runtime = modifierRuntime,
                        now = now,
                        targetWetMix = if (shouldBeActive) modifierRuntime.manifest.wetMix else 0f,
                        targetFeedback = if (shouldBeActive) modifierRuntime.manifest.feedback else 0f,
                        targetDamping = if (shouldBeActive) modifierRuntime.manifest.damping else 0f
                    )
                }
            }

            eventRuntime.gainModifiers.forEach { modifierRuntime ->
                updateModifierValue(modifierRuntime, now)
            }
            eventRuntime.reverbModifiers.forEach { modifierRuntime ->
                updateReverbValue(modifierRuntime, now)
            }
        }
    }

    private fun beginModifierFade(
        runtime: GainModifierRuntime,
        now: Long,
        targetValue: Float
    ) {
        runtime.fadeStartElapsedMs = now
        runtime.fadeDurationMs = runtime.manifest.fadeMs.coerceAtLeast(1L)
        runtime.fadeFromValue = runtime.currentValue
        runtime.fadeToValue = targetValue.coerceAtLeast(0f)
    }

    private fun beginReverbFade(
        runtime: ReverbModifierRuntime,
        now: Long,
        targetWetMix: Float,
        targetFeedback: Float,
        targetDamping: Float
    ) {
        runtime.fadeStartElapsedMs = now
        runtime.fadeDurationMs = runtime.manifest.fadeMs.coerceAtLeast(1L)
        runtime.fadeFromWetMix = runtime.currentWetMix
        runtime.fadeToWetMix = targetWetMix.coerceIn(0f, MAX_SAFE_REVERB_WET_MIX)
        runtime.fadeFromFeedback = runtime.currentFeedback
        runtime.fadeToFeedback = targetFeedback.coerceIn(0f, MAX_SAFE_REVERB_FEEDBACK)
        runtime.fadeFromDamping = runtime.currentDamping
        runtime.fadeToDamping = targetDamping.coerceIn(0f, MAX_SAFE_REVERB_DAMPING)
        runtime.currentDelayMs = runtime.manifest.delayMs
    }

    private fun updateModifierValue(
        runtime: GainModifierRuntime,
        now: Long
    ) {
        val progress = ((now - runtime.fadeStartElapsedMs).toFloat() / runtime.fadeDurationMs.coerceAtLeast(1L).toFloat())
            .coerceIn(0f, 1f)
        runtime.currentValue = runtime.fadeFromValue + ((runtime.fadeToValue - runtime.fadeFromValue) * progress)
    }

    private fun updateReverbValue(
        runtime: ReverbModifierRuntime,
        now: Long
    ) {
        val progress = ((now - runtime.fadeStartElapsedMs).toFloat() / runtime.fadeDurationMs.coerceAtLeast(1L).toFloat())
            .coerceIn(0f, 1f)
        runtime.currentWetMix = runtime.fadeFromWetMix + ((runtime.fadeToWetMix - runtime.fadeFromWetMix) * progress)
        runtime.currentFeedback = runtime.fadeFromFeedback + ((runtime.fadeToFeedback - runtime.fadeFromFeedback) * progress)
        runtime.currentDamping = runtime.fadeFromDamping + ((runtime.fadeToDamping - runtime.fadeFromDamping) * progress)
    }

    private fun aggregateStemModifiers(runtime: StemRuntime) {
        var gainMultiplier = 1f
        val activeEvents = mutableListOf<String>()
        var reverbWetMix = 0f
        var reverbFeedback = 0f
        var reverbDamping = 0f
        var reverbDelayMs = DEFAULT_REVERB_DELAY_MS
        var strongestWetMix = 0f

        runtime.eventRuntimes.forEach { eventRuntime ->
            val eventHasEffect = eventRuntime.gainModifiers.any { it.currentValue != 1f } ||
                eventRuntime.reverbModifiers.any { it.currentWetMix > 0f || it.currentFeedback > 0f }
            if (eventHasEffect) {
                activeEvents += eventRuntime.manifest.displayName ?: eventRuntime.manifest.eventId
            }
            eventRuntime.gainModifiers.forEach { modifierRuntime ->
                gainMultiplier *= modifierRuntime.currentValue
            }
            eventRuntime.reverbModifiers.forEach { modifierRuntime ->
                reverbWetMix = max(reverbWetMix, modifierRuntime.currentWetMix)
                reverbFeedback = max(reverbFeedback, modifierRuntime.currentFeedback)
                reverbDamping = max(reverbDamping, modifierRuntime.currentDamping)
                if (modifierRuntime.currentWetMix >= strongestWetMix) {
                    strongestWetMix = modifierRuntime.currentWetMix
                    reverbDelayMs = modifierRuntime.currentDelayMs
                }
            }
        }

        val idleBaseEffect = if (runtime.manifest.rule is BaseStemRule) {
            calculateIdleBaseStemEffect(smoothedSpeedMph)
        } else {
            IdleBaseStemEffect.None
        }
        if (idleBaseEffect.isActive) {
            gainMultiplier *= idleBaseEffect.gainMultiplier
            activeEvents += "Idle gain"
        }

        val songMuffleEffect = calculateSongMuffleEffect(currentSong?.muffle, smoothedSpeedMph)
        if (songMuffleEffect.isActive) {
            activeEvents += "Song muffle"
        }

        runtime.currentGainMultiplier = gainMultiplier.coerceAtLeast(0f)
        runtime.currentOutputGain = (runtime.activationGain * runtime.currentGainMultiplier)
            .coerceAtLeast(0f)
        val combinedMuffleAmount = maxOf(songMuffleEffect.muffleAmount, idleBaseEffect.muffleAmount)
        val combinedMuffleCutoffHz = if (combinedMuffleAmount > 0f) {
            minOf(songMuffleEffect.muffleCutoffHz, idleBaseEffect.muffleCutoffHz)
        } else {
            CLEAR_MUFFLE_CUTOFF_HZ
        }
        runtime.currentMuffleAmount = combinedMuffleAmount
        runtime.currentMuffleCutoffHz = combinedMuffleCutoffHz
        runtime.currentReverbWetMix = reverbWetMix.coerceIn(0f, MAX_SAFE_REVERB_WET_MIX)
        runtime.currentReverbFeedback = reverbFeedback.coerceIn(0f, MAX_SAFE_REVERB_FEEDBACK)
        runtime.currentReverbDamping = reverbDamping.coerceIn(0f, MAX_SAFE_REVERB_DAMPING)
        runtime.currentReverbDelayMs = reverbDelayMs
        runtime.activeEvents = activeEvents
    }

    private fun loadSongAudio(song: SongManifest) {
        songLoadJob?.cancel()
        val targetSongId = song.songId
        val startPositionMs = transportPlayer.currentPosition
        val preloadedSong = takePreloadedSong(targetSongId)
        if (preloadedSong == null && preloadedSongId == targetSongId) {
            preloadSongJob?.cancel()
            preloadSongJob = null
            preloadedSongId = null
        }
        audioLoadInProgress = true
        audioReadySongId = null
        audioLoadError = null
        songLoadJob = scope.launch(Dispatchers.IO) {
            try {
                val loadedSong = preloadedSong ?: audioLoader.load(song)
                withContext(Dispatchers.Main.immediate) {
                    if (currentSong?.songId != targetSongId) {
                        loadedSong.close()
                        return@withContext
                    }
                    currentSongTotalDurationMs = loadedSong.totalDurationMs
                    lastAudioLoadDiagnostics = loadedSong.loadDiagnostics
                    audioLoadInProgress = false
                    audioReadySongId = targetSongId
                    audioLoadError = null
                    audioEngine.loadSong(
                        song = loadedSong,
                        startPositionMs = startPositionMs,
                        playWhenReady = false
                    )
                    pushCurrentStemMixToAudioEngine()
                    scheduleQueuedSongPreload()
                    if (playAfterAudioLoad) {
                        startTransportAndAudioEngine()
                    } else {
                        syncAudioEnginePlayback()
                    }
                    publishState(SystemClock.elapsedRealtime())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    if (currentSong?.songId != targetSongId) {
                        return@withContext
                    }
                    audioLoadInProgress = false
                    audioReadySongId = null
                    audioLoadError = error.message ?: error::class.java.simpleName
                    playAfterAudioLoad = false
                    transportPlayer.pause()
                    audioEngine.pause()
                    releasePlaybackWakeLock()
                    speedRouter.setPlaybackDemandActive(false)
                    publishState(SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private fun takePreloadedSong(songId: String): LoadedSongAudio? {
        if (preloadedSongId != songId) {
            return null
        }
        val loadedSong = preloadedSongAudio ?: return null
        preloadedSongAudio = null
        preloadedSongId = null
        preloadSongJob = null
        return loadedSong
    }

    private fun clearQueuedSongPreload() {
        preloadSongJob?.cancel()
        preloadSongJob = null
        preloadedSongAudio?.close()
        preloadedSongAudio = null
        preloadedSongId = null
    }

    private fun scheduleQueuedSongPreload() {
        val song = currentSong ?: run {
            clearQueuedSongPreload()
            return
        }
        if (!isCurrentAudioReady()) {
            return
        }
        val nextSongId = queuedNextSongId
        if (nextSongId == null || nextSongId == song.songId) {
            clearQueuedSongPreload()
            return
        }
        if (preloadedSongId == nextSongId && (preloadedSongAudio != null || preloadSongJob?.isActive == true)) {
            return
        }
        clearQueuedSongPreload()
        val nextSong = catalogRepository.findSong(nextSongId) ?: return
        preloadedSongId = nextSongId
        preloadSongJob = scope.launch(Dispatchers.IO) {
            try {
                val loadedSong = audioLoader.load(nextSong)
                withContext(Dispatchers.Main.immediate) {
                    preloadSongJob = null
                    if (queuedNextSongId != nextSongId) {
                        loadedSong.close()
                        if (preloadedSongId == nextSongId) {
                            preloadedSongId = null
                        }
                        return@withContext
                    }
                    preloadedSongAudio?.close()
                    preloadedSongAudio = loadedSong
                    preloadedSongId = nextSongId
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    if (preloadedSongId == nextSongId) {
                        preloadedSongId = null
                    }
                    preloadSongJob = null
                }
            }
        }
    }

    private fun pushCurrentStemMixToAudioEngine() {
        val song = currentSong ?: return
        val mixStates = Array(song.stems.size) { index ->
            val stemId = song.stems[index].stemId
            val runtime = stemRuntimes[stemId]
            StemAudioMixState(
                gain = runtime?.currentOutputGain ?: 0f,
                muffleAmount = runtime?.currentMuffleAmount ?: 0f,
                muffleCutoffHz = runtime?.currentMuffleCutoffHz ?: CLEAR_MUFFLE_CUTOFF_HZ,
                reverbWetMix = runtime?.currentReverbWetMix ?: 0f,
                reverbFeedback = runtime?.currentReverbFeedback ?: 0f,
                reverbDamping = runtime?.currentReverbDamping ?: 0f,
                reverbDelayMs = runtime?.currentReverbDelayMs ?: DEFAULT_REVERB_DELAY_MS
            )
        }
        audioEngine.setStemMixStates(mixStates)
    }

    private fun initializeStemRuntimes(song: SongManifest) {
        stemRuntimes.clear()
        val now = SystemClock.elapsedRealtime()
        song.stems.forEach { stem ->
            stemRuntimes[stem.stemId] = StemRuntime(
                manifest = stem,
                lifecycleState = StemLifecycleState.INACTIVE,
                fadeStartElapsedMs = now,
                fadeDurationMs = stem.fadeInMs,
                fadeFromGain = 0f,
                fadeToGain = 0f,
                activationGain = 0f,
                currentOutputGain = 0f,
                currentGainMultiplier = 1f,
                currentMuffleAmount = 0f,
                currentMuffleCutoffHz = CLEAR_MUFFLE_CUTOFF_HZ,
                currentReverbWetMix = 0f,
                currentReverbFeedback = 0f,
                currentReverbDamping = 0f,
                currentReverbDelayMs = DEFAULT_REVERB_DELAY_MS,
                overlayExpiresAtMs = null,
                eventRuntimes = stem.events.map { event -> createEventRuntime(event, now) }.toMutableList(),
                activeEvents = emptyList()
            )
        }
        pushCurrentStemMixToAudioEngine()
    }

    private fun createEventRuntime(
        event: StemEventManifest,
        now: Long
    ): StemEventRuntime {
        val gainModifiers = event.modifiers.mapNotNull { modifier ->
            (modifier as? StemModifierManifest.GainMultiplier)?.let { gainModifier ->
                GainModifierRuntime(
                    manifest = gainModifier,
                    fadeStartElapsedMs = now,
                    fadeDurationMs = gainModifier.fadeMs.coerceAtLeast(1L)
                )
            }
        }
        val reverbModifiers = event.modifiers.mapNotNull { modifier ->
            (modifier as? StemModifierManifest.Reverb)?.let { reverbModifier ->
                ReverbModifierRuntime(
                    manifest = reverbModifier,
                    fadeStartElapsedMs = now,
                    fadeDurationMs = reverbModifier.fadeMs.coerceAtLeast(1L),
                    currentDelayMs = reverbModifier.delayMs
                )
            }
        }
        return StemEventRuntime(
            manifest = event,
            gainModifiers = gainModifiers.toMutableList(),
            reverbModifiers = reverbModifiers.toMutableList()
        )
    }

    private fun ensurePlaybackServiceStarted() {
        MyMusicService.ensureStarted(appContext)
    }

    // ---------------------------------------------------------------- advancement

    private fun handleLoopBoundaryReached() {
        completedLoopCount += 1
        val shouldAdvance = PlaybackQueueRules.shouldAdvanceAfterLoop(
            policy = settingsState.playbackPolicy,
            completedLoopCount = completedLoopCount,
            loopsBeforeAdvance = settingsState.loopsBeforeAdvance
        )
        if (shouldAdvance && !playingOutCurrentSong) {
            beginAdvanceTransitionAtLoopBoundary()
        }
    }

    private fun beginAdvanceTransitionAtLoopBoundary() {
        val song = currentSong ?: return
        val nextSongId = queuedNextSongId ?: computeNextAutomaticSongId(song.songId) ?: return
        val hasPostLoopTail = currentSongTotalDurationMs <= 0L || currentSongTotalDurationMs > song.loopRegion.endMs
        if (!hasPostLoopTail) {
            playNextSongImmediately(nextSongId)
            return
        }
        if (!audioEngine.playCurrentLoopToSongEnd()) {
            playNextSongImmediately(nextSongId)
            return
        }
        queuedNextSongId = nextSongId
        playingOutCurrentSong = true
        completedLoopCount = 0
        transportUsesLoopClip = false
        inFirstPassIntro = false
        transportPlayer.repeatMode = Player.REPEAT_MODE_OFF
        suppressNextPlayOutSeek = true
        val playOutPositionMs = transportPlayer.currentPosition.coerceAtLeast(0L)
        transportPlayer.setMediaItems(
            listOf(catalogRepository.getSongPlayOutMediaItem(song.songId)),
            0,
            playOutPositionMs
        )
        transportPlayer.prepare()
        scope.launch {
            delay(TICK_INTERVAL_MS)
            suppressNextPlayOutSeek = false
        }
        syncAudioEnginePlayback()
        publishState(SystemClock.elapsedRealtime())
    }

    private fun handleFirstPassIntroComplete() {
        val song = currentSong ?: return
        inFirstPassIntro = false
        transportUsesLoopClip = true
        transportPlayer.repeatMode = Player.REPEAT_MODE_ONE
        transportPlayer.setMediaItems(buildLoopingMediaQueue(song.songId), 0, 0L)
        transportPlayer.prepare()
        transportPlayer.playWhenReady = true
        transportPlayer.play()
        syncAudioEnginePlayback()
        publishState(SystemClock.elapsedRealtime())
    }

    private fun handleSongFinishedPlayingOut() {
        val nextSongId = queuedNextSongId ?: return
        queuedNextSongId = null
        playingOutCurrentSong = false
        suppressNextPlayOutSeek = false
        transportUsesLoopClip = true
        inFirstPassIntro = false
        selectSong(nextSongId)
        playAfterAudioLoad = true
        if (isCurrentAudioReady()) {
            startTransportAndAudioEngine()
        } else {
            acquirePlaybackWakeLock()
            speedRouter.setPlaybackDemandActive(true)
        }
        publishState(SystemClock.elapsedRealtime())
    }

    private fun playNextSongImmediately(nextSongId: String) {
        queuedNextSongId = null
        playingOutCurrentSong = false
        suppressNextPlayOutSeek = false
        completedLoopCount = 0
        transportUsesLoopClip = true
        inFirstPassIntro = false
        selectSong(nextSongId)
        playAfterAudioLoad = true
        if (isCurrentAudioReady()) {
            startTransportAndAudioEngine()
        } else {
            acquirePlaybackWakeLock()
            speedRouter.setPlaybackDemandActive(true)
        }
        publishState(SystemClock.elapsedRealtime())
    }

    private fun buildLoopingMediaQueue(currentSongId: String): List<MediaItem> {
        return listOf(catalogRepository.getSongMediaItem(currentSongId))
    }

    private fun refreshQueuedNextSong() {
        val songId = currentSong?.songId
        queuedNextSongId = songId?.let(::computeNextAutomaticSongId)
        scheduleQueuedSongPreload()
    }

    private fun computeNextAutomaticSongId(currentSongId: String): String? {
        return PlaybackQueueRules.nextAutomaticSongId(
            collectionSongIds = collectionSongIds(settingsState.activeCollection),
            currentSongId = currentSongId,
            policy = settingsState.playbackPolicy,
            random = random
        )
    }

    private fun collectionSongIds(
        collection: ActiveCollection,
        libraryState: SongLibraryState = currentLibraryState
    ): List<String> {
        val availableIds = libraryState.songs.map { it.songId }
        return when (collection) {
            ActiveCollection.AllSongs -> availableIds

            is ActiveCollection.Playlist -> {
                val resolved = playlistRepository.resolve(collection.playlistId, availableIds.toSet())
                // A deleted (or fully unavailable) playlist falls back to All Songs.
                if (resolved == null || resolved.availableSongIds.isEmpty()) {
                    availableIds
                } else {
                    resolved.availableSongIds
                }
            }
        }
    }

    private fun activeCollectionName(): String {
        return when (val collection = settingsState.activeCollection) {
            ActiveCollection.AllSongs -> "All Songs"
            is ActiveCollection.Playlist ->
                playlistRepository.findPlaylist(collection.playlistId)?.name ?: "All Songs"
        }
    }

    private fun syncAudioEnginePlayback() {
        val playbackActive = transportPlayer.playWhenReady || transportPlayer.isPlaying
        val audioReady = isCurrentAudioReady()
        speedRouter.setPlaybackDemandActive(playbackActive || playAfterAudioLoad)
        if (playbackActive) {
            acquirePlaybackWakeLock()
            if (audioReady) {
                audioEngine.play()
            } else {
                audioEngine.pause()
            }
        } else {
            audioEngine.pause()
            if (!playAfterAudioLoad) {
                releasePlaybackWakeLock()
            }
        }
    }

    private fun startTransportAndAudioEngine() {
        if (!isCurrentAudioReady()) {
            return
        }
        if (!requestAudioFocus()) {
            playAfterAudioLoad = false
            transportPlayer.playWhenReady = false
            transportPlayer.pause()
            audioEngine.pause()
            releasePlaybackWakeLock()
            speedRouter.setPlaybackDemandActive(false)
            publishState(SystemClock.elapsedRealtime())
            return
        }
        transportPlayer.playWhenReady = true
        transportPlayer.play()
        syncAudioEnginePlayback()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (AudioFocusPolicy.resolve(focusChange)) {
            AudioFocusAction.RESUME_IF_PENDING -> {
                if (playAfterAudioLoad && isCurrentAudioReady()) {
                    startTransportAndAudioEngine()
                }
            }

            AudioFocusAction.PAUSE_AND_CLEAR_INTENT -> pause()

            AudioFocusAction.PAUSE_KEEPING_INTENT -> {
                transportPlayer.pause()
                syncAudioEnginePlayback()
            }

            AudioFocusAction.NONE -> Unit
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            return true
        }
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) {
            return
        }
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
    }

    private fun isCurrentAudioReady(): Boolean {
        return currentSong?.songId != null && audioReadySongId == currentSong?.songId && !audioLoadInProgress
    }

    @SuppressLint("WakelockTimeout")
    private fun acquirePlaybackWakeLock() {
        runCatching {
            if (!playbackWakeLock.isHeld) {
                playbackWakeLock.acquire()
            }
        }
    }

    private fun releasePlaybackWakeLock() {
        runCatching {
            if (playbackWakeLock.isHeld) {
                playbackWakeLock.release()
            }
        }
    }

    private fun isStemActive(stemId: String): Boolean {
        val runtime = stemRuntimes[stemId] ?: return false
        return runtime.lifecycleState != StemLifecycleState.INACTIVE
    }

    private fun publishState(now: Long) {
        val song = currentSong
        val collectionIds = collectionSongIds(settingsState.activeCollection)
        val collectionOptions = collectionIds.mapNotNull { songId ->
            currentLibraryState.songs.firstOrNull { it.songId == songId }
        }.map { manifest ->
            com.BalancedLight.dynamicdriving.shared.catalog.SongOption(
                songId = manifest.songId,
                displayName = manifest.displayName,
                artist = manifest.artist,
                album = manifest.album
            )
        }

        _uiState.value = PlaybackUiState(
            currentSongId = song?.songId,
            currentSongTitle = song?.displayName,
            currentSongArtist = song?.artist,
            currentSongAlbum = song?.album,
            isPlaying = transportPlayer.isPlaying || transportPlayer.playWhenReady || playAfterAudioLoad,
            activeCollection = settingsState.activeCollection,
            activeCollectionName = activeCollectionName(),
            playbackPolicy = settingsState.playbackPolicy,
            loopsBeforeAdvance = settingsState.loopsBeforeAdvance,
            completedLoopCount = completedLoopCount,
            queuedSongTitle = queuedNextSongId?.let { catalogRepository.findSong(it)?.displayName },
            playingOutCurrentSong = playingOutCurrentSong,
            speed = latestSpeedState,
            smoothedSpeedMph = smoothedSpeedMph,
            currentPositionMs = when {
                transportUsesLoopClip || playingOutCurrentSong ->
                    (song?.loopRegion?.startMs ?: 0L) + transportPlayer.currentPosition.coerceAtLeast(0L)

                else -> transportPlayer.currentPosition.coerceAtLeast(0L)
            },
            loopStartMs = song?.loopRegion?.startMs ?: 0L,
            loopEndMs = song?.loopRegion?.endMs ?: 0L,
            nextLoopInMs = song?.let(::nextLoopInMs) ?: 0L,
            libraryRootSummary = currentLibraryState.rootSummary,
            libraryRefreshing = currentLibraryState.isRefreshing,
            libraryDiagnostics = currentLibraryState.diagnostics,
            audioLoading = audioLoadInProgress,
            audioLoadError = audioLoadError,
            collectionSongOptions = collectionOptions,
            diagnostics = if (BuildConfig.DEBUG) buildDiagnostics(song, now) else null
        )
    }

    private fun nextLoopInMs(song: SongManifest): Long {
        return when {
            inFirstPassIntro -> {
                val firstPassDuration = transportPlayer.duration.takeIf { it > 0L } ?: song.loopRegion.endMs
                (firstPassDuration - transportPlayer.currentPosition).coerceAtLeast(0L)
            }

            transportUsesLoopClip -> {
                val loopDuration = transportPlayer.duration.takeIf { it > 0L } ?: song.loopRegion.durationMs
                (loopDuration - transportPlayer.currentPosition).coerceAtLeast(0L)
            }

            else -> {
                val totalDuration = transportPlayer.duration.takeIf { it > 0L } ?: currentSongTotalDurationMs
                (totalDuration - transportPlayer.currentPosition).coerceAtLeast(0L)
            }
        }
    }

    private fun buildDiagnostics(song: SongManifest?, now: Long): PlaybackDiagnostics {
        val audioDiagnostics = audioEngine.diagnostics()
        val stems = song?.stems?.map { stem ->
            val runtime = stemRuntimes[stem.stemId]
            val overlayRule = stem.rule as? OverlayStemRule
            val overlayState = overlayRule?.let { overlayGroupStates[it.overlayGroup.groupId] }
            val eligible = when (val rule = stem.rule) {
                is BaseStemRule -> PlaybackRules.shouldActivateBaseStem(
                    rule = rule,
                    mph = smoothedSpeedMph,
                    currentlyActive = runtime != null && runtime.lifecycleState != StemLifecycleState.INACTIVE,
                    hysteresisMph = HYSTERESIS_MPH
                )

                is OverlayStemRule -> PlaybackRules.isOverlayEligible(
                    rule = rule,
                    mph = smoothedSpeedMph,
                    currentlyActive = overlayState?.activeStemId == stem.stemId,
                    hysteresisMph = HYSTERESIS_MPH
                )
            }
            PlaybackStemDiagnostics(
                stemId = stem.stemId,
                displayName = stem.displayName,
                stateLabel = runtime?.lifecycleState?.label ?: if ((overlayState?.cooldownUntilMs ?: 0L) > now) {
                    "Cooling down"
                } else {
                    "Inactive"
                },
                eligible = eligible,
                currentGain = runtime?.currentOutputGain ?: 0f,
                targetGain = ((runtime?.fadeToGain ?: stem.gain) * (runtime?.currentGainMultiplier ?: 1f))
                    .coerceAtLeast(0f),
                gainMultiplier = runtime?.currentGainMultiplier ?: 1f,
                reverbWetMix = runtime?.currentReverbWetMix ?: 0f,
                activeEvents = runtime?.activeEvents ?: emptyList(),
                activeRemainingMs = runtime?.overlayExpiresAtMs?.let { (it - now).coerceAtLeast(0L) },
                cooldownRemainingMs = overlayState?.cooldownUntilMs?.takeIf { it > now }?.minus(now)
            )
        }.orEmpty()

        return PlaybackDiagnostics(
            audioLoadDurationMs = lastAudioLoadDiagnostics.loadDurationMs,
            audioLoadSourceSummary = lastAudioLoadDiagnostics.sourceSummary,
            audioUnderrunCount = audioDiagnostics.underrunCount,
            activeAudioStemCount = audioDiagnostics.activeStemCount,
            lastMixerRenderMs = audioDiagnostics.lastRenderDurationMs,
            maxMixerRenderMs = audioDiagnostics.maxRenderDurationMs,
            lastAudioWriteMs = audioDiagnostics.lastWriteDurationMs,
            maxAudioWriteMs = audioDiagnostics.maxWriteDurationMs,
            speedSampleAgeMs = (System.currentTimeMillis() - latestSpeedState.sample.timestampMs)
                .coerceAtLeast(0L),
            stems = stems
        )
    }

    private fun rebuildOverlayGroups(song: SongManifest) {
        overlayGroupStates.clear()
        song.stems
            .mapNotNull { stem -> (stem.rule as? OverlayStemRule)?.overlayGroup?.groupId }
            .distinct()
            .forEach { groupId -> overlayGroupStates[groupId] = OverlayGroupState() }
    }

    private fun ensureSongSelected() {
        if (currentSong != null) {
            return
        }
        val preferredSongId = settingsState.lastSongId
            ?.takeIf { songId -> currentLibraryState.songs.any { it.songId == songId } }
            ?: firstSongIdForActiveCollection(currentLibraryState)
        preferredSongId?.let(::selectSong)
    }

    private fun skipToAdjacentSong(offset: Int) {
        val collectionIds = collectionSongIds(settingsState.activeCollection)
        val queuedShuffleSong = queuedNextSongId?.takeIf { queuedSongId ->
            offset > 0 &&
                settingsState.playbackPolicy == PlaylistPlaybackPolicy.SHUFFLE &&
                queuedSongId in collectionIds
        }
        val targetSongId = queuedShuffleSong ?: PlaybackQueueRules.manualSkipSongId(
            collectionSongIds = collectionIds,
            currentSongId = currentSong?.songId,
            offset = offset,
            policy = settingsState.playbackPolicy,
            random = random
        ) ?: return
        val wasPlaying = transportPlayer.playWhenReady || transportPlayer.isPlaying || playAfterAudioLoad
        selectSong(targetSongId)
        if (wasPlaying) {
            play()
        }
    }

    private data class StemRuntime(
        val manifest: StemManifest,
        var lifecycleState: StemLifecycleState,
        var fadeStartElapsedMs: Long,
        var fadeDurationMs: Long,
        var fadeFromGain: Float,
        var fadeToGain: Float,
        var activationGain: Float,
        var currentOutputGain: Float,
        var currentGainMultiplier: Float,
        var currentMuffleAmount: Float,
        var currentMuffleCutoffHz: Float,
        var currentReverbWetMix: Float,
        var currentReverbFeedback: Float,
        var currentReverbDamping: Float,
        var currentReverbDelayMs: Float,
        var overlayExpiresAtMs: Long?,
        val eventRuntimes: MutableList<StemEventRuntime>,
        var activeEvents: List<String>
    )

    private data class StemEventRuntime(
        val manifest: StemEventManifest,
        var active: Boolean = false,
        val gainModifiers: MutableList<GainModifierRuntime>,
        val reverbModifiers: MutableList<ReverbModifierRuntime>
    )

    private data class GainModifierRuntime(
        val manifest: StemModifierManifest.GainMultiplier,
        var currentValue: Float = 1f,
        var fadeStartElapsedMs: Long,
        var fadeDurationMs: Long,
        var fadeFromValue: Float = 1f,
        var fadeToValue: Float = 1f
    )

    private data class ReverbModifierRuntime(
        val manifest: StemModifierManifest.Reverb,
        var currentWetMix: Float = 0f,
        var currentFeedback: Float = 0f,
        var currentDamping: Float = 0f,
        var currentDelayMs: Float,
        var fadeStartElapsedMs: Long,
        var fadeDurationMs: Long,
        var fadeFromWetMix: Float = 0f,
        var fadeToWetMix: Float = 0f,
        var fadeFromFeedback: Float = 0f,
        var fadeToFeedback: Float = 0f,
        var fadeFromDamping: Float = 0f,
        var fadeToDamping: Float = 0f
    )

    private enum class StemLifecycleState(val label: String) {
        INACTIVE("Inactive"),
        FADING_IN("Fading in"),
        ACTIVE("Active"),
        FADING_OUT("Fading out")
    }
}

internal data class IdleBaseStemEffect(
    val gainMultiplier: Float,
    val muffleAmount: Float,
    val muffleCutoffHz: Float
) {
    val isActive: Boolean
        get() = gainMultiplier < 0.999f || muffleAmount > 0.001f

    companion object {
        val None = IdleBaseStemEffect(
            gainMultiplier = 1f,
            muffleAmount = 0f,
            muffleCutoffHz = SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ
        )
    }
}

internal data class SongMuffleEffect(
    val muffleAmount: Float,
    val muffleCutoffHz: Float
) {
    val isActive: Boolean
        get() = muffleAmount > 0.001f

    companion object {
        val None = SongMuffleEffect(
            muffleAmount = 0f,
            muffleCutoffHz = SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ
        )
    }
}

internal fun calculateIdleBaseStemEffect(smoothedSpeedMph: Double): IdleBaseStemEffect {
    val clampedSpeed = smoothedSpeedMph.coerceAtLeast(0.0)
    val blend = ((SpeedAdaptivePlaybackController.IDLE_BASE_EFFECT_RELEASE_MPH - clampedSpeed) /
        SpeedAdaptivePlaybackController.IDLE_BASE_EFFECT_RELEASE_MPH)
        .coerceIn(0.0, 1.0)
        .toFloat()
    if (blend <= 0f) {
        return IdleBaseStemEffect.None
    }
    val gainMultiplier = 1f - ((1f - SpeedAdaptivePlaybackController.IDLE_BASE_MIN_GAIN_MULTIPLIER) * blend)
    val muffleAmount = SpeedAdaptivePlaybackController.IDLE_BASE_MAX_MUFFLE_AMOUNT * blend
    val muffleCutoffHz = SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ -
        ((SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ - SpeedAdaptivePlaybackController.IDLE_BASE_MUFFLED_CUTOFF_HZ) * blend)
    return IdleBaseStemEffect(
        gainMultiplier = gainMultiplier.coerceIn(
            SpeedAdaptivePlaybackController.IDLE_BASE_MIN_GAIN_MULTIPLIER,
            1f
        ),
        muffleAmount = muffleAmount.coerceIn(0f, SpeedAdaptivePlaybackController.IDLE_BASE_MAX_MUFFLE_AMOUNT),
        muffleCutoffHz = muffleCutoffHz.coerceIn(
            SpeedAdaptivePlaybackController.IDLE_BASE_MUFFLED_CUTOFF_HZ,
            SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ
        )
    )
}

internal fun calculateSongMuffleEffect(
    songMuffle: SongMuffleManifest?,
    smoothedSpeedMph: Double
): SongMuffleEffect {
    val manifest = songMuffle ?: return SongMuffleEffect.None
    val releaseMph = manifest.releaseMph.coerceAtLeast(0.001)
    val blend = ((releaseMph - smoothedSpeedMph.coerceAtLeast(0.0)) / releaseMph)
        .coerceIn(0.0, 1.0)
        .toFloat()
    if (blend <= 0f) {
        return SongMuffleEffect.None
    }
    return SongMuffleEffect(
        muffleAmount = (manifest.wetMix * blend).coerceIn(0f, 1f),
        muffleCutoffHz = (SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ -
            ((SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ - manifest.cutoffHz) * blend))
            .coerceIn(manifest.cutoffHz.coerceAtLeast(80f), SpeedAdaptivePlaybackController.CLEAR_MUFFLE_CUTOFF_HZ)
    )
}
