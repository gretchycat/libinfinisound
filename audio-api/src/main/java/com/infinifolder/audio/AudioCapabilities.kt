package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Wrap a real PCM decoder without changing the existing decoder interface/ABI. */
open class PcmCapabilityHandler(
    val decoder: PcmDecoder, val formats: List<FormatSupport>,
    val tier: String = "portable", private val runtime: () -> HandlerAvailability = { HandlerAvailability() },
    limitations: List<String> = emptyList()
) : CapabilityHandler {
    override val descriptor = HandlerDescriptor("sound.${decoder.name}", "sound", decoder.name, formats,
        setOf(Operations.IDENTIFY, Operations.DECODE, Operations.PLAY),
        metadata = setOf("sampleRate", "channels", "bitsPerSample", "totalFrames"),
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
                private val originalLength = context.resource.metadata.length
                // Portable streaming parsers can validate a large file from its first
                // frames. Keep the true bound while making reads past this shared prefix
                // return EOF; buffered/native decoders must never allocate the full source
                // merely because identification was requested.
                override val size = if (tier == "portable") originalLength ?: bytes.size.toLong() else bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    require(position >= 0 && offset >= 0 && length >= 0 && length <= buffer.size-offset)
                    if(length == 0) return 0
                    if(position >= size || position >= bytes.size) return -1
                    val count = minOf(length,minOf(bytes.size-position.toInt(),(size-position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+count)
                    return count
                }
                override fun close() {}
            }
        }
        val probe = AudioProbe(bytes, input.name.substringAfterLast('.', ""))
        if (!decoder.matches(input, probe)) return metadata.copy(confidence = MatchConfidence.NONE)

        // Generic native fallbacks accept nearly every byte stream and may wait
        // while trying codecs. They are playback routes, not bounded identity
        // parsers. Keep them out of directory scans.
        if (tier == "fallback")
            return metadata.copy(confidence = MatchConfidence.NONE,
                reason = "Generic fallback decoders are not used as format validators")
        // A matching filename or weak tag is only a candidate. Parse the
        // bounded prefix and request a small PCM block before claiming the
        // format. This also lets buffered compatibility decoders validate
        // large files without loading the complete resource into memory.
        var stream: PcmStream? = null
        return try {
            stream = decoder.open(input)
            val channels = stream.format.channels.coerceIn(1, 8)
            val samples = IntArray(channels * 256)
            if (stream.read(samples, 0, samples.size) <= 0) metadata.copy(confidence = MatchConfidence.NONE)
            else {
                val candidateMime = context.resource.metadata.content.mimeType?.takeUnless {
                    it.endsWith("/*") || it.lowercase() in setOf("application/octet-stream", "application/binary",
                        "application/x-binary", "binary/octet-stream", "application/x-unknown")
                }
                val extension = input.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                val signatureFormats = descriptor.formats.filter { format -> format.magic.any { it.matches(bytes) } }
                val extensionFormats = descriptor.formats.filter { format -> format.extensions.any { it.equals(extension, true) } }
                val mimeFormats = descriptor.formats.filter { format ->
                    format.mimeTypes.any { it == candidateMime || it.endsWith("/*") && candidateMime?.startsWith(it.dropLast(1)) == true }
                }
                val identityFormats = when {
                    signatureFormats.isNotEmpty() -> signatureFormats
                    extensionFormats.isNotEmpty() -> extensionFormats
                    else -> mimeFormats
                }
                val declaredMimes = identityFormats.flatMap { it.mimeTypes }.filterNot { it.contains('*') }.distinct()
                val sourceMime = stream.sourceFormat?.substringBefore(';')?.lowercase(java.util.Locale.ROOT)
                    ?.takeIf { it.startsWith("audio/") && it != "audio/raw" }
                val parsedMime = when {
                    sourceMime == "audio/mp4a-latm" && bytes.size >= 12 &&
                        String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" -> "audio/mp4"
                    sourceMime == "audio/mp4a-latm" -> "audio/aac"
                    sourceMime != null && sourceMime in setOf("audio/vorbis", "audio/opus", "audio/flac") && bytes.size >= 4 &&
                        String(bytes, 0, 4, Charsets.US_ASCII) == "OggS" -> "audio/ogg"
                    else -> sourceMime
                }
                val mime = parsedMime
                    ?: candidateMime?.takeIf { it in declaredMimes }
                    ?: declaredMimes.firstOrNull()
                    ?: candidateMime?.takeIf { extensionFormats.isNotEmpty() }
                metadata.copy(confidence = MatchConfidence.VERIFIED,
                    reason = "Decoder parsed and produced audio from the bounded content probe",
                    metadata = buildMap {
                        put("format", decoder.name)
                        if (mime != null) put("mimeType", mime)
                        put("sampleRate", stream.format.sampleRate.toString())
                        put("channels", channels.toString())
                    })
            }
        } catch (cancel: java.io.InterruptedIOException) {
            throw cancel
        } catch (_: Exception) {
            metadata.copy(confidence = MatchConfidence.NONE)
        } catch (_: LinkageError) {
            metadata.copy(confidence = MatchConfidence.NONE)
        } finally {
            runCatching { stream?.close() }
        }
    }
}

