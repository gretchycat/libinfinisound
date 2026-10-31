package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.IOException

/** Streaming RIFF WAVE PCM/IEEE-float reader, including extensible sample formats. */
object WavePcmDecoder : PcmDecoder {
    override val name = "Portable.WAVE"
    override fun matches(input: AudioInput, probe: AudioProbe): Boolean = probe.bytes.size >= 12 &&
        String(probe.bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(probe.bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
    override fun open(input: AudioInput): PcmStream {
        val source = input.open()
        try { return WaveStream(ByteReader(source)) } catch (error: Throwable) { source.close(); throw error }
    }
    private class WaveStream(private val r: ByteReader) : BlockPcmStream(), FrameSeekablePcmStream {
        override val format: PcmFormat
        private val depth: Int
        private val tag: Int
        private var position: Long
        private val end: Long
        private val dataStart: Long
        init {
            if (r.text(0, 4) != "RIFF" || r.text(8, 4) != "WAVE") throw IOException("Missing RIFF WAVE signature")
            var pos = 12L
            var fmt: ByteArray? = null
            var dataOffset = -1L
            var dataLength = 0L
            while (pos + 8 <= r.source.size) {
                val type = r.text(pos, 4)
                val size = le(r.bytes(pos + 4, 4), 0, 4)
                if (size > r.source.size - pos - 8) throw IOException("Truncated WAVE chunk")
                if (type == "fmt ") {
                    if (size !in 16..65536) throw IOException("Invalid WAVE format chunk")
                    fmt = r.bytes(pos + 8, size.toInt())
                } else if (type == "data") {
                    dataOffset = pos + 8; dataLength = size; break
                }
                pos += 8 + size + (size and 1)
            }
            val info = fmt ?: throw IOException("Missing WAVE format")
            var encoding = le(info, 0, 2).toInt()
            if (encoding == 0xfffe) {
                if (info.size < 40) throw IOException("Truncated extensible WAVE format")
                encoding = le(info, 24, 2).toInt()
            }
            tag = encoding
            val channels = le(info, 2, 2).toInt()
            val rate = le(info, 4, 4).toInt()
            depth = le(info, 14, 2).toInt()
            if (tag !in setOf(1, 3) || depth !in setOf(8, 16, 24, 32) || (tag == 3 && depth != 32) || dataOffset < 0)
                throw IOException("Unsupported WAVE PCM format")
            if (channels !in 1..8 || dataLength % (channels * (depth / 8)) != 0L) throw IOException("Incomplete WAVE PCM frame")
            dataStart = dataOffset; position = dataOffset; end = dataOffset + dataLength
            format = PcmFormat(rate, channels, dataLength / (channels * (depth / 8)), if (depth <= 16) 16 else depth)
        }
        override fun seekToFrame(frame: Long) {
            require(frame in 0..format.totalFrames!!)
            clearDecodedBlock()
            position = dataStart + frame * format.channels * (depth / 8)
        }
        override fun nextBlock(): IntArray? {
            if (position >= end) return null
            val width = depth / 8
            val count = minOf(4096L * format.channels, (end - position) / width).toInt()
            val bytes = r.bytes(position, count * width)
            position += bytes.size
            return IntArray(count) { i ->
                val value = le(bytes, i * width, width).toInt()
                when {
                    tag == 3 -> (Float.fromBits(value).toDouble().coerceIn(-1.0, 1.0) * 2147483648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    depth == 8 -> (value - 128) shl 24
                    else -> value shl (32 - depth)
                }
            }
        }
        private fun le(bytes: ByteArray, start: Int, count: Int): Long {
            var value = 0L
            for (i in 0 until count) value = value or ((bytes[start + i].toLong() and 255) shl (i * 8))
            return value
        }
        override fun close() = r.close()
    }
}
