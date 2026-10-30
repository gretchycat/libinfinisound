package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import java.io.IOException

/** Compatibility bridge; no implicit download/spool when a codec needs positional reads. */
class StreamAudioInput(private val resource: SourceResource,
    override val name: String = resource.metadata.name ?: "audio") : AudioInput {
    override fun open(): AudioBytes {
        val source = resource.open()
        try {
            val positional = source as? PositionalReadable ?: throw IOException("Audio input requires positional reads; explicit staging is needed")
            val size = source.metadata.length ?: throw IOException("Audio input requires known length; explicit staging is needed")
            return object : AudioBytes {
                override val size = size
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                    positional.readAt(position, buffer, offset, length)
                override fun close() = source.close()
            }
        } catch (error: Throwable) { try { source.close() } catch (close: Throwable) { error.addSuppressed(close) }; throw error }
    }
}

/** Typed PCM output retains exact integer sample contract; no conversion to a byte stream. */
class PcmStreamHandle(val pcm: PcmStream, private val ownsPcm: Boolean = true) : StreamHandle {
    override val metadata: StreamMetadata get() = StreamMetadata(
        ContentType(DataKind.PCM_AUDIO, "audio/x-raw", "pcm-signed-left-aligned32", "signed-int32"),
        finiteness = Finiteness.FINITE, capabilities = setOf(Capability.READ),
        ownership = if (ownsPcm) Ownership.OWNED else Ownership.BORROWED,
        attributes = buildMap {
            put("sampleRate", pcm.format.sampleRate.toString()); put("channels", pcm.format.channels.toString())
            put("sourceBitsPerSample", pcm.format.bitsPerSample.toString())
            pcm.format.totalFrames?.let { put("totalFrames", it.toString()) }
            pcm.format.durationMs?.let { put("durationMs", it.toString()) }
            pcm.decoderName?.let { put("decoder", it) }
        })
    private var closed = false
    fun read(samples: IntArray, offset: Int = 0, length: Int = samples.size - offset): Int {
        if (closed) throw IOException("PCM handle is closed")
        return pcm.read(samples, offset, length)
    }
    override fun close() { if (!closed) { closed = true; if (ownsPcm) pcm.close() } }
}
