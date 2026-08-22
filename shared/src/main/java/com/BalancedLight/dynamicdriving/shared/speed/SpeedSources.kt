package com.BalancedLight.dynamicdriving.shared.speed

import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SpeedSample(
    val mph: Double,
    val sourceLabel: String,
    val isLiveSource: Boolean,
    val available: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
    val isStale: Boolean = false,
    /** Human-readable explanation shown when [available] is false. */
    val unavailableReason: String? = null
)

interface SpeedSource {
    val label: String
    val samples: StateFlow<SpeedSample>

    fun start()
    fun stop()
}

enum class CarConnectionMode {
    NOT_CONNECTED,
    AAOS_NATIVE,
    ANDROID_AUTO_PROJECTION
}

/** The concrete source the router settled on for the current selection. */
enum class ResolvedSpeedSource {
    NONE,
    CAR_NATIVE,
    CAR_PROJECTED,
    PHONE_GPS,
    MANUAL
}

/**
 * Everything the UI needs to explain the current speed reading, including why an explicitly
 * selected source is producing 0 mph.
 */
data class EffectiveSpeedState(
    val selection: SpeedSourceSelection = SpeedSourceSelection.AUTOMATIC,
    val resolvedSource: ResolvedSpeedSource = ResolvedSpeedSource.NONE,
    val connectionMode: CarConnectionMode = CarConnectionMode.NOT_CONNECTED,
    val sample: SpeedSample = SpeedSample(
        mph = 0.0,
        sourceLabel = "No speed source",
        isLiveSource = true,
        available = false,
        unavailableReason = "No speed source is available yet."
    )
) {
    val mph: Double get() = if (sample.available) sample.mph else 0.0

    /** Set when an explicit (non-automatic) selection cannot produce a reading. */
    val strictUnavailableExplanation: String?
        get() = if (selection != SpeedSourceSelection.AUTOMATIC && !sample.available) {
            sample.unavailableReason ?: "${sample.sourceLabel} is unavailable."
        } else {
            null
        }

    /** True when offering a one-tap switch back to Automatic would help the user. */
    val canOfferAutomaticFallback: Boolean
        get() = strictUnavailableExplanation != null
}

interface CarConnectionStateProvider {
    val label: String
    val connectionModes: StateFlow<CarConnectionMode>

    fun start()
    fun stop()
}

class StaticCarConnectionStateProvider(
    initialMode: CarConnectionMode
) : CarConnectionStateProvider {
    override val label: String = "Static car connection"
    private val _connectionModes = MutableStateFlow(initialMode)

    override val connectionModes: StateFlow<CarConnectionMode> = _connectionModes

    override fun start() = Unit

    override fun stop() = Unit

    fun setMode(mode: CarConnectionMode) {
        _connectionModes.value = mode
    }
}

/**
 * The user-set speed used when [SpeedSourceSelection.MANUAL] is selected.
 *
 * Unlike the other sources this one is always "available" — a manual reading cannot fail — so
 * Manual never produces the strict-unavailable explanation.
 */
class ManualSpeedSource(initialMph: Double = 0.0) : SpeedSource {
    override val label: String = "Manual speed"

    private val _samples = MutableStateFlow(
        SpeedSample(
            mph = initialMph.coerceAtLeast(0.0),
            sourceLabel = label,
            isLiveSource = false,
            available = true
        )
    )

    override val samples: StateFlow<SpeedSample> = _samples

    override fun start() = Unit

    override fun stop() = Unit

    fun setMph(mph: Double) {
        _samples.update { current ->
            current.copy(
                mph = mph.coerceIn(0.0, 160.0),
                available = true,
                isStale = false,
                unavailableReason = null,
                timestampMs = System.currentTimeMillis()
            )
        }
    }
}

/** A placeholder source used when a platform has no live speed hardware at all. */
class StaticSpeedSource(
    override val label: String = "No live vehicle speed",
    private val reason: String = "This device does not report a live speed."
) : SpeedSource {
    private val _samples = MutableStateFlow(
        unavailableSample(label = label, isLiveSource = true, reason = reason)
    )

    override val samples: StateFlow<SpeedSample> = _samples

    override fun start() = Unit

    override fun stop() = Unit
}

