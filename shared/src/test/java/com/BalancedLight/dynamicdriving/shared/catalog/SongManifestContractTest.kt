package com.BalancedLight.dynamicdriving.shared.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the shared contract corpus in `contracts/fixtures` and asserts the canonical projection
 * recorded in `expected.json`.
 *
 * The C# editor runs the identical assertions against the identical files, so a parser change on
 * one side that is not mirrored on the other fails a build.
 */
class SongManifestContractTest {
    private val parser = SongCatalogParser()
    private val fixturesDirectory = ContractFixtures.directory()
    private val expected = JSONObject(File(fixturesDirectory, "expected.json").readText())
        .getJSONObject("fixtures")

    @Test
    fun every_fixture_matches_the_shared_expected_projection() {
        val fixtureNames = expected.keys().asSequence().toList()
        assertTrue("Expected corpus should not be empty", fixtureNames.isNotEmpty())

        fixtureNames.forEach { fixtureName ->
            val manifest = parseFixture(fixtureName)
            val expectation = expected.getJSONObject(fixtureName)

            assertEquals(fixtureName, expectation.getString("songId"), manifest.songId)
            assertEquals(fixtureName, expectation.getString("displayName"), manifest.displayName)
            assertEquals(fixtureName, expectation.optNullableString("artist"), manifest.artist)
            assertEquals(fixtureName, expectation.optNullableString("album"), manifest.album)
            assertEquals(
                fixtureName,
                expectation.getString("transportStemId"),
                manifest.transportStemId
            )

            val expectedLoop = expectation.getJSONObject("loop")
            assertEquals(fixtureName, expectedLoop.getLong("startMs"), manifest.loopRegion.startMs)
            assertEquals(fixtureName, expectedLoop.getLong("endMs"), manifest.loopRegion.endMs)
            assertEquals(
                fixtureName,
                expectedLoop.getBoolean("playTailOverLoop"),
                manifest.loopRegion.playTailOverLoop
            )
            assertEquals(
                fixtureName,
                expectedLoop.getBoolean("loopStartsSong"),
                manifest.loopRegion.loopStartsSong
            )

            assertMuffle(fixtureName, expectation, manifest.muffle)
            assertStems(fixtureName, expectation, manifest)
        }
    }

    @Test
    fun missing_artist_and_album_stay_null_rather_than_becoming_placeholders() {
        val manifest = parseFixture("minimal.json")

        assertNull(manifest.artist)
        assertNull(manifest.album)
    }

    @Test
    fun gain_multiplier_above_unity_survives_parsing() {
        val manifest = parseFixture("events.json")
        val modifier = manifest.stems.single().events[0].modifiers[0]
            as StemModifierManifest.GainMultiplier

        assertEquals(1.75f, modifier.multiplier, 1e-6f)
    }

    @Test
    fun blank_artist_and_album_are_treated_as_absent() {
        val manifest = parser.parse(
            rawJson = """
                {
                  "songId": "blank_metadata",
                  "displayName": "Blank Metadata",
                  "artist": "   ",
                  "album": "",
                  "transportStemId": "main",
                  "loopRegion": { "startMs": 0, "endMs": 1000 },
                  "stems": [
                    { "stemId": "main", "displayName": "Main", "assetPath": "main.wav", "rule": { "type": "base" } }
                  ]
                }
            """.trimIndent(),
            context = parsingContext()
        )

        assertNull(manifest.artist)
        assertNull(manifest.album)
    }

    @Test
    fun unknown_properties_do_not_break_parsing() {
        val manifest = parseFixture("unknown-properties.json")

        assertNotNull(manifest)
        assertEquals("solo", manifest.transportStemId)
        assertEquals(1, manifest.stems.size)
    }

