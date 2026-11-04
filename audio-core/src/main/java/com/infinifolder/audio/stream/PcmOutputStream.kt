package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.IOException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Bounded PCM normalization used by output adapters. */
internal open class PcmOutputStream(protected val input: PcmStream, options: PcmOutputOptions) : PcmStream {
    private val inputFormat = input.format
    private val sourceFrames = inputFormat.totalFrames
    private val requestedChannels = options.channels
        ?: if (options.duplicateMono && inputFormat.channels == 1) 2 else inputFormat.channels
    protected val outputRate = options.sampleRate ?: inputFormat.sampleRate
    private val outputBits = options.bitsPerSample ?: inputFormat.bitsPerSample
    override val format = PcmFormat(outputRate, requestedChannels,
        sourceFrames?.let { (it * outputRate + inputFormat.sampleRate / 2L) / inputFormat.sampleRate },
        outputBits)
    override val decoderName get() = input.decoderName
    override val sourceFormat get() = input.sourceFormat

    private val capacityFrames = 8192
    private val buffered = IntArray(capacityFrames * inputFormat.channels)
    private var bufferedStart = 0L
    private var bufferedFrames = 0
    private var inputEnded = false
    protected var outputFrame = 0L
    private var closed = false
    private val inputScratch = IntArray(capacityFrames * inputFormat.channels)
    private val targetShift = 32 - outputBits

    override fun read(samples: IntArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= samples.size - length)
        checkDecodeCancellation()
        if (closed) throw IOException("PCM output adapter is closed")
        if (length == 0) return 0
        require(length >= format.channels)
        val requested = min(4096, length / format.channels)
        var produced = 0
        while (produced < requested) {
            checkDecodeCancellation()
            if (format.totalFrames?.let { outputFrame >= it } == true) break
            val sourcePosition = outputFrame.toDouble() * inputFormat.sampleRate / outputRate
            val frame0 = floor(sourcePosition).toLong()
            val frame1 = if (sourceFrames != null && frame0 + 1 >= sourceFrames) frame0 else frame0 + 1
            if (!ensureFrame(frame0)) break
            if (!ensureFrame(frame1) && !inputEnded) break
            val fraction = (sourcePosition - frame0).coerceIn(0.0, 1.0)
            val left = mix(frame0, 0, fraction)
            val right = if (format.channels == 2) mix(frame0, 1, fraction) else left
            samples[offset + produced * format.channels] = quantize(left)
            if (format.channels == 2) samples[offset + produced * 2 + 1] = quantize(right)
            outputFrame++
            produced++
            discardBefore(floor(outputFrame.toDouble() * inputFormat.sampleRate / outputRate).toLong())
        }
        return if (produced == 0) -1 else produced * format.channels
    }

    private fun ensureFrame(frame: Long): Boolean {
        if (frame < bufferedStart) throw IOException("PCM output cursor moved behind its decode buffer")
        while (frame >= bufferedStart + bufferedFrames && !inputEnded) {
            if (bufferedFrames == capacityFrames) discardBefore(frame)
            val room = capacityFrames - bufferedFrames
            if (room <= 0) throw IOException("PCM resampler exceeded its bounded frame buffer")
            val count = input.read(inputScratch, 0, room * inputFormat.channels)
            if (count == -1) { inputEnded = true; break }
            if (count <= 0 || count > room * inputFormat.channels || count % inputFormat.channels != 0)
                throw IOException("Invalid PCM resampler input: $count")
            val frames = count / inputFormat.channels
            System.arraycopy(inputScratch, 0, buffered, bufferedFrames * inputFormat.channels, count)
            bufferedFrames += frames
        }
        return frame >= bufferedStart && frame < bufferedStart + bufferedFrames
    }

    private fun discardBefore(frame: Long) {
        val drop = min(bufferedFrames.toLong(), (frame - bufferedStart).coerceAtLeast(0)).toInt()
        if (drop > 0) {
            System.arraycopy(buffered, drop * inputFormat.channels, buffered, 0,
                (bufferedFrames - drop) * inputFormat.channels)
            bufferedFrames -= drop
            bufferedStart += drop
        }
    }

    private fun sample(frame: Long, channel: Int): Double {
        val index = ((frame - bufferedStart).toInt() * inputFormat.channels + channel)
        return buffered[index].toDouble()
    }

    private fun mix(frame: Long, side: Int, fraction: Double): Double {
        fun at(channel: Int): Double {
            val next = if (sourceFrames != null && frame + 1 >= sourceFrames ||
                frame + 1 >= bufferedStart + bufferedFrames) frame else frame + 1
            return sample(frame, channel) * (1.0 - fraction) + sample(next, channel) * fraction
        }
        val value = when (format.channels) {
            1 -> if (inputFormat.channels == 1) at(0) else {
                var sum = 0.0
                for (channel in 0 until inputFormat.channels) sum += at(channel)
                sum / inputFormat.channels
            }
            else -> when (inputFormat.channels) {
                1 -> at(0)
                2 -> at(side)
                else -> {
                    // Standard interleaved order: L, R, C, LFE, surrounds, then back channels.
                    val main = at(side)
                    val center = at(2) * 0.7071067811865476
                    val lfe = if (inputFormat.channels > 3) at(3) * 0.25 else 0.0
                    var surround = 0.0
                    var weight = 1.0 + 0.7071067811865476 + if (inputFormat.channels > 3) 0.25 else 0.0
                    for (channel in 4 until inputFormat.channels) {
                        val belongs = (channel % 2 == 0) == (side == 0)
                        if (belongs) { surround += at(channel) * 0.5; weight += 0.5 }
                    }
                    (main + center + lfe + surround) / max(1.0, weight)
                }
            }
        }
        return value.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
    }

    private fun quantize(value: Double): Int = (value.toLong().toInt() shr targetShift) shl targetShift

    protected fun resetAfterSeek(output: Long, source: Long) {
        outputFrame = output
        bufferedStart = source
        bufferedFrames = 0
        inputEnded = false
    }

    override fun close() { if (!closed) { closed = true; input.close() } }
}

internal fun PcmStream.forOutput(options: PcmOutputOptions): PcmStream {
    val desiredChannels = options.channels ?: if (options.duplicateMono && format.channels == 1) 2 else format.channels
    if ((options.bitsPerSample == null || options.bitsPerSample == format.bitsPerSample) &&
        (options.sampleRate == null || options.sampleRate == format.sampleRate) && desiredChannels == format.channels) return this
    val source = this
    return if (source is FrameSeekablePcmStream) object : PcmOutputStream(source, options), FrameSeekablePcmStream {
        override fun seekToFrame(frame: Long) {
            checkDecodeCancellation()
            val target = frame.coerceAtLeast(0)
            val sourceFrame = (target * source.format.sampleRate / outputRate).coerceAtLeast(0)
            source.seekToFrame(sourceFrame)
            resetAfterSeek(target, sourceFrame)
        }
    } else PcmOutputStream(source, options)
}
