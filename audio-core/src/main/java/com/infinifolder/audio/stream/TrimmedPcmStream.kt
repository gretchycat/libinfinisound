package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.IOException

/** Applies encoder delay/edit-list start and end padding without treating silence as padding. */
class TrimmedPcmStream(
    private val stream: PcmStream,
    skipFrames: Long = 0,
    private val paddingFrames: Int = 0,
    totalFrames: Long? = null
) : BlockPcmStream() {
    override val format = stream.format.copy(totalFrames = totalFrames)
    override val decoderName get() = stream.decoderName
    override val sourceFormat get() = stream.sourceFormat
    private var skip = skipFrames * format.channels
    private var remaining = totalFrames?.let { it * format.channels }
    private var tail = IntArray(0)
    private val buffer = IntArray(4096 * format.channels)
    init { require(skipFrames >= 0 && paddingFrames in 0..1_048_576) }
    override fun nextBlock(): IntArray? {
        if (remaining == 0L) return null
        while (true) {
            val count = stream.read(buffer, 0, buffer.size)
            if (count < 0) return null // Retained tail is encoder padding, even if its samples are nonzero.
            if (count == 0) throw IOException("PCM stream made no progress")
            val drop = minOf(skip, count.toLong()).toInt()
            skip -= drop
            val joined = IntArray(tail.size + count - drop)
            tail.copyInto(joined)
            buffer.copyInto(joined, tail.size, drop, count)
            val available = (joined.size - paddingFrames * format.channels).coerceAtLeast(0)
            if (available == 0) { tail = joined; continue }
            val emit = minOf(available.toLong(), remaining ?: Long.MAX_VALUE).toInt()
            tail = joined.copyOfRange(emit, joined.size)
            remaining = remaining?.minus(emit)
            return joined.copyOf(emit)
        }
    }
    override fun close() = stream.close()
}
