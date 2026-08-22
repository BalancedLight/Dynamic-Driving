package com.BalancedLight.dynamicdriving.shared.catalog

import org.json.JSONArray
import org.json.JSONObject

class SongCatalogParser {
    fun parse(
        rawJson: String,
        context: SongManifestParsingContext
    ): SongManifest {
        val songJson = JSONObject(rawJson)
        val stemsJson = songJson.getJSONArray("stems")
        val stems = buildList {
            for (index in 0 until stemsJson.length()) {
                add(parseStem(stemsJson.getJSONObject(index), context))
            }
        }
        require(stems.isNotEmpty()) {
            "Song manifest must contain at least one stem."
        }

        val transportStemId = songJson.getString("transportStemId")
        require(stems.any { it.stemId == transportStemId }) {
            "Transport stem '$transportStemId' is missing for song ${songJson.getString("songId")}."
        }

        return SongManifest(
            songId = songJson.getString("songId"),
            displayName = songJson.getString("displayName"),
            artist = songJson.optNullableString("artist"),
            album = songJson.optNullableString("album"),
            transportStemId = transportStemId,
            loopRegion = parseLoopRegion(songJson.getJSONObject("loopRegion")),
            muffle = songJson.optJSONObject("muffle")?.let(::parseMuffle),
            stems = stems,
            libraryRoot = context.libraryRoot,
            manifestFile = context.manifestFile,
            artworkFile = context.resolveArtworkFile()
        )
    }

    private fun parseLoopRegion(loopJson: JSONObject): LoopRegion {
        return LoopRegion(
            startMs = loopJson.getLong("startMs"),
            endMs = loopJson.getLong("endMs"),
            playTailOverLoop = loopJson.optBoolean("playTailOverLoop", false),
            loopStartsSong = loopJson.optBoolean("loopStartsSong", true)
        )
    }

    private fun parseMuffle(muffleJson: JSONObject): SongMuffleManifest {
        return SongMuffleManifest(
            releaseMph = muffleJson.optDouble("releaseMph", 1.0).coerceAtLeast(0.0),
            wetMix = muffleJson.optDouble("wetMix", 0.85).toFloat().coerceIn(0f, 1f),
            cutoffHz = muffleJson.optDouble("cutoffHz", 300.0).toFloat().coerceIn(20f, 18_000f),
            fadeMs = muffleJson.optLong("fadeMs", 1_200L).coerceAtLeast(1L)
        )
    }

    private fun parseStem(
        stemJson: JSONObject,
        context: SongManifestParsingContext
    ): StemManifest {
        val sourcePath = stemJson.getString("assetPath")
        val audioFile = context.resolveRelativeFile(sourcePath)
            ?: error("Stem asset '$sourcePath' could not be resolved from ${context.manifestFile.displayPath}.")
        val eventsJson = stemJson.optJSONArray("events")
        return StemManifest(
            stemId = stemJson.getString("stemId"),
            displayName = stemJson.getString("displayName"),
            sourcePath = sourcePath,
            audioFile = audioFile,
            playTailOverLoop = stemJson.optBoolean("playTailOverLoop", true),
            gain = stemJson.optDouble("gain", 1.0).toFloat(),
            fadeInMs = stemJson.optLong("fadeInMs", 1_500L),
            fadeOutMs = stemJson.optLong("fadeOutMs", 1_500L),
            rule = parseRule(stemJson.getJSONObject("rule")),
            events = parseEvents(eventsJson)
        )
    }

    private fun parseRule(ruleJson: JSONObject): StemActivationRule {
        return when (ruleJson.getString("type")) {
            "base" -> BaseStemRule(
                minMph = ruleJson.optNullableDouble("minMph"),
                maxMphExclusive = ruleJson.optNullableDouble("maxMphExclusive")
            )

            "overlay" -> OverlayStemRule(
                minMph = ruleJson.getDouble("minMph"),
                maxMphExclusive = ruleJson.optNullableDouble("maxMphExclusive"),
                overlayGroup = OverlayGroup(
                    groupId = ruleJson.getString("groupId"),
                    displayName = ruleJson.optString("groupName", ruleJson.getString("groupId"))
                ),
                durationMs = ruleJson.optLong("durationMs", 20_000L),
                cooldownMinMs = ruleJson.optLong("cooldownMinMs", 20_000L),
                cooldownMaxMs = ruleJson.optLong("cooldownMaxMs", 40_000L),
                weight = ruleJson.optInt("weight", 1)
            )

            else -> error("Unsupported rule type: ${ruleJson.getString("type")}")
        }
    }

