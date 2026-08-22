package com.BalancedLight.dynamicdriving.shared

import android.content.Context
import com.BalancedLight.dynamicdriving.shared.artwork.SongArtworkStore
import com.BalancedLight.dynamicdriving.shared.catalog.MediaBrowseTree
import com.BalancedLight.dynamicdriving.shared.catalog.SongCatalogRepository
import com.BalancedLight.dynamicdriving.shared.playback.SpeedAdaptivePlaybackController
import com.BalancedLight.dynamicdriving.shared.playlist.PlaylistRepository
import com.BalancedLight.dynamicdriving.shared.settings.DynamicDrivingSettings
import com.BalancedLight.dynamicdriving.shared.speed.CarConnectionStateProvider
import com.BalancedLight.dynamicdriving.shared.speed.ManualSpeedSource
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSource
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSourceRouter

/**
 * Process-wide object graph.
 *
 * Each app flavour installs the speed sources it can actually provide (native vehicle speed on
 * AAOS, projected car speed and phone GPS on the phone); everything else is shared.
 */
class DynamicDrivingRuntime private constructor(
    context: Context
) {
    val settings = DynamicDrivingSettings(context)
    val artworkStore: SongArtworkStore = SongArtworkStore.get(context)
    val catalogRepository = SongCatalogRepository(
        context,
        bundledDemoEnabled = settings.state.value.bundledDemoEnabled
    )
    val playlistRepository = PlaylistRepository(context)
    val manualSpeedSource = ManualSpeedSource(settings.state.value.manualSpeedMph)
    val speedRouter = SpeedSourceRouter(manualSpeedSource)
    val mediaBrowseTree = MediaBrowseTree(catalogRepository, playlistRepository)
    val playbackController = SpeedAdaptivePlaybackController(
        context = context,
        catalogRepository = catalogRepository,
        playlistRepository = playlistRepository,
        settings = settings,
        manualSpeedSource = manualSpeedSource,
        speedRouter = speedRouter
    )

    init {
        speedRouter.start()
    }

    fun refreshLiveInputs() {
        speedRouter.refresh()
    }

    fun setBundledDemoEnabled(enabled: Boolean) {
        settings.setBundledDemoEnabled(enabled)
        catalogRepository.setBundledDemoEnabled(enabled)
    }

    /** Called while a screen that shows live speed is visible. */
    fun setForegroundMonitoringActive(active: Boolean) {
        speedRouter.setForegroundMonitoringActive(active)
    }

    fun setCarConnectionProvider(provider: CarConnectionStateProvider?) {
        speedRouter.setConnectionProvider(provider)
    }

    fun setAaosSpeedSource(source: SpeedSource?) {
        speedRouter.setAaosSource(source)
    }

    fun setProjectionSpeedSource(source: SpeedSource?) {
        speedRouter.setProjectionSource(source)
    }

    fun setPhoneGpsSpeedSource(source: SpeedSource?) {
        speedRouter.setGpsSource(source)
    }

    companion object {
        @Volatile
        private var instance: DynamicDrivingRuntime? = null

        fun initialize(context: Context) {
            if (instance != null) return
            synchronized(this) {
                if (instance == null) {
                    instance = DynamicDrivingRuntime(context.applicationContext)
                }
            }
        }

        fun require(): DynamicDrivingRuntime {
            return checkNotNull(instance) {
                "DynamicDrivingRuntime has not been initialized."
            }
        }
    }
}