/**
 * Routes the four [SpeedSourceSelection] choices onto the concrete sources registered by each app
 * flavour.
 *
 * Automatic prefers the richest available reading for the current connection. Every explicit
 * selection is strict: if the chosen source cannot produce a sample the router reports 0 mph with
 * an explanation instead of silently substituting a different source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedSourceRouter(
    private val manualSource: ManualSpeedSource,
    private val liveSampleStaleTimeoutMs: Long = LIVE_SAMPLE_STALE_TIMEOUT_MS,
    private val staleCheckIntervalMs: Long = STALE_CHECK_INTERVAL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis
) : SpeedSource {
    companion object {
        const val NO_SOURCE_LABEL = "No speed source"
        const val AAOS_LABEL = "Vehicle speed"
        const val PROJECTION_LABEL = "Projected car speed"
        const val GPS_LABEL = "Phone GPS speed"

        private const val LIVE_SAMPLE_STALE_TIMEOUT_MS = 5_000L
        private const val STALE_CHECK_INTERVAL_MS = 1_000L
    }

    override val label: String = "Effective speed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectionProviderHolder = MutableStateFlow<CarConnectionStateProvider?>(null)
    private val aaosSourceHolder = MutableStateFlow<SpeedSource?>(null)
    private val projectionSourceHolder = MutableStateFlow<SpeedSource?>(null)
    private val gpsSourceHolder = MutableStateFlow<SpeedSource?>(null)
    private val connectionModesInternal = MutableStateFlow(CarConnectionMode.NOT_CONNECTED)
    private val selectionFlow = MutableStateFlow(SpeedSourceSelection.AUTOMATIC)
    private val staleClockMs = MutableStateFlow(clockMs())

    private val defaultAaosSample = unavailableSample(
        label = AAOS_LABEL,
        isLiveSource = true,
        reason = "This device does not expose native vehicle speed."
    )
    private val defaultProjectionSample = unavailableSample(
        label = PROJECTION_LABEL,
        isLiveSource = true,
        reason = "Connect to Android Auto to receive projected car speed."
    )
    private val defaultGpsSample = unavailableSample(
        label = GPS_LABEL,
        isLiveSource = true,
        reason = "Phone GPS is not running yet."
    )
    private val defaultUnavailableSample = unavailableSample(
        label = NO_SOURCE_LABEL,
        isLiveSource = true,
        reason = "No speed source is available yet."
    )

    private val aaosSamples: StateFlow<SpeedSample> = aaosSourceHolder
        .flatMapLatest { source -> source?.samples ?: flowOf(defaultAaosSample) }
        .stateIn(scope, SharingStarted.Eagerly, defaultAaosSample)

    private val projectionSamples: StateFlow<SpeedSample> = projectionSourceHolder
        .flatMapLatest { source -> source?.samples ?: flowOf(defaultProjectionSample) }
        .stateIn(scope, SharingStarted.Eagerly, defaultProjectionSample)

    private val gpsSamples: StateFlow<SpeedSample> = gpsSourceHolder
        .flatMapLatest { source -> source?.samples ?: flowOf(defaultGpsSample) }
        .stateIn(scope, SharingStarted.Eagerly, defaultGpsSample)

    private val liveSamples = combine(
        aaosSamples,
        projectionSamples,
        gpsSamples,
        staleClockMs
    ) { aaosSample, projectionSample, gpsSample, nowMs ->
        LiveSamples(
            aaos = aaosSample.withStaleTimeout(nowMs),
            projection = projectionSample.withStaleTimeout(nowMs),
            gps = gpsSample.withStaleTimeout(nowMs)
        )
    }

    val effectiveState: StateFlow<EffectiveSpeedState> = combine(
        selectionFlow,
        connectionModesInternal,
        liveSamples,
        manualSource.samples
    ) { selection, connectionMode, live, manualSample ->
        resolve(
            selection = selection,
            connectionMode = connectionMode,
            aaosSample = live.aaos,
            projectionSample = live.projection,
            gpsSample = live.gps,
            manualSample = manualSample
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = EffectiveSpeedState(sample = defaultUnavailableSample)
    )

    override val samples: StateFlow<SpeedSample> = effectiveState
        .map { it.sample }
        .stateIn(scope, SharingStarted.Eagerly, defaultUnavailableSample)

    private var currentAaosSource: SpeedSource? = null
    private var currentProjectionSource: SpeedSource? = null
    private var currentGpsSource: SpeedSource? = null
    private var isAaosRunning = false
    private var isProjectionRunning = false
    private var isGpsRunning = false
    private var playbackDemandActive = false
    private var foregroundMonitoringActive = false
    private var connectionModeCollectionJob: Job? = null

    init {
        scope.launch {
            while (isActive) {
                staleClockMs.value = clockMs()
                delay(staleCheckIntervalMs.coerceAtLeast(100L))
            }
        }
        scope.launch {
            selectionFlow.collect { syncManagedSources() }
        }
        scope.launch {
            connectionModesInternal.collect { syncManagedSources() }
        }
    }

    override fun start() {
        manualSource.start()
        connectionProviderHolder.value?.start()
        syncManagedSources()
    }

    override fun stop() {
        manualSource.stop()
        connectionModeCollectionJob?.cancel()
        connectionModeCollectionJob = null
        connectionProviderHolder.value?.stop()
        connectionModesInternal.value = CarConnectionMode.NOT_CONNECTED
        stopAaosSource()
        stopProjectionSource()
        stopGpsSource()
        scope.cancel()
    }

    fun refresh() {
        syncManagedSources(forceRestart = true)
    }

    fun setSelection(selection: SpeedSourceSelection) {
        selectionFlow.value = selection
    }

    /** True while the media foreground service is running and may legitimately read GPS. */
    fun setPlaybackDemandActive(active: Boolean) {
        if (playbackDemandActive == active) {
            return
        }
        playbackDemandActive = active
        syncManagedSources()
    }

    /** True while an app screen that displays live speed is visible. */
    fun setForegroundMonitoringActive(active: Boolean) {
        if (foregroundMonitoringActive == active) {
            return
        }
        foregroundMonitoringActive = active
        syncManagedSources()
    }

    fun setConnectionProvider(provider: CarConnectionStateProvider?) {
        val previous = connectionProviderHolder.value
        if (previous === provider) {
            return
        }
        connectionModeCollectionJob?.cancel()
        connectionModeCollectionJob = null
        previous?.stop()
        connectionProviderHolder.value = provider
        connectionModesInternal.value = provider?.connectionModes?.value ?: CarConnectionMode.NOT_CONNECTED
        provider?.start()
        connectionModeCollectionJob = provider?.let { currentProvider ->
            scope.launch {
                currentProvider.connectionModes.collect { mode ->
                    connectionModesInternal.value = mode
                }
            }
        }
        syncManagedSources()
    }

    fun setAaosSource(source: SpeedSource?) {
        if (aaosSourceHolder.value === source) {
            return
        }
        stopAaosSource()
        aaosSourceHolder.value = source
        syncManagedSources()
    }

    fun setProjectionSource(source: SpeedSource?) {
        if (projectionSourceHolder.value === source) {
            return
        }
        stopProjectionSource()
        projectionSourceHolder.value = source
        syncManagedSources()
    }

    fun setGpsSource(source: SpeedSource?) {
        if (gpsSourceHolder.value === source) {
            return
        }
        stopGpsSource()
        gpsSourceHolder.value = source
        syncManagedSources()
    }

    private fun resolve(
        selection: SpeedSourceSelection,
        connectionMode: CarConnectionMode,
        aaosSample: SpeedSample,
        projectionSample: SpeedSample,
        gpsSample: SpeedSample,
        manualSample: SpeedSample
    ): EffectiveSpeedState {
        return when (selection) {
            SpeedSourceSelection.MANUAL -> EffectiveSpeedState(
                selection = selection,
                resolvedSource = ResolvedSpeedSource.MANUAL,
                connectionMode = connectionMode,
                sample = manualSample
            )

            SpeedSourceSelection.PHONE_GPS -> EffectiveSpeedState(
                selection = selection,
                resolvedSource = ResolvedSpeedSource.PHONE_GPS,
                connectionMode = connectionMode,
                sample = gpsSample.strict()
            )

            SpeedSourceSelection.CAR -> when (connectionMode) {
                CarConnectionMode.AAOS_NATIVE -> EffectiveSpeedState(
                    selection = selection,
                    resolvedSource = ResolvedSpeedSource.CAR_NATIVE,
                    connectionMode = connectionMode,
                    sample = aaosSample.strict()
                )

                CarConnectionMode.ANDROID_AUTO_PROJECTION -> EffectiveSpeedState(
                    selection = selection,
                    resolvedSource = ResolvedSpeedSource.CAR_PROJECTED,
                    connectionMode = connectionMode,
                    sample = projectionSample.strict()
                )

                CarConnectionMode.NOT_CONNECTED -> EffectiveSpeedState(
                    selection = selection,
                    resolvedSource = ResolvedSpeedSource.NONE,
                    connectionMode = connectionMode,
                    sample = unavailableSample(
                        label = "Car speed",
                        isLiveSource = true,
                        reason = "No car is connected, so car speed cannot be read."
                    )
                )
            }

            SpeedSourceSelection.AUTOMATIC -> resolveAutomatic(
                connectionMode = connectionMode,
                aaosSample = aaosSample,
                projectionSample = projectionSample,
                gpsSample = gpsSample
            )
        }
    }

    private fun resolveAutomatic(
        connectionMode: CarConnectionMode,
        aaosSample: SpeedSample,
        projectionSample: SpeedSample,
        gpsSample: SpeedSample
    ): EffectiveSpeedState {
        val (resolvedSource, sample) = when (connectionMode) {
            CarConnectionMode.AAOS_NATIVE -> when {
                aaosSample.available -> ResolvedSpeedSource.CAR_NATIVE to aaosSample
                gpsSample.available -> ResolvedSpeedSource.PHONE_GPS to gpsSample
                else -> ResolvedSpeedSource.CAR_NATIVE to aaosSample
            }

            CarConnectionMode.ANDROID_AUTO_PROJECTION -> when {
                projectionSample.available -> ResolvedSpeedSource.CAR_PROJECTED to projectionSample
                gpsSample.available -> ResolvedSpeedSource.PHONE_GPS to gpsSample
                else -> ResolvedSpeedSource.CAR_PROJECTED to projectionSample
            }

            CarConnectionMode.NOT_CONNECTED -> ResolvedSpeedSource.PHONE_GPS to gpsSample
        }
        return EffectiveSpeedState(
            selection = SpeedSourceSelection.AUTOMATIC,
            resolvedSource = resolvedSource,
            connectionMode = connectionMode,
            sample = sample
        )
    }

    /** Explicit selections report a hard zero rather than a stale or partial reading. */
    private fun SpeedSample.strict(): SpeedSample {
        if (available) {
            return this
        }
        return copy(
            mph = 0.0,
            unavailableReason = unavailableReason ?: "$sourceLabel is unavailable."
        )
    }

    private fun SpeedSample.withStaleTimeout(nowMs: Long): SpeedSample {
        if (!available || !isLiveSource) {
            return this
        }
        val ageMs = nowMs - timestampMs
        if (ageMs <= liveSampleStaleTimeoutMs) {
            return copy(isStale = false)
        }
        return copy(
            mph = 0.0,
            available = false,
            isStale = true,
            unavailableReason = "$sourceLabel stopped updating."
        )
    }

    private fun syncManagedSources(forceRestart: Boolean = false) {
        val selection = selectionFlow.value
        val connectionMode = connectionModesInternal.value
        val locationAllowed = playbackDemandActive || foregroundMonitoringActive

        val needsAaos = when (selection) {
            SpeedSourceSelection.AUTOMATIC, SpeedSourceSelection.CAR ->
                connectionMode == CarConnectionMode.AAOS_NATIVE

            else -> false
        }
        val needsProjection = when (selection) {
            SpeedSourceSelection.AUTOMATIC, SpeedSourceSelection.CAR ->
                connectionMode == CarConnectionMode.ANDROID_AUTO_PROJECTION

            else -> false
        }
        val needsGps = when (selection) {
            SpeedSourceSelection.PHONE_GPS -> true
            SpeedSourceSelection.AUTOMATIC -> connectionMode != CarConnectionMode.AAOS_NATIVE
            else -> false
        }

        if (needsAaos) startAaosSource(forceRestart) else stopAaosSource()
        if (needsProjection) startProjectionSource(forceRestart) else stopProjectionSource()
        if (needsGps && locationAllowed) startGpsSource(forceRestart) else stopGpsSource()
    }

    private fun startAaosSource(forceRestart: Boolean) {
        val source = aaosSourceHolder.value ?: return
        if (currentAaosSource !== source) {
            stopAaosSource()
        }
        currentAaosSource = source
        if (!isAaosRunning || forceRestart) {
            if (isAaosRunning && forceRestart) {
                source.stop()
            }
            source.start()
            isAaosRunning = true
        }
    }

    private fun stopAaosSource() {
        currentAaosSource?.takeIf { isAaosRunning }?.stop()
        currentAaosSource = null
        isAaosRunning = false
    }

    private fun startProjectionSource(forceRestart: Boolean) {
        val source = projectionSourceHolder.value ?: return
        if (currentProjectionSource !== source) {
            stopProjectionSource()
        }
        currentProjectionSource = source
        if (!isProjectionRunning || forceRestart) {
            if (isProjectionRunning && forceRestart) {
                source.stop()
            }
            source.start()
            isProjectionRunning = true
        }
    }

    private fun stopProjectionSource() {
        currentProjectionSource?.takeIf { isProjectionRunning }?.stop()
        currentProjectionSource = null
        isProjectionRunning = false
    }

    private fun startGpsSource(forceRestart: Boolean) {
        val source = gpsSourceHolder.value ?: return
        if (currentGpsSource !== source) {
            stopGpsSource()
        }
        currentGpsSource = source
        if (!isGpsRunning || forceRestart) {
            if (isGpsRunning && forceRestart) {
                source.stop()
            }
            source.start()
            isGpsRunning = true
        }
    }

    private fun stopGpsSource() {
        currentGpsSource?.takeIf { isGpsRunning }?.stop()
        currentGpsSource = null
        isGpsRunning = false
    }

    private data class LiveSamples(
        val aaos: SpeedSample,
        val projection: SpeedSample,
        val gps: SpeedSample
    )
}

internal fun unavailableSample(
    label: String,
    isLiveSource: Boolean,
    reason: String
): SpeedSample {
    return SpeedSample(
        mph = 0.0,
        sourceLabel = label,
        isLiveSource = isLiveSource,
        available = false,
        unavailableReason = reason
    )
}
