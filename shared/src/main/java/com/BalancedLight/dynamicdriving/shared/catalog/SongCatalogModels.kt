package com.BalancedLight.dynamicdriving.shared.catalog

import android.net.Uri
import androidx.core.net.toUri

data class SongManifest(
    val songId: String,
    val displayName: String,
    val artist: String? = null,
    val album: String? = null,
    val transportStemId: String,
    val loopRegion: LoopRegion,
    val muffle: SongMuffleManifest? = null,
    val stems: List<StemManifest>,
    val libraryRoot: SongLibraryRoot,
    val manifestFile: SongFileRef,
    val artworkFile: SongFileRef? = null
) {
    val transportStem: StemManifest
        get() = stems.first { it.stemId == transportStemId }
}

data class LoopRegion(
    val startMs: Long,
    val endMs: Long,
    val playTailOverLoop: Boolean = false,
    val loopStartsSong: Boolean = true
) {
    init {
        require(endMs > startMs) { "Loop end must be greater than loop start." }
    }

    val durationMs: Long
        get() = endMs - startMs
}

data class StemManifest(
    val stemId: String,
    val displayName: String,
    val sourcePath: String,
    val audioFile: SongFileRef,
    val playTailOverLoop: Boolean = true,
    val gain: Float,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val rule: StemActivationRule,
    val events: List<StemEventManifest> = emptyList()
)

sealed interface StemActivationRule

data class BaseStemRule(
    val minMph: Double? = null,
    val maxMphExclusive: Double? = null
) : StemActivationRule

data class OverlayStemRule(
    val minMph: Double,
    val maxMphExclusive: Double? = null,
    val overlayGroup: OverlayGroup,
    val durationMs: Long,
    val cooldownMinMs: Long,
    val cooldownMaxMs: Long,
    val weight: Int = 1
) : StemActivationRule

data class OverlayGroup(
    val groupId: String,
    val displayName: String
)

data class SongMuffleManifest(
    val releaseMph: Double = 1.0,
    val wetMix: Float = 0.85f,
    val cutoffHz: Float = 300f,
    val fadeMs: Long = 1_200L
)

data class StemEventManifest(
    val eventId: String,
    val displayName: String? = null,
    val condition: EventConditionManifest,
    val modifiers: List<StemModifierManifest>
)

sealed interface EventConditionManifest {
    data class All(
        val conditions: List<EventConditionManifest>
    ) : EventConditionManifest

    data class Any(
        val conditions: List<EventConditionManifest>
    ) : EventConditionManifest

    data class MphComparison(
        val operator: ComparisonOperator,
        val value: Double
    ) : EventConditionManifest
}

enum class ComparisonOperator {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    NEQ
}

sealed interface StemModifierManifest {
    data class GainMultiplier(
        val multiplier: Float,
        val fadeMs: Long = 1_500L
    ) : StemModifierManifest

    data class Reverb(
        val wetMix: Float,
        val feedback: Float = 0.55f,
        val damping: Float = 0.35f,
        val delayMs: Float = 140f,
        val fadeMs: Long = 1_500L
    ) : StemModifierManifest
}

sealed interface SongLibraryRoot {
    val rootId: String
    val displayName: String

    data class BundledAssets(
        val assetRootPath: String
    ) : SongLibraryRoot {
        override val rootId: String = "asset:$assetRootPath"
        override val displayName: String = "Bundled demo"
    }

    data class DocumentTree(
        val treeUri: Uri,
        override val displayName: String
    ) : SongLibraryRoot {
        override val rootId: String = treeUri.toString()
    }
}

sealed interface SongFileRef {
    val displayPath: String
    val uri: Uri

    data class Asset(
        val assetPath: String
    ) : SongFileRef {
        override val displayPath: String = assetPath
        override val uri: Uri
            get() = "asset:///$assetPath".toUri()
    }

    data class Content(
        override val uri: Uri,
        override val displayPath: String
    ) : SongFileRef
}

enum class SongLibraryDiagnosticKind {
    EMPTY_FOLDER,
    MALFORMED_SONG,
    DUPLICATE_SONG,
    UNAVAILABLE_ROOT
}

data class SongLibraryDiagnostic(
    val source: String,
    val message: String,
    val kind: SongLibraryDiagnosticKind = SongLibraryDiagnosticKind.MALFORMED_SONG
)

data class SongLibraryState(
    val songs: List<SongManifest> = emptyList(),
    val diagnostics: List<SongLibraryDiagnostic> = emptyList(),
    val roots: List<SongLibraryRoot> = emptyList(),
    val selectedExternalTreeUri: Uri? = null,
    val isRefreshing: Boolean = false,
    val lastRefreshTimestampMs: Long = 0L,
    val generation: Long = 0L
) {
    val songOptions: List<SongOption>
        get() = songs.map {
            SongOption(
                songId = it.songId,
                displayName = it.displayName,
                artist = it.artist,
                album = it.album
            )
        }

    val rootSummary: String
        get() = if (roots.isEmpty()) {
            "No song libraries loaded"
        } else {
            roots.joinToString(" + ") { it.displayName }
        }

    val externalRoot: SongLibraryRoot.DocumentTree?
        get() = roots.filterIsInstance<SongLibraryRoot.DocumentTree>().firstOrNull()
}

data class SongOption(
    val songId: String,
    val displayName: String,
    val artist: String? = null,
    val album: String? = null
)

/** Outcome of a transactional library-root change. */
sealed interface LibraryChangeResult {
    /**
     * The new root was scanned successfully and is now the active library.
     * [diagnostics] may still be non-empty (empty folders, malformed songs) — those are
     * recoverable and are surfaced in the UI rather than aborting the switch.
     */
    data class Success(
        val root: SongLibraryRoot?,
        val songCount: Int,
        val diagnostics: List<SongLibraryDiagnostic>
    ) : LibraryChangeResult

    /** The candidate root could not be adopted. The previous library is untouched. */
    data class Failed(
        val reason: LibraryChangeFailure,
        val message: String
    ) : LibraryChangeResult

    /** A newer change request superseded this one; nothing was applied for this call. */
    data object Superseded : LibraryChangeResult
}

enum class LibraryChangeFailure {
    /** The document provider refused to grant persistable read access. */
    PERMISSION_DENIED,

    /** The tree URI does not resolve to a readable directory (revoked, ejected, deleted). */
    UNAVAILABLE,

    /** Scanning threw an unexpected provider/parser/IO failure. */
    SCAN_FAILED
}
