package com.infinifolder.audio.stream

import com.infinifolder.audio.AudioBytes
import io.github.gretchycat.infinistream.requireRange
import java.io.EOFException
import java.io.IOException

/** JLayer validates each packet against the next sync or EOF. ID3v1 is metadata,
 * not a following packet: exposing it makes JLayer silently discard the last frame.
 * This owned view bounds reads without changing the original file or musical trim.
 */
internal class MpegAudioBytes(private val source: AudioBytes) : AudioBytes {
    override val size: Long
    init {
        checkDecodeCancellation()
        var end = source.size
        if (end >= 128) {
            val marker = ByteArray(3)
            var at = 0
            while (at < marker.size) {
                checkDecodeCancellation()
                val count = source.readAt(end - 128 + at, marker, at, marker.size - at)
                if (count < 0) throw EOFException("Truncated MPEG footer probe")
                if (count == 0 || count > marker.size - at) throw IOException("Invalid MPEG footer read")
                at += count
            }
            if (marker.contentEquals(byteArrayOf(84, 65, 71))) end -= 128
        }
        size = end
    }
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        requireRange(position, buffer, offset, length)
        checkDecodeCancellation()
        if (length == 0) return 0
        if (position >= size) return -1
        return source.readAt(position, buffer, offset, minOf(length.toLong(), size - position).toInt())
    }
    override fun close() = source.close()
}
