package com.BalancedLight.dynamicdriving.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.settings.DynamicDrivingSettings
import com.BalancedLight.dynamicdriving.shared.settings.DynamicDrivingSettingsState
import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import com.BalancedLight.dynamicdriving.shared.settings.SpeedSourceSelection
import com.BalancedLight.dynamicdriving.shared.ui.description
import com.BalancedLight.dynamicdriving.shared.ui.label
import com.BalancedLight.dynamicdriving.shared.ui.permissions.AppPermissionRequest
import com.BalancedLight.dynamicdriving.shared.ui.permissions.openAppSettings
import com.BalancedLight.dynamicdriving.shared.ui.permissions.rememberPermissionUiState

@Composable
fun SettingsScreen(
    settings: DynamicDrivingSettingsState,
    permissions: List<AppPermissionRequest>,
    versionName: String,
    onSpeedSourceSelected: (SpeedSourceSelection) -> Unit,
    onManualSpeedChanged: (Double) -> Unit,
    onPlaybackPolicySelected: (PlaylistPlaybackPolicy) -> Unit,
    onLoopsBeforeAdvanceChanged: (Int) -> Unit,
    onBundledDemoEnabledChanged: (Boolean) -> Unit,
    onReplayOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsCard(stringResource(R.string.settings_speed_heading)) {
                Column(Modifier.selectableGroup()) {
                    SpeedSourceSelection.entries.forEach { selection ->
                        OptionRow(
                            title = selection.label(),
                            description = selection.description(),
                            selected = settings.speedSourceSelection == selection,
                            onSelect = { onSpeedSourceSelected(selection) }
                        )
                    }
                }

                // The manual slider only exists while Manual is the selected source; showing it
                // otherwise would imply it affects an automatic or live reading.
                if (settings.speedSourceSelection == SpeedSourceSelection.MANUAL) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_manual_speed),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_manual_speed_value,
                            settings.manualSpeedMph
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = settings.manualSpeedMph.toFloat(),
                        onValueChange = { onManualSpeedChanged(it.toDouble()) },
                        valueRange = DynamicDrivingSettings.MIN_MANUAL_SPEED_MPH.toFloat()..
                            DynamicDrivingSettings.MAX_MANUAL_SPEED_MPH.toFloat(),
                        steps = 0
                    )
                }
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_playback_heading)) {
                Column(Modifier.selectableGroup()) {
                    PlaylistPlaybackPolicy.entries.forEach { policy ->
                        OptionRow(
                            title = policy.label(),
                            description = policy.description(),
                            selected = settings.playbackPolicy == policy,
                            onSelect = { onPlaybackPolicySelected(policy) }
                        )
                    }
                }

                if (settings.playbackPolicy != PlaylistPlaybackPolicy.REPEAT_SONG) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.playback_loops_before_advance),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(
                            R.string.playback_loops_value,
                            settings.loopsBeforeAdvance
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = settings.loopsBeforeAdvance.toFloat(),
                        onValueChange = { onLoopsBeforeAdvanceChanged(it.toInt()) },
                        valueRange = DynamicDrivingSettings.MIN_LOOPS_BEFORE_ADVANCE.toFloat()..
                            DynamicDrivingSettings.MAX_LOOPS_BEFORE_ADVANCE.toFloat(),
                        steps = DynamicDrivingSettings.MAX_LOOPS_BEFORE_ADVANCE -
                            DynamicDrivingSettings.MIN_LOOPS_BEFORE_ADVANCE - 1
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = stringResource(R.string.settings_bundled_demo),
                    description = stringResource(R.string.settings_bundled_demo_description),
                    checked = settings.bundledDemoEnabled,
                    onCheckedChange = onBundledDemoEnabledChanged
                )
            }
        }

        if (permissions.isNotEmpty()) {
            item {
                SettingsCard(stringResource(R.string.settings_permissions_heading)) {
                    permissions.forEach { request ->
                        PermissionRow(request)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { context.openAppSettings() }) {
                        Text(stringResource(R.string.settings_permission_open_settings))
                    }
                }
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_about_heading)) {
                Text(
                    text = stringResource(R.string.settings_about_version, versionName),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_about_privacy_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_about_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_about_licence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onReplayOnboarding) {
                    Text(stringResource(R.string.settings_replay_onboarding))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRow(request: AppPermissionRequest) {
    val state = rememberPermissionUiState(request)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.isGranted) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (state.isGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(request.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        if (state.isGranted) {
                            R.string.settings_permission_granted
                        } else {
                            R.string.settings_permission_needed
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(request.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!state.isGranted) {
                TextButton(onClick = state::requestPermission) {
                    Text(stringResource(R.string.settings_permission_grant))
                }
            }
        }
    }
}
