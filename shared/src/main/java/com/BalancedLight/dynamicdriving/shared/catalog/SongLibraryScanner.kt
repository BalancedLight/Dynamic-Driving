package com.BalancedLight.dynamicdriving.shared.catalog

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader

/** Raised when a document tree cannot be opened at all (revoked, ejected, deleted). */
class LibraryRootUnavailableException(
    val treeUri: Uri,
    message: String
) : Exception(message)

/** One scanned root and everything that was learned about it. */
data class LibraryScanResult(
    val root: SongLibraryRoot?,
    val songs: List<SongManifest>,
    val diagnostics: List<SongLibraryDiagnostic>
)

internal class SongLibraryScanner(
    private val context: Context,
    private val parser: SongCatalogParser = SongCatalogParser()
) {
    companion object {
        const val BUNDLED_SONGS_ROOT = "songs"
        private const val MANIFEST_FILE_NAME = "song.json"
        private val ARTWORK_FILE_NAMES = setOf("cover.png", "cover.jpg", "cover.jpeg", "cover.webp")
    }

    /**
     * Scans the bundled demo plus an optional external tree.
     *
     * @throws LibraryRootUnavailableException when [selectedExternalTreeUri] cannot be opened.
     */
    fun scan(selectedExternalTreeUri: Uri?): SongLibraryState {
        val diagnostics = mutableListOf<SongLibraryDiagnostic>()
        val roots = mutableListOf<SongLibraryRoot>()
        val songsById = linkedMapOf<String, SongManifest>()

        selectedExternalTreeUri?.let { treeUri ->
            val external = scanDocumentTree(treeUri)
            external.root?.let(roots::add)
            diagnostics += external.diagnostics
            external.songs.forEach { song -> addSong(song, songsById, diagnostics) }
        }

        val bundled = scanBundledAssets()
        bundled.root?.let(roots::add)
        diagnostics += bundled.diagnostics
        bundled.songs.forEach { song -> addSong(song, songsById, diagnostics) }

        return SongLibraryState(
            songs = songsById.values.toList(),
            diagnostics = diagnostics.toList(),
            roots = roots.toList(),
            selectedExternalTreeUri = selectedExternalTreeUri,
            isRefreshing = false,
            lastRefreshTimestampMs = System.currentTimeMillis()
        )
    }

    fun scanBundledAssets(): LibraryScanResult {
        val root = SongLibraryRoot.BundledAssets(BUNDLED_SONGS_ROOT)
        val diagnostics = mutableListOf<SongLibraryDiagnostic>()
        val songs = mutableListOf<SongManifest>()
        val manifestPaths = mutableListOf<String>()
        runCatching { collectAssetManifestPaths(BUNDLED_SONGS_ROOT, manifestPaths) }
        manifestPaths.sorted().forEach { manifestPath ->
            runCatching {
                parseBundledManifest(root, manifestPath)
            }.onSuccess(songs::add).onFailure { error ->
                diagnostics += SongLibraryDiagnostic(
                    source = manifestPath,
                    message = error.message ?: "Unknown manifest parsing failure.",
                    kind = SongLibraryDiagnosticKind.MALFORMED_SONG
                )
            }
        }
        return LibraryScanResult(root = root, songs = songs, diagnostics = diagnostics)
    }

    /**
     * Scans one document tree.
     *
     * A tree that opens but holds no `song.json` is reported as a recoverable
     * [SongLibraryDiagnosticKind.EMPTY_FOLDER] diagnostic rather than a failure; only a tree that
     * cannot be opened at all raises [LibraryRootUnavailableException].
     */
    fun scanDocumentTree(treeUri: Uri): LibraryScanResult {
        val treeRoot = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        if (treeRoot == null || !treeRoot.isDirectory || !treeRoot.canRead()) {
            throw LibraryRootUnavailableException(
                treeUri = treeUri,
                message = "That folder could not be opened. It may have been moved, deleted, or had its permission revoked."
            )
        }

        val displayName = treeRoot.name?.ifBlank { null } ?: "External songs"
        val root = SongLibraryRoot.DocumentTree(treeUri = treeUri, displayName = displayName)
        val diagnostics = mutableListOf<SongLibraryDiagnostic>()
        val songs = mutableListOf<SongManifest>()
        val manifestEntries = mutableListOf<DocumentManifestEntry>()
        collectDocumentManifestFiles(
            directory = treeRoot,
            displayPath = displayName,
            results = manifestEntries
        )

        if (manifestEntries.isEmpty()) {
            diagnostics += SongLibraryDiagnostic(
                source = displayName,
                message = "No song.json files were found in this folder. Add one per song folder, then refresh.",
                kind = SongLibraryDiagnosticKind.EMPTY_FOLDER
            )
        }

        manifestEntries.forEach { entry ->
            runCatching {
                parseDocumentManifest(root, entry)
            }.onSuccess(songs::add).onFailure { error ->
                diagnostics += SongLibraryDiagnostic(
                    source = entry.manifestDisplayPath,
                    message = error.message ?: "Unknown manifest parsing failure.",
                    kind = SongLibraryDiagnosticKind.MALFORMED_SONG
                )
            }
        }
        return LibraryScanResult(root = root, songs = songs, diagnostics = diagnostics)
    }

    private fun parseBundledManifest(
        root: SongLibraryRoot.BundledAssets,
        manifestPath: String
    ): SongManifest {
        val manifestFile = SongFileRef.Asset(manifestPath)
        val folderPath = manifestPath.substringBeforeLast('/', "")
        val rawJson = context.assets.open(manifestPath).use { input ->
            BufferedReader(input.reader()).readText()
        }
        return parser.parse(
            rawJson = rawJson,
            context = SongManifestParsingContext(
                libraryRoot = root,
                manifestFile = manifestFile,
                resolveRelativeFile = { relativePath -> resolveAssetFile(folderPath, relativePath) },
                resolveArtworkFile = { resolveAssetArtwork(folderPath) }
            )
        )
    }

    /**
     * Walks the bundled asset tree looking for `song.json`.
     *
     * Asset managers differ in what `list()` returns: the device returns immediate children, while
     * some test and tooling implementations return paths relative to the queried directory. Matching
     * on the last path segment and recursing only into entries that themselves list children handles
     * both shapes without double-counting a manifest.
     */
    private fun collectAssetManifestPaths(
        currentDirectory: String,
        results: MutableList<String>
    ) {
        val children = context.assets.list(currentDirectory).orEmpty().sorted()
        children.forEach { child ->
            val childPath = if (currentDirectory.isBlank()) child else "$currentDirectory/$child"
            when {
                child.substringAfterLast('/').equals(MANIFEST_FILE_NAME, ignoreCase = true) ->
                    results += childPath

                context.assets.list(childPath).orEmpty().isNotEmpty() ->
                    collectAssetManifestPaths(childPath, results)
            }
        }
    }

    private fun resolveAssetFile(
        folderPath: String,
        relativePath: String
    ): SongFileRef? {
        val normalizedPath = normalizeRelativePath(relativePath) ?: return null
        val assetPath = if (folderPath.isBlank()) {
            normalizedPath
        } else {
            "$folderPath/$normalizedPath"
        }
        return if (assetExists(assetPath)) SongFileRef.Asset(assetPath) else null
    }

    private fun resolveAssetArtwork(folderPath: String): SongFileRef? {
        val artworkName = context.assets.list(folderPath).orEmpty().firstOrNull { candidate ->
            ARTWORK_FILE_NAMES.contains(candidate.lowercase())
        } ?: return null
        return SongFileRef.Asset("$folderPath/$artworkName")
    }

    private fun assetExists(assetPath: String): Boolean {
        return runCatching { context.assets.open(assetPath).use { } }.isSuccess
    }

    private fun collectDocumentManifestFiles(
        directory: DocumentFile,
        displayPath: String,
        results: MutableList<DocumentManifestEntry>
    ) {
        val children = runCatching {
            directory.listFiles().sortedBy { it.name.orEmpty().lowercase() }
        }.getOrDefault(emptyList())
        children.forEach { child ->
            when {
                child.isDirectory -> {
                    val childDisplayPath = "$displayPath/${child.name ?: child.uri.lastPathSegment.orEmpty()}"
                    collectDocumentManifestFiles(child, childDisplayPath, results)
                }

                child.name.equals(MANIFEST_FILE_NAME, ignoreCase = true) -> {
                    results += DocumentManifestEntry(
                        manifestFile = child,
                        folder = directory,
                        manifestDisplayPath = "$displayPath/${child.name}"
                    )
                }
            }
        }
    }

    private fun parseDocumentManifest(
        root: SongLibraryRoot.DocumentTree,
        entry: DocumentManifestEntry
    ): SongManifest {
        val manifestFile = SongFileRef.Content(
            uri = entry.manifestFile.uri,
            displayPath = entry.manifestDisplayPath
        )
        val rawJson = context.contentResolver.openInputStream(entry.manifestFile.uri)?.use { input ->
            BufferedReader(input.reader()).readText()
        } ?: error("Unable to open manifest ${entry.manifestDisplayPath}.")
        val folderDisplayPath = entry.manifestDisplayPath.substringBeforeLast('/')
        return parser.parse(
            rawJson = rawJson,
            context = SongManifestParsingContext(
                libraryRoot = root,
                manifestFile = manifestFile,
                resolveRelativeFile = { relativePath ->
                    resolveDocumentFile(entry.folder, folderDisplayPath, relativePath)
                },
                resolveArtworkFile = { resolveDocumentArtwork(entry.folder, folderDisplayPath) }
            )
        )
    }

    private fun resolveDocumentFile(
        folder: DocumentFile,
        folderDisplayPath: String,
        relativePath: String
    ): SongFileRef? {
        val normalizedPath = normalizeRelativePath(relativePath) ?: return null
        var current: DocumentFile? = folder
        normalizedPath.split('/').forEach { segment ->
            current = current?.findFile(segment)
            if (current == null) {
                return null
            }
        }
        val resolvedFile = current?.takeIf { it.isFile } ?: return null
        return SongFileRef.Content(
            uri = resolvedFile.uri,
            displayPath = "$folderDisplayPath/$normalizedPath"
        )
    }

    private fun resolveDocumentArtwork(
        folder: DocumentFile,
        folderDisplayPath: String
    ): SongFileRef? {
        val artwork = runCatching {
            folder.listFiles().firstOrNull { file ->
                file.isFile && ARTWORK_FILE_NAMES.contains(file.name?.lowercase())
            }
        }.getOrNull() ?: return null
        return SongFileRef.Content(
            uri = artwork.uri,
            displayPath = "$folderDisplayPath/${artwork.name}"
        )
    }

    private fun addSong(
        song: SongManifest,
        songsById: MutableMap<String, SongManifest>,
        diagnostics: MutableList<SongLibraryDiagnostic>
    ) {
        val existingSong = songsById[song.songId]
        if (existingSong != null) {
            diagnostics += SongLibraryDiagnostic(
                source = song.manifestFile.displayPath,
                message = "Duplicate songId '${song.songId}' ignored; already loaded from ${existingSong.manifestFile.displayPath}.",
                kind = SongLibraryDiagnosticKind.DUPLICATE_SONG
            )
            return
        }
        songsById[song.songId] = song
    }

    private fun normalizeRelativePath(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/').trim('/')
        if (normalized.isBlank()) {
            return null
        }
        val parts = normalized.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }
        return normalized
    }

    private data class DocumentManifestEntry(
        val manifestFile: DocumentFile,
        val folder: DocumentFile,
        val manifestDisplayPath: String
    )
}
