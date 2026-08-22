package com.BalancedLight.dynamicdriving

import android.content.Context
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingApplication
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import com.BalancedLight.dynamicdriving.shared.speed.CarConnectionMode
import com.BalancedLight.dynamicdriving.shared.speed.StaticCarConnectionStateProvider

class DynamicDrivingAutomotiveApplication : DynamicDrivingApplication() {
    override fun configureRuntime(runtime: DynamicDrivingRuntime, context: Context) {
        runtime.setCarConnectionProvider(
            StaticCarConnectionStateProvider(CarConnectionMode.AAOS_NATIVE)
        )
        runtime.setAaosSpeedSource(VehicleSpeedSource(context))
    }
}
