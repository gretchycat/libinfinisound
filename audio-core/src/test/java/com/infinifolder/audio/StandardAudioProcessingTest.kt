package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StandardAudioProcessingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/audio/$name")!!.use { it.readBytes() }
    private fun input(name: String) = MemoryAudioInput(fixture(name), name)
    private fun decode(name: String, blockFrames: Int = 257): Pair<PcmFormat, IntArray> {
        AudioFileProcessor().openPcm(input(name), DecoderPreference.PORTABLE_ONLY).use { stream ->
            val samples = ArrayList<Int>()
            val buffer = IntArray(blockFrames * stream.format.channels)
            while (true) {
                val n = stream.read(buffer, 0, buffer.size)
                if (n < 0) break
                assertTrue(n > 0 && n % stream.format.channels == 0)
                for (i in 0 until n) samples.add(buffer[i])
            }
            return stream.format to samples.toIntArray()
        }
    }
    @Test fun codecRuntimeHasNoAndroidClasses() {
        try {
            Class.forName("android.media.MediaExtractor")
            fail("Portable codecs must not depend on the Android runtime")
        } catch (_: ClassNotFoundException) {}
    }
    @Test fun portableFormatsDecodeWithoutAndroid() {
        for (name in listOf("chord.mp3", "chord.aac", "chord.m4a", "chord.flac")) {
            val destination = temporary.newFile("$name.wav")
            val result = AudioFileProcessor().prepare(input(name), destination, DecoderPreference.PORTABLE_ONLY)
            assertTrue(name, result.decoder.startsWith("Portable."))
            assertEquals(name, 44100, result.format.sampleRate)
            assertEquals(name, 2, result.format.channels)
            assertTrue("$name duration ${result.format.durationMs}", result.format.durationMs!! in 1400..1650)
            assertTrue(name, result.signalMetrics!!.peakAmplitude > 0.05f)
            println("$name -> ${result.decoder}: ${result.format}")
        }
    }
    @Test fun flacIsSampleAccurateAndPacketReadsAreIndependentOfCallerBufferSize() {
        val wave = decode("chord.wav", 1)
        val flac = decode("chord.flac", 31)
        assertEquals(wave.first.sampleRate, flac.first.sampleRate)
        assertArrayEquals(wave.second, flac.second)
    }
    @Test fun flacPreservesMonoStereoAndHighResolutionSamples() {
        for ((name, bits, channels) in listOf(Triple("mono.flac",16,1),Triple("stereo.flac",16,2),Triple("high-resolution.flac",24,2),Triple("full-resolution.flac",32,2))) {
            val (format, actual) = decode(name, 13)
            assertEquals(bits, format.bitsPerSample)
            assertEquals(channels, format.channels)
            assertEquals(5003 * channels, actual.size)
            for (frame in 0 until 5003) for (channel in 0 until channels) {
                val raw = ((frame * 7919L + channel * 17011L) % (1L shl bits)) - (1L shl (bits - 1))
                assertEquals("$name frame=$frame channel=$channel", (raw shl (32 - bits)).toInt(), actual[frame * channels + channel])
            }
        }
    }
    @Test fun mp4EditListTrimsEncoderDelayAndPadding() {
        val (_, samples) = decode("chord.m4a")
        assertEquals(66150 * 2, samples.size)
    }
    @Test fun lossyPortableDecodersPreserveTheMusicalTimeline() {
        val (_, source) = decode("chord.wav")
        for (name in listOf("chord.mp3", "chord.m4a")) {
            val (_, actual) = decode(name)
            assertEquals(name, source.size, actual.size)
            var signal = 0.0
            var error = 0.0
            for (i in source.indices) {
                val reference = source[i] / 2147483648.0
                val difference = actual[i] / 2147483648.0 - reference
                signal += reference * reference
                error += difference * difference
            }
            val snr = 10 * kotlin.math.log10(signal / error)
            println("$name portable/source SNR = $snr dB")
            if (snr <= 20) {
                var best = -1.0; var bestLag = 0
                for (lag in -2048..2048) {
                    var dot = 0.0; var a2 = 0.0; var b2 = 0.0
                    for (frame in 2048 until 10048) {
                        val a = source[frame * 2] / 2147483648.0
                        val b = actual[(frame + lag) * 2] / 2147483648.0
                        dot += a*b; a2 += a*a; b2 += b*b
                    }
                    val correlation = dot / kotlin.math.sqrt(a2*b2)
                    if (correlation > best) { best=correlation; bestLag=lag }
                }
                var actualEnergy=0.0
                for (v in actual) { val f=v/2147483648.0; actualEnergy += f*f }
                println("$name diagnostic: bestLag=$bestLag correlation=$best energyRatio=${actualEnergy/signal}")
            }
            assertTrue("$name timeline or signal differs: SNR=$snr dB", snr > 20)
        }
    }
    @Test fun platformTimingUsesTheSameExactContainerBoundaries() {
        val mp4 = AudioStreamTiming.read(input("chord.m4a"),44100)!!
        assertEquals(1024L,mp4.leadingFrames)
        assertEquals(66150L,mp4.totalFrames)
        val mp3 = AudioStreamTiming.read(input("chord.mp3"),44100)!!
        assertEquals(1105L,mp3.leadingFrames)
        assertEquals(66150L,mp3.totalFrames)
        assertEquals(5003L,AudioStreamTiming.read(input("full-resolution.flac"),48000)!!.totalFrames)
    }
    @Test fun failedPlatformDecodeRestartsPortableDecodeFromTheBeginning() {
        var closed = 0
        val broken = object : PcmDecoder {
            override val name = "broken platform"
            override fun matches(input: AudioInput, probe: AudioProbe) = true
            override fun open(input: AudioInput): PcmStream = object : PcmStream {
                override val format = PcmFormat(44100,2)
                private var first = true
                override fun read(samples: IntArray, offset: Int, length: Int): Int {
                    if (!first) throw IOException("Simulated codec failure after partial output")
                    first = false
                    samples.fill(Int.MAX_VALUE, offset, offset + 100)
                    return 100
                }
                override fun close() { closed++ }
            }
        }
        val baseline = temporary.newFile("baseline.wav")
        val fallback = temporary.newFile("fallback.wav")
        AudioFileProcessor().prepare(input("chord.mp3"), baseline, DecoderPreference.PORTABLE_ONLY)
        val result = AudioFileProcessor(listOf(broken)).prepare(input("chord.mp3"), fallback)
        assertEquals("Portable.MPEG",result.decoder)
        assertEquals(1, closed)
        assertArrayEquals(baseline.readBytes(), fallback.readBytes())
    }
    @Test fun flacCrcCorruptionCannotProduceAReportedSuccessfulPreparation() {
        val data = fixture("chord.flac")
        data[data.lastIndex - 10] = (data[data.lastIndex - 10].toInt() xor 64).toByte()
        val output = temporary.newFile("invalid.wav")
        try {
            AudioFileProcessor().prepare(MemoryAudioInput(data,"invalid.flac"), output, DecoderPreference.PORTABLE_ONLY)
            fail("Corrupted FLAC must fail")
        } catch (_: IOException) { assertFalse(output.exists()) }
    }
    @Test fun unavailablePlatformBinaryFallsBackToPortableCodec() {
        val unavailable = object : PcmDecoder {
            override val name = "unavailable platform binary"
            override fun matches(input: AudioInput, probe: AudioProbe) = true
            override fun open(input: AudioInput): PcmStream = throw UnsatisfiedLinkError("Optional native codec is not installed")
        }
        val result = AudioFileProcessor(listOf(unavailable)).prepare(input("chord.flac"),temporary.newFile("linked.wav"))
        assertEquals("Portable.FLAC",result.decoder)
        assertEquals(1500L,result.format.durationMs)
    }
    @Test fun cancellationDiscardsPcmAndClosesDecoder() {
        var closed = false
        var reads = 0
        val decoder = object : PcmDecoder {
            override val name = "test decoder"
            override fun matches(input: AudioInput, probe: AudioProbe) = true
            override fun open(input: AudioInput): PcmStream = object : PcmStream {
                override val format = PcmFormat(48000,2)
                override fun read(samples: IntArray, offset: Int, length: Int): Int { reads++; return length }
                override fun close() { closed = true }
            }
        }
        val output = temporary.newFile("cancelled.wav")
        try {
            AudioFileProcessor(listOf(decoder)).prepare(MemoryAudioInput(byteArrayOf(1),"source.unknown"),output,cancelled={reads>0})
            fail("Cancelled preparation must fail")
        } catch (_: java.io.InterruptedIOException) {
            assertTrue(closed)
            assertFalse(output.exists())
            assertEquals(1, reads)
        }
    }
}
