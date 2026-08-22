package com.BalancedLight.dynamicdriving.shared.playback

import com.BalancedLight.dynamicdriving.shared.catalog.OverlayStemRule
import com.BalancedLight.dynamicdriving.shared.catalog.StemManifest
import kotlin.random.Random

data class OverlayGroupState(
    val activeStemId: String? = null,
    val activeUntilMs: Long = 0L,
    val cooldownUntilMs: Long = 0L
)

sealed interface OverlayGroupAction {
    data object None : OverlayGroupAction
    data class Start(val stem: StemManifest, val activeUntilMs: Long) : OverlayGroupAction
    data class Stop(val stemId: String, val cooldownUntilMs: Long) : OverlayGroupAction
}

data class OverlayGroupDecision(
    val state: OverlayGroupState,
    val action: OverlayGroupAction
)

object OverlayGroupScheduler {
    fun tick(
        currentState: OverlayGroupState,
        nowMs: Long,
        eligibleCandidates: List<StemManifest>,
        activeStem: StemManifest?,
        forceStopActive: Boolean,
        random: Random
    ): OverlayGroupDecision {
        if (currentState.activeStemId != null && activeStem != null) {
            val overlayRule = activeStem.rule as OverlayStemRule
            if (forceStopActive || nowMs >= currentState.activeUntilMs) {
                val cooldownUntilMs = nowMs + PlaybackRules.nextCooldownMs(overlayRule, random)
                return OverlayGroupDecision(
                    state = currentState.copy(
                        activeStemId = null,
                        activeUntilMs = 0L,
                        cooldownUntilMs = cooldownUntilMs
                    ),
                    action = OverlayGroupAction.Stop(activeStem.stemId, cooldownUntilMs)
                )
            }
            return OverlayGroupDecision(currentState, OverlayGroupAction.None)
        }

        if (nowMs < currentState.cooldownUntilMs) {
            return OverlayGroupDecision(currentState, OverlayGroupAction.None)
        }

        val selectedStem = PlaybackRules.chooseWeightedOverlay(eligibleCandidates, random)
            ?: return OverlayGroupDecision(currentState, OverlayGroupAction.None)
        val overlayRule = selectedStem.rule as OverlayStemRule
        val activeUntilMs = nowMs + overlayRule.durationMs
        return OverlayGroupDecision(
            state = OverlayGroupState(
                activeStemId = selectedStem.stemId,
                activeUntilMs = activeUntilMs,
                cooldownUntilMs = currentState.cooldownUntilMs
            ),
            action = OverlayGroupAction.Start(selectedStem, activeUntilMs)
        )
    }
}
