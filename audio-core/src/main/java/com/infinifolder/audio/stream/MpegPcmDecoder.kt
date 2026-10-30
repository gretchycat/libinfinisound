package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.BufferedInputStream
import java.io.IOException

/** MPEG audio frame parsing and synthesis are translated from JLayer into common PCM. */
object MpegPcmDecoder : PcmDecoder {
    override val name = "Portable.MPEG"
    override fun matches(input: AudioInput, probe: AudioProbe): Boolean {
        if (probe.normalizedExtension in setOf("mp3", "mp2", "mpa")) return true
        val b = probe.bytes
        return b.size >= 3 && (String(b, 0, 3, Charsets.US_ASCII) == "ID3" ||
            (b[0].toInt() and 255 == 255 && b[1].toInt() and 0xe0 == 0xe0 && b[1].toInt() and 6 != 0))
    }
    override fun open(input: AudioInput): PcmStream {
        val bytes = input.open()
        var stream: MpegStream? = null
        try {
            stream = MpegStream(bytes)
            val trim = MpegGapless.read(input)
            return if (trim == null) stream else TrimmedPcmStream(stream, trim.skip, totalFrames = trim.total)
        } catch (error: Throwable) { if (stream == null) bytes.close() else stream.close(); throw error }
    }
    private class MpegStream(source: AudioBytes) : BlockPcmStream() {
        private val input = BufferedInputStream(AudioInputStream(source), 65536)
        private val bitstream = Bitstream(input)
        private val decoder = Decoder()
        private var rate = 0
        private var channels = 0
        private var pending: IntArray? = frame() ?: throw IOException("No MPEG audio frames")
        override val format = PcmFormat(rate, channels)
        private fun frame(): IntArray? {
            checkDecodeCancellation()
            val header = bitstream.readFrame() ?: return null
            try {
                val pcm = decoder.decodeFrame(header, bitstream) as? SampleBuffer ?: throw IOException("Missing MPEG PCM")
                if (rate != 0 && (rate != pcm.sampleFrequency || channels != pcm.channelCount))
                    throw IOException("MPEG audio format changes midstream")
                rate = pcm.sampleFrequency; channels = pcm.channelCount
                return IntArray(pcm.bufferLength) { pcm.buffer[it].toInt() shl 16 }
            } finally { bitstream.closeFrame() }
        }
        override fun nextBlock(): IntArray? = pending?.also { pending = null } ?: frame()
        override fun close() { try { bitstream.close() } finally { input.close() } }
    }
}
