package com.BalancedLight.dynamicdriving

import android.Manifest
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
 * Phone and Android Auto host activity.
 *
 * Permissions are explained in Settings and requested only when the user asks for them there or in
 * onboarding; nothing is requested eagerly at launch.
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
                        permission = Manifest.permission.ACCESS_FINE_LOCATION,
                        titleRes = R.string.settings_permission_location,
                        bodyRes = R.string.settings_permission_location_body
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
        // A permission may have been granted from the system settings screen while we were away.
        DynamicDrivingRuntime.require().refreshLiveInputs()
    }
}
