package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import net.sourceforge.jaad.aac.Decoder
import net.sourceforge.jaad.aac.Receiver
import java.io.IOException

object AacPcmDecoder : PcmDecoder {
    override val name = "Portable.AAC"
    override fun matches(input: AudioInput, probe: AudioProbe): Boolean {
        val b = probe.bytes
        if (probe.normalizedExtension in setOf("aac", "m4a", "m4b", "m4r", "mp4", "3ga")) return true
        return b.size >= 8 && (String(b, 4, 4, Charsets.US_ASCII) == "ftyp" ||
            (b[0].toInt() and 255 == 255 && b[1].toInt() and 0xf6 == 0xf0))
    }
    override fun open(input: AudioInput): PcmStream {
        val source = input.open()
        try {
            val r = ByteReader(source)
            val isMp4 = source.size >= 8 && r.text(4, 4) in setOf("ftyp", "moov", "mdat", "free", "wide")
            val packets: AacPackets = if (isMp4) Mp4AacPackets(r) else AdtsPackets(r)
            val pcm = AacStream(packets)
            if (packets.timeScale == 0) return pcm
            val rate = pcm.format.sampleRate
            return TrimmedPcmStream(pcm, packets.skipFrames * rate / packets.timeScale,
                totalFrames = packets.totalFrames?.let { it * rate / packets.timeScale })
        } catch (error: Throwable) { source.close(); throw error }
    }
    private class AacStream(private val packets: AacPackets) : BlockPcmStream() {
        private val decoder = Decoder.create(packets.configuration) ?: throw IOException("Unsupported AAC configuration")
        private var rate = 0
        private var channels = 0
        private var pending: IntArray? = frame() ?: throw IOException("No AAC audio frames")
        override val format = PcmFormat(rate, channels)
        private fun frame(): IntArray? {
            checkDecodeCancellation()
            val packet = packets.next() ?: return null
            var output: IntArray? = null
            decoder.decodeFrame(packet, Receiver { samples, count, sampleRate ->
                if (samples.size !in 1..8 || count !in 1..8192 || sampleRate <= 0) throw IOException("Invalid AAC PCM format")
                if (rate != 0 && (rate != sampleRate || channels != samples.size)) throw IOException("AAC format changes midstream")
                rate = sampleRate; channels = samples.size
                output = IntArray(count * channels) { index ->
                    val channel = samples[index % channels]
                    if (channel.isEmpty()) throw IOException("Empty AAC channel")
                    val value = channel[channel.size * (index / channels) / count]
                    java.lang.Math.round(value).coerceIn(-32768,32767) shl 16
                }
            })
            return output ?: throw IOException("AAC decoder produced no PCM for its packet")
        }
        override fun nextBlock(): IntArray? = pending?.also { pending = null } ?: frame()
        override fun close() = packets.close()
    }
}
