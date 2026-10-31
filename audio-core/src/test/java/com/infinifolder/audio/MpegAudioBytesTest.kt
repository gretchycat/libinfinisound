package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class MpegAudioBytesTest {
    private class Tracked(private val bytes: ByteArray, private val chunk: Int = Int.MAX_VALUE) : AudioBytes {
        override val size = bytes.size.toLong()
        var readBytes = 0
        var closes = 0
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length, chunk, (size - position).toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            readBytes += n
            return n
        }
        override fun close() { closes++ }
    }
    @Test fun footerDetectionReadsOnlyThreeBytesRegardlessOfTrackLength() {
        val bytes = ByteArray(2_000_128)
        "TAG".toByteArray().copyInto(bytes, 2_000_000)
        val source = Tracked(bytes)
        MpegAudioBytes(source).use { audio ->
            assertEquals(2_000_000L, audio.size)
            assertEquals(3, source.readBytes)
        }
        assertEquals(1, source.closes)
    }
    @Test fun shortReadsPreservePayloadAndNeverExposeTheTag() {
        val bytes = ByteArray(256) { it.toByte() }
        "TAG".toByteArray().copyInto(bytes, 128)
        val source = Tracked(bytes, 1)
        MpegAudioBytes(source).use { audio ->
            assertEquals(3, source.readBytes)
            val actual = ByteArray(128)
            for (i in actual.indices) assertEquals(1, audio.readAt(i.toLong(), actual, i, 1))
            assertArrayEquals(bytes.copyOf(128), actual)
            assertEquals(-1, audio.readAt(128, actual, 0, 1))
            assertEquals(0, audio.readAt(Long.MAX_VALUE, actual, 0, 0))
        }
        assertEquals(1, source.closes)
        assertEquals("TAG", String(bytes, 128, 3))
    }
    @Test fun markerElsewhereAndIncompleteTagsDoNotTruncateAudio() {
        for (length in listOf(127, 256)) {
            val bytes = ByteArray(length)
            "TAG".toByteArray().copyInto(bytes, length - 127)
            val source = Tracked(bytes)
            MpegAudioBytes(source).use { assertEquals(length.toLong(), it.size) }
            assertEquals(if (length < 128) 0 else 3, source.readBytes)
        }
    }
    @Test fun positionedReadsRespectRangesAndTheExactPayloadEnd() {
        val bytes = ByteArray(256); "TAG".toByteArray().copyInto(bytes, 128)
        MpegAudioBytes(Tracked(bytes)).use { audio ->
            val result = ByteArray(8)
            assertEquals(2, audio.readAt(126, result, 2, 6))
            assertEquals(-1, audio.readAt(1000, result, 0, 1))
            assertThrows(IOException::class.java) { audio.readAt(Long.MAX_VALUE, result, 0, 1) }
            assertThrows(IOException::class.java) { audio.readAt(-1, result, 0, 1) }
            assertThrows(IOException::class.java) { audio.readAt(0, result, 7, 2) }
        }
    }
    @Test fun invalidFooterReadsFailInsteadOfLoopingOrInventingLength() {
        for (count in listOf(-1, 0, 4)) {
            val source = object : AudioBytes {
                override val size = 128L
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = count
                override fun close() {}
            }
            assertThrows(IOException::class.java) { MpegAudioBytes(source) }
        }
    }
    @Test fun cancellationDoesNotProbeOrReadTheSource() {
        val source = Tracked(ByteArray(256))
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedIOException::class.java) { MpegAudioBytes(source) } }
        finally { Thread.interrupted() }
        assertEquals(0, source.readBytes)
    }
}
