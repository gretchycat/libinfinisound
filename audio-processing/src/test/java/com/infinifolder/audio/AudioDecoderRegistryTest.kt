package com.infinifolder.audio

import com.infinifolder.audio.model.AudioPcmData
import org.junit.Assert.*
import org.junit.Test

class AudioDecoderRegistryTest {
    @Test
    fun fallbackAcceptsSilentPcmAndRetainsDiagnostics() {
        val events = mutableListOf<String>()
        val silence = AudioPcmData(44100, 2, ShortArray(8820))
        fun decoder(name: String, render: () -> AudioPcmData?) = object : AudioDecoder {
            override val name = name
            override fun matches(probe: AudioProbe) = true
            override fun decode(request: AudioDecodeRequest): AudioPcmData? {
                events += name
                return render()
            }
            override fun sourceFormat(request: AudioDecodeRequest) = "composite audio"
        }
        val processor: AudioProcessor = AudioDecoderRegistry(listOf(
            decoder("unavailable") { null },
            decoder("broken") { throw IllegalArgumentException("invalid file") },
            decoder("silent") { silence },
            decoder("unused") { fail("Valid silence must stop fallback"); null }
        ))
        val observer = object : AudioDecodeObserver {
            override fun warning(decoder: String, error: Throwable) { events += "warning:$decoder" }
            override fun decoded(result: AudioDecodeResult) { events += "decoded:${result.decoder}" }
            override fun unsupported() { fail("Silent PCM is valid audio") }
        }
        val result = processor.decode(AudioDecodeRequest(byteArrayOf(1)), observer)!!
        assertSame(silence, result.pcm)
        assertEquals("composite audio", result.sourceFormat)
        assertEquals(listOf("unavailable", "broken", "warning:broken", "silent", "decoded:silent"), events)
    }

    @Test
    fun unsupportedInputDoesNotDecodeAndNotifiesOnlyItsOwnObserver() {
        var failures = 0
        val processor: AudioProcessor = AudioDecoderRegistry(listOf(object : AudioDecoder {
            override val name = "other format"
            override fun matches(probe: AudioProbe) = false
            override fun decode(request: AudioDecodeRequest): AudioPcmData? {
                fail("A decoder must not run for an unmatched source")
                return null
            }
        }))
        val observer = object : AudioDecodeObserver {
            override fun unsupported() { failures++ }
        }
        val request = AudioDecodeRequest(byteArrayOf(1), "unknown")
        assertFalse(processor.isSupported(request.probe))
        assertNull(processor.decode(request, observer))
        assertNull(processor.decode(request))
        assertEquals(1, failures)
    }
}
