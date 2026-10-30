package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException

class FileAudioInput(val file: File, override val name: String = file.name) : AudioInput {
    override fun open(): AudioBytes {
        val handle = StreamAudioInput(io.github.gretchycat.infinistream.FileSourceResource(file), name).open()
        return object : AudioBytes by handle {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                checkDecodeCancellation()
                return handle.readAt(position, buffer, offset, length)
            }
        }
    }
}

class MemoryAudioInput(private val bytes: ByteArray, override val name: String, val fileHint: File? = null) : AudioInput {
    override fun open(): AudioBytes = object : AudioBytes {
        override val size = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length)
            checkDecodeCancellation()
            if (length == 0) return 0
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() {}
    }
}

internal fun checkDecodeCancellation() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio decoding cancelled")
}

internal class AudioInputStream(private val bytes: AudioBytes) : InputStream() {
    private var position = 0L
    private val single = ByteArray(1)
    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = bytes.readAt(position, buffer, offset, length)
        if (n > 0) position += n
        return n
    }
    override fun skip(n: Long): Long {
        val count = n.coerceIn(0, (bytes.size - position).coerceAtLeast(0))
        position += count
        return count
    }
    override fun available() = (bytes.size - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    override fun close() = bytes.close()
}

fun AudioInput.probe(limit: Int = 128 * 1024): AudioProbe = open().use { bytes ->
    val head = ByteArray(minOf(limit.toLong(), bytes.size).toInt())
    var offset = 0
    while (offset < head.size) {
        val count = bytes.readAt(offset.toLong(), head, offset, head.size - offset)
        if (count < 0) break
        check(count > 0) { "Input made no progress" }
        offset += count
    }
    AudioProbe(head.copyOf(offset), name.substringAfterLast('.', ""))
}
