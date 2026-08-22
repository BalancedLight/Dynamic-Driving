package com.BalancedLight.dynamicdriving.shared.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.scale
import com.BalancedLight.dynamicdriving.shared.catalog.SongFileRef
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Renders song cover art into small, app-owned JPEG files and hands out `content://` URIs for them.
 *
 * Android Auto and AAOS cannot read a document-provider URI that was granted only to this app, and
 * shipping full-size bitmaps across the binder is both slow and size-capped. Caching a bounded JPEG
 * per song and serving it through [SongArtworkProvider] avoids both problems.
 *
 * This is a process-wide singleton because [SongArtworkProvider] is created before
 * `Application.onCreate` and therefore cannot depend on the app runtime.
 */
class SongArtworkStore private constructor(context: Context) {
    companion object {
        const val MAX_ARTWORK_EDGE_PX: Int = 512
        private const val ARTWORK_JPEG_QUALITY = 85
        private const val CACHE_DIRECTORY_NAME = "song-artwork"
        private const val ARTWORK_PATH_SEGMENT = "song"

        @Volatile
        private var instance: SongArtworkStore? = null

        fun get(context: Context): SongArtworkStore {
            return instance ?: synchronized(this) {
                instance ?: SongArtworkStore(context.applicationContext).also { instance = it }
            }
        }

        fun authorityFor(context: Context): String = "${context.applicationContext.packageName}.artwork"
    }

    private val appContext = context.applicationContext
    private val authority = authorityFor(appContext)
    private val cacheDirectory = File(appContext.cacheDir, CACHE_DIRECTORY_NAME)
    private val sources = linkedMapOf<String, SongFileRef>()
    private val renderedFiles = linkedMapOf<String, File?>()

    /**
     * Replaces the set of songs this store can serve.
     *
     * Cached files for songs that are no longer in the library are deleted, which keeps the cache
     * proportional to the current library rather than to everything ever browsed.
     */
    fun setSources(newSources: Map<String, SongFileRef?>) {
        synchronized(this) {
            sources.clear()
            newSources.forEach { (songId, ref) ->
                if (ref != null) {
                    sources[songId] = ref
                }
            }
            renderedFiles.keys.retainAll(sources.keys)
        }
        pruneCacheDirectory(sources.keys)
    }

    /** A stable content URI for [songId], or null when the song has no cover art. */
    fun artworkUri(songId: String): Uri? {
        val hasSource = synchronized(this) { sources.containsKey(songId) }
        if (!hasSource) {
            return null
        }
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(ARTWORK_PATH_SEGMENT)
            .appendPath(songId)
            .build()
    }

    fun songIdFromUri(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != ARTWORK_PATH_SEGMENT) {
            return null
        }
        return segments[1].takeIf { it.isNotBlank() }
    }

    fun hasArtwork(songId: String): Boolean = synchronized(this) { sources.containsKey(songId) }

    /** Opens the cached JPEG for [songId], rendering it first if needed. */
    fun openArtworkFile(songId: String): ParcelFileDescriptor? {
        val file = renderArtworkFile(songId) ?: return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }

    fun artworkFileLength(songId: String): Long = renderArtworkFile(songId)?.length() ?: 0L

    /** Decodes the cached cover for in-app display. */
    fun loadBitmap(songId: String): Bitmap? {
        val file = renderArtworkFile(songId) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /**
     * Decodes the cached cover no larger than [maxEdgePx].
     *
     * Car templates carry their icons across a binder transaction with a hard size budget, so grid
     * art is decoded small rather than decoded large and scaled by the host.
     */
    fun loadBitmapScaled(songId: String, maxEdgePx: Int): Bitmap? {
        val file = renderArtworkFile(songId) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val scaled = scaleBitmapIfNeeded(decoded, maxEdgePx)
            if (scaled !== decoded) {
                decoded.recycle()
            }
            scaled
        }.getOrNull()
    }

    private fun renderArtworkFile(songId: String): File? {
        synchronized(this) {
            if (renderedFiles.containsKey(songId)) {
                val cached = renderedFiles[songId]
                if (cached != null && cached.exists()) {
                    return cached
                }
                renderedFiles.remove(songId)
            }
        }
        val source = synchronized(this) { sources[songId] } ?: return null
        val rendered = runCatching { writeScaledJpeg(songId, source) }.getOrNull()
        synchronized(this) {
            renderedFiles[songId] = rendered
        }
        return rendered
    }

    private fun writeScaledJpeg(songId: String, source: SongFileRef): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInputStream(source)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_ARTWORK_EDGE_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openInputStream(source)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        val scaled = scaleBitmapIfNeeded(decoded, MAX_ARTWORK_EDGE_PX)
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            if (scaled !== decoded) {
                decoded.recycle()
            }
            scaled.recycle()
            return null
        }
        val target = File(cacheDirectory, "${cacheFileName(songId)}.jpg")
        val written = runCatching {
            FileOutputStream(target).use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, output)
            }
        }.getOrDefault(false)
        if (scaled !== decoded) {
            decoded.recycle()
        }
        scaled.recycle()
        if (!written) {
            target.delete()
            return null
        }
        return target
    }

    private fun openInputStream(fileRef: SongFileRef): InputStream? = runCatching {
        when (fileRef) {
            is SongFileRef.Asset -> appContext.assets.open(fileRef.assetPath)
            is SongFileRef.Content -> appContext.contentResolver.openInputStream(fileRef.uri)
        }
    }.getOrNull()

    private fun pruneCacheDirectory(keepSongIds: Set<String>) {
        val keepFileNames = keepSongIds.map { "${cacheFileName(it)}.jpg" }.toSet()
        cacheDirectory.listFiles()?.forEach { file ->
            if (!keepFileNames.contains(file.name)) {
                file.delete()
            }
        }
    }

    private fun cacheFileName(songId: String): String {
        val sanitized = songId.map { character ->
            if (character.isLetterOrDigit() || character == '_' || character == '-') character else '_'
        }.joinToString("")
        return "${sanitized.take(48)}_${songId.hashCode().toUInt().toString(16)}"
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sampleSize = 1
        val largestEdge = max(width, height)
        while (largestEdge / sampleSize > maxEdgePx) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val largestEdge = max(bitmap.width, bitmap.height)
        if (largestEdge <= maxEdgePx) {
            return bitmap
        }
        val scale = maxEdgePx.toFloat() / largestEdge.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight)
    }
}