/** Bounded, content-only identity for codecs with conclusive container or frame signatures. */
class MagicAudioIdentityHandler(override val descriptor: HandlerDescriptor) : CapabilityHandler, FormatProbe {
    init {
        require(Operations.IDENTIFY in descriptor.operations)
        require(descriptor.formats.any { it.magic.isNotEmpty() })
    }

    override fun probe(context: CapabilityProbeContext): HandlerProbe {
        if (context.resource.source == null) return super<CapabilityHandler>.probe(context)
        val raw = context.prefix()
        val extension = context.resource.metadata.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val bytes = identityPrefix(raw, extension) ?: return HandlerProbe(MatchConfidence.NONE, emptySet())
        val matched = descriptor.formats.filter { format -> format.magic.any { it.matches(bytes) } }
        if (matched.isEmpty()) return HandlerProbe(MatchConfidence.NONE, emptySet())
        val mimes = matched.flatMap { it.mimeTypes }.filterNot { it.contains('*') }.distinct()
        val hinted = context.resource.metadata.content.mimeType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it in mimes }
        val extensionMimes = matched.filter { extension != null && extension in it.extensions }
            .flatMap { it.mimeTypes }.filterNot { it.contains('*') }.distinct()
        val mime = hinted ?: extensionMimes.singleOrNull() ?: mimes.singleOrNull()
            ?: return HandlerProbe(MatchConfidence.NONE, emptySet(), "Signature maps to multiple audio identities")
        val signature = matched.flatMap { it.magic }.first { it.matches(bytes) }
        return HandlerProbe(MatchConfidence.SIGNATURE, setOf(Operations.IDENTIFY),
            signature.description.ifBlank { "Audio signature matched" }, mapOf("mimeType" to mime))
    }

    override fun detect(reader: ProbeReader): List<FormatCandidate> {
        val signatures = descriptor.formats.flatMap { it.magic }
        val extension = reader.metadata.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val length = if (extension == "vgz") {
            (reader.metadata.length ?: 65536L).coerceAtMost(65536L).toInt()
        } else {
            signatures.maxOfOrNull { (it.offset + it.hex.length / 2).coerceAtMost(reader.metadata.length ?: 65536L) }
                ?.coerceAtMost(65536L)?.toInt() ?: return emptyList()
        }
        val raw = reader.bytes(0, length)
        val bytes = identityPrefix(raw, extension) ?: return emptyList()
        val matched = descriptor.formats.filter { it.magic.any { signature -> signature.matches(bytes) } }
        val narrowed = matched.filter { extension != null && extension in it.extensions }.ifEmpty { matched }
        return narrowed.flatMap { format ->
            format.mimeTypes.filterNot { it.contains('*') }.map { mime ->
                val signature = format.magic.first { it.matches(bytes) }
                FormatCandidate(ContentType(DataKind.ENCODED_AUDIO, mimeType = mime), 0.95,
                    signature.description.ifBlank { "Audio signature matched" })
            }
        }.distinctBy { it.content.mimeType }
    }

    /** .vgz is a gzip-wrapped VGM stream; inspect only its first four decoded bytes. */
    private fun identityPrefix(bytes: ByteArray, extension: String?): ByteArray? {
        if (extension != "vgz") return bytes
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return null
        return try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                val prefix = ByteArray(4)
                var count = 0
                while (count < prefix.size) {
                    val read = gzip.read(prefix, count, prefix.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count == prefix.size) prefix else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

object SoundCapabilities {
    val registry get() = RuntimeCapabilities.library("sound")
    fun decoders(tier: String? = null): List<PcmDecoder> = registry.query(Operations.DECODE)
        .filter { tier == null || it.descriptor.attributes["decoderTier"] == tier }
        .mapNotNull { it.handler.service(PcmDecoder::class.java) }
}
