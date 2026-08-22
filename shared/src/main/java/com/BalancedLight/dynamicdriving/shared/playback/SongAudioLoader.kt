package com.BalancedLight.dynamicdriving.shared.playback

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import com.BalancedLight.dynamicdriving.shared.catalog.SongFileRef
import com.BalancedLight.dynamicdriving.shared.catalog.SongManifest
import com.BalancedLight.dynamicdriving.shared.catalog.StemManifest
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.roundToInt

internal fun requireCompatibleStems(
    songId: String,
    loadedStems: List<LoadedStemAudio>
): LoadedStemAudio {
    val firstStem = loadedStems.firstOrNull()
        ?: error("Song '$songId' must contain at least one stem.")
    loadedStems.forEach { loadedStem ->
        require(loadedStem.sampleRate == firstStem.sampleRate) {
            "All stems must share the same sample rate."
        }
        require(loadedStem.channelCount == firstStem.channelCount) {
            "All stems must share the same channel count."
        }
        require(loadedStem.bitsPerSample == firstStem.bitsPerSample) {
            "All stems must share the same bit depth."
        }
        require(loadedStem.frameCount == firstStem.frameCount) {
            "Stem '${loadedStem.stemId}' has ${loadedStem.frameCount} frames; " +
                "all stems must match '${firstStem.stemId}' (${firstStem.frameCount} frames)."
        }
    }
    return firstStem
}

