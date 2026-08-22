package com.BalancedLight.dynamicdriving.shared.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.Arrays
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

internal data class StemAudioMixState(
    val gain: Float = 0f,
    val muffleAmount: Float = 0f,
    val muffleCutoffHz: Float = 18_000f,
    val reverbWetMix: Float = 0f,
    val reverbFeedback: Float = 0f,
    val reverbDamping: Float = 0f,
    val reverbDelayMs: Float = 140f
)

internal data class StemAudioEngineDiagnostics(
    val currentSongId: String? = null,
    val currentPositionMs: Long = 0L,
    val underrunCount: Int = 0,
    val activeStemCount: Int = 0,
    val lastRenderDurationMs: Float = 0f,
    val maxRenderDurationMs: Float = 0f,
    val lastWriteDurationMs: Float = 0f,
    val maxWriteDurationMs: Float = 0f
)

internal class StemMixingAudioEngine {
    companion object {
        private const val BUFFER_FRAMES = 1_024
        private const val OUTPUT_BUFFER_TARGET_MS = 250
        private const val OUTPUT_BUFFER_CHUNKS = 12
        private const val MIN_BUFFER_MULTIPLIER = 3
        private const val GAIN_EPSILON = 0.0005f
        private const val EFFECT_EPSILON = 0.0005f
    }

    private val lock = Object()
    private val renderThread = Thread(::renderLoop, "DynamicDrivingStemMixer").apply {
        start()
    }

    @Volatile
    private var currentStemStates: Array<StemAudioMixState> = emptyArray()

    @Volatile
    private var currentDiagnostics = StemAudioEngineDiagnostics()

    private var released = false
    @Volatile
    private var currentSong: LoadedSongAudio? = null
    private var pendingSong: LoadedSongAudio? = null
    private var playWhenReady = false
    private var pendingSeekFrame: Int? = null
    private var playbackMode = PlaybackMode.LOOPING

    fun loadSong(song: LoadedSongAudio, startPositionMs: Long, playWhenReady: Boolean) {
        synchronized(lock) {
            pendingSong?.close()
            pendingSong = song
            pendingSeekFrame = if (song.loopStartsSong) song.positionMsToLoopFrame(startPositionMs) else 0
            currentStemStates = Array(song.stems.size) { StemAudioMixState() }
            this.playWhenReady = playWhenReady
            playbackMode = if (song.loopStartsSong) PlaybackMode.LOOPING else PlaybackMode.FIRST_PASS_THEN_LOOP
            lock.notifyAll()
        }
    }

    fun setStemMixStates(states: Array<StemAudioMixState>) {
        currentStemStates = states
    }

    fun diagnostics(): StemAudioEngineDiagnostics = currentDiagnostics

    fun play() {
        synchronized(lock) {
            playWhenReady = true
            lock.notifyAll()
        }
    }

