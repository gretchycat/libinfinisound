package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException

class MpegPlaybackTest {
    private fun fixture() = javaClass.getResourceAsStream("/audio/chord.mp3")!!.use { it.readBytes() }
    private fun samples(bytes: ByteArray): IntArray = MpegPcmDecoder.open(MemoryAudioInput(bytes, "source.mp3")).use { stream ->
        val result = java.io.ByteArrayOutputStream()
        val buffer = IntArray(8192)
        val output = java.io.DataOutputStream(result)
        while (true) {
            val count = stream.read(buffer, 0, buffer.size)
            if (count < 0) break
            repeat(count) { output.writeInt(buffer[it]) }
        }
        val data = java.nio.ByteBuffer.wrap(result.toByteArray())
        IntArray(data.remaining() / 4) { data.int }
    }
    @Test fun trailingId3v1DoesNotDiscardTheLastAudioPacket() {
        val original = fixture()
        val tag = ByteArray(128)
        "TAGtest title".toByteArray().copyInto(tag)
        assertArrayEquals(samples(original), samples(original + tag))
    }
    @Test fun seeksThroughTaggedEndMatchUntaggedLinearSamples() {
        val original = fixture()
        val expected = samples(original)
        val tag = ByteArray(128); "TAG".toByteArray().copyInto(tag)
        (MpegPcmDecoder.open(MemoryAudioInput(original + tag, "tagged.mp3")) as FrameSeekablePcmStream).use { stream ->
            val frame = stream.format.totalFrames!! - 4096
            stream.seekToFrame(frame)
            val actual = IntArray(8192)
            var at = 0
            while (at < actual.size) {
                val count = stream.read(actual, at, actual.size - at)
                assertTrue("Tagged MPEG ended before its declared musical timeline", count > 0)
                at += count
            }
            assertArrayEquals(expected.copyOfRange((frame * 2).toInt(), expected.size), actual)
            assertEquals(-1, stream.read(IntArray(2), 0, 2))
        }
    }
    @Test fun portableMpegSeeksMatchEverySampleOfLinearDecoding() {
        val input = MemoryAudioInput(fixture(), "source.mp3")
        val expected: IntArray
        MpegPcmDecoder.open(input).use { stream ->
            expected = IntArray(stream.format.totalFrames!!.toInt() * stream.format.channels)
            var at = 0
            while (at < expected.size) {
                val count = stream.read(expected, at, minOf(8192, expected.size - at))
                assertTrue(count > 0); at += count
            }
        }
        (MpegPcmDecoder.open(input) as FrameSeekablePcmStream).use { stream ->
            for (frame in listOf(50000L, 5000L, 60000L, 0L, 50000L)) {
                stream.seekToFrame(frame)
                val actual = IntArray(4096)
                var at = 0
                while (at < actual.size) {
                    val count = stream.read(actual, at, actual.size - at)
                    assertTrue(count > 0); at += count
                }
                assertArrayEquals(expected.copyOfRange((frame * 2).toInt(), (frame * 2).toInt() + actual.size), actual)
            }
            stream.seekToFrame(stream.format.totalFrames!!)
            assertEquals(-1, stream.read(IntArray(2), 0, 2))
        }
    }
    @Test fun playbackPolicyUsesValidatedContentInsteadOfFilename() {
        assertTrue(StandardAudioDecoders.prefersPortablePlayback(MemoryAudioInput(fixture(), "wrong.bin")))
        val wave = javaClass.getResourceAsStream("/audio/chord.wav")!!.use { it.readBytes() }
        assertFalse(StandardAudioDecoders.prefersPortablePlayback(MemoryAudioInput(wave, "wrong.mp3")))
        assertFalse(StandardAudioDecoders.prefersPortablePlayback(MemoryAudioInput("ID3".toByteArray() + ByteArray(20), "bad.mp3")))
    }
    @Test fun cancelledPolicyProbeDoesNotStartAPlatformFallback() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                StandardAudioDecoders.prefersPortablePlayback(MemoryAudioInput(fixture(), "source.mp3"))
            }
        } finally { Thread.interrupted() }
    }
}
