package com.BalancedLight.dynamicdriving.projected

import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSample
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProjectionCarHardwareSpeedSource(
    private val carContext: CarContext
) : SpeedSource {
    companion object {
        private const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        private const val MPS_TO_MPH = 2.23693629
    }

    override val label: String = "Projected car speed"

    private val _samples = MutableStateFlow(unavailableSample("$label unavailable"))
    override val samples: StateFlow<SpeedSample> = _samples

    private val carInfo by lazy {
        carContext.getCarService(CarHardwareManager::class.java).carInfo
    }
    private var started = false
    private val listener = OnCarDataAvailableListener<Speed> { speed ->
        val rawSpeed = speed.rawSpeedMetersPerSecond
        val displaySpeed = speed.displaySpeedMetersPerSecond
        val carSpeedMps = when {
            rawSpeed.status == CarValue.STATUS_SUCCESS -> rawSpeed.value
            displaySpeed.status == CarValue.STATUS_SUCCESS -> displaySpeed.value
            else -> null
        }
        if (carSpeedMps != null) {
            _samples.value = SpeedSample(
                mph = carSpeedMps.toDouble() * MPS_TO_MPH,
                sourceLabel = label,
                isLiveSource = true,
                available = true
            )
        } else {
            _samples.value = unavailableSample("$label unavailable")
        }
    }

    override fun start() {
        if (started) {
            return
        }
        started = true
        if (!hasCarSpeedPermission()) {
            _samples.value = unavailableSample("$label permission required")
            return
        }
        try {
            carInfo.addSpeedListener(carContext.mainExecutor, listener)
        } catch (_: Throwable) {
            _samples.value = unavailableSample("$label unavailable")
        }
    }

    override fun stop() {
        if (started) {
            try {
                carInfo.removeSpeedListener(listener)
            } catch (_: Throwable) {
            }
        }
        started = false
        _samples.value = unavailableSample("$label unavailable")
    }

    private fun hasCarSpeedPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            carContext,
            CAR_SPEED_PERMISSION
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