    private fun assertMuffle(
        fixtureName: String,
        expectation: JSONObject,
        actual: SongMuffleManifest?
    ) {
        if (expectation.isNull("muffle")) {
            assertNull(fixtureName, actual)
            return
        }
        val expectedMuffle = expectation.getJSONObject("muffle")
        assertNotNull(fixtureName, actual)
        val muffle = requireNotNull(actual)
        assertEquals(fixtureName, expectedMuffle.getDouble("releaseMph"), muffle.releaseMph, 1e-6)
        assertEquals(
            fixtureName,
            expectedMuffle.getDouble("wetMix").toFloat(),
            muffle.wetMix,
            1e-6f
        )
        assertEquals(
            fixtureName,
            expectedMuffle.getDouble("cutoffHz").toFloat(),
            muffle.cutoffHz,
            1e-6f
        )
        assertEquals(fixtureName, expectedMuffle.getLong("fadeMs"), muffle.fadeMs)
    }

    private fun assertStems(
        fixtureName: String,
        expectation: JSONObject,
        manifest: SongManifest
    ) {
        val expectedStems = expectation.getJSONArray("stems")
        assertEquals(fixtureName, expectedStems.length(), manifest.stems.size)

        for (index in 0 until expectedStems.length()) {
            val expectedStem = expectedStems.getJSONObject(index)
            val stem = manifest.stems[index]
            val label = "$fixtureName[${stem.stemId}]"

            assertEquals(label, expectedStem.getString("stemId"), stem.stemId)
            assertEquals(label, expectedStem.getString("displayName"), stem.displayName)
            assertEquals(label, expectedStem.getString("assetPath"), stem.sourcePath)
            assertEquals(
                label,
                expectedStem.getBoolean("playTailOverLoop"),
                stem.playTailOverLoop
            )
            assertEquals(label, expectedStem.getDouble("gain").toFloat(), stem.gain, 1e-6f)
            assertEquals(label, expectedStem.getLong("fadeInMs"), stem.fadeInMs)
            assertEquals(label, expectedStem.getLong("fadeOutMs"), stem.fadeOutMs)
            assertEquals(label, expectedStem.getInt("eventCount"), stem.events.size)

            when (expectedStem.getString("ruleType")) {
                "base" -> {
                    val rule = stem.rule as BaseStemRule
                    assertEquals(label, expectedStem.optNullableDouble("minMph"), rule.minMph)
                    assertEquals(
                        label,
                        expectedStem.optNullableDouble("maxMphExclusive"),
                        rule.maxMphExclusive
                    )
                    assertNull(label, expectedStem.optNullableString("groupId"))
                }

                "overlay" -> {
                    val rule = stem.rule as OverlayStemRule
                    assertEquals(label, expectedStem.getDouble("minMph"), rule.minMph, 1e-6)
                    assertEquals(
                        label,
                        expectedStem.optNullableDouble("maxMphExclusive"),
                        rule.maxMphExclusive
                    )
                    assertEquals(
                        label,
                        expectedStem.getString("groupId"),
                        rule.overlayGroup.groupId
                    )
                }

                else -> error("Unsupported rule type in $label")
            }
        }
    }

    private fun parseFixture(fixtureName: String): SongManifest {
        val rawJson = File(fixturesDirectory, fixtureName).readText()
        return parser.parse(rawJson = rawJson, context = parsingContext())
    }

    /**
     * Resolves stem paths without touching the filesystem: the corpus deliberately ships no audio,
     * so every referenced path resolves to a synthetic asset reference.
     */
    private fun parsingContext() = SongManifestParsingContext(
        libraryRoot = SongLibraryRoot.BundledAssets("fixtures"),
        manifestFile = SongFileRef.Asset("fixtures/song.json"),
        resolveRelativeFile = { relativePath -> SongFileRef.Asset("fixtures/$relativePath") },
        resolveArtworkFile = { null }
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (isNull(key)) null else getDouble(key)
}

/** Locates `contracts/fixtures` regardless of which directory the test runner starts in. */
internal object ContractFixtures {
    fun directory(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            val fixtures = File(candidate, "contracts/fixtures")
            if (fixtures.isDirectory) {
                return fixtures
            }
            candidate = candidate.parentFile
        }
        error("Could not locate contracts/fixtures from ${File(".").absolutePath}")
    }
}
