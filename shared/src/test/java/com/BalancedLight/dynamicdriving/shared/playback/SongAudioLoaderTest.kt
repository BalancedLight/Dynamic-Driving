package com.BalancedLight.dynamicdriving.shared.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class SongAudioLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun wav_reader_parses_header_and_reads_pcm_samples() {
        val samples = shortArrayOf(0, 32_767, -32_768, -1234)
        val wavBytes = buildPcm16Wav(
            sampleRate = 44_100,
            channelCount = 2,
            samples = samples
        )

        val header = WavPcmReader.parseHeader(ByteArrayInputStream(wavBytes))
        val decodedSamples = WavPcmReader.readPcm16Samples(ByteArrayInputStream(wavBytes), header)

        assertEquals(1, header.audioFormat)
        assertEquals(44_100, header.sampleRate)
        assertEquals(2, header.channelCount)
        assertEquals(16, header.bitsPerSample)
        assertEquals(2, header.frameCount)
        assertArrayEquals(samples, decodedSamples)
    }

    @Test
    fun short_array_sample_data_reads_by_absolute_sample_index() {
        val sampleData = ShortArraySampleData(shortArrayOf(-1, 0, 42))

        assertEquals((-1).toShort(), sampleData.get(0))
        assertEquals(42.toShort(), sampleData.get(2))
    }

    @Test
    fun pcm_cache_returns_samples_only_when_metadata_matches() {
        val cache = PcmStemCache(temporaryFolder.newFolder("pcm-cache"))
        val metadata = CachedPcmStemMetadata(
            sampleRate = 44_100,
            channelCount = 2,
            bitsPerSample = 16,
            frameCount = 2,
            sampleCount = 4,
            sourceLength = 128L,
            sourceLastModified = 1_000L
        )
        val cacheKey = PcmStemCache.cacheKey(
            songId = "song",
            stemId = "stem",
            displayPath = "folder/stem.wav",
            uri = "content://library/stem.wav",
            sourceMetadata = AudioSourceMetadata(length = 128L, lastModified = 1_000L)
        )
        val samples = shortArrayOf(10, 20, 30, 40)

        cache.write(cacheKey, metadata, samples)

        assertArrayEquals(samples, cache.read(cacheKey, metadata))
        assertNull(cache.read(cacheKey, metadata.copy(sourceLength = 256L)))
        assertNull(cache.read(cacheKey, metadata))
    }

    @Test
    fun file_window_sample_data_reads_cached_pcm_without_full_heap_copy() {
        val pcmFile = temporaryFolder.newFile("stem.pcm")
        val samples = ShortArray(12) { index -> (index * 11 - 30).toShort() }
        writeRawPcm(pcmFile, samples)
        val sampleData = FileWindowSampleData(
            pcmFile = pcmFile,
            sampleCount = samples.size,
            windowSampleCount = 4
        )

        try {
            assertEquals(samples[0], sampleData.get(0))
            assertEquals(samples[5], sampleData.get(5))
            assertEquals(samples[4], sampleData.get(4))
            assertEquals(samples[11], sampleData.get(11))
        } finally {
            sampleData.close()
        }
    }

    @Test
    fun cache_key_changes_when_source_metadata_changes() {
        val originalKey = PcmStemCache.cacheKey(
            songId = "song",
            stemId = "stem",
            displayPath = "folder/stem.wav",
            uri = "content://library/stem.wav",
            sourceMetadata = AudioSourceMetadata(length = 128L, lastModified = 1_000L)
        )
        val changedKey = PcmStemCache.cacheKey(
            songId = "song",
            stemId = "stem",
            displayPath = "folder/stem.wav",
            uri = "content://library/stem.wav",
            sourceMetadata = AudioSourceMetadata(length = 256L, lastModified = 1_000L)
        )

        assertNotNull(originalKey)
        assertNotNull(changedKey)
        assertNotEquals(originalKey, changedKey)
    }

    @Test
    fun stems_with_different_frame_counts_are_rejected_instead_of_cropped() {
        val bed = loadedStem("bed", frameCount = 8)
        val shortLift = loadedStem("lift", frameCount = 6)

        val error = assertThrows(IllegalArgumentException::class.java) {
            requireCompatibleStems("test_song", listOf(bed, shortLift))
        }

        assertTrue(error.message.orEmpty().contains("lift"))
        assertTrue(error.message.orEmpty().contains("6 frames"))
        assertTrue(error.message.orEmpty().contains("bed"))
    }

    private fun buildPcm16Wav(
        sampleRate: Int,
        channelCount: Int,
        samples: ShortArray
    ): ByteArray {
        val bytesPerSample = 2
        val byteRate = sampleRate * channelCount * bytesPerSample
        val blockAlign = channelCount * bytesPerSample
        val dataSize = samples.size * bytesPerSample
        return ByteArrayOutputStream().use { output ->
            output.writeAscii("RIFF")
            output.writeLittleEndianInt(36 + dataSize)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(channelCount)
            output.writeLittleEndianInt(sampleRate)
            output.writeLittleEndianInt(byteRate)
            output.writeLittleEndianShort(blockAlign)
            output.writeLittleEndianShort(16)
            output.writeAscii("data")
            output.writeLittleEndianInt(dataSize)
            samples.forEach { sample -> output.writeLittleEndianShort(sample.toInt()) }
            output.toByteArray()
        }
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun writeRawPcm(file: File, samples: ShortArray) {
        FileOutputStream(file).use { output ->
            samples.forEach { sample ->
                val value = sample.toInt()
                output.write(value and 0xFF)
                output.write((value ushr 8) and 0xFF)
            }
        }
    }

    private fun loadedStem(stemId: String, frameCount: Int): LoadedStemAudio = LoadedStemAudio(
        stemId = stemId,
        sampleRate = 44_100,
        channelCount = 1,
        bitsPerSample = 16,
        frameCount = frameCount,
        playTailOverLoop = true,
        sampleData = ShortArraySampleData(ShortArray(frameCount))
    )
}