    private fun parseEvents(eventsJson: JSONArray?): List<StemEventManifest> {
        if (eventsJson == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until eventsJson.length()) {
                val eventJson = eventsJson.getJSONObject(index)
                val modifiers = parseModifiers(eventJson.getJSONArray("modifiers"))
                require(modifiers.isNotEmpty()) {
                    "Stem event must contain at least one modifier."
                }
                add(
                    StemEventManifest(
                        eventId = eventJson.optString("eventId", "event_${index + 1}"),
                        displayName = eventJson.optString("displayName").ifBlank { null },
                        condition = parseCondition(eventJson.getJSONObject("condition")),
                        modifiers = modifiers
                    )
                )
            }
        }
    }

    private fun parseCondition(conditionJson: JSONObject): EventConditionManifest {
        conditionJson.optJSONArray("all")?.let { array ->
            return EventConditionManifest.All(
                conditions = parseConditionArray(array)
            )
        }
        conditionJson.optJSONArray("any")?.let { array ->
            return EventConditionManifest.Any(
                conditions = parseConditionArray(array)
            )
        }

        val metric = conditionJson.optString("metric", "mph")
        require(metric.equals("mph", ignoreCase = true)) {
            "Unsupported event condition metric '$metric'."
        }
        return EventConditionManifest.MphComparison(
            operator = parseOperator(conditionJson.getString("operator")),
            value = conditionJson.getDouble("value")
        )
    }

    private fun parseConditionArray(array: JSONArray): List<EventConditionManifest> {
        return buildList {
            for (index in 0 until array.length()) {
                add(parseCondition(array.getJSONObject(index)))
            }
        }
    }

    private fun parseOperator(rawOperator: String): ComparisonOperator {
        return when (rawOperator.lowercase()) {
            "gt", ">" -> ComparisonOperator.GT
            "gte", ">=" -> ComparisonOperator.GTE
            "lt", "<" -> ComparisonOperator.LT
            "lte", "<=" -> ComparisonOperator.LTE
            "eq", "==" -> ComparisonOperator.EQ
            "neq", "!=" -> ComparisonOperator.NEQ
            else -> error("Unsupported comparison operator '$rawOperator'.")
        }
    }

    private fun parseModifiers(modifiersJson: JSONArray): List<StemModifierManifest> {
        return buildList {
            for (index in 0 until modifiersJson.length()) {
                val modifierJson = modifiersJson.getJSONObject(index)
                add(parseModifier(modifierJson))
            }
        }
    }

    private fun parseModifier(modifierJson: JSONObject): StemModifierManifest {
        return when (modifierJson.getString("type")) {
            "gainMultiplier" -> StemModifierManifest.GainMultiplier(
                multiplier = modifierJson.optDouble("multiplier", modifierJson.optDouble("value", 1.0))
                    .toFloat()
                    .coerceAtLeast(0f),
                fadeMs = modifierJson.optLong("fadeMs", 1_500L)
            )

            "reverb" -> StemModifierManifest.Reverb(
                wetMix = modifierJson.optDouble("wetMix", 0.35).toFloat().coerceIn(0f, 1f),
                feedback = modifierJson.optDouble("feedback", 0.55).toFloat().coerceIn(0f, 0.98f),
                damping = modifierJson.optDouble("damping", 0.35).toFloat().coerceIn(0f, 0.98f),
                delayMs = modifierJson.optDouble("delayMs", 140.0).toFloat().coerceIn(20f, 750f),
                fadeMs = modifierJson.optLong("fadeMs", 1_500L)
            )

            else -> error("Unsupported stem modifier type: ${modifierJson.getString("type")}")
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        return if (has(key) && !isNull(key)) getDouble(key) else null
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return getString(key).trim().ifBlank { null }
    }
}

data class SongManifestParsingContext(
    val libraryRoot: SongLibraryRoot,
    val manifestFile: SongFileRef,
    val resolveRelativeFile: (String) -> SongFileRef?,
    val resolveArtworkFile: () -> SongFileRef?
)
