package com.BalancedLight.dynamicdriving.shared.ui.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * One runtime permission the app can explain and request.
 *
 * Each app flavour decides which of these exist on the device it is running on, so this type stays
 * free of version checks.
 */
data class AppPermissionRequest(
    val permission: String,
    val titleRes: Int,
    val bodyRes: Int
) {
    companion object {
        /**
         * Declared as a literal because the app supports API 28, where the constant does not exist.
         * Requesting an unknown permission on an older release is simply a no-op.
         */
        const val POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"
    }
}

class PermissionUiState internal constructor(
    val request: AppPermissionRequest,
    val isGranted: Boolean,
    private val launch: () -> Unit
) {
    fun requestPermission() = launch()
}

/**
 * Tracks whether [request] is granted and offers a launcher for it.
 *
 * The grant state is re-read on every resume so returning from the system settings screen updates
 * the row without the user having to do anything else.
 */
@Composable
fun rememberPermissionUiState(request: AppPermissionRequest): PermissionUiState {
    val context = LocalContext.current
    var isGranted by remember(request.permission) {
        mutableStateOf(context.isPermissionGranted(request.permission))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isGranted = granted
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, request.permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isGranted = context.isPermissionGranted(request.permission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return PermissionUiState(
        request = request,
        isGranted = isGranted,
        launch = { launcher.launch(request.permission) }
    )
}

fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { startActivity(intent) }
}
