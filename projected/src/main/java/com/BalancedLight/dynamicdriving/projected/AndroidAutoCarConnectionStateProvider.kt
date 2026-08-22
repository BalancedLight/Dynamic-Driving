package com.BalancedLight.dynamicdriving.projected

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.BalancedLight.dynamicdriving.shared.speed.CarConnectionMode
import com.BalancedLight.dynamicdriving.shared.speed.CarConnectionStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidAutoCarConnectionStateProvider(
    context: Context
) : CarConnectionStateProvider {
    override val label: String = "Android Auto connection"

    private val appContext = context.applicationContext
    private val carConnection by lazy { CarConnection(appContext) }
    private val _connectionModes = MutableStateFlow(CarConnectionMode.NOT_CONNECTED)
    private var started = false

    override val connectionModes: StateFlow<CarConnectionMode> = _connectionModes

    private val typeObserver = Observer<Int> { connectionType ->
        _connectionModes.value = when (connectionType) {
            CarConnection.CONNECTION_TYPE_NATIVE -> CarConnectionMode.AAOS_NATIVE
            CarConnection.CONNECTION_TYPE_PROJECTION -> CarConnectionMode.ANDROID_AUTO_PROJECTION
            else -> CarConnectionMode.NOT_CONNECTED
        }
    }

    override fun start() {
        if (started) {
            return
        }
        started = true
        carConnection.type.observeForever(typeObserver)
    }

    override fun stop() {
        if (!started) {
            return
        }
        carConnection.type.removeObserver(typeObserver)
        started = false
        _connectionModes.value = CarConnectionMode.NOT_CONNECTED
    }
}
