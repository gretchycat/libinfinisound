package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class StreamAudioAdaptersTest {
    @Test fun portableDecoderAcceptsCommonSourceAndPublishesActualPcmMetadata() {
        val file = File.createTempFile("stream-audio", ".flac")
        try {
            javaClass.getResourceAsStream("/audio/chord.flac")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            FileSourceResource(file).open().use { source ->
                AudioStreamProcessor().open(mapOf("encoded" to listOf(source)), ProcessorOptions()).use { session ->
                    val pcm = session.outputs.getValue("pcm").single() as PcmStreamHandle
                    assertEquals(DataKind.PCM_AUDIO, pcm.metadata.content.kind)
                    assertEquals("44100", pcm.metadata.attributes["sampleRate"])
                    assertFalse(Capability.POSITIONAL_READ in pcm.metadata.capabilities)
                    val samples = IntArray(8192); var count = 0L
                    while (true) { val n = pcm.read(samples); if (n < 0) break; count += n }
                    assertEquals(66150L * 2, count)
                }
                assertEquals(1, (source as PositionalReadable).readAt(0, ByteArray(1)))
            }
        } finally { file.delete() }
    }
    @Test fun sequentialOnlyInputsRequireExplicitStagingAndCloseFailedHandle() {
        var closes = 0
        val resource = object : SourceResource {
            override val metadata = StreamMetadata()
            override fun open() = InputStreamSource(object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closes++; super.close() }
            })
        }
        assertThrows(IOException::class.java) { StreamAudioInput(resource).open() }
        assertEquals(1, closes)
    }
}
