package com.BalancedLight.dynamicdriving.shared.playback

import com.BalancedLight.dynamicdriving.shared.catalog.BaseStemRule
import com.BalancedLight.dynamicdriving.shared.catalog.ComparisonOperator
import com.BalancedLight.dynamicdriving.shared.catalog.EventConditionManifest
import com.BalancedLight.dynamicdriving.shared.catalog.LoopRegion
import com.BalancedLight.dynamicdriving.shared.catalog.OverlayStemRule
import com.BalancedLight.dynamicdriving.shared.catalog.StemManifest
import kotlin.math.abs
import kotlin.random.Random

object PlaybackRules {
    fun shouldActivateBaseStem(
        rule: BaseStemRule,
        mph: Double,
        currentlyActive: Boolean,
        hysteresisMph: Double
    ): Boolean {
        val min = rule.minMph ?: Double.NEGATIVE_INFINITY
        val max = rule.maxMphExclusive ?: Double.POSITIVE_INFINITY
        val lowerBound = if (currentlyActive) min - hysteresisMph else min
        val upperBound = if (currentlyActive) max + hysteresisMph else max
        return mph >= lowerBound && mph < upperBound
    }

    fun isOverlayEligible(
        rule: OverlayStemRule,
        mph: Double,
        currentlyActive: Boolean,
        hysteresisMph: Double
    ): Boolean {
        val lowerBound = if (currentlyActive) rule.minMph - hysteresisMph else rule.minMph
        val upperBound = if (currentlyActive) {
            (rule.maxMphExclusive ?: Double.POSITIVE_INFINITY) + hysteresisMph
        } else {
            rule.maxMphExclusive ?: Double.POSITIVE_INFINITY
        }
        return mph >= lowerBound && mph < upperBound
    }

    fun loopRelativePosition(positionMs: Long, loopRegion: LoopRegion): Long {
        if (positionMs <= loopRegion.startMs) return loopRegion.startMs
        val loopDuration = loopRegion.durationMs
        val distanceIntoLoop = (positionMs - loopRegion.startMs) % loopDuration
        return loopRegion.startMs + distanceIntoLoop
    }

    fun chooseWeightedOverlay(
        candidates: List<StemManifest>,
        random: Random
    ): StemManifest? {
        if (candidates.isEmpty()) return null
        val totalWeight = candidates.sumOf {
            val rule = it.rule as OverlayStemRule
            rule.weight.coerceAtLeast(1)
        }
        if (totalWeight <= 0) return candidates.first()
        var draw = random.nextInt(totalWeight)
        candidates.forEach { stem ->
            draw -= (stem.rule as OverlayStemRule).weight.coerceAtLeast(1)
            if (draw < 0) {
                return stem
            }
        }
        return candidates.last()
    }

    fun nextCooldownMs(rule: OverlayStemRule, random: Random): Long {
        if (rule.cooldownMaxMs <= rule.cooldownMinMs) {
            return rule.cooldownMinMs
        }
        return random.nextLong(rule.cooldownMinMs, rule.cooldownMaxMs + 1)
    }

    fun shouldCorrectDrift(referencePositionMs: Long, stemPositionMs: Long, toleranceMs: Long): Boolean {
        return abs(referencePositionMs - stemPositionMs) > toleranceMs
    }

    fun shouldCorrectLoopDrift(
        referencePositionMs: Long,
        stemPositionMs: Long,
        loopDurationMs: Long,
        toleranceMs: Long
    ): Boolean {
        if (loopDurationMs <= 0L) {
            return shouldCorrectDrift(referencePositionMs, stemPositionMs, toleranceMs)
        }
        val reference = referencePositionMs.floorMod(loopDurationMs)
        val stem = stemPositionMs.floorMod(loopDurationMs)
        val directDistance = abs(reference - stem)
        val wrappedDistance = loopDurationMs - directDistance
        return minOf(directDistance, wrappedDistance) > toleranceMs
    }

    fun evaluateEventCondition(
        condition: EventConditionManifest,
        mph: Double
    ): Boolean {
        return when (condition) {
            is EventConditionManifest.All -> condition.conditions.all { child ->
                evaluateEventCondition(child, mph)
            }

            is EventConditionManifest.Any -> condition.conditions.any { child ->
                evaluateEventCondition(child, mph)
            }

            is EventConditionManifest.MphComparison -> compare(mph, condition.operator, condition.value)
        }
    }

    private fun compare(
        actual: Double,
        operator: ComparisonOperator,
        expected: Double
    ): Boolean {
        return when (operator) {
            ComparisonOperator.GT -> actual > expected
            ComparisonOperator.GTE -> actual >= expected
            ComparisonOperator.LT -> actual < expected
            ComparisonOperator.LTE -> actual <= expected
            ComparisonOperator.EQ -> actual == expected
            ComparisonOperator.NEQ -> actual != expected
        }
    }

    private fun Long.floorMod(divisor: Long): Long {
        val remainder = this % divisor
        return if (remainder < 0L) remainder + divisor else remainder
    }
}
