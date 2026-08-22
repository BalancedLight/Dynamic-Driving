package com.BalancedLight.dynamicdriving.shared

import android.app.Application
import android.content.Context

open class DynamicDrivingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicDrivingRuntime.initialize(this)
        configureRuntime(DynamicDrivingRuntime.require(), this)
    }

    protected open fun configureRuntime(runtime: DynamicDrivingRuntime, context: Context) = Unit
}
