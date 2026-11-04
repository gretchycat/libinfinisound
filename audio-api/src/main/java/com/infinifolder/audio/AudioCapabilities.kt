package com.infinifolder.audio

import io.github.gretchycat.infinistream.*

/** Wrap a real PCM decoder without changing the existing decoder interface/ABI. */
open class PcmCapabilityHandler(
    val decoder: PcmDecoder, val formats: List<FormatSupport>,
    val tier: String = "portable", private val runtime: () -> HandlerAvailability = { HandlerAvailability() },
    limitations: List<String> = emptyList()
) : CapabilityHandler {
    override val descriptor = HandlerDescriptor("sound.${decoder.name}", "sound", decoder.name, formats,
        setOf(Operations.DECODE, Operations.PLAY), metadata = setOf("sampleRate", "channels", "bitsPerSample", "totalFrames"),
        limitations = limitations, attributes = mapOf("presentation" to "audio", "decoderTier" to tier))
    override fun availability() = runtime()
    override fun <T : Any> service(type: Class<T>): T? = if(type.isInstance(decoder)) type.cast(decoder) else super.service(type)
    override fun probe(context: CapabilityProbeContext): HandlerProbe {
        val metadata = super.probe(context)
        if(context.resource.source == null) return metadata
        if(metadata.confidence==MatchConfidence.NONE && tier in setOf("platform","fallback"))return metadata
        // Prefix matching is evidence only; openPcm still validates the complete codec header.
        val bytes = context.prefix()
        val input = object : AudioInput {
            override val name = context.resource.metadata.name ?: "audio"
            override fun open(): AudioBytes = object : AudioBytes {
                override val size = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    require(position >= 0 && offset >= 0 && length >= 0 && length <= buffer.size-offset)
                    if(length == 0) return 0
                    if(position >= bytes.size) return -1
                    val count = minOf(length,bytes.size-position.toInt())
                    bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+count)
                    return count
                }
                override fun close() {}
            }
        }
        val probe = AudioProbe(bytes, input.name.substringAfterLast('.', ""))
        return if(decoder.matches(input, probe)) metadata.copy(confidence = MatchConfidence.CANDIDATE,
            reason = "Decoder accepts bounded probe; opening validates content") else metadata.copy(confidence = MatchConfidence.NONE)
    }
}

object SoundCapabilities {
    val registry get() = RuntimeCapabilities.library("sound")
    fun decoders(tier: String? = null): List<PcmDecoder> = registry.query(Operations.DECODE)
        .filter { tier == null || it.descriptor.attributes["decoderTier"] == tier }
        .mapNotNull { it.handler.service(PcmDecoder::class.java) }
}
