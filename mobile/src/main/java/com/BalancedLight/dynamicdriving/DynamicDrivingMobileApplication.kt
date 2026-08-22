package com.BalancedLight.dynamicdriving

import com.BalancedLight.dynamicdriving.shared.DynamicDrivingApplication
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import com.BalancedLight.dynamicdriving.projected.AndroidAutoCarConnectionStateProvider

class DynamicDrivingMobileApplication : DynamicDrivingApplication() {
    override fun configureRuntime(runtime: DynamicDrivingRuntime, context: android.content.Context) {
        runtime.setCarConnectionProvider(AndroidAutoCarConnectionStateProvider(context))
        runtime.setPhoneGpsSpeedSource(PhoneLocationSpeedSource(context))
    }
}
