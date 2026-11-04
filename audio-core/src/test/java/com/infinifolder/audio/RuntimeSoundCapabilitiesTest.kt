package com.infinifolder.audio

import com.infinifolder.audio.stream.MemoryAudioInput
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RuntimeSoundCapabilitiesTest {
    @Test fun discoveredWaveDecoderReadsActualPcmWithoutAndroidOrTheApp() {
        val samples=shortArrayOf(-32768,0,16384,32767)
        val data=ByteBuffer.allocate(44+samples.size*2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(capacity()-8);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
            put("data".toByteArray());putInt(samples.size*2);samples.forEach(::putShort)
        }.array()
        val selected=SoundCapabilities.registry.query(Operations.DECODE).single {it.descriptor.id=="sound.Portable.WAVE"}
        selected.handler.service(PcmDecoder::class.java)!!.open(MemoryAudioInput(data,"sample.wav")).use { pcm ->
            assertEquals(8000,pcm.format.sampleRate);assertEquals(4L,pcm.format.totalFrames)
            val output=IntArray(4);assertEquals(4,pcm.read(output,0,4))
            assertArrayEquals(samples.map{it.toInt() shl 16}.toIntArray(),output)
        }
    }
    @Test fun registeringANewDecoderChangesActualFileDispatchWithoutEditingItsRegistry() {
        val decoder=object:PcmDecoder {
            override val name="Test.Unsigned8"
            override fun matches(input:AudioInput,probe:AudioProbe)=probe.bytes.size>=4&&probe.bytes.take(4).toByteArray().decodeToString()=="U8P0"
            override fun open(input:AudioInput):PcmStream {
                val values=input.open().use { source -> ByteArray((source.size-4).toInt()).also { assertEquals(it.size,source.readAt(4,it,0,it.size)) } }
                return object:PcmStream {
                    override val format=PcmFormat(8000,1,values.size.toLong(),16)
                    private var cursor=0
                    override fun read(samples:IntArray,offset:Int,length:Int):Int {
                        if(cursor==values.size)return -1
                        val count=minOf(length,values.size-cursor)
                        repeat(count){samples[offset+it]=((values[cursor++].toInt() and 255)-128) shl 24}
                        return count
                    }
                    override fun close(){}
                }
            }
        }
        val registered=PcmCapabilityHandler(decoder,listOf(FormatSupport(setOf("audio/x-test-u8"),setOf("u8p"))))
        SoundCapabilities.registry.register(registered).use {
            assertTrue(SoundCapabilities.decoders().any {it===decoder})
            AudioFileProcessor().openPcm(MemoryAudioInput("U8P0".toByteArray()+byteArrayOf(0,128.toByte(),255.toByte()),"new.u8p"),DecoderPreference.PORTABLE_ONLY).use { pcm ->
                val samples=IntArray(3);assertEquals(3,pcm.read(samples,0,3))
                assertArrayEquals(intArrayOf(Int.MIN_VALUE,0,127 shl 24),samples)
            }
        }
        assertFalse(SoundCapabilities.decoders().any{it===decoder})
    }
}
