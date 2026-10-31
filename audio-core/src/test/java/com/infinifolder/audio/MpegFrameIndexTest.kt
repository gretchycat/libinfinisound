package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class MpegFrameIndexTest {
    private fun packets(count: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        repeat(count) { i ->
            val size = if (i % 2 == 0) 417 else 522
            val bytes = ByteArray(size) { (i % 127).toByte() }
            bytes[0] = 0xff.toByte(); bytes[1] = 0xfb.toByte()
            bytes[2] = if (i % 2 == 0) 0x90.toByte() else 0xa0.toByte(); bytes[3] = 0
            result.write(bytes)
        }
        return result.toByteArray()
    }
    private fun offset(packet: Int) = packet / 2L * (417 + 522) + if (packet % 2 == 1) 417 else 0

    @Test fun variableBitrateAnchorsUseActualPacketLengthsAndSampleCounts() {
        val index = MpegFrameIndex(MemoryAudioInput(packets(1000), "test.mp3"), 44100)
        for (packet in listOf(0, 301, 700, 3, 301)) {
            val anchor = index.before(packet * 1152L + 100)
            assertEquals(packet * 1152L, anchor.frame)
            assertEquals(offset(packet), anchor.byteOffset)
            assertEquals(1152L, anchor.headerFrames)
        }
    }

    @Test fun id3AndItsOptionalFooterAreExcludedFromPacketOffsets() {
        val tag = ByteArray(40)
        "ID3".toByteArray().copyInto(tag)
        tag[5] = 16; tag[9] = 20
        val index = MpegFrameIndex(MemoryAudioInput(tag + packets(20), "tagged.mp3"), 44100)
        assertEquals(40L + offset(10), index.before(10 * 1152L).byteOffset)
    }

    @Test fun indexedSliceIsReopenableAndMapsExactlyToOriginalBytes() {
        val bytes = packets(100)
        val index = MpegFrameIndex(MemoryAudioInput(bytes, "source.mp3"), 44100)
        val anchor = index.before(40 * 1152L)
        val input = index.inputFrom(anchor)
        repeat(2) {
            input.open().use { source ->
                assertEquals(bytes.size - anchor.byteOffset, source.size)
                val actual = ByteArray(600); assertEquals(600, source.readAt(0, actual, 0, actual.size))
                assertArrayEquals(bytes.copyOfRange(anchor.byteOffset.toInt(), anchor.byteOffset.toInt() + 600), actual)
                assertEquals(-1, source.readAt(source.size, actual, 0, actual.size))
                assertEquals(0, source.readAt(source.size, actual, 0, 0))
            }
        }
    }

    @Test fun shortReadsAreHandledAndEveryIndexHandleIsClosed() {
        val memory = MemoryAudioInput(packets(100), "short.mp3")
        var opens = 0; var closes = 0
        val input = object : AudioInput {
            override val name = memory.name
            override fun open(): AudioBytes {
                opens++
                val delegate = memory.open()
                return object : AudioBytes by delegate {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) =
                        delegate.readAt(position, buffer, offset, minOf(3, length))
                    override fun close() { closes++; delegate.close() }
                }
            }
        }
        val index = MpegFrameIndex(input, 44100)
        assertEquals(offset(90), index.before(90 * 1152L).byteOffset)
        assertEquals(offset(3), index.before(3 * 1152L).byteOffset)
        assertEquals(opens, closes)
    }

    @Test fun malformedId3LengthIsRejected() {
        val tag = ByteArray(10); "ID3".toByteArray().copyInto(tag); tag[6] = 0x80.toByte()
        assertThrows(IOException::class.java) { MpegFrameIndex(MemoryAudioInput(tag + packets(4), "bad.mp3"), 44100).before(0) }
    }

    @Test fun missingPacketOrChangedRateCannotFabricateAnAnchor() {
        val index = MpegFrameIndex(MemoryAudioInput(packets(4), "short.mp3"), 44100)
        assertThrows(IOException::class.java) { index.before(20 * 1152L) }
        val wrong = MpegFrameIndex(MemoryAudioInput(packets(4), "wrong.mp3"), 48000)
        assertThrows(IOException::class.java) { wrong.before(0) }
    }

    @Test fun slicedReadsRejectNegativeAndOverflowingPositions() {
        val index = MpegFrameIndex(MemoryAudioInput(packets(8), "source.mp3"), 44100)
        index.inputFrom(index.before(1152)).open().use { source ->
            assertThrows(IOException::class.java) { source.readAt(-1, ByteArray(4), 0, 4) }
            assertThrows(IOException::class.java) { source.readAt(Long.MAX_VALUE, ByteArray(4), 0, 4) }
        }
    }

    @Test fun cancellationStopsHeaderIndexing() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                MpegFrameIndex(MemoryAudioInput(packets(30), "cancel.mp3"), 44100).before(20 * 1152L)
            }
        } finally { Thread.interrupted() }
    }
}
