package com.BalancedLight.dynamicdriving.shared.playback

import com.BalancedLight.dynamicdriving.shared.catalog.BaseStemRule
import com.BalancedLight.dynamicdriving.shared.catalog.ComparisonOperator
import com.BalancedLight.dynamicdriving.shared.catalog.EventConditionManifest
import com.BalancedLight.dynamicdriving.shared.catalog.LoopRegion
import com.BalancedLight.dynamicdriving.shared.catalog.OverlayGroup
import com.BalancedLight.dynamicdriving.shared.catalog.OverlayStemRule
import com.BalancedLight.dynamicdriving.shared.catalog.SongCatalogParser
import com.BalancedLight.dynamicdriving.shared.catalog.SongFileRef
import com.BalancedLight.dynamicdriving.shared.catalog.SongLibraryRoot
import com.BalancedLight.dynamicdriving.shared.catalog.SongMuffleManifest
import com.BalancedLight.dynamicdriving.shared.catalog.SongManifestParsingContext
import com.BalancedLight.dynamicdriving.shared.catalog.StemEventManifest
import com.BalancedLight.dynamicdriving.shared.catalog.StemManifest
import com.BalancedLight.dynamicdriving.shared.catalog.StemModifierManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackRulesTest {
    @Test
    fun parser_supports_per_song_manifest_and_events() {
        val song = SongCatalogParser().parse(
            rawJson =
            """
            {
              "songId": "test_song",
              "displayName": "Test Song",
              "transportStemId": "guitar",
              "loopRegion": { "startMs": 1000, "endMs": 9000, "playTailOverLoop": true },
              "muffle": { "releaseMph": 1.0, "wetMix": 0.85, "cutoffHz": 900.0, "fadeMs": 1200 },
              "stems": [
                {
                  "stemId": "guitar",
                  "displayName": "Guitar",
                  "assetPath": "guitar.wav",
                                    "playTailOverLoop": false,
                  "gain": 1.0,
                  "fadeInMs": 1000,
                  "fadeOutMs": 1000,
                  "rule": { "type": "base" },
                  "events": [
                    {
                      "eventId": "drive_fx",
                      "displayName": "Drive FX",
                      "condition": {
                        "all": [
                          { "metric": "mph", "operator": "gt", "value": 5.0 },
                          { "metric": "mph", "operator": "lt", "value": 35.0 }
                        ]
                      },
                      "modifiers": [
                        { "type": "gainMultiplier", "multiplier": 0.5, "fadeMs": 1200 },
                        { "type": "reverb", "wetMix": 0.4, "feedback": 0.6, "damping": 0.3, "delayMs": 180.0 }
                      ]
                    }
                  ]
                },
                {
                  "stemId": "lead_blues",
                  "displayName": "Lead Blues",
                  "assetPath": "lead_blues.wav",
                  "gain": 0.8,
                  "fadeInMs": 1500,
                  "fadeOutMs": 1500,
                  "rule": {
                    "type": "overlay",
                    "minMph": 30.0,
                    "groupId": "lead",
                    "groupName": "Lead",
                    "durationMs": 20000,
                    "cooldownMinMs": 20000,
                    "cooldownMaxMs": 40000,
                    "weight": 2
                  }
                }
              ]
            }
            """.trimIndent(),
            context = parsingContext()
        )

        assertEquals("test_song", song.songId)
        assertEquals(2, song.stems.size)
        assertTrue(song.loopRegion.playTailOverLoop)
        assertNotNull(song.muffle)
        assertTrue(song.stems.any { it.rule is BaseStemRule })
        assertTrue(song.stems.any { it.rule is OverlayStemRule })

        val guitarStem = song.stems.first { it.stemId == "guitar" }
        assertEquals("songs/test/guitar.wav", guitarStem.audioFile.displayPath)
        assertFalse(guitarStem.playTailOverLoop)
        assertEquals(1, guitarStem.events.size)
        val event = guitarStem.events.first()
        assertEquals("drive_fx", event.eventId)
        assertTrue(event.condition is EventConditionManifest.All)
        assertTrue(event.modifiers.any { it is StemModifierManifest.GainMultiplier })
        assertTrue(event.modifiers.any { it is StemModifierManifest.Reverb })
        assertEquals(1.0, song.muffle!!.releaseMph, 0.0)

        val leadStem = song.stems.first { it.stemId == "lead_blues" }
        assertTrue(leadStem.playTailOverLoop)
    }

    @Test
    fun mood05_speed_bands_match_expected_stem_activation() {
        val idleRule = BaseStemRule()
        val bassRule = BaseStemRule(minMph = 5.0)
        val drumsRule = BaseStemRule(minMph = 30.0)

        assertTrue(PlaybackRules.shouldActivateBaseStem(idleRule, mph = 3.0, currentlyActive = false, hysteresisMph = 2.0))
        assertFalse(PlaybackRules.shouldActivateBaseStem(bassRule, mph = 3.0, currentlyActive = false, hysteresisMph = 2.0))
        assertFalse(PlaybackRules.shouldActivateBaseStem(drumsRule, mph = 3.0, currentlyActive = false, hysteresisMph = 2.0))

        assertTrue(PlaybackRules.shouldActivateBaseStem(idleRule, mph = 15.0, currentlyActive = true, hysteresisMph = 2.0))
        assertTrue(PlaybackRules.shouldActivateBaseStem(bassRule, mph = 15.0, currentlyActive = false, hysteresisMph = 2.0))
        assertFalse(PlaybackRules.shouldActivateBaseStem(drumsRule, mph = 15.0, currentlyActive = false, hysteresisMph = 2.0))

        assertTrue(PlaybackRules.shouldActivateBaseStem(idleRule, mph = 35.0, currentlyActive = true, hysteresisMph = 2.0))
        assertTrue(PlaybackRules.shouldActivateBaseStem(bassRule, mph = 35.0, currentlyActive = true, hysteresisMph = 2.0))
        assertTrue(PlaybackRules.shouldActivateBaseStem(drumsRule, mph = 35.0, currentlyActive = false, hysteresisMph = 2.0))
    }

    @Test
    fun hysteresis_prevents_threshold_chatter() {
        val rule = BaseStemRule(minMph = 30.0)

        assertTrue(PlaybackRules.shouldActivateBaseStem(rule, mph = 30.0, currentlyActive = false, hysteresisMph = 2.0))
        assertTrue(PlaybackRules.shouldActivateBaseStem(rule, mph = 28.5, currentlyActive = true, hysteresisMph = 2.0))
        assertFalse(PlaybackRules.shouldActivateBaseStem(rule, mph = 27.5, currentlyActive = true, hysteresisMph = 2.0))
    }

    @Test
    fun overlay_scheduler_enforces_duration_and_cooldown() {
        val overlayStem = buildOverlayStem()
        val started = OverlayGroupScheduler.tick(
            currentState = OverlayGroupState(),
            nowMs = 1_000L,
            eligibleCandidates = listOf(overlayStem),
            activeStem = null,
            forceStopActive = false,
            random = Random(0)
        )

        val startAction = started.action as OverlayGroupAction.Start
        assertEquals("blues", startAction.stem.stemId)
        assertEquals(21_000L, startAction.activeUntilMs)

        val stopped = OverlayGroupScheduler.tick(
            currentState = started.state,
            nowMs = 21_000L,
            eligibleCandidates = listOf(overlayStem),
            activeStem = overlayStem,
            forceStopActive = false,
            random = Random(0)
        )

        val stopAction = stopped.action as OverlayGroupAction.Stop
        assertEquals("blues", stopAction.stemId)
        assertTrue(stopAction.cooldownUntilMs > 21_000L)

        val coolingDown = OverlayGroupScheduler.tick(
            currentState = stopped.state,
            nowMs = stopAction.cooldownUntilMs - 1,
            eligibleCandidates = listOf(overlayStem),
            activeStem = null,
            forceStopActive = false,
            random = Random(0)
        )
        assertTrue(coolingDown.action is OverlayGroupAction.None)
    }

    @Test
    fun overlay_scheduler_stops_when_speed_drops_below_threshold() {
        val overlayStem = buildOverlayStem()
        val activeState = OverlayGroupState(
            activeStemId = overlayStem.stemId,
            activeUntilMs = 50_000L,
            cooldownUntilMs = 0L
        )

        val decision = OverlayGroupScheduler.tick(
            currentState = activeState,
            nowMs = 10_000L,
            eligibleCandidates = emptyList(),
            activeStem = overlayStem,
            forceStopActive = true,
            random = Random(0)
        )

        assertTrue(decision.action is OverlayGroupAction.Stop)
        assertEquals(null, decision.state.activeStemId)
    }

    @Test
    fun event_conditions_support_all_and_any_groups() {
        val condition = EventConditionManifest.All(
            conditions = listOf(
                EventConditionManifest.MphComparison(ComparisonOperator.GT, 5.0),
                EventConditionManifest.Any(
                    conditions = listOf(
                        EventConditionManifest.MphComparison(ComparisonOperator.LT, 15.0),
                        EventConditionManifest.MphComparison(ComparisonOperator.GTE, 35.0)
                    )
                )
            )
        )

        assertTrue(PlaybackRules.evaluateEventCondition(condition, mph = 10.0))
        assertFalse(PlaybackRules.evaluateEventCondition(condition, mph = 20.0))
        assertTrue(PlaybackRules.evaluateEventCondition(condition, mph = 40.0))
    }

    @Test
    fun idle_base_stem_effect_reaches_half_gain_at_stop_and_clears_when_moving() {
        val stopped = calculateIdleBaseStemEffect(smoothedSpeedMph = 0.0)
        assertEquals(0.5f, stopped.gainMultiplier)
        assertTrue(stopped.isActive)

        val rolling = calculateIdleBaseStemEffect(smoothedSpeedMph = 3.0)
        assertEquals(0.75f, rolling.gainMultiplier)
        assertTrue(rolling.isActive)

        val cruising = calculateIdleBaseStemEffect(smoothedSpeedMph = 8.0)
        assertEquals(1f, cruising.gainMultiplier)
        assertFalse(cruising.isActive)
    }

    @Test
    fun song_level_muffle_tapers_off_at_one_mph() {
        val muffle = SongMuffleManifest(releaseMph = 1.0, wetMix = 0.85f, cutoffHz = 900f, fadeMs = 1_200L)

        val stopped = calculateSongMuffleEffect(muffle, smoothedSpeedMph = 0.0)
        assertEquals(0.85f, stopped.muffleAmount)
        assertEquals(900f, stopped.muffleCutoffHz)
        assertTrue(stopped.isActive)

        val creeping = calculateSongMuffleEffect(muffle, smoothedSpeedMph = 0.5)
        assertTrue(creeping.muffleAmount in 0.4f..0.5f)
        assertTrue(creeping.muffleCutoffHz in 9_400f..9_500f)

        val cruising = calculateSongMuffleEffect(muffle, smoothedSpeedMph = 1.0)
        assertEquals(0f, cruising.muffleAmount)
        assertEquals(18_000f, cruising.muffleCutoffHz)
        assertFalse(cruising.isActive)
    }

    @Test
    fun loop_math_and_drift_correction_behave_as_expected() {
        val loop = LoopRegion(startMs = 5_000L, endMs = 35_000L)

        assertEquals(5_000L, PlaybackRules.loopRelativePosition(5_000L, loop))
        assertEquals(10_000L, PlaybackRules.loopRelativePosition(40_000L, loop))
        assertTrue(PlaybackRules.shouldCorrectDrift(10_000L, 10_200L, 80L))
        assertFalse(PlaybackRules.shouldCorrectDrift(10_000L, 10_050L, 80L))
    }

    @Test
    fun loop_drift_correction_treats_loop_boundary_as_continuous() {
        assertFalse(
            PlaybackRules.shouldCorrectLoopDrift(
                referencePositionMs = 50L,
                stemPositionMs = 29_950L,
                loopDurationMs = 30_000L,
                toleranceMs = 100L
            )
        )
        assertTrue(
            PlaybackRules.shouldCorrectLoopDrift(
                referencePositionMs = 50L,
                stemPositionMs = 28_000L,
                loopDurationMs = 30_000L,
                toleranceMs = 100L
            )
        )
    }

    @Test
    fun post_loop_tail_is_available_even_when_loop_overlay_tail_is_disabled() {
        val song = LoadedSongAudio(
            songId = "tail_test",
            sampleRate = 48_000,
            channelCount = 2,
            totalFrameCount = 240_000,
            loopStartFrame = 48_000,
            loopEndFrame = 192_000,
            playTailOverLoop = false,
            loopStartsSong = true,
            stems = emptyList()
        )

        assertEquals(48_000, song.postLoopFrameCount)
        assertEquals(192_000, song.playOutFrameCount)
        assertEquals(0, song.tailFrameCount)
    }

    @Test
    fun play_out_frames_continue_through_loop_before_tail() {
        val song = LoadedSongAudio(
            songId = "playout_test",
            sampleRate = 1_000,
            channelCount = 2,
            totalFrameCount = 5_000,
            loopStartFrame = 1_000,
            loopEndFrame = 3_000,
            playTailOverLoop = false,
            loopStartsSong = true,
            stems = emptyList()
        )

        assertEquals(4_000, song.playOutFrameCount)
        assertEquals(1_000, song.sourceFrameForPlayOutFrame(0))
        assertEquals(2_999, song.sourceFrameForPlayOutFrame(1_999))
        assertEquals(3_000, song.sourceFrameForPlayOutFrame(2_000))
        assertEquals(4_999, song.sourceFrameForPlayOutFrame(3_999))
        assertEquals(1_500, song.positionMsToPlayOutFrame(1_500))
    }

    private fun parsingContext(): SongManifestParsingContext {
        val root = SongLibraryRoot.BundledAssets("songs/test")
        return SongManifestParsingContext(
            libraryRoot = root,
            manifestFile = SongFileRef.Asset("songs/test/song.json"),
            resolveRelativeFile = { relativePath ->
                SongFileRef.Asset("songs/test/$relativePath")
            },
            resolveArtworkFile = {
                SongFileRef.Asset("songs/test/Cover.png")
            }
        )
    }

    private fun buildOverlayStem(): StemManifest {
        val rule = OverlayStemRule(
            minMph = 30.0,
            overlayGroup = OverlayGroup(groupId = "lead", displayName = "Lead"),
            durationMs = 20_000L,
            cooldownMinMs = 20_000L,
            cooldownMaxMs = 40_000L,
            weight = 1
        )
        val stem = StemManifest(
            stemId = "blues",
            displayName = "Blues",
            sourcePath = "Mood05_Blues.wav",
            audioFile = SongFileRef.Asset("songs/mood_05/Mood05_Blues.wav"),
            gain = 0.95f,
            fadeInMs = 1_500L,
            fadeOutMs = 1_500L,
            rule = rule,
            events = listOf(
                StemEventManifest(
                    eventId = "fx",
                    condition = EventConditionManifest.MphComparison(ComparisonOperator.GT, 5.0),
                    modifiers = listOf(StemModifierManifest.GainMultiplier(0.8f))
                )
            )
        )
        assertNotNull(stem)
        return stem
    }
}
