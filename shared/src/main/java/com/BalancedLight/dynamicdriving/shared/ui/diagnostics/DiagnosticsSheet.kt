package com.BalancedLight.dynamicdriving.shared.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.playback.PlaybackDiagnostics
import com.BalancedLight.dynamicdriving.shared.playback.PlaybackUiState
import com.BalancedLight.dynamicdriving.shared.ui.formatDuration

/**
 * Stem and mixer internals, shown only in debug builds.
 *
 * The release build never constructs [PlaybackDiagnostics] at all, so this composable simply has
 * nothing to render there. Keeping it behind a collapsed button also stops it from getting in the
 * way of the normal UI while debugging.
 */
@Composable
fun DiagnosticsSheet(
    diagnostics: PlaybackDiagnostics,
    playback: PlaybackUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        if (expanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.diagnostics_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.diagnostics_debug_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.diagnostics_audio,
                            diagnostics.audioLoadDurationMs,
                            diagnostics.audioLoadSourceSummary,
                            diagnostics.activeAudioStemCount,
                            diagnostics.audioUnderrunCount
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(
                            R.string.diagnostics_mixer,
                            diagnostics.lastMixerRenderMs,
                            diagnostics.maxMixerRenderMs,
                            diagnostics.lastAudioWriteMs,
                            diagnostics.maxAudioWriteMs
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(
                            R.string.diagnostics_loop_window,
                            formatDuration(playback.loopStartMs),
                            formatDuration(playback.loopEndMs),
                            formatDuration(playback.nextLoopInMs)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(diagnostics.stems, key = { it.stemId }) { stem ->
                            Column {
                                Row {
                                    Text(
                                        text = stem.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stem.stateLabel,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append(
                                            stringResource(
                                                R.string.diagnostics_stem_line,
                                                stem.currentGain,
                                                stem.gainMultiplier,
                                                stem.reverbWetMix
                                            )
                                        )
                                        append(" · ")
                                        append(
                                            stringResource(
                                                if (stem.eligible) {
                                                    R.string.diagnostics_eligible
                                                } else {
                                                    R.string.diagnostics_not_eligible
                                                }
                                            )
                                        )
                                        if (stem.activeEvents.isNotEmpty()) {
                                            append(" · ")
                                            append(stem.activeEvents.joinToString())
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        SmallFloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = stringResource(R.string.diagnostics_title)
            )
        }
    }
}