internal class SongAudioLoader(
    private val context: Context,
    private val pcmStemCache: PcmStemCache = PcmStemCache(context)
) {
    fun load(song: SongManifest): LoadedSongAudio {
        val loadStartedAtMs = SystemClock.elapsedRealtime()
        val loadedStems = song.stems.map { stem -> loadStem(song.songId, stem) }
        val firstStem = requireCompatibleStems(song.songId, loadedStems)
        val totalFrameCount = firstStem.frameCount

        val loopStartFrame = msToFrame(song.loopRegion.startMs, firstStem.sampleRate)
        val loopEndFrame = msToFrame(song.loopRegion.endMs, firstStem.sampleRate)
        require(loopStartFrame >= 0) { "Loop start must be non-negative." }
        require(loopEndFrame > loopStartFrame) { "Loop end must be after loop start." }
        require(loopEndFrame <= totalFrameCount) {
            "Loop end exceeds the stem length."
        }

        val warmFrames = linkedSetOf(if (song.loopRegion.loopStartsSong) loopStartFrame else 0, loopStartFrame)
        if (totalFrameCount > 0) {
            warmFrames += loopEndFrame.coerceIn(0, totalFrameCount - 1)
        }
        loadedStems.forEach { stem ->
            warmFrames.forEach(stem::warmFrame)
        }

        val loadDurationMs = SystemClock.elapsedRealtime() - loadStartedAtMs
        return LoadedSongAudio(
            songId = song.songId,
            sampleRate = firstStem.sampleRate,
            channelCount = firstStem.channelCount,
            totalFrameCount = totalFrameCount,
            loopStartFrame = loopStartFrame,
            loopEndFrame = loopEndFrame,
            playTailOverLoop = song.loopRegion.playTailOverLoop,
            loopStartsSong = song.loopRegion.loopStartsSong,
            stems = loadedStems,
            loadDiagnostics = SongAudioLoadDiagnostics(
                loadDurationMs = loadDurationMs,
                stemSources = loadedStems.map { loadedStem ->
                    StemAudioLoadDiagnostic(
                        stemId = loadedStem.stemId,
                        source = loadedStem.loadSource
                    )
                }
            )
        )
    }

    private fun loadStem(songId: String, stem: StemManifest): LoadedStemAudio {
        val header = openInputStream(stem.audioFile).use(WavPcmReader::parseHeader)
        require(header.audioFormat == WAV_FORMAT_PCM) {
            "Only PCM WAV assets are supported."
        }
        require(header.bitsPerSample == 16) {
            "Only 16-bit PCM WAV assets are supported."
        }
        val sourceMetadata = sourceMetadata(stem.audioFile)
        val cachedStem = if (stem.audioFile is SongFileRef.Content) {
            val cacheKey = PcmStemCache.cacheKey(
                songId = songId,
                stemId = stem.stemId,
                displayPath = stem.audioFile.displayPath,
                uri = stem.audioFile.uri.toString(),
                sourceMetadata = sourceMetadata
            )
            val metadata = CachedPcmStemMetadata(
                sampleRate = header.sampleRate,
                channelCount = header.channelCount,
                bitsPerSample = header.bitsPerSample,
                frameCount = header.frameCount,
                sampleCount = header.sampleCount,
                sourceLength = sourceMetadata.length,
                sourceLastModified = sourceMetadata.lastModified
            )
            pcmStemCache.entry(cacheKey, metadata)?.let { entry ->
                CachedStemResult(
                    sampleData = FileWindowSampleData(entry.pcmFile, header.sampleCount),
                    source = StemAudioLoadSource.CONTENT_CACHE
                )
            } ?: run {
                val entry = openInputStream(stem.audioFile).use { input ->
                    pcmStemCache.writeFromWav(cacheKey, metadata, input, header)
                }
                CachedStemResult(
                    sampleData = FileWindowSampleData(entry.pcmFile, header.sampleCount),
                    source = StemAudioLoadSource.CONTENT_SOURCE
                )
            }
        } else {
            val samples = openInputStream(stem.audioFile).use { input ->
                WavPcmReader.readPcm16Samples(input, header)
            }
            CachedStemResult(
                sampleData = ShortArraySampleData(samples),
                source = StemAudioLoadSource.ASSET_SOURCE
            )
        }
        return LoadedStemAudio(
            stemId = stem.stemId,
            sampleRate = header.sampleRate,
            channelCount = header.channelCount,
            bitsPerSample = header.bitsPerSample,
            frameCount = header.frameCount,
            playTailOverLoop = stem.playTailOverLoop,
            sampleData = cachedStem.sampleData,
            loadSource = cachedStem.source
        )
    }

    private fun msToFrame(positionMs: Long, sampleRate: Int): Int {
        return ((positionMs.toDouble() * sampleRate.toDouble()) / 1000.0).roundToInt()
    }

    private companion object {
        private const val WAV_FORMAT_PCM = 1
    }

    private fun openInputStream(fileRef: SongFileRef): InputStream {
        return when (fileRef) {
            is SongFileRef.Asset -> context.assets.open(fileRef.assetPath)
            is SongFileRef.Content -> context.contentResolver.openInputStream(fileRef.uri)
                ?: error("Unable to open ${fileRef.displayPath}.")
        }
    }

    private fun openAssetFileDescriptor(fileRef: SongFileRef): AssetFileDescriptor? {
        return when (fileRef) {
            is SongFileRef.Asset -> context.assets.openFd(fileRef.assetPath)
            is SongFileRef.Content -> context.contentResolver.openAssetFileDescriptor(fileRef.uri, "r")
        }
    }

    private fun sourceMetadata(fileRef: SongFileRef): AudioSourceMetadata {
        val descriptorLength = runCatching {
            openAssetFileDescriptor(fileRef)?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        if (fileRef !is SongFileRef.Content) {
            return AudioSourceMetadata(length = descriptorLength ?: -1L, lastModified = -1L)
        }

        var queriedLength: Long? = null
        var queriedLastModified: Long? = null
        val projection = arrayOf(
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        runCatching {
            context.contentResolver.query(fileRef.uri, projection, null, null, null)?.use { cursor ->
                queriedLength = cursor.getOptionalLong(OpenableColumns.SIZE)
                queriedLastModified = cursor.getOptionalLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            }
        }
        return AudioSourceMetadata(
            length = descriptorLength ?: queriedLength ?: -1L,
            lastModified = queriedLastModified ?: -1L
        )
    }

    private fun Cursor.getOptionalLong(columnName: String): Long? {
        val columnIndex = getColumnIndex(columnName)
        return if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else null
    }

    private data class CachedStemResult(
        val sampleData: SampleData,
        val source: StemAudioLoadSource
    )
}

internal object WavPcmReader {
    fun parseHeader(stream: InputStream): WavHeader {
        val input = BufferedInputStream(stream)
        val riffHeader = readExactly(input, 12)
        require(riffHeader.decodeToString(0, 4) == "RIFF") { "Invalid WAV header." }
        require(riffHeader.decodeToString(8, 12) == "WAVE") { "Invalid WAV type." }

        var channelCount = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataOffset = 12L

        while (true) {
            val chunkHeader = readExactly(input, 8)
            val chunkId = chunkHeader.decodeToString(0, 4)
            val chunkSize = littleEndianInt(chunkHeader, 4)
            require(chunkSize >= 0) { "Invalid WAV chunk size." }
            dataOffset += 8L
            when (chunkId) {
                "fmt " -> {
                    val fmtChunk = readExactly(input, chunkSize)
                    audioFormat = littleEndianShort(fmtChunk, 0)
                    channelCount = littleEndianShort(fmtChunk, 2)
                    sampleRate = littleEndianInt(fmtChunk, 4)
                    bitsPerSample = littleEndianShort(fmtChunk, 14)
                }

                "data" -> {
                    require(channelCount > 0 && sampleRate > 0 && bitsPerSample > 0) {
                        "fmt chunk must precede data chunk."
                    }
                    val bytesPerFrame = channelCount * (bitsPerSample / 8)
                    require(bytesPerFrame > 0) { "Invalid WAV frame size." }
                    require(chunkSize % 2 == 0) { "PCM data must contain whole 16-bit samples." }
                    return WavHeader(
                        audioFormat = audioFormat,
                        channelCount = channelCount,
                        sampleRate = sampleRate,
                        bitsPerSample = bitsPerSample,
                        dataOffset = dataOffset,
                        dataSize = chunkSize,
                        frameCount = chunkSize / bytesPerFrame
                    )
                }

                else -> skipExactly(input, chunkSize.toLong())
            }

            dataOffset += chunkSize.toLong()
            if (chunkSize % 2 == 1) {
                skipExactly(input, 1)
                dataOffset += 1L
            }
        }
    }

    fun readPcm16Samples(stream: InputStream, header: WavHeader): ShortArray {
        require(header.bitsPerSample == 16) { "Only 16-bit PCM data can be read into ShortArray samples." }
        val input = BufferedInputStream(stream)
        skipExactly(input, header.dataOffset)
        val sampleCount = header.sampleCount
        val samples = ShortArray(sampleCount)
        val buffer = ByteArray(16 * 1024)
        var sampleIndex = 0
        while (sampleIndex < sampleCount) {
            val bytesToRead = minOf(buffer.size, (sampleCount - sampleIndex) * Short.SIZE_BYTES)
            readFully(input, buffer, 0, bytesToRead)
            var byteIndex = 0
            while (byteIndex < bytesToRead) {
                samples[sampleIndex++] = littleEndianShort(buffer, byteIndex).toShort()
                byteIndex += Short.SIZE_BYTES
            }
        }
        return samples
    }

    fun copyPcm16Data(stream: InputStream, header: WavHeader, output: BufferedOutputStream) {
        val input = BufferedInputStream(stream)
        skipExactly(input, header.dataOffset)
        var remaining = header.dataSize
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val bytesToRead = minOf(buffer.size, remaining)
            readFully(input, buffer, 0, bytesToRead)
            output.write(buffer, 0, bytesToRead)
            remaining -= bytesToRead
        }
    }

    private fun readExactly(input: InputStream, byteCount: Int): ByteArray {
        val buffer = ByteArray(byteCount)
        readFully(input, buffer, 0, byteCount)
        return buffer
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, byteCount: Int) {
        var totalRead = 0
        while (totalRead < byteCount) {
            val bytesRead = input.read(buffer, offset + totalRead, byteCount - totalRead)
            require(bytesRead >= 0) { "Unexpected end of WAV file." }
            totalRead += bytesRead
        }
    }

    private fun skipExactly(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            require(input.read() >= 0) { "Unexpected end of WAV file." }
            remaining -= 1L
        }
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

internal data class WavHeader(
    val audioFormat: Int,
    val channelCount: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Int,
    val frameCount: Int
) {
    val sampleCount: Int
        get() = dataSize / Short.SIZE_BYTES
}

internal data class AudioSourceMetadata(
    val length: Long,
    val lastModified: Long
)

internal data class CachedPcmStemMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Int,
    val sampleCount: Int,
    val sourceLength: Long,
    val sourceLastModified: Long
) {
    fun toProperties(): Properties = Properties().apply {
        setProperty("version", CACHE_METADATA_VERSION.toString())
        setProperty("sampleRate", sampleRate.toString())
        setProperty("channelCount", channelCount.toString())
        setProperty("bitsPerSample", bitsPerSample.toString())
        setProperty("frameCount", frameCount.toString())
        setProperty("sampleCount", sampleCount.toString())
        setProperty("sourceLength", sourceLength.toString())
        setProperty("sourceLastModified", sourceLastModified.toString())
    }

    companion object {
        const val CACHE_METADATA_VERSION = 1

        fun fromProperties(properties: Properties): CachedPcmStemMetadata? {
            if (properties.getProperty("version")?.toIntOrNull() != CACHE_METADATA_VERSION) {
                return null
            }
            return CachedPcmStemMetadata(
                sampleRate = properties.getRequiredInt("sampleRate") ?: return null,
                channelCount = properties.getRequiredInt("channelCount") ?: return null,
                bitsPerSample = properties.getRequiredInt("bitsPerSample") ?: return null,
                frameCount = properties.getRequiredInt("frameCount") ?: return null,
                sampleCount = properties.getRequiredInt("sampleCount") ?: return null,
                sourceLength = properties.getRequiredLong("sourceLength") ?: return null,
                sourceLastModified = properties.getRequiredLong("sourceLastModified") ?: return null
            )
        }

        private fun Properties.getRequiredInt(key: String): Int? = getProperty(key)?.toIntOrNull()

        private fun Properties.getRequiredLong(key: String): Long? = getProperty(key)?.toLongOrNull()
    }
}

internal class PcmStemCache(
    private val cacheDirectory: File
) {
    constructor(context: Context) : this(File(context.cacheDir, CACHE_DIRECTORY_NAME))

    fun entry(cacheKey: String, expectedMetadata: CachedPcmStemMetadata): PcmStemCacheEntry? {
        val metadataFile = metadataFile(cacheKey)
        val pcmFile = pcmFile(cacheKey)
        if (!metadataFile.isFile || !pcmFile.isFile) {
            return null
        }
        val actualMetadata = runCatching {
            FileInputStream(metadataFile).use { input ->
                Properties().apply { load(input) }
            }
        }.mapCatching(CachedPcmStemMetadata::fromProperties).getOrNull()
        if (actualMetadata != expectedMetadata) {
            clear(cacheKey)
            return null
        }
        val expectedByteCount = expectedMetadata.sampleCount.toLong() * Short.SIZE_BYTES.toLong()
        if (pcmFile.length() != expectedByteCount) {
            clear(cacheKey)
            return null
        }
        return PcmStemCacheEntry(pcmFile = pcmFile, metadata = expectedMetadata)
    }

    fun read(cacheKey: String, expectedMetadata: CachedPcmStemMetadata): ShortArray? {
        val entry = entry(cacheKey, expectedMetadata) ?: return null
        return runCatching {
            readPcmFile(entry.pcmFile, expectedMetadata.sampleCount)
        }.getOrElse {
            clear(cacheKey)
            null
        }
    }

    fun write(cacheKey: String, metadata: CachedPcmStemMetadata, samples: ShortArray) {
        require(samples.size == metadata.sampleCount) {
            "Cached PCM sample count does not match metadata."
        }
        cacheDirectory.mkdirs()
        writePcmFile(pcmFile(cacheKey), samples)
        FileOutputStream(metadataFile(cacheKey)).use { output ->
            metadata.toProperties().store(output, null)
        }
    }

    fun writeFromWav(
        cacheKey: String,
        metadata: CachedPcmStemMetadata,
        stream: InputStream,
        header: WavHeader
    ): PcmStemCacheEntry {
        require(metadata.sampleCount == header.sampleCount) {
            "Cached PCM sample count does not match WAV header."
        }
        cacheDirectory.mkdirs()
        clear(cacheKey)
        BufferedOutputStream(FileOutputStream(pcmFile(cacheKey))).use { output ->
            WavPcmReader.copyPcm16Data(stream, header, output)
        }
        FileOutputStream(metadataFile(cacheKey)).use { output ->
            metadata.toProperties().store(output, null)
        }
        return entry(cacheKey, metadata)
            ?: error("Decoded PCM cache entry could not be validated after writing.")
    }

    fun clear(cacheKey: String) {
        metadataFile(cacheKey).delete()
        pcmFile(cacheKey).delete()
    }

    private fun readPcmFile(file: File, sampleCount: Int): ShortArray {
        val samples = ShortArray(sampleCount)
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(16 * 1024)
            var sampleIndex = 0
            while (sampleIndex < sampleCount) {
                val bytesToRead = minOf(buffer.size, (sampleCount - sampleIndex) * Short.SIZE_BYTES)
                var bytesRead = 0
                while (bytesRead < bytesToRead) {
                    val read = input.read(buffer, bytesRead, bytesToRead - bytesRead)
                    require(read >= 0) { "Unexpected end of cached PCM data." }
                    bytesRead += read
                }
                var byteIndex = 0
                while (byteIndex < bytesToRead) {
                    samples[sampleIndex++] = (
                        (buffer[byteIndex].toInt() and 0xFF) or
                            ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8)
                        ).toShort()
                    byteIndex += Short.SIZE_BYTES
                }
            }
        }
        return samples
    }

    private fun writePcmFile(file: File, samples: ShortArray) {
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            val buffer = ByteArray(16 * 1024)
            var sampleIndex = 0
            while (sampleIndex < samples.size) {
                var byteIndex = 0
                while (sampleIndex < samples.size && byteIndex < buffer.size) {
                    val sample = samples[sampleIndex++].toInt()
                    buffer[byteIndex++] = (sample and 0xFF).toByte()
                    buffer[byteIndex++] = ((sample ushr 8) and 0xFF).toByte()
                }
                output.write(buffer, 0, byteIndex)
            }
        }
    }

    private fun metadataFile(cacheKey: String): File = File(cacheDirectory, "$cacheKey.properties")

    private fun pcmFile(cacheKey: String): File = File(cacheDirectory, "$cacheKey.pcm")

    companion object {
        private const val CACHE_DIRECTORY_NAME = "decoded_stem_pcm"

        fun cacheKey(
            songId: String,
            stemId: String,
            displayPath: String,
            uri: String,
            sourceMetadata: AudioSourceMetadata
        ): String {
            val rawKey = listOf(
                songId,
                stemId,
                displayPath,
                uri,
                sourceMetadata.length.toString(),
                sourceMetadata.lastModified.toString()
            ).joinToString(separator = "\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(rawKey.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

internal data class PcmStemCacheEntry(
    val pcmFile: File,
    val metadata: CachedPcmStemMetadata
)

internal data class LoadedSongAudio(
    val songId: String,
    val sampleRate: Int,
    val channelCount: Int,
    val totalFrameCount: Int,
    val loopStartFrame: Int,
    val loopEndFrame: Int,
    val playTailOverLoop: Boolean,
    val loopStartsSong: Boolean,
    val stems: List<LoadedStemAudio>,
    val loadDiagnostics: SongAudioLoadDiagnostics = SongAudioLoadDiagnostics()
) {
    val loopFrameCount: Int
        get() = loopEndFrame - loopStartFrame

    val postLoopFrameCount: Int
        get() = (totalFrameCount - loopEndFrame).coerceAtLeast(0)

    val playOutFrameCount: Int
        get() = loopFrameCount + postLoopFrameCount

    val tailFrameCount: Int
        get() = if (playTailOverLoop) {
            postLoopFrameCount
        } else {
            0
        }

    val totalDurationMs: Long
        get() = ((totalFrameCount.toDouble() / sampleRate.toDouble()) * 1000.0).toLong()

    fun positionMsToLoopFrame(positionMs: Long): Int {
        if (loopFrameCount <= 0) return 0
        val frame = ((positionMs.coerceAtLeast(0L) * sampleRate.toLong()) / 1000L).toInt()
        return if (frame >= loopFrameCount) frame % loopFrameCount else frame
    }

    fun positionMsToPlayOutFrame(positionMs: Long): Int {
        val frame = ((positionMs.coerceAtLeast(0L) * sampleRate.toLong()) / 1000L).toInt()
        return frame.coerceIn(0, playOutFrameCount)
    }

    fun sourceFrameForPlayOutFrame(playOutFrame: Int): Int {
        if (playOutFrameCount <= 0) return loopStartFrame
        val clampedFrame = playOutFrame.coerceIn(0, playOutFrameCount - 1)
        return if (clampedFrame < loopFrameCount) {
            loopStartFrame + clampedFrame
        } else {
            loopEndFrame + (clampedFrame - loopFrameCount)
        }
    }

    fun warmLoopRestart() {
        stems.forEach { stem ->
            stem.warmFrame(loopStartFrame)
        }
    }

    fun close() {
        stems.forEach(LoadedStemAudio::close)
    }
}

internal data class LoadedStemAudio(
    val stemId: String,
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Int,
    val playTailOverLoop: Boolean,
    private val sampleData: SampleData,
    val loadSource: StemAudioLoadSource = StemAudioLoadSource.UNKNOWN
) {
    fun getSample(sampleIndex: Int): Short = sampleData.get(sampleIndex)

    fun warmFrame(frameIndex: Int) {
        if (frameCount <= 0) {
            return
        }
        val clampedFrame = frameIndex.coerceIn(0, frameCount - 1)
        sampleData.warm(clampedFrame * channelCount)
    }

    fun close() {
        sampleData.close()
    }
}

internal interface SampleData {
    fun get(sampleIndex: Int): Short

    fun warm(sampleIndex: Int) = Unit

    fun close() = Unit
}

internal class ShortArraySampleData(
    private val samples: ShortArray
) : SampleData {
    override fun get(sampleIndex: Int): Short = samples[sampleIndex]
}

internal class FileWindowSampleData(
    private val pcmFile: File,
    private val sampleCount: Int,
    private val windowSampleCount: Int = DEFAULT_WINDOW_SAMPLE_COUNT
) : SampleData {
    private val file = RandomAccessFile(pcmFile, "r")
    private val primaryWindow = SampleWindow(windowSampleCount)
    private val secondaryWindow = SampleWindow(windowSampleCount)
    private var accessGeneration = 0L

    override fun get(sampleIndex: Int): Short {
        require(sampleIndex >= 0 && sampleIndex < sampleCount) {
            "Sample index $sampleIndex out of range for ${pcmFile.name}."
        }
        findWindow(sampleIndex)?.let { window ->
            window.lastAccessGeneration = nextAccessGeneration()
            return window.samples[sampleIndex - window.startSample]
        }

        val targetWindow = selectWindowForReload()
        loadWindow(targetWindow, sampleIndex)
        targetWindow.lastAccessGeneration = nextAccessGeneration()
        return targetWindow.samples[sampleIndex - targetWindow.startSample]
    }

    override fun warm(sampleIndex: Int) {
        if (sampleCount <= 0) {
            return
        }
        val clampedIndex = sampleIndex.coerceIn(0, sampleCount - 1)
        findWindow(clampedIndex)?.let { window ->
            window.lastAccessGeneration = nextAccessGeneration()
            return
        }

        val targetWindow = selectWindowForReload()
        loadWindow(targetWindow, clampedIndex)
        targetWindow.lastAccessGeneration = nextAccessGeneration()
    }

    override fun close() {
        file.close()
    }

    private fun findWindow(sampleIndex: Int): SampleWindow? {
        return when {
            primaryWindow.contains(sampleIndex) -> primaryWindow
            secondaryWindow.contains(sampleIndex) -> secondaryWindow
            else -> null
        }
    }

    private fun selectWindowForReload(): SampleWindow {
        return when {
            primaryWindow.loadedSamples <= 0 -> primaryWindow
            secondaryWindow.loadedSamples <= 0 -> secondaryWindow
            primaryWindow.lastAccessGeneration <= secondaryWindow.lastAccessGeneration -> primaryWindow
            else -> secondaryWindow
        }
    }

    private fun nextAccessGeneration(): Long {
        accessGeneration += 1L
        return accessGeneration
    }

    private fun loadWindow(window: SampleWindow, sampleIndex: Int) {
        window.startSample = (sampleIndex / windowSampleCount) * windowSampleCount
        window.loadedSamples = minOf(windowSampleCount, sampleCount - window.startSample)
        file.seek(window.startSample.toLong() * Short.SIZE_BYTES.toLong())
        var loadedBytes = 0
        val bytesToLoad = window.loadedSamples * Short.SIZE_BYTES
        while (loadedBytes < bytesToLoad) {
            val read = file.read(window.byteBuffer, loadedBytes, bytesToLoad - loadedBytes)
            require(read >= 0) { "Unexpected end of cached PCM data." }
            loadedBytes += read
        }
        var byteIndex = 0
        var sampleOffset = 0
        while (sampleOffset < window.loadedSamples) {
            window.samples[sampleOffset++] = (
                (window.byteBuffer[byteIndex].toInt() and 0xFF) or
                    ((window.byteBuffer[byteIndex + 1].toInt() and 0xFF) shl 8)
                ).toShort()
            byteIndex += Short.SIZE_BYTES
        }
    }

    private class SampleWindow(windowSampleCount: Int) {
        val samples = ShortArray(windowSampleCount)
        val byteBuffer = ByteArray(windowSampleCount * Short.SIZE_BYTES)
        var startSample = -1
        var loadedSamples = 0
        var lastAccessGeneration = 0L

        fun contains(sampleIndex: Int): Boolean {
            return sampleIndex >= startSample && sampleIndex < (startSample + loadedSamples)
        }
    }

    private companion object {
        private const val DEFAULT_WINDOW_SAMPLE_COUNT = 256 * 1024
    }
}

internal enum class StemAudioLoadSource(
    val label: String
) {
    ASSET_SOURCE("asset pcm"),
    CONTENT_SOURCE("external pcm"),
    CONTENT_CACHE("cache pcm"),
    UNKNOWN("unknown")
}

internal data class SongAudioLoadDiagnostics(
    val loadDurationMs: Long = 0L,
    val stemSources: List<StemAudioLoadDiagnostic> = emptyList()
) {
    val sourceSummary: String
        get() = if (stemSources.isEmpty()) {
            "No stems loaded"
        } else {
            stemSources
                .groupingBy { it.source }
                .eachCount()
                .entries
                .joinToString { (source, count) -> "${source.label}: $count" }
        }
}

internal data class StemAudioLoadDiagnostic(
    val stemId: String,
    val source: StemAudioLoadSource
)
