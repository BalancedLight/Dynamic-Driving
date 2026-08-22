package com.BalancedLight.dynamicdriving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSample
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PhoneLocationSpeedSource(
    context: Context
) : SpeedSource {
    companion object {
        private const val MPS_TO_MPH = 2.23693629
    }

    override val label: String = "Phone GPS speed"

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val _samples = MutableStateFlow(
        unavailableSample("$label unavailable")
    )

    override val samples: StateFlow<SpeedSample> = _samples

    private var started = false
    private val listener = LocationListener { location ->
        publishLocation(location)
    }

    override fun start() {
        if (started) {
            return
        }
        started = true
        if (!hasLocationPermission()) {
            _samples.value = unavailableSample("$label permission required")
            return
        }

        val providers = locationManager.allProviders
        var requestedUpdates = false
        providers.filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
            .forEach { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)?.let(::publishLocation)
                    locationManager.requestLocationUpdates(
                        provider,
                        1_000L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    requestedUpdates = true
                } catch (_: SecurityException) {
                    _samples.value = unavailableSample("$label permission required")
                } catch (_: Throwable) {
                }
            }

        if (!requestedUpdates) {
            _samples.value = unavailableSample("$label unavailable")
        }
    }

    override fun stop() {
        if (started) {
            try {
                locationManager.removeUpdates(listener)
            } catch (_: Throwable) {
            }
        }
        started = false
        _samples.value = unavailableSample("$label unavailable")
    }

    private fun publishLocation(location: Location) {
        val hasSpeed = location.hasSpeed()
        if (!hasSpeed) {
            publishWaitingForSpeedFix()
            return
        }
        _samples.value = SpeedSample(
            mph = location.speed.toDouble() * MPS_TO_MPH,
            sourceLabel = label,
            isLiveSource = true,
            available = true
        )
    }

    private fun publishWaitingForSpeedFix() {
        val current = _samples.value
        if (current.available) {
            _samples.value = current.copy(
                sourceLabel = "$label waiting for speed fix",
                isStale = false
            )
            return
        }
        _samples.value = unavailableSample("$label waiting for speed fix")
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun unavailableSample(reason: String): SpeedSample {
        return SpeedSample(
            mph = 0.0,
            sourceLabel = reason,
            isLiveSource = true,
            available = false
        )
    }
}
