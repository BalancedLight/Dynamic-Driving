package com.BalancedLight.dynamicdriving.projected

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import com.BalancedLight.dynamicdriving.shared.MyMusicService

/**
 * Android Auto entry point for the templated media experience.
 *
 * Browsing is a host-rendered section/grid selector, and playback is handed to the host's own
 * media UI via [MediaPlaybackManager]. The MediaLibraryService browse tree remains the fallback the
 * host uses when it prefers to render the library itself.
 */
class DynamicDrivingCarAppService : CarAppService() {
    @Suppress("PrivateResource") // hosts_allowlist_sample is the documented allow-list to use here.
    override fun createHostValidator(): HostValidator {
        // Android documents ALLOW_ALL_HOSTS_VALIDATOR as development-only: it lets any app on the
        // device drive this service. Release builds accept only the signed Google host packages
        // shipped in the Car App Library's allow-list.
        // https://developer.android.com/reference/androidx/car/app/validation/HostValidator
        return if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = DynamicDrivingProjectionSession()
}

private class DynamicDrivingProjectionSession : Session() {
    private val runtime by lazy { DynamicDrivingRuntime.require() }
    private val handler = Handler(Looper.getMainLooper())
    private var playbackTokenRegistered = false
    private var projectionSpeedSource: ProjectionCarHardwareSpeedSource? = null

    private val tokenRegistrationRunnable = object : Runnable {
        override fun run() {
            MyMusicService.ensureStarted(carContext)
            runtime.playbackController.warmUpPlaybackStack()
            val token = MyMusicService.currentCompatSessionToken()
            if (token != null) {
                playbackTokenRegistered = registerMediaPlaybackToken(token)
                if (playbackTokenRegistered) {
                    return
                }
            }
            handler.postDelayed(this, 250L)
        }
    }

    init {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> {
                        projectionSpeedSource = ProjectionCarHardwareSpeedSource(carContext)
                        runtime.setProjectionSpeedSource(projectionSpeedSource)
                        MyMusicService.ensureStarted(carContext)
                        handler.post(tokenRegistrationRunnable)
                    }

                    Lifecycle.Event.ON_DESTROY -> {
                        handler.removeCallbacks(tokenRegistrationRunnable)
                        runtime.setProjectionSpeedSource(null)
                        projectionSpeedSource = null
                        playbackTokenRegistered = false
                    }

                    else -> Unit
                }
            }
        )
    }

    override fun onCreateScreen(intent: Intent): Screen {
        if (!playbackTokenRegistered) {
            handler.post(tokenRegistrationRunnable)
        }
        return CarBrowseScreen(carContext)
    }

    @Suppress("DEPRECATION")
    private fun registerMediaPlaybackToken(token: MediaSessionCompat.Token): Boolean {
        return runCatching {
            val mediaPlaybackManager = carContext.getCarService(
                CarContext.MEDIA_PLAYBACK_SERVICE
            ) as MediaPlaybackManager
            mediaPlaybackManager.registerMediaPlaybackToken(token)
        }.isSuccess
    }
}

/** Host-reported cap on grid items, falling back to the library's conservative default. */
internal fun CarContext.gridContentLimit(): Int {
    return runCatching {
        getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
    }.getOrDefault(DEFAULT_GRID_CONTENT_LIMIT).coerceAtLeast(1)
}

private const val DEFAULT_GRID_CONTENT_LIMIT = 6
