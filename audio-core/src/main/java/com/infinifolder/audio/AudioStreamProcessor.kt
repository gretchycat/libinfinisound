package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import java.io.IOException

/** Pull decoder bridge. Borrow the encoded source; own decoded PCM, never an output device. */
class AudioStreamProcessor(
    private val decode: (AudioInput) -> PcmStream = { AudioFileProcessor().openPcm(it, DecoderPreference.PORTABLE_ONLY) },
    backendId: String = "sound.portable"
) : StreamProcessor {
    override val descriptor = ProcessorDescriptor(backendId,
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES, DataKind.ENCODED_AUDIO),
            capabilities = setOf(Capability.POSITIONAL_READ, Capability.KNOWN_LENGTH))),
        listOf(PortDescriptor("pcm", setOf(DataKind.PCM_AUDIO), capabilities = setOf(Capability.READ))))
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        options.cancellation.check()
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty()) throw IOException("Unsupported audio mode/options")
        val handle = inputs["encoded"]?.singleOrNull() as? ByteSource ?: throw IOException("One encoded byte input is required")
        val positional = handle as? PositionalReadable ?: throw IOException("Audio requires positional input")
        if (!descriptor.inputs.single().accepts(handle.metadata)) throw IOException("Incompatible encoded source")
        val resource = object : SourceResource {
            override val metadata = handle.metadata
            override fun open(): ByteSource = object : ByteSource, PositionalReadable {
                override val metadata = handle.metadata.copy(ownership = Ownership.BORROWED)
                private var cursor = 0L
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = readAt(cursor, buffer, offset, length); if (n > 0) cursor += n; return n
                }
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    options.cancellation.check(); return positional.readAt(position, buffer, offset, length)
                }
                override fun close() {}
            }
        }
        val pcm = PcmStreamHandle(decode(StreamAudioInput(resource)), inputMetadata = handle.metadata)
        return object : ProcessorSession {
            override val outputs = mapOf("pcm" to listOf<StreamHandle>(pcm))
            override fun close() = pcm.close()
        }
    }
}
