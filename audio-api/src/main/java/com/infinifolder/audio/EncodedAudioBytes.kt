package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import java.io.IOException

/** Explicit, bounded materialization for small score/emulation formats. Owns only its opened view. */
fun AudioInput.readEncodedBytes(maxBytes: Int, minBytes: Int = 0): ByteArray {
    require(minBytes >= 0 && maxBytes >= minBytes)
    return open().use { input ->
        val size = input.size
        if (size !in minBytes.toLong()..maxBytes.toLong()) throw IOException("Encoded audio input exceeds parsing bounds")
        val source = object : ByteSource {
            private var position = 0L
            override val metadata = StreamMetadata(name = this@readEncodedBytes.name, length = size,
                capabilities = setOf(Capability.READ, Capability.KNOWN_LENGTH), ownership = Ownership.BORROWED)
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                requireRange(position,buffer,offset,length)
                if (length == 0) return 0
                if (position == size) return -1
                val wanted = minOf(length.toLong(),size-position).toInt()
                val count = input.readAt(position,buffer,offset,wanted)
                if (count <= 0 || count > wanted) throw IOException("Truncated or invalid encoded audio input")
                position += count
                return count
            }
            override fun close() {} // The surrounding use owns the opened AudioBytes.
        }
        readBytes(source,maxBytes)
    }
}
