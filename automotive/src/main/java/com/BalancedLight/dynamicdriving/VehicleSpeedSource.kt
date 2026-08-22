package com.BalancedLight.dynamicdriving

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSample
import com.BalancedLight.dynamicdriving.shared.speed.SpeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Native AAOS vehicle speed, read from `PERF_VEHICLE_SPEED`.
 *
 * The car property service pushes updates, so this subscribes rather than polling. Everything is
 * wrapped defensively: a head unit that withholds the property, or revokes the permission at
 * runtime, must degrade to "unavailable" rather than take playback down with it.
 */
class VehicleSpeedSource(
    private val context: Context
) : SpeedSource {
    companion object {
        private const val CAR_SPEED_PERMISSION = Car.PERMISSION_SPEED
        private const val MPS_TO_MPH = 2.23693629
        private const val UPDATE_RATE_HZ = 5f
    }

    override val label: String = "Vehicle speed"

    private val _samples = MutableStateFlow(unavailable("Vehicle speed is not available yet."))

    override val samples: StateFlow<SpeedSample> = _samples

    private var started = false
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                return
            }
            val speedMps = value.value as? Float ?: return
            _samples.value = SpeedSample(
                mph = speedMps.toDouble() * MPS_TO_MPH,
                sourceLabel = label,
                isLiveSource = true,
                available = true
            )
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            _samples.value = unavailable("The vehicle stopped reporting its speed.")
        }
    }

    override fun start() {
        if (started) {
            return
        }
        started = true
        if (ContextCompat.checkSelfPermission(context, CAR_SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            _samples.value = unavailable("Dynamic Driving needs the car speed permission to read vehicle speed.")
            return
        }
        val createdCar = runCatching { Car.createCar(context) }.getOrNull()
        if (createdCar == null) {
            _samples.value = unavailable("This device does not expose the car service.")
            return
        }
        car = createdCar
        val manager = runCatching {
            createdCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }.getOrNull()
        if (manager == null) {
            _samples.value = unavailable("This device does not expose vehicle properties.")
            return
        }
        propertyManager = manager
        val registered = runCatching {
            manager.registerCallback(
                propertyCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                UPDATE_RATE_HZ
            )
        }.getOrDefault(false)
        if (!registered) {
            _samples.value = unavailable("This vehicle does not report a speed property.")
        }
    }

    override fun stop() {
        runCatching { propertyManager?.unregisterCallback(propertyCallback) }
        runCatching { car?.disconnect() }
        propertyManager = null
        car = null
        started = false
        _samples.value = unavailable("Vehicle speed is not being read right now.")
    }

    private fun unavailable(reason: String) = SpeedSample(
        mph = 0.0,
        sourceLabel = label,
        isLiveSource = true,
        available = false,
        unavailableReason = reason
    )
}
