package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.IOException

/** Bounded packing adapter; source rate/timing and existing surround channels are retained. */
internal open class PcmOutputStream(protected val input: PcmStream, options: PcmOutputOptions) : PcmStream {
    override val format = input.format.copy(bitsPerSample = options.bitsPerSample ?: input.format.bitsPerSample,
        channels = if (options.duplicateMono && input.format.channels == 1) 2 else input.format.channels)
    override val decoderName get() = input.decoderName
    override val sourceFormat get() = input.sourceFormat
    private val buffer = IntArray(4096*input.format.channels)
    private val shift = 32-format.bitsPerSample
    private var closed = false

    override fun read(samples: IntArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= samples.size-length)
        checkDecodeCancellation()
        if (closed) throw IOException("PCM output adapter is closed")
        if (length == 0) return 0
        require(length >= format.channels)
        val frames = minOf(4096,length/format.channels)
        val count = input.read(buffer,0,frames*input.format.channels)
        if (count == -1) return -1
        if (count <= 0 || count > frames*input.format.channels || count%input.format.channels != 0)
            throw IOException("Invalid PCM output read: $count")
        val actualFrames = count/input.format.channels
        if (input.format.channels == format.channels) {
            for (i in 0 until count) samples[offset+i]=(buffer[i] shr shift) shl shift
        } else {
            for (i in 0 until actualFrames) {
                val value = (buffer[i] shr shift) shl shift
                samples[offset+i*2]=value; samples[offset+i*2+1]=value
            }
        }
        return actualFrames*format.channels
    }

    override fun close() { if (!closed) { closed=true; input.close() } }
}

internal fun PcmStream.forOutput(options: PcmOutputOptions): PcmStream {
    if ((options.bitsPerSample == null || options.bitsPerSample == format.bitsPerSample) &&
        (!options.duplicateMono || format.channels != 1)) return this
    val source = this
    return if (source is FrameSeekablePcmStream) object : PcmOutputStream(source,options), FrameSeekablePcmStream {
        override fun seekToFrame(frame: Long) { checkDecodeCancellation(); source.seekToFrame(frame) }
    } else PcmOutputStream(source,options)
}
