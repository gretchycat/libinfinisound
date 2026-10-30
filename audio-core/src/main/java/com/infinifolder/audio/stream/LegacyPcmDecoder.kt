package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.IOException
import com.infinifolder.audio.decoder.AlacAndMpeg2Decoder

/** Compatibility adapter for the existing bounded, buffered composite/specialized renderers. */
object LegacyPcmDecoder : PcmDecoder {
    override val name = "Portable.Legacy"
    override fun matches(input: AudioInput, probe: AudioProbe): Boolean {
        val ext = probe.normalizedExtension
        if (ext in setOf("mp3", "aac", "flac", "wav", "wave")) return false
        if (ext in setOf("m4a", "m4b", "m4r", "mp4", "3ga")) return AlacAndMpeg2Decoder.canDecode(probe.bytes, ext)
        return PortableAudioProcessing.isSupported(probe)
    }
    override fun open(input: AudioInput): PcmStream {
        val encoded = input.open().use { bytes ->
            if (bytes.size > Int.MAX_VALUE) throw IOException("Buffered audio source exceeds decoder limit")
            ByteReader(bytes).bytes(0, bytes.size.toInt())
        }
        val result = PortableAudioProcessing.decodeLegacy(AudioDecodeRequest(encoded, input.name.substringAfterLast('.', ""), ((input as? FileAudioInput)?.file ?: (input as? MemoryAudioInput)?.fileHint)))
            ?: throw IOException("No buffered decoder accepted ${input.name}")
        return object : BlockPcmStream() {
            private var position = 0
            private val pcm = result.pcm
            override val format = PcmFormat(pcm.sampleRate, pcm.channels, (pcm.samples.size / pcm.channels).toLong())
            override val decoderName = result.decoder
            override val sourceFormat = result.sourceFormat
            override fun nextBlock(): IntArray? {
                if (position >= pcm.samples.size) return null
                val end = minOf(position + 4096 * pcm.channels, pcm.samples.size)
                return IntArray(end - position) { pcm.samples[position + it].toInt() shl 16 }.also { position = end }
            }
            override fun close() {}
        }
    }
}
