package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PcmFrameSeekingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val input = MemoryAudioInput(byteArrayOf(1), "test.unknown")
    private class Decoder(val frames: Long = 100000, val failSeek: Boolean = false,
        val runtimeFail: Boolean = false) : PcmDecoder {
        override val name = "Test.FrameSeek"
        var emitted = 0L
        val seeks = mutableListOf<Long>()
        var closes = 0
        override fun matches(input: AudioInput, probe: AudioProbe) = true
        override fun open(input: AudioInput) = object : FrameSeekablePcmStream {
            override val format = PcmFormat(48000, 2, frames, 32)
            override val decoderName = name
            private var frame = 0L
            private var closed = false
            override fun seekToFrame(frame: Long) {
                if (runtimeFail) throw IllegalStateException("Injected backend failure")
                if (failSeek) throw IOException("Injected frame seek failure")
                require(frame in 0..frames)
                seeks += frame
                this.frame = frame
            }
            override fun read(samples: IntArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val count = minOf(length / 2L, frames - frame).toInt()
                if (count == 0) return -1
                repeat(count * 2) { samples[offset + it] = ((frame * 2 + it) * 7919).toInt() }
                frame += count; emitted += count
                return count * 2
            }
            override fun close() { if (!closed) { closes++; closed = true } }
        }
    }
    private fun open(decoder: Decoder, observer: AudioPreparationObserver? = null) =
        AudioFileProcessor(listOf(decoder)).openWaveSource(input, temporary.newFile(), observer = observer)
    private fun expected(position: Long, length: Int) = ByteArray(length) { i ->
        val byte = position + i - 44
        (((byte / 4 * 7919).toInt()) ushr ((byte % 4).toInt() * 8)).toByte()
    }

    @Test fun coldFarReadDecodesOnlyItsBlocksAndAdvertisesTheActualAccessCost() {
        val decoder = Decoder(10_000_000)
        open(decoder).use { source ->
            assertEquals(4096L, decoder.emitted)
            val position = 44 + 9_000_000L * 8
            val bytes = ByteArray(300)
            source.readAt(position, bytes)
            assertArrayEquals(expected(position, bytes.size), bytes)
            assertTrue(decoder.emitted <= 3 * 4096)
            assertEquals("decoded-block-disk-cache", source.metadata.attributes["access"])
            assertEquals("decoder-frame-seek", source.metadata.attributes["forwardSeek"])
            assertTrue(source.metadata.attributes.getValue("cachedPcmBytes").toLong() < 100000)
        }
        assertEquals(1, decoder.closes)
    }

    @Test fun backwardAndRepeatedReadsReuseBlocksWithoutRepositioningTheDecoder() {
        val decoder = Decoder()
        open(decoder).use { source ->
            val bytes = ByteArray(500)
            source.readAt(44 + 50000L * 8, bytes)
            val count = decoder.emitted; val seeks = decoder.seeks.toList()
            source.readAt(44, bytes)
            source.readAt(44 + 50000L * 8, bytes)
            assertEquals(count, decoder.emitted)
            assertEquals(seeks, decoder.seeks)
            assertArrayEquals(expected(44 + 50000L * 8, bytes.size), bytes)
        }
    }

    @Test fun sparseFileHolesAreDecodedBeforeTheyCanBeRead() {
        val decoder = Decoder()
        open(decoder).use { source ->
            val bytes = ByteArray(501)
            source.readAt(44 + 90000L * 8, bytes)
            val count = decoder.emitted
            val position = 44 + 45000L * 8 + 3
            source.readAt(position, bytes)
            assertTrue(decoder.emitted > count)
            assertArrayEquals(expected(position, bytes.size), bytes)
        }
    }

    @Test fun unalignedReadsAcrossBlockBoundariesPreserveEveryPcmByte() {
        open(Decoder()).use { source ->
            for (position in listOf(44 + 4096L * 8 - 5, 44 + 45056L * 8 - 3)) {
                val bytes = ByteArray(700)
                source.readAt(position, bytes)
                assertArrayEquals(expected(position, bytes.size), bytes)
            }
        }
    }

    @Test fun outOfOrderReadsKeepIndependentSequentialCursors() {
        open(Decoder()).use { source ->
            val first = source.borrowedResource().open(); val second = source.borrowedResource().open()
            first.read(ByteArray(44))
            (second as PositionalReadable).readAt(44 + 90000L * 8, ByteArray(100))
            val bytes = ByteArray(100); first.read(bytes)
            assertArrayEquals(expected(44, bytes.size), bytes)
            first.close(); second.close()
        }
    }

    @Test fun metricsWaitUntilEveryBlockHasBeenDecodedExactlyOnce() {
        val decoder = Decoder(9000)
        val completed = mutableListOf<AudioPreparation>()
        open(decoder, object : AudioPreparationObserver {
            override fun prepared(result: AudioPreparation) { completed += result }
        }).use { source ->
            source.readAt(source.metadata.length!! - 1, ByteArray(1))
            assertTrue(completed.isEmpty())
            source.readAt(44 + 4096L * 8, ByteArray(100))
            assertEquals(1, completed.size)
            assertEquals(9000L, decoder.emitted)
            assertEquals(9000L, completed.single().format.totalFrames)
            assertEquals(1, decoder.closes)
            source.readAt(44 + 4096L * 8, ByteArray(100))
            assertEquals(1, completed.size)
        }
        assertEquals(1, decoder.closes)
    }

    @Test fun failedFrameSeekRetainsCompletePreparationFallback() {
        val decoder = Decoder(failSeek = true)
        open(decoder).use { source ->
            val position = 44 + 90000L * 8
            val bytes = ByteArray(100)
            assertEquals(100, source.readAt(position, bytes))
            assertFalse(source.progressive)
            assertArrayEquals(expected(position, bytes.size), bytes)
        }
    }

    @Test fun runtimeBackendSeekFailureRetainsCompletePreparationFallback() {
        open(Decoder(runtimeFail = true)).use { source ->
            val position = 44 + 90000L * 8
            val bytes = ByteArray(100)
            assertEquals(100, source.readAt(position, bytes))
            assertFalse(source.progressive)
            assertArrayEquals(expected(position, bytes.size), bytes)
        }
    }

    @Test fun portableWaveSeekingClearsPendingDecodedBlocksAndPreservesPrecision() {
        val file = temporary.newFile("chord.wav")
        javaClass.getResourceAsStream("/audio/chord.wav")!!.use { src -> file.outputStream().use { src.copyTo(it) } }
        val linear = WavePcmDecoder.open(FileAudioInput(file))
        val expected = IntArray(2000)
        linear.read(expected, 0, expected.size); linear.close()
        (WavePcmDecoder.open(FileAudioInput(file)) as FrameSeekablePcmStream).use { stream ->
            stream.read(IntArray(100), 0, 100)
            stream.seekToFrame(50000)
            stream.read(IntArray(100), 0, 100)
            stream.seekToFrame(0)
            val actual = IntArray(2000); stream.read(actual, 0, actual.size)
            assertArrayEquals(expected, actual)
            stream.seekToFrame(stream.format.totalFrames!!)
            assertEquals(-1, stream.read(actual, 0, actual.size))
            assertThrows(IllegalArgumentException::class.java) { stream.seekToFrame(-1) }
        }
    }
}