    fun pause() {
        synchronized(lock) {
            playWhenReady = false
            lock.notifyAll()
        }
    }

    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            val song = pendingSong ?: currentSong ?: return
            pendingSeekFrame = song.positionMsToLoopFrame(positionMs)
            playbackMode = PlaybackMode.LOOPING
            lock.notifyAll()
        }
    }

    fun seekFirstPass(positionMs: Long) {
        synchronized(lock) {
            val song = pendingSong ?: currentSong ?: return
            val frame = ((positionMs.coerceAtLeast(0L) * song.sampleRate.toLong()) / 1000L).toInt()
                .coerceIn(0, song.loopEndFrame)
            pendingSeekFrame = frame
            lock.notifyAll()
        }
    }

    fun seekPlayOut(positionMs: Long) {
        synchronized(lock) {
            val song = pendingSong ?: currentSong ?: return
            val frame = song.positionMsToPlayOutFrame(positionMs)
            pendingSeekFrame = frame
            playbackMode = PlaybackMode.FINISH_TO_END
            lock.notifyAll()
        }
    }

    fun seekTailPlayback(positionMs: Long) {
        synchronized(lock) {
            val song = pendingSong ?: currentSong ?: return
            val tailFrame = ((positionMs.coerceAtLeast(0L) * song.sampleRate.toLong()) / 1000L).toInt()
                .coerceIn(0, song.postLoopFrameCount)
            pendingSeekFrame = song.loopFrameCount + tailFrame
            playbackMode = PlaybackMode.FINISH_TO_END
            lock.notifyAll()
        }
    }

    fun playCurrentLoopToSongEnd(): Boolean {
        synchronized(lock) {
            val song = pendingSong ?: currentSong ?: return false
            if (song.postLoopFrameCount <= 0) {
                return false
            }
            if (playbackMode == PlaybackMode.FIRST_PASS_THEN_LOOP) {
                return false
            }
            playbackMode = PlaybackMode.FINISH_TO_END
            lock.notifyAll()
            return true
        }
    }

    fun playTailFromLoopEnd(): Boolean {
        synchronized(lock) {
            val song = pendingSong ?: currentSong ?: return false
            if (song.postLoopFrameCount <= 0) {
                return false
            }
            if (playbackMode == PlaybackMode.FIRST_PASS_THEN_LOOP) {
                return false
            }
            pendingSeekFrame = song.loopFrameCount
            playbackMode = PlaybackMode.FINISH_TO_END
            lock.notifyAll()
            return true
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
        renderThread.join(2_000L)
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var activeSong: LoadedSongAudio? = null
        var audioTrack: AudioTrack? = null
        var currentFrame = 0
        var mixBuffer = ShortArray(BUFFER_FRAMES * 2)
        var activeStemIndices = IntArray(0)
        var activePlaybackMode = PlaybackMode.LOOPING
        var reverbProcessors = emptyArray<SimpleStereoReverb>()
        var lowPassProcessors = emptyArray<SimpleStereoLowPass>()
        var maxRenderDurationMs = 0f
        var maxWriteDurationMs = 0f

        while (true) {
            val nextSong: LoadedSongAudio?
            val seekFrame: Int?
            val shouldPlay: Boolean
            val nextPlaybackMode: PlaybackMode
            synchronized(lock) {
                while (!released && activeSong == null && pendingSong == null) {
                    lock.wait()
                }
                if (released) {
                    break
                }
                nextSong = pendingSong.also { pendingSong = null }
                seekFrame = pendingSeekFrame.also { pendingSeekFrame = null }
                shouldPlay = playWhenReady
                nextPlaybackMode = playbackMode
            }

            if (nextSong != null) {
                activeSong?.close()
                activeSong = nextSong
                currentSong = nextSong
                currentFrame = seekFrame ?: 0
                activePlaybackMode = nextPlaybackMode
                audioTrack?.release()
                audioTrack = createAudioTrack(nextSong)
                mixBuffer = ShortArray(BUFFER_FRAMES * nextSong.channelCount)
                activeStemIndices = IntArray(nextSong.stems.size)
                reverbProcessors = Array(nextSong.stems.size) {
                    SimpleStereoReverb(
                        sampleRate = nextSong.sampleRate,
                        channelCount = nextSong.channelCount
                    )
                }
                lowPassProcessors = Array(nextSong.stems.size) {
                    SimpleStereoLowPass(
                        sampleRate = nextSong.sampleRate,
                        channelCount = nextSong.channelCount
                    )
                }
                maxRenderDurationMs = 0f
                maxWriteDurationMs = 0f
                currentDiagnostics = StemAudioEngineDiagnostics(
                    currentSongId = nextSong.songId,
                    currentPositionMs = frameToPositionMs(nextSong, currentFrame)
                )
                if (shouldPlay) {
                    audioTrack.play()
                }
                continue
            }

            val song = activeSong ?: continue
            val track = audioTrack ?: createAudioTrack(song).also { audioTrack = it }

            if (seekFrame != null) {
                currentFrame = seekFrame
                activePlaybackMode = nextPlaybackMode
                currentDiagnostics = currentDiagnostics.copy(
                    currentSongId = song.songId,
                    currentPositionMs = frameToPositionMs(song, currentFrame)
                )
                track.pause()
                track.flush()
                if (shouldPlay) {
                    track.play()
                }
            } else {
                activePlaybackMode = nextPlaybackMode
            }

            if (!shouldPlay) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                synchronized(lock) {
                    if (!released && !playWhenReady && pendingSong == null && pendingSeekFrame == null) {
                        lock.wait()
                    }
                }
                continue
            }

            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }

            val stemStates = currentStemStates
            val activeStemCount = collectActiveStemIndices(
                song = song,
                stemStates = stemStates,
                reverbProcessors = reverbProcessors,
                output = activeStemIndices
            )
            val framesToWrite = when (activePlaybackMode) {
                PlaybackMode.LOOPING -> BUFFER_FRAMES
                PlaybackMode.FINISH_TO_END -> (song.playOutFrameCount - currentFrame)
                    .coerceAtMost(BUFFER_FRAMES)
                PlaybackMode.FIRST_PASS_THEN_LOOP -> (song.loopEndFrame - currentFrame)
                    .coerceIn(0, BUFFER_FRAMES)
            }
            if (framesToWrite <= 0) {
                if (activePlaybackMode == PlaybackMode.FIRST_PASS_THEN_LOOP) {
                    currentFrame = 0
                    activePlaybackMode = PlaybackMode.LOOPING
                    currentDiagnostics = currentDiagnostics.copy(
                        currentSongId = song.songId,
                        currentPositionMs = 0L
                    )
                    synchronized(lock) {
                        if (playbackMode == PlaybackMode.FIRST_PASS_THEN_LOOP) {
                            playbackMode = PlaybackMode.LOOPING
                        }
                    }
                    continue
                }
                synchronized(lock) {
                    playWhenReady = false
                    playbackMode = PlaybackMode.LOOPING
                }
                track.pause()
                track.flush()
                continue
            }
            val renderStartedAtNs = System.nanoTime()
            mixIntoBuffer(
                song = song,
                startFrame = currentFrame,
                stemStates = stemStates,
                activeStemIndices = activeStemIndices,
                activeStemCount = activeStemCount,
                lowPassProcessors = lowPassProcessors,
                reverbProcessors = reverbProcessors,
                output = mixBuffer,
                frameCount = framesToWrite,
                playbackMode = activePlaybackMode
            )
            val renderDurationMs = nanosToMillis(System.nanoTime() - renderStartedAtNs)
            maxRenderDurationMs = max(maxRenderDurationMs, renderDurationMs)
            val samplesToWrite = framesToWrite * song.channelCount
            val writeStartedAtNs = System.nanoTime()
            val samplesWritten = track.write(mixBuffer, 0, samplesToWrite, AudioTrack.WRITE_BLOCKING)
            val writeDurationMs = nanosToMillis(System.nanoTime() - writeStartedAtNs)
            maxWriteDurationMs = max(maxWriteDurationMs, writeDurationMs)
            currentDiagnostics = StemAudioEngineDiagnostics(
                currentSongId = song.songId,
                currentPositionMs = frameToPositionMs(song, currentFrame),
                underrunCount = track.underrunCount,
                activeStemCount = activeStemCount,
                lastRenderDurationMs = renderDurationMs,
                maxRenderDurationMs = maxRenderDurationMs,
                lastWriteDurationMs = writeDurationMs,
                maxWriteDurationMs = maxWriteDurationMs
            )
            if (samplesWritten <= 0) {
                continue
            }
            val framesWritten = samplesWritten / song.channelCount
            maybeWarmLoopRestart(
                song = song,
                currentFrame = currentFrame,
                framesWritten = framesWritten,
                playbackMode = activePlaybackMode
            )
            currentFrame = when (activePlaybackMode) {
                PlaybackMode.LOOPING -> (currentFrame + framesWritten) % song.loopFrameCount
                PlaybackMode.FINISH_TO_END -> currentFrame + framesWritten
                PlaybackMode.FIRST_PASS_THEN_LOOP -> currentFrame + framesWritten
            }
        }

        audioTrack?.release()
        activeSong?.close()
        synchronized(lock) {
            pendingSong?.close()
            pendingSong = null
            currentSong = null
            currentDiagnostics = StemAudioEngineDiagnostics()
        }
    }

    private fun frameToPositionMs(song: LoadedSongAudio, frame: Int): Long {
        if (song.sampleRate <= 0) {
            return 0L
        }
        return (frame.coerceAtLeast(0).toLong() * 1_000L) / song.sampleRate.toLong()
    }

    private fun maybeWarmLoopRestart(
        song: LoadedSongAudio,
        currentFrame: Int,
        framesWritten: Int,
        playbackMode: PlaybackMode
    ) {
        if (playbackMode != PlaybackMode.LOOPING || song.loopFrameCount <= 0) {
            return
        }
        val framesUntilLoopRestart = song.loopFrameCount - currentFrame
        if (framesUntilLoopRestart > (BUFFER_FRAMES * 2)) {
            return
        }
        if ((currentFrame + framesWritten) < (song.loopFrameCount - BUFFER_FRAMES)) {
            return
        }
        song.warmLoopRestart()
    }

    private fun createAudioTrack(song: LoadedSongAudio): AudioTrack {
        val channelMask = when (song.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Unsupported channel count: ${song.channelCount}")
        }
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(song.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBufferSize = AudioTrack.getMinBufferSize(
            song.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bytesPerFrame = song.channelCount * Short.SIZE_BYTES
        val targetFrames = (song.sampleRate * OUTPUT_BUFFER_TARGET_MS) / 1_000
        val targetBufferSize = max(
            max(minBufferSize * MIN_BUFFER_MULTIPLIER, targetFrames * bytesPerFrame),
            BUFFER_FRAMES * bytesPerFrame * OUTPUT_BUFFER_CHUNKS
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(targetBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun mixIntoBuffer(
        song: LoadedSongAudio,
        startFrame: Int,
        stemStates: Array<StemAudioMixState>,
        activeStemIndices: IntArray,
        activeStemCount: Int,
        lowPassProcessors: Array<SimpleStereoLowPass>,
        reverbProcessors: Array<SimpleStereoReverb>,
        output: ShortArray,
        frameCount: Int,
        playbackMode: PlaybackMode
    ) {
        val channelCount = song.channelCount
        val stems = song.stems
        Arrays.fill(output, 0, frameCount * channelCount, 0.toShort())
        var outputIndex = 0
        for (frameOffset in 0 until frameCount) {
            val absoluteFrame = startFrame + frameOffset
            val sourceFrame = when (playbackMode) {
                PlaybackMode.LOOPING -> song.loopStartFrame + (absoluteFrame % song.loopFrameCount)
                PlaybackMode.FINISH_TO_END -> song.sourceFrameForPlayOutFrame(absoluteFrame)
                PlaybackMode.FIRST_PASS_THEN_LOOP -> absoluteFrame
            }
            val sampleBaseIndex = sourceFrame * channelCount
            val loopFrame = absoluteFrame % song.loopFrameCount
            var mixedLeft = 0f
            var mixedRight = 0f
            var activeIndex = 0
            while (activeIndex < activeStemCount) {
                val stemIndex = activeStemIndices[activeIndex]
                activeIndex += 1
                val stem = stems[stemIndex]
                val state = stemStates[stemIndex]
                val lowPassProcessor = lowPassProcessors[stemIndex]
                val processor = reverbProcessors[stemIndex]
                val tailSampleBaseIndex = if (
                    playbackMode == PlaybackMode.LOOPING &&
                    song.playTailOverLoop &&
                    stem.playTailOverLoop &&
                    loopFrame < song.tailFrameCount
                ) {
                    (song.loopEndFrame + loopFrame) * channelCount
                } else {
                    null
                }

                var dryLeft = stem.getSample(sampleBaseIndex).toFloat() * state.gain
                var dryRight = when (channelCount) {
                    1 -> stem.getSample(sampleBaseIndex).toFloat() * state.gain
                    else -> stem.getSample(sampleBaseIndex + 1).toFloat() * state.gain
                }
                tailSampleBaseIndex?.let { tailIndex ->
                    dryLeft += stem.getSample(tailIndex).toFloat() * state.gain
                    dryRight += when (channelCount) {
                        1 -> stem.getSample(tailIndex).toFloat() * state.gain
                        else -> stem.getSample(tailIndex + 1).toFloat() * state.gain
                    }
                }

                var processedLeft = dryLeft
                var processedRight = dryRight
                if (lowPassProcessor.hasActiveFilter() || abs(state.muffleAmount) > EFFECT_EPSILON) {
                    lowPassProcessor.process(
                        inputLeft = processedLeft,
                        inputRight = processedRight,
                        muffleAmount = state.muffleAmount,
                        cutoffHz = state.muffleCutoffHz
                    )
                    processedLeft = lowPassProcessor.outputLeft
                    processedRight = lowPassProcessor.outputRight
                }
                if (
                    processor.hasActiveTail() ||
                    abs(state.reverbWetMix) > EFFECT_EPSILON ||
                    abs(state.reverbFeedback) > EFFECT_EPSILON
                ) {
                    processor.process(
                        inputLeft = processedLeft,
                        inputRight = processedRight,
                        mixState = state
                    )
                    processedLeft = processor.outputLeft
                    processedRight = processor.outputRight
                }
                mixedLeft += processedLeft
                mixedRight += processedRight
            }
            output[outputIndex++] = clampToShort(mixedLeft)
            if (channelCount > 1) {
                output[outputIndex++] = clampToShort(mixedRight)
            }
        }
    }

    private fun collectActiveStemIndices(
        song: LoadedSongAudio,
        stemStates: Array<StemAudioMixState>,
        reverbProcessors: Array<SimpleStereoReverb>,
        output: IntArray
    ): Int {
        val activeLimit = min(song.stems.size, min(stemStates.size, output.size))
        var count = 0
        var stemIndex = 0
        while (stemIndex < activeLimit) {
            val state = stemStates[stemIndex]
            val reverbProcessor = reverbProcessors.getOrNull(stemIndex)
            if (
                abs(state.gain) > GAIN_EPSILON ||
                abs(state.muffleAmount) > EFFECT_EPSILON ||
                abs(state.reverbWetMix) > EFFECT_EPSILON ||
                abs(state.reverbFeedback) > EFFECT_EPSILON ||
                reverbProcessor?.hasActiveTail() == true
            ) {
                output[count++] = stemIndex
            }
            stemIndex += 1
        }
        return count
    }

    private fun nanosToMillis(nanos: Long): Float = nanos.toFloat() / 1_000_000f

    private fun clampToShort(sample: Float): Short {
        return sample.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
    }

    private enum class PlaybackMode {
        LOOPING,
        FINISH_TO_END,
        FIRST_PASS_THEN_LOOP
    }
}

private class SimpleStereoLowPass(
    private val sampleRate: Int,
    private val channelCount: Int
) {
    companion object {
        private const val PARAMETER_SMOOTHING = 0.01f
        private const val CLEAR_CUTOFF_HZ = 18_000f
        private const val MIN_CUTOFF_HZ = 80f
        private const val ALPHA_RECALCULATION_THRESHOLD_HZ = 8f
    }

    private var smoothedMuffleAmount = 0f
    private var smoothedCutoffHz = CLEAR_CUTOFF_HZ
    private var filteredLeft = 0f
    private var filteredRight = 0f
    private var cachedAlpha = 1f
    private var cachedAlphaCutoffHz = CLEAR_CUTOFF_HZ
    var outputLeft = 0f
        private set
    var outputRight = 0f
        private set

    fun process(
        inputLeft: Float,
        inputRight: Float,
        muffleAmount: Float,
        cutoffHz: Float
    ) {
        val targetMuffleAmount = muffleAmount.coerceIn(0f, 1f)
        val targetCutoffHz = if (targetMuffleAmount <= 0f) {
            CLEAR_CUTOFF_HZ
        } else {
            cutoffHz.coerceIn(MIN_CUTOFF_HZ, CLEAR_CUTOFF_HZ)
        }
        smoothedMuffleAmount += (targetMuffleAmount - smoothedMuffleAmount) * PARAMETER_SMOOTHING
        smoothedCutoffHz += (targetCutoffHz - smoothedCutoffHz) * PARAMETER_SMOOTHING
        val alpha = alphaFor(smoothedCutoffHz)

        filteredLeft += alpha * (inputLeft - filteredLeft)
        val rightInput = if (channelCount == 1) inputLeft else inputRight
        filteredRight += alpha * (rightInput - filteredRight)

        outputLeft = blend(inputLeft, filteredLeft, smoothedMuffleAmount)
        outputRight = if (channelCount == 1) {
            outputLeft
        } else {
            blend(inputRight, filteredRight, smoothedMuffleAmount)
        }
    }

    private fun lowPassAlpha(cutoffHz: Float): Float {
        val normalizedCutoff = cutoffHz.coerceIn(MIN_CUTOFF_HZ, CLEAR_CUTOFF_HZ).toDouble()
        val coefficient = (-2.0 * PI * normalizedCutoff / sampleRate.toDouble())
        return (1.0 - exp(coefficient)).toFloat().coerceIn(0.01f, 1f)
    }

    private fun alphaFor(cutoffHz: Float): Float {
        if (abs(cutoffHz - cachedAlphaCutoffHz) >= ALPHA_RECALCULATION_THRESHOLD_HZ) {
            cachedAlphaCutoffHz = cutoffHz
            cachedAlpha = lowPassAlpha(cachedAlphaCutoffHz)
        }
        return cachedAlpha
    }

    private fun blend(dry: Float, filtered: Float, amount: Float): Float {
        return (dry * (1f - amount)) + (filtered * amount)
    }

    fun hasActiveFilter(): Boolean {
        return smoothedMuffleAmount > PARAMETER_SMOOTHING
    }
}

private class SimpleStereoReverb(
    private val sampleRate: Int,
    private val channelCount: Int
) {
    companion object {
        private const val PARAMETER_SMOOTHING = 0.0025f
        private const val CROSS_FEED = 0.18f
        private const val TAIL_DECAY_THRESHOLD = 0.25f
        private const val PARAMETER_EPSILON = 0.0005f
        private const val MAX_SAFE_WET_MIX = 0.65f
        private const val MAX_SAFE_FEEDBACK = 0.72f
        private const val MAX_SAFE_DAMPING = 0.92f
        private const val WET_SIGNAL_ATTENUATION = 0.75f
        private const val FEEDBACK_SIGNAL_ATTENUATION = 0.65f
        private const val INPUT_SIGNAL_ATTENUATION = 0.85f
        private const val SAMPLE_HEADROOM = Short.MAX_VALUE.toFloat() * 0.9f
    }

    private val maxDelaySamples = (sampleRate * 1.5f).toInt().coerceAtLeast(4)
    private val leftBuffer = FloatArray(maxDelaySamples)
    private val rightBuffer = FloatArray(maxDelaySamples)
    private var writeIndex = 0
    private var smoothedWetMix = 0f
    private var smoothedFeedback = 0f
    private var smoothedDamping = 0f
    private var smoothedDelaySamples = (sampleRate * 0.14f).coerceAtLeast(1f)
    private var filteredLeft = 0f
    private var filteredRight = 0f
    private var tailEnvelope = 0f
    var outputLeft = 0f
        private set
    var outputRight = 0f
        private set

    fun process(
        inputLeft: Float,
        inputRight: Float,
        mixState: StemAudioMixState
    ) {
        val safeWetMix = mixState.reverbWetMix.coerceIn(0f, MAX_SAFE_WET_MIX)
        val safeFeedback = mixState.reverbFeedback.coerceIn(0f, MAX_SAFE_FEEDBACK)
        val safeDamping = mixState.reverbDamping.coerceIn(0f, MAX_SAFE_DAMPING)

        smoothedWetMix += (safeWetMix - smoothedWetMix) * PARAMETER_SMOOTHING
        smoothedFeedback += (safeFeedback - smoothedFeedback) * PARAMETER_SMOOTHING
        smoothedDamping += (safeDamping - smoothedDamping) * PARAMETER_SMOOTHING
        val targetDelaySamples = ((mixState.reverbDelayMs / 1_000f) * sampleRate.toFloat())
            .coerceIn(1f, (maxDelaySamples - 2).toFloat())
        smoothedDelaySamples += (targetDelaySamples - smoothedDelaySamples) * PARAMETER_SMOOTHING

        val primaryDelay = smoothedDelaySamples.toInt().coerceIn(1, maxDelaySamples - 2)
        val secondaryDelay = (primaryDelay * 1.37f).toInt().coerceIn(1, maxDelaySamples - 2)

        val delayedLeft = leftBuffer[readIndex(primaryDelay)]
        val delayedRight = rightBuffer[readIndex(primaryDelay)]
        val crossLeft = rightBuffer[readIndex(secondaryDelay)]
        val crossRight = leftBuffer[readIndex(secondaryDelay)]

        filteredLeft += ((delayedLeft + (crossLeft * 0.35f)) - filteredLeft) * (1f - smoothedDamping)
        filteredRight += ((delayedRight + (crossRight * 0.35f)) - filteredRight) * (1f - smoothedDamping)
        filteredLeft = softLimit(filteredLeft)
        filteredRight = softLimit(filteredRight)

        val feedbackLeft = softLimit((filteredLeft * FEEDBACK_SIGNAL_ATTENUATION) + (filteredRight * CROSS_FEED))
        val feedbackRight = softLimit((filteredRight * FEEDBACK_SIGNAL_ATTENUATION) + (filteredLeft * CROSS_FEED))

        leftBuffer[writeIndex] = softLimit((inputLeft * INPUT_SIGNAL_ATTENUATION) + (feedbackLeft * smoothedFeedback))
        rightBuffer[writeIndex] = if (channelCount == 1) {
            softLimit((inputLeft * INPUT_SIGNAL_ATTENUATION) + (feedbackRight * smoothedFeedback))
        } else {
            softLimit((inputRight * INPUT_SIGNAL_ATTENUATION) + (feedbackRight * smoothedFeedback))
        }
        writeIndex = (writeIndex + 1) % maxDelaySamples

        val wetLeft = softLimit(filteredLeft * WET_SIGNAL_ATTENUATION)
        val wetRight = if (channelCount == 1) wetLeft else softLimit(filteredRight * WET_SIGNAL_ATTENUATION)
        val dryMix = 1f - (smoothedWetMix * 0.25f)
        outputLeft = softLimit((inputLeft * dryMix) + (wetLeft * smoothedWetMix))
        outputRight = if (channelCount == 1) {
            outputLeft
        } else {
            softLimit((inputRight * dryMix) + (wetRight * smoothedWetMix))
        }

        tailEnvelope = (tailEnvelope * 0.995f).coerceAtLeast(
            max(abs(wetLeft), abs(wetRight))
        )
    }

    fun hasActiveTail(): Boolean {
        return tailEnvelope > TAIL_DECAY_THRESHOLD || smoothedWetMix > PARAMETER_EPSILON || smoothedFeedback > PARAMETER_EPSILON
    }

    private fun readIndex(delaySamples: Int): Int {
        var index = writeIndex - delaySamples
        while (index < 0) {
            index += maxDelaySamples
        }
        return index % maxDelaySamples
    }

    private fun softLimit(sample: Float): Float {
        if (sample in -SAMPLE_HEADROOM..SAMPLE_HEADROOM) {
            return sample
        }
        val normalized = (sample / SAMPLE_HEADROOM).toDouble()
        return (tanh(normalized) * SAMPLE_HEADROOM).toFloat()
    }
}
