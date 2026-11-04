package com.infinifolder.audio.model

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawAudioExplorerTest {
    @Test fun unsignedAndSignedEightBitSamples(){
        val c=RawAudioExplorer.Config()
        assertArrayEquals(shortArrayOf(-32768,0,32512),RawAudioExplorer.decode(byteArrayOf(0,128.toByte(),255.toByte()),c).samples)
        assertArrayEquals(shortArrayOf(-32768,0,32512),RawAudioExplorer.decode(byteArrayOf(128.toByte(),0,127),c.copy(encoding=RawAudioExplorer.Encoding.S8)).samples)
    }
    @Test fun integerByteOrderAndWidths(){
        val c=RawAudioExplorer.Config(encoding=RawAudioExplorer.Encoding.S16)
        assertArrayEquals(shortArrayOf(-32768,32767),RawAudioExplorer.decode(byteArrayOf(0,128.toByte(),255.toByte(),127),c).samples)
        assertArrayEquals(shortArrayOf(-32768,32767),RawAudioExplorer.decode(byteArrayOf(128.toByte(),0,127,255.toByte()),c.copy(littleEndian=false)).samples)
        assertEquals((-32768).toShort(),RawAudioExplorer.decode(byteArrayOf(0,0,128.toByte()),c.copy(encoding=RawAudioExplorer.Encoding.S24)).samples[0])
        assertEquals((-32768).toShort(),RawAudioExplorer.decode(byteArrayOf(128.toByte(),0,0,0),c.copy(encoding=RawAudioExplorer.Encoding.S32,littleEndian=false)).samples[0])
    }
    @Test fun floatsDecodeAndNonFiniteValuesAreRejected(){
        val c=RawAudioExplorer.Config(encoding=RawAudioExplorer.Encoding.F32)
        val data=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(-1f).putFloat(1f).array()
        assertArrayEquals(shortArrayOf(-32767,32767),RawAudioExplorer.decode(data,c).samples)
        val nan=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()
        assertThrows(IllegalArgumentException::class.java){RawAudioExplorer.decode(nan,c)}
    }
    @Test fun envelopeKeepsPeaksAndChannels(){
        val pcm=AudioPcmData(8000,2,shortArrayOf(-32768,100,32767,-100,0,0,1000,-1000))
        val e=RawAudioExplorer.envelope(pcm,2)
        assertEquals(4,e.frames);assertEquals(2,e.minimum.size)
        assertEquals(-1f,e.minimum[0][0],0f);assertEquals(32767/32768f,e.maximum[0][0],0f)
        assertEquals(-100/32768f,e.minimum[1][0],0f);assertEquals(100/32768f,e.maximum[1][0],0f)
    }
    @Test fun waveHeaderDetectionReadsActualPcmConfiguration(){
        val file=AudioPcmData(11025,1,shortArrayOf(1,2,3)).toWavByteArray()
        val result=RawAudioExplorer.detect(file,file.size.toLong()).single()
        assertTrue(result.certain);assertEquals(44L,result.config.offset);assertEquals(11025,result.config.sampleRate)
        assertEquals(6L,result.config.byteLength);assertEquals(RawAudioExplorer.Encoding.S16,result.config.encoding)
    }
    @Test fun rawGuessesAreNotPresentedAsDetection(){
        assertTrue(RawAudioExplorer.detect(byteArrayOf(1,2),200).none{it.certain})
        assertTrue(RawAudioExplorer.detect(byteArrayOf(),0).isEmpty())
    }
    @Test fun invalidFormatAndPartialFramesAreRejected(){
        assertThrows(IllegalArgumentException::class.java){RawAudioExplorer.Config(channels=3).validate()}
        assertThrows(IllegalArgumentException::class.java){RawAudioExplorer.Config(sampleRate=0).validate()}
        assertThrows(IllegalArgumentException::class.java){RawAudioExplorer.decode(byteArrayOf(0),RawAudioExplorer.Config(encoding=RawAudioExplorer.Encoding.S16))}
    }
}
