package com.BalancedLight.dynamicdriving.shared.speed

import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedSourceRouterTest {

    @Test
    fun automatic_prefers_native_vehicle_speed_on_aaos() {
        val fixture = routerFixture(CarConnectionMode.AAOS_NATIVE)
        fixture.aaos.emit(42.0, available = true)
        fixture.gps.emit(11.0, available = true)

        fixture.router.setSelection(SpeedSourceSelection.AUTOMATIC)
        fixture.router.start()

        val state = awaitState(fixture.router) {
            it.resolvedSource == ResolvedSpeedSource.CAR_NATIVE && it.mph == 42.0
        }
        assertEquals(42.0, state.mph, 0.001)
    }

    @Test
    fun automatic_prefers_projected_car_speed_and_falls_back_to_gps() {
        val fixture = routerFixture(CarConnectionMode.ANDROID_AUTO_PROJECTION)
        fixture.projection.emit(58.0, available = true)
        fixture.gps.emit(31.0, available = true)

        fixture.router.setSelection(SpeedSourceSelection.AUTOMATIC)
        fixture.router.setForegroundMonitoringActive(true)
        fixture.router.start()

        val projectedState = awaitState(fixture.router) {
            it.resolvedSource == ResolvedSpeedSource.CAR_PROJECTED && it.mph == 58.0
        }
        assertEquals(58.0, projectedState.mph, 0.001)

        fixture.projection.emitUnavailable("Projected car speed unavailable")

        val gpsState = awaitState(fixture.router) {
            it.resolvedSource == ResolvedSpeedSource.PHONE_GPS && it.mph == 31.0
        }
        assertEquals(31.0, gpsState.mph, 0.001)
    }

    @Test
    fun automatic_on_an_unconnected_phone_uses_gps() {
        val fixture = routerFixture(CarConnectionMode.NOT_CONNECTED)
        fixture.gps.emit(24.0, available = true)

        fixture.router.setSelection(SpeedSourceSelection.AUTOMATIC)
        fixture.router.setForegroundMonitoringActive(true)
        fixture.router.start()

        awaitValue { fixture.router.effectiveState.value.sample.available }
        assertEquals(ResolvedSpeedSource.PHONE_GPS, fixture.router.effectiveState.value.resolvedSource)
        assertEquals(24.0, fixture.router.effectiveState.value.mph, 0.001)
        assertTrue(fixture.gps.startCount > 0)
    }

    @Test
    fun explicit_car_selection_never_falls_back_to_gps() {
        val fixture = routerFixture(CarConnectionMode.ANDROID_AUTO_PROJECTION)
        fixture.gps.emit(45.0, available = true)
        fixture.projection.emitUnavailable("Projected car speed permission required")

        fixture.router.setSelection(SpeedSourceSelection.CAR)
        fixture.router.setForegroundMonitoringActive(true)
        fixture.router.start()

        awaitValue {
            fixture.router.effectiveState.value.resolvedSource == ResolvedSpeedSource.CAR_PROJECTED
        }
        val state = fixture.router.effectiveState.value
        assertFalse(state.sample.available)
        assertEquals(0.0, state.mph, 0.0)
        assertNotNull(state.strictUnavailableExplanation)
        assertTrue(state.canOfferAutomaticFallback)
    }

    @Test
    fun explicit_car_selection_with_no_car_connected_reports_zero_and_explains() {
        val fixture = routerFixture(CarConnectionMode.NOT_CONNECTED)
        fixture.gps.emit(45.0, available = true)

        fixture.router.setSelection(SpeedSourceSelection.CAR)
        fixture.router.start()

        awaitValue { fixture.router.effectiveState.value.strictUnavailableExplanation != null }
        val state = fixture.router.effectiveState.value
        assertEquals(0.0, state.mph, 0.0)
        assertEquals(ResolvedSpeedSource.NONE, state.resolvedSource)
        assertTrue(
            state.strictUnavailableExplanation.orEmpty().contains("No car is connected")
        )
    }

    @Test
    fun explicit_phone_gps_selection_never_falls_back_to_car_speed() {
        val fixture = routerFixture(CarConnectionMode.ANDROID_AUTO_PROJECTION)
        fixture.projection.emit(70.0, available = true)
        fixture.gps.emitUnavailable("Phone GPS speed permission required")

        fixture.router.setSelection(SpeedSourceSelection.PHONE_GPS)
        fixture.router.setForegroundMonitoringActive(true)
        fixture.router.start()

        awaitValue { fixture.router.effectiveState.value.resolvedSource == ResolvedSpeedSource.PHONE_GPS }
        val state = fixture.router.effectiveState.value
        assertFalse(state.sample.available)
        assertEquals(0.0, state.mph, 0.0)
        assertNotNull(state.strictUnavailableExplanation)
    }

    @Test
    fun manual_selection_is_always_available_and_never_reports_unavailable() {
        val fixture = routerFixture(CarConnectionMode.NOT_CONNECTED)
        fixture.manual.setMph(37.0)

        fixture.router.setSelection(SpeedSourceSelection.MANUAL)
        fixture.router.start()

        awaitValue { fixture.router.effectiveState.value.resolvedSource == ResolvedSpeedSource.MANUAL }
        val state = fixture.router.effectiveState.value
        assertEquals(37.0, state.mph, 0.001)
        assertTrue(state.sample.available)
        assertNull(state.strictUnavailableExplanation)
    }

    @Test
    fun a_live_sample_that_stops_updating_goes_stale_and_reports_zero() {
        var now = 100_000L
        val manual = ManualSpeedSource()
        val router = SpeedSourceRouter(
            manualSource = manual,
            liveSampleStaleTimeoutMs = 1_000L,
            staleCheckIntervalMs = 100L,
            clockMs = { now }
        )
        val gps = FakeSpeedSource(SpeedSourceRouter.GPS_LABEL)
        router.setConnectionProvider(MutableConnectionProvider(CarConnectionMode.NOT_CONNECTED))
        router.setGpsSource(gps)
        router.setSelection(SpeedSourceSelection.PHONE_GPS)
        router.setForegroundMonitoringActive(true)
        router.start()

        gps.emit(30.0, available = true, timestampMs = now)
        awaitValue { router.effectiveState.value.mph == 30.0 }

        now += 5_000L

        awaitValue { router.effectiveState.value.sample.isStale }
        val state = router.effectiveState.value
        assertEquals(0.0, state.mph, 0.0)
        assertFalse(state.sample.available)
        assertNotNull(state.strictUnavailableExplanation)
    }

    @Test
    fun gps_only_runs_while_playback_or_a_foreground_screen_needs_it() {
        val fixture = routerFixture(CarConnectionMode.NOT_CONNECTED)
        fixture.router.setSelection(SpeedSourceSelection.PHONE_GPS)
        fixture.router.start()

        assertEquals(0, fixture.gps.startCount)

        fixture.router.setPlaybackDemandActive(true)
        awaitValue { fixture.gps.startCount == 1 }

        fixture.router.setPlaybackDemandActive(false)
        awaitValue { fixture.gps.stopCount == 1 }

        fixture.router.setForegroundMonitoringActive(true)
        awaitValue { fixture.gps.startCount == 2 }
    }

    private fun routerFixture(connectionMode: CarConnectionMode): RouterFixture {
        val manual = ManualSpeedSource()
        val router = SpeedSourceRouter(manual)
        val aaos = FakeSpeedSource(SpeedSourceRouter.AAOS_LABEL)
        val projection = FakeSpeedSource(SpeedSourceRouter.PROJECTION_LABEL)
        val gps = FakeSpeedSource(SpeedSourceRouter.GPS_LABEL)
        router.setConnectionProvider(MutableConnectionProvider(connectionMode))
        router.setAaosSource(aaos)
        router.setProjectionSource(projection)
        router.setGpsSource(gps)
        return RouterFixture(router, manual, aaos, projection, gps)
    }

    private data class RouterFixture(
        val router: SpeedSourceRouter,
        val manual: ManualSpeedSource,
        val aaos: FakeSpeedSource,
        val projection: FakeSpeedSource,
        val gps: FakeSpeedSource
    )

    private fun awaitValue(
        timeoutMs: Long = 3_000L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(10L)
        }
        throw AssertionError("Timed out waiting for expected value.")
    }

    private fun awaitState(
        router: SpeedSourceRouter,
        timeoutMs: Long = 3_000L,
        condition: (EffectiveSpeedState) -> Boolean
    ): EffectiveSpeedState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = router.effectiveState.value
            if (condition(state)) {
                return state
            }
            Thread.sleep(10L)
        }
        throw AssertionError("Timed out waiting for expected speed state.")
    }

    private class MutableConnectionProvider(
        initialMode: CarConnectionMode
    ) : CarConnectionStateProvider {
        override val label: String = "Test connection"
        private val _connectionModes = MutableStateFlow(initialMode)
        override val connectionModes: StateFlow<CarConnectionMode> = _connectionModes

        override fun start() = Unit

        override fun stop() = Unit

        fun setMode(mode: CarConnectionMode) {
            _connectionModes.value = mode
        }
    }

    private class FakeSpeedSource(
        override val label: String
    ) : SpeedSource {
        private val _samples = MutableStateFlow(
            SpeedSample(
                mph = 0.0,
                sourceLabel = label,
                isLiveSource = true,
                available = false,
                unavailableReason = "$label unavailable"
            )
        )

        override val samples: StateFlow<SpeedSample> = _samples
        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set

        override fun start() {
            startCount += 1
        }

        override fun stop() {
            stopCount += 1
        }

        fun emit(
            mph: Double,
            available: Boolean,
            timestampMs: Long = System.currentTimeMillis()
        ) {
            _samples.value = SpeedSample(
                mph = mph,
                sourceLabel = label,
                isLiveSource = true,
                available = available,
                timestampMs = timestampMs
            )
        }

        fun emitUnavailable(reason: String) {
            _samples.value = SpeedSample(
                mph = 0.0,
                sourceLabel = label,
                isLiveSource = true,
                available = false,
                unavailableReason = reason
            )
        }
    }
}
