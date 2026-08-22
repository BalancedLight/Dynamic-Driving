package com.BalancedLight.dynamicdriving.shared.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Read-only provider that serves the bounded cover-art JPEGs produced by [SongArtworkStore].
 *
 * Car hosts run in a different process and cannot read the document-provider URIs granted to this
 * app, so browse trees hand them these URIs instead. The provider exposes nothing but downsampled
 * cover art for songs in the currently loaded library: no audio, no manifests, no file paths.
 */
class SongArtworkProvider : ContentProvider() {
    private val store: SongArtworkStore?
        get() = context?.let(SongArtworkStore::get)

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Artwork is read-only.")
        }
        val activeStore = store ?: throw FileNotFoundException("Artwork store unavailable.")
        val songId = activeStore.songIdFromUri(uri) ?: throw FileNotFoundException("Unknown artwork URI: $uri")
        return activeStore.openArtworkFile(songId)
            ?: throw FileNotFoundException("No artwork for song $songId")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val activeStore = store ?: return null
        val songId = activeStore.songIdFromUri(uri) ?: return null
        if (!activeStore.hasArtwork(songId)) {
            return null
        }
        val columns = projection?.toList()
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns.toTypedArray())
        val row: Array<Any?> = Array(columns.size) { index ->
            when (columns[index]) {
                OpenableColumns.DISPLAY_NAME -> "$songId.jpg"
                OpenableColumns.SIZE -> activeStore.artworkFileLength(songId)
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Artwork is read-only.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read-only.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Artwork is read-only.")
}
