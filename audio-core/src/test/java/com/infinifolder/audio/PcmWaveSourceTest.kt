package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors

class PcmWaveSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val input = MemoryAudioInput(byteArrayOf(1, 2, 3), "test.unknown")
    private class Decoder(private val frames: Long = 100000, private val known: Boolean = true,
        private val failAfter: Long = Long.MAX_VALUE, private val silent: Boolean = false,
        override val name: String = "Test.PCM") : PcmDecoder {
        var emitted = 0L
        var closes = 0
        var opens = 0
        override fun matches(input: AudioInput, probe: AudioProbe) = true
        override fun open(input: AudioInput): PcmStream {
            opens++
            return object : PcmStream {
                override val format = PcmFormat(44100, 2, if (known) frames else null, 24)
                override val decoderName = name
                private var position = 0L
                private var closed = false
                override fun read(samples: IntArray, offset: Int, length: Int): Int {
                    if (position / 2 >= failAfter) throw IOException("Injected decoder failure")
                    val count = minOf(length.toLong(), frames * 2 - position).toInt()
                    if (count == 0) return -1
                    repeat(count) { samples[offset + it] = if (silent) 0 else ((position + it).toInt() * 7919) shl 8 }
                    position += count; emitted += count / 2
                    return count
                }
                override fun close() { if (!closed) { closes++; closed = true } }
            }
        }
    }
    private fun open(decoder: Decoder, file: File = temporary.newFile(), cancelled: () -> Boolean = { false }) =
        AudioFileProcessor(listOf(decoder)).openWaveSource(input, file, cancelled = cancelled)

    @Test fun openingLongKnownPcmDecodesOnlyOneBoundedPrefix() {
        val decoder = Decoder(frames = 100_000_000)
        val file = temporary.newFile()
        open(decoder, file).use { source ->
            assertTrue(source.progressive)
            assertEquals(4096, decoder.emitted.toInt())
            assertTrue(file.length() < 32 * 1024)
            assertEquals(600_000_044L, source.metadata.length)
            assertEquals("decoded-prefix-disk-cache", source.metadata.attributes["access"])
            assertEquals("decode-intervening-frames", source.metadata.attributes["forwardSeek"])
            assertEquals("audio/wav", source.metadata.content.mimeType)
        }
        assertEquals(1, decoder.closes)
    }

    @Test fun positionalReadsPreserveSequentialCursorAndReuseDecodedBytes() {
        val decoder = Decoder()
        open(decoder).use { source ->
            val a = ByteArray(50); val b = ByteArray(50)
            source.read(a)
            source.readAt(30000, b)
            val after = decoder.emitted
            source.readAt(44, b)
            assertEquals(after, decoder.emitted)
            source.read(a)
            source.readAt(50, b)
            assertArrayEquals(a, b)
            assertEquals(0, source.readAt(source.metadata.length!!, b, 0, 0))
            assertEquals(-1, source.readAt(source.metadata.length!!, b))
        }
    }

    @Test fun unknownFrameCountsRetainCompletePreparation() {
        val decoder = Decoder(10000, known = false)
        open(decoder).use { source ->
            assertFalse(source.progressive)
            assertEquals(10000L, source.preparation.format.totalFrames)
            assertNotNull(source.preparation.signalMetrics)
            assertEquals(10000L, decoder.emitted)
        }
        assertEquals(1, decoder.closes)
    }

    @Test fun portableWaveTransportIsByteExactAtEverySourcePrecision() {
        for (name in listOf("chord.mp3", "chord.wav", "chord.flac", "high-resolution.flac", "full-resolution.flac")) {
            val file = temporary.newFile(name)
            javaClass.getResourceAsStream("/audio/$name")!!.use { src -> file.outputStream().use { src.copyTo(it) } }
            val scratch = temporary.newFile("$name.lazy.wav")
            val expected = temporary.newFile("$name.full.wav")
            val processor = AudioFileProcessor()
            processor.prepare(FileAudioInput(file), expected, DecoderPreference.PORTABLE_ONLY)
            val completions = mutableListOf<AudioPreparation>()
            processor.openWaveSource(FileAudioInput(file), scratch, DecoderPreference.PORTABLE_ONLY,
                observer = object : AudioPreparationObserver { override fun prepared(result: AudioPreparation) { completions += result } }).use { source ->
                assertTrue(name, source.progressive)
                val actual = source.asInputStream(ownsSource = false).readBytes()
                assertArrayEquals(name, expected.readBytes(), actual)
                assertEquals(1, completions.size)
                assertEquals(source.preparation.format, completions.single().format)
            }
        }
    }

    @Test fun aacTransportPreservesExactTimingAndHeaderWithoutAssumingDeterministicNoiseSynthesis() {
        // JAAD's PNS uses a shared evolving randomState. Separate AAC decodes are not bit-identical.
        val file = temporary.newFile("chord.m4a")
        javaClass.getResourceAsStream("/audio/chord.m4a")!!.use { src -> file.outputStream().use { src.copyTo(it) } }
        val processor = AudioFileProcessor()
        val expected = temporary.newFile()
        val prepared = processor.prepare(FileAudioInput(file), expected, DecoderPreference.PORTABLE_ONLY)
        processor.openWaveSource(FileAudioInput(file), temporary.newFile(), DecoderPreference.PORTABLE_ONLY).use { source ->
            assertTrue(source.progressive)
            assertEquals(prepared.format, source.preparation.format)
            val actual = source.asInputStream(ownsSource = false).readBytes()
            assertEquals(expected.length(), actual.size.toLong())
            assertArrayEquals(expected.readBytes().copyOf(44), actual.copyOf(44))
            assertTrue(actual.drop(44).any { it != 0.toByte() })
        }
    }

    @Test fun lateDecodeFailureRetriesFullPreparationThroughTheRegistry() {
        val bad = Decoder(failAfter = 4096, name = "Test.Failing")
        val good = Decoder(name = "Test.Fallback")
        val processor = AudioFileProcessor(listOf(bad, good))
        processor.openWaveSource(input, temporary.newFile()).use { source ->
            assertTrue(source.progressive)
            val data = ByteArray(200)
            assertEquals(200, source.readAt(100000, data))
            assertFalse(source.progressive)
            assertEquals("Test.Fallback", source.preparation.decoder)
            assertEquals(100000L, good.emitted)
        }
        assertEquals(2, bad.closes)
        assertEquals(1, good.closes)
    }

    @Test fun initialDecodeFailureFallsBackBeforePublishingSource() {
        val bad = Decoder(failAfter = 0)
        val good = Decoder(name = "Test.Fallback")
        AudioFileProcessor(listOf(bad, good)).openWaveSource(input, temporary.newFile()).use {
            assertEquals("Test.Fallback", it.preparation.decoder)
            assertEquals(4096L, good.emitted)
        }
        assertEquals(1, bad.closes)
        assertEquals(1, good.closes)
    }

    @Test fun cancellationDoesNotRestartTheDecoderOrInventEof() {
        val decoder = Decoder()
        var cancelled = false
        open(decoder, cancelled = { cancelled }).use { source ->
            cancelled = true
            assertThrows(InterruptedIOException::class.java) { source.readAt(50000, ByteArray(100)) }
            assertEquals(1, decoder.opens)
        }
        assertEquals(1, decoder.closes)
    }

    @Test fun borrowedAnalysisViewsCloseIndependentlyOfPlayback() {
        val decoder = Decoder()
        val source = open(decoder)
        val resource = source.borrowedResource()
        val first = resource.open(); val second = resource.open()
        first.read(ByteArray(50)); first.close()
        assertEquals(0, decoder.closes)
        assertEquals(50, second.read(ByteArray(50)))
        source.close(); source.close(); second.close()
        assertEquals(1, decoder.closes)
        assertThrows(IOException::class.java) { second.read(ByteArray(10)) }
    }

    @Test fun invalidRangesNeverTriggerDecoderFallback() {
        val decoder = Decoder()
        open(decoder).use { source ->
            assertThrows(IOException::class.java) { source.readAt(-1, ByteArray(20)) }
            assertThrows(IOException::class.java) { source.readAt(Long.MAX_VALUE, ByteArray(20)) }
            assertEquals(1, decoder.opens)
        }
    }

    @Test fun oversizedRiffIsRejectedWithoutReadingOrLeakingTheCodec() {
        val decoder = Decoder(frames = Long.MAX_VALUE)
        val file = temporary.newFile()
        assertThrows(IOException::class.java) { open(decoder, file) }
        assertEquals(0L, decoder.emitted)
        assertEquals(1, decoder.closes)
        assertFalse(file.exists())
    }

    @Test fun silentPcmIsValidAndMetricsOnlyDescribeCompleteOutput() {
        val decoder = Decoder(silent = true)
        var result: AudioPreparation? = null
        AudioFileProcessor(listOf(decoder)).openWaveSource(input, temporary.newFile(),
            observer = object : AudioPreparationObserver { override fun prepared(value: AudioPreparation) { result = value } }).use { source ->
            assertNull(result)
            source.readAt(source.metadata.length!! - 1, ByteArray(1))
            assertTrue(result!!.signalMetrics!!.isSilent)
        }
    }

    @Test fun concurrentIndependentPositionsReturnTheSameBytes() {
        open(Decoder()).use { source ->
            val executor = Executors.newFixedThreadPool(3)
            try {
                val positions = listOf(0L, 300000L, 4000L, 12000L)
                val results = positions.map { p -> executor.submit<ByteArray> { ByteArray(300).also { source.readAt(p, it) } } }
                results.forEachIndexed { i, f ->
                    val expected = ByteArray(300); source.readAt(positions[i], expected)
                    assertArrayEquals(expected, f.get())
                }
            } finally { executor.shutdownNow() }
        }
    }
}
