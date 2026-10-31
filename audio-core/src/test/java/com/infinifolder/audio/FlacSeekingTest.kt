package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException

class FlacSeekingTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.use { it.readBytes() }
    private fun read(stream: PcmStream, frames: Int): IntArray {
        val samples = IntArray(frames * stream.format.channels)
        var at = 0
        while (at < samples.size) {
            val n = stream.read(samples, at, samples.size - at)
            assertTrue("Premature FLAC EOF", n > 0); at += n
        }
        return samples
    }
    @Test fun exactSeekingPreservesEverySourceBitAndChannel() {
        for (name in listOf("mono.flac", "stereo.flac", "high-resolution.flac", "full-resolution.flac")) {
            val input = MemoryAudioInput(fixture(name), name)
            val expected = FlacPcmDecoder.open(input).use { read(it, 5003) }
            (FlacPcmDecoder.open(input) as FrameSeekablePcmStream).use { stream ->
                for (frame in listOf(4097, 1, 4999, 0, 4095, 2500)) {
                    stream.seekToFrame(frame.toLong())
                    val count = minOf(257, 5003 - frame)
                    assertArrayEquals(name, expected.copyOfRange(frame * stream.format.channels, (frame + count) * stream.format.channels), read(stream, count))
                }
                stream.seekToFrame(5003)
                assertEquals(-1, stream.read(IntArray(stream.format.channels), 0, stream.format.channels))
            }
        }
    }
    @Test fun seekingClearsPartlyConsumedDecodedBlocks() {
        val input = MemoryAudioInput(fixture("chord.flac"), "audio.flac")
        val expected = FlacPcmDecoder.open(input).use { read(it, 1000) }
        (FlacPcmDecoder.open(input) as FrameSeekablePcmStream).use { stream ->
            read(stream, 13); stream.seekToFrame(7)
            assertArrayEquals(expected.copyOfRange(14, expected.size), read(stream, 993))
        }
    }
    private class Tracked(private val data: ByteArray, private val chunk: Int = Int.MAX_VALUE) : AudioInput {
        override val name = "tracked.flac"
        var bytes = 0L
        var closes = 0
        override fun open(): AudioBytes = object : AudioBytes {
            override val size = data.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position >= size) return -1
                val n = minOf(length, chunk, (size - position).toInt())
                data.copyInto(buffer, offset, position.toInt(), position.toInt() + n); bytes += n
                return n
            }
            override fun close() { closes++ }
        }
    }
    @Test fun coldFarSeekDoesNotReadOrDecodeTheWholePrefix() {
        val data = synthetic(100000, false)
        val input = Tracked(data)
        (FlacPcmDecoder.open(input) as FrameSeekablePcmStream).use { stream ->
            input.bytes = 0
            stream.seekToFrame(99900L * 4096 + 37)
            assertEquals(value(99900), read(stream, 1)[0])
            assertTrue("Read ${input.bytes} of ${data.size} encoded bytes", input.bytes < data.size / 2 && input.bytes < 512 * 1024)
            input.bytes = 0
            stream.seekToFrame(7)
            assertEquals(value(0), read(stream, 1)[0])
            assertTrue(input.bytes < 65536)
        }
        assertEquals(1, input.closes)
    }
    @Test fun variableBlocksUseSampleNumbersRatherThanFrameNumbers() {
        val input = MemoryAudioInput(synthetic(10000, true), "variable.flac")
        (FlacPcmDecoder.open(input) as FrameSeekablePcmStream).use { stream ->
            for (block in listOf(9900, 100, 7501, 0, 9999)) {
                val start = block / 2L * 768 + if (block % 2 == 1) 256 else 0
                stream.seekToFrame(start + 13)
                assertEquals(value(block), read(stream, 1)[0])
            }
        }
    }
    @Test fun aValidFrameEmbeddedInVerbatimSamplesIsNotASeekAnchor() {
        val input = MemoryAudioInput(synthetic(128, false, true), "embedded.flac")
        val expected = FlacPcmDecoder.open(input).use { read(it, 128 * 4096) }
        (FlacPcmDecoder.open(input) as FrameSeekablePcmStream).use { stream ->
            for (target in listOf(64 * 4096 + 2500, 63 * 4096 + 2500, 65 * 4096 + 1)) {
                stream.seekToFrame(target.toLong())
                assertArrayEquals(expected.copyOfRange(target, target + 128), read(stream, 128))
            }
        }
    }
    @Test fun shortPositionalReadsRemainExact() {
        val input = Tracked(fixture("full-resolution.flac"), 1)
        (FlacPcmDecoder.open(input) as FrameSeekablePcmStream).use { stream ->
            stream.seekToFrame(4999)
            val actual = read(stream, 4)
            for (frame in 4999..5002) for (channel in 0..1) {
                val raw = ((frame * 7919L + channel * 17011L) % (1L shl 32)) - (1L shl 31)
                assertEquals(raw.toInt(), actual[(frame - 4999) * 2 + channel])
            }
        }
        assertEquals(1, input.closes)
    }
    @Test fun corruptedTargetFrameCannotBeReportedAsValidPcm() {
        val bytes = fixture("chord.flac"); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        (FlacPcmDecoder.open(MemoryAudioInput(bytes, "corrupt.flac")) as FrameSeekablePcmStream).use { stream ->
            assertThrows(IOException::class.java) { stream.seekToFrame(stream.format.totalFrames!! - 1) }
        }
    }
    @Test fun invalidSeekCancellationAndCloseKeepTheirContracts() {
        val input = Tracked(fixture("mono.flac"))
        val stream = FlacPcmDecoder.open(input) as FrameSeekablePcmStream
        assertThrows(IllegalArgumentException::class.java) { stream.seekToFrame(-1) }
        assertThrows(IllegalArgumentException::class.java) { stream.seekToFrame(5004) }
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedIOException::class.java) { stream.seekToFrame(4000) } }
        finally { Thread.interrupted() }
        read(stream, 1)
        stream.close(); stream.close()
        assertEquals(1, input.closes)
        assertThrows(IOException::class.java) { stream.seekToFrame(0) }
        assertThrows(IOException::class.java) { read(stream, 1) }
    }
    private fun value(block: Int) = ((block * 7919) and 65535) shl 16
    /** Constant subframes with independent block values; no external encoder needed by tests. */
    private fun synthetic(blocks: Int, variable: Boolean, embedded: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray()); output.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        val info = ByteArray(34)
        val min = if (variable) 256 else 4096; val max = if (variable) 512 else 4096
        info[0] = (min ushr 8).toByte(); info[1] = min.toByte(); info[2] = (max ushr 8).toByte(); info[3] = max.toByte()
        val total = if (variable) blocks / 2L * 768 + if (blocks % 2 == 1) 256 else 0 else blocks * 4096L
        val packed = (48000L shl 44) or (15L shl 36) or total
        repeat(8) { info[10 + it] = (packed ushr (56 - 8 * it)).toByte() }; output.write(info)
        var sample = 0L
        repeat(blocks) { block ->
            val count = if (variable) if (block % 2 == 0) 256 else 512 else 4096
            val payload = if (embedded) ByteArray(count * 2).also { frame(block, count, sample, variable, null).copyInto(it, 2048) } else null
            output.write(frame(block, count, sample, variable, payload)); sample += count
        }
        return output.toByteArray()
    }
    private fun frame(block: Int, count: Int, sample: Long, variable: Boolean, payload: ByteArray?): ByteArray {
        val frame = ByteArrayOutputStream(); frame.write(255); frame.write(if (variable) 249 else 248); frame.write(112); frame.write(0)
        frame.write(coded(if (variable) sample else block.toLong())); frame.write((count - 1) ushr 8); frame.write((count - 1) and 255)
        frame.write(crc(frame.toByteArray(), 8, 7))
        if (payload == null) {
            frame.write(0); frame.write(value(block) ushr 24); frame.write(value(block) ushr 16 and 255)
        } else { frame.write(2); frame.write(payload) }
        val crc = crc(frame.toByteArray(), 16, 0x8005); frame.write(crc ushr 8); frame.write(crc and 255)
        return frame.toByteArray()
    }
    private fun coded(value: Long): ByteArray {
        if (value < 128) return byteArrayOf(value.toByte())
        var count = 2
        while (value >= (1L shl (5 * count + 1))) count++
        return ByteArray(count) { i -> if (i == 0) ((255 shl (8 - count)) or (value ushr (6 * (count - 1))).toInt()).toByte()
            else (128 or ((value ushr (6 * (count - i - 1))).toInt() and 63)).toByte() }
    }
    private fun crc(bytes: ByteArray, width: Int, polynomial: Int): Int {
        var result = 0
        for (byte in bytes) {
            result = result xor ((byte.toInt() and 255) shl (width - 8))
            repeat(8) { result = ((result shl 1) xor if (result and (1 shl (width - 1)) != 0) polynomial else 0) and ((1 shl width) - 1) }
        }
        return result
    }
}
