package com.BalancedLight.dynamicdriving

import android.car.Car
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import com.BalancedLight.dynamicdriving.shared.R as SharedR
import com.BalancedLight.dynamicdriving.shared.ui.DynamicDrivingApp
import com.BalancedLight.dynamicdriving.shared.ui.DynamicDrivingAppConfig
import com.BalancedLight.dynamicdriving.shared.ui.permissions.AppPermissionRequest
import com.BalancedLight.dynamicdriving.shared.ui.theme.DynamicDrivingTheme

/**
 * Android Automotive OS settings surface.
 *
 * Browsing and playback in the car happen through the media template, so this activity is the
 * app's own screen for library, playlists, and settings.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val config = DynamicDrivingAppConfig(
            versionName = BuildConfig.VERSION_NAME,
            permissions = buildList {
                add(
                    AppPermissionRequest(
                        permission = Car.PERMISSION_SPEED,
                        titleRes = R.string.settings_permission_car_speed,
                        bodyRes = R.string.settings_permission_car_speed_body
                    )
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(
                        AppPermissionRequest(
                            permission = AppPermissionRequest.POST_NOTIFICATIONS,
                            titleRes = SharedR.string.settings_permission_notifications,
                            bodyRes = SharedR.string.settings_permission_notifications_body
                        )
                    )
                }
            },
            supportsFolderPicker = true
        )

        setContent {
            DynamicDrivingTheme {
                DynamicDrivingApp(config = config)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DynamicDrivingRuntime.require().setForegroundMonitoringActive(true)
    }

    override fun onStop() {
        DynamicDrivingRuntime.require().setForegroundMonitoringActive(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        DynamicDrivingRuntime.require().refreshLiveInputs()
    }
}
