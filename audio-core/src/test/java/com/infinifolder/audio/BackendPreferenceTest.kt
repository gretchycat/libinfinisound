package com.infinifolder.audio

import com.infinifolder.audio.stream.MemoryAudioInput
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BackendPreferenceTest {
    private fun decoder(label:String, calls:MutableList<String>, fails:Boolean=false) = object:PcmDecoder {
        override val name=label
        override fun matches(input:AudioInput,probe:AudioProbe)=true
        override fun open(input:AudioInput):PcmStream {
            calls+=label
            if(fails) throw IOException("Rejected source")
            return object:PcmStream {
                override val format=PcmFormat(44100,1)
                override val decoderName=label
                override fun read(samples:IntArray,offset:Int,length:Int)=-1
                override fun close() {}
            }
        }
    }
    private val input=MemoryAudioInput(byteArrayOf(1,2,3),"unknown")
    @Test fun additionalCodecRunsAfterExistingBackendsReject() {
        val calls=mutableListOf<String>()
        AudioFileProcessor(listOf(decoder("platform",calls,true)),listOf(decoder("extra",calls)))
            .openPcm(input).use { assertEquals("extra",it.decoderName) }
        assertEquals(listOf("platform","extra"),calls)
    }
    @Test fun existingBackendStillWins() {
        val calls=mutableListOf<String>()
        AudioFileProcessor(listOf(decoder("platform",calls)),listOf(decoder("extra",calls)))
            .openPcm(input).close()
        assertEquals(listOf("platform"),calls)
    }
    @Test fun explicitPreferredSynthesizerPrecedesLegacyRendering() {
        val calls=mutableListOf<String>()
        AudioFileProcessor(listOf(decoder("platform",calls)),preferredDecoders=listOf(decoder("sf2",calls)))
            .openPcm(input).close()
        assertEquals(listOf("sf2"),calls)
    }
    @Test fun portableOnlyNeverLoadsEitherNativeBackend() {
        val calls=mutableListOf<String>()
        try {
            AudioFileProcessor(listOf(decoder("platform",calls)),listOf(decoder("extra",calls)),listOf(decoder("sf2",calls)))
                .openPcm(input,DecoderPreference.PORTABLE_ONLY).close()
            fail("Unknown input must reject")
        } catch(_:IOException) {}
        assertTrue(calls.isEmpty())
    }
    @Test fun portableFirstPrecedesAnOtherwisePreferredBackend() {
        val calls=mutableListOf<String>()
        val bytes=javaClass.getResourceAsStream("/audio/chord.wav")!!.use { it.readBytes() }
        AudioFileProcessor(preferredDecoders=listOf(decoder("native synth",calls)))
            .openPcm(MemoryAudioInput(bytes,"chord.wav"),DecoderPreference.PORTABLE_FIRST).use {
                assertEquals(44100,it.format.sampleRate)
                assertEquals(2,it.format.channels)
            }
        assertTrue(calls.isEmpty())
    }

}
