package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.EOFException
import java.io.IOException

/** Native FLAC bitstream parser and lossless sample reconstruction, per RFC 9639. */
object FlacPcmDecoder : PcmDecoder {
    override val name = "Portable.FLAC"
    override fun matches(input: AudioInput, probe: AudioProbe) =
        probe.bytes.size >= 4 && probe.bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(102, 76, 97, 67))
    override fun open(input: AudioInput): PcmStream {
        val bytes = input.open()
        try { return FlacStream(bytes) } catch (error: Throwable) { bytes.close(); throw error }
    }

    private class FlacStream(private val source: AudioBytes) : BlockPcmStream() {
        private val bits = Bits(source)
        override val format: PcmFormat
        private val depth: Int
        private val maxBlock: Int
        private var frames = 0L
        init {
            if (bits.read(32) != 0x664c6143L) throw IOException("Missing FLAC signature")
            var info: ByteArray? = null
            var last: Boolean
            do {
                last = bits.read(1) != 0L
                val type = bits.read(7).toInt()
                val length = bits.read(24).toInt()
                if (info == null && type != 0) throw IOException("FLAC STREAMINFO must be first")
                if (type == 127) throw IOException("Invalid FLAC metadata type")
                if (type == 0) {
                    if (info != null || length != 34) throw IOException("Invalid FLAC STREAMINFO")
                    info = ByteArray(34) { bits.read(8).toByte() }
                } else bits.skipBytes(length)
            } while (!last)
            val data = info ?: throw IOException("Missing FLAC STREAMINFO")
            maxBlock = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
            var packed = 0L
            for (i in 10..17) packed = (packed shl 8) or (data[i].toLong() and 255)
            val rate = (packed ushr 44).toInt()
            val channels = ((packed ushr 41).toInt() and 7) + 1
            depth = ((packed ushr 36).toInt() and 31) + 1
            val total = packed and 0xfffffffffL
            if (rate <= 0 || depth !in 4..32 || maxBlock !in 16..65535) throw IOException("Invalid FLAC format")
            format = PcmFormat(rate, channels, total.takeIf { it > 0 }, if (depth <= 16) 16 else if (depth <= 24) 24 else 32)
        }

        override fun nextBlock(): IntArray? {
            checkDecodeCancellation()
            if (format.totalFrames?.let { frames >= it } == true) return null
            if (bits.atEnd()) {
                if (format.totalFrames?.let { frames < it } == true) throw EOFException("Truncated FLAC audio")
                return null
            }
            bits.beginFrame()
            if (bits.read(14) != 0x3ffeL || bits.read(1) != 0L) throw IOException("Invalid FLAC frame sync")
            bits.read(1) // Blocking strategy; PCM position is tracked independently.
            val blockCode = bits.read(4).toInt()
            val rateCode = bits.read(4).toInt()
            val assignment = bits.read(4).toInt()
            val depthCode = bits.read(3).toInt()
            if (bits.read(1) != 0L) throw IOException("Reserved FLAC header bit")
            readCodedNumber()
            val count = when (blockCode) {
                1 -> 192
                in 2..5 -> 576 shl (blockCode - 2)
                6 -> bits.read(8).toInt() + 1
                7 -> bits.read(16).toInt() + 1
                in 8..15 -> 256 shl (blockCode - 8)
                else -> throw IOException("Reserved FLAC block size")
            }
            val rate = when (rateCode) {
                0 -> format.sampleRate
                1 -> 88200; 2 -> 176400; 3 -> 192000; 4 -> 8000; 5 -> 16000
                6 -> 22050; 7 -> 24000; 8 -> 32000; 9 -> 44100; 10 -> 48000; 11 -> 96000
                12 -> bits.read(8).toInt() * 1000
                13 -> bits.read(16).toInt()
                14 -> bits.read(16).toInt() * 10
                else -> throw IOException("Reserved FLAC sample rate")
            }
            val frameDepth = when (depthCode) {
                0 -> depth; 1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24; 7 -> 32
                else -> throw IOException("Reserved FLAC sample depth")
            }
            val channels = if (assignment <= 7) assignment + 1 else if (assignment <= 10) 2 else 0
            if (rate != format.sampleRate || channels != format.channels || frameDepth != depth || count > maxBlock)
                throw IOException("Inconsistent FLAC frame format")
            if (format.totalFrames?.let { frames + count > it } == true) throw IOException("FLAC frame exceeds declared duration")
            bits.read(8)
            if (bits.crc8 != 0) throw IOException("FLAC header CRC mismatch")
            val samples = Array(channels) { channel ->
                val extra = (assignment == 8 && channel == 1) || (assignment == 9 && channel == 0) || (assignment == 10 && channel == 1)
                subframe(count, depth + if (extra) 1 else 0)
            }
            bits.align()
            bits.read(16)
            if (bits.crc16 != 0) throw IOException("FLAC frame CRC mismatch")
            val output = IntArray(count * channels)
            for (i in 0 until count) {
                when (assignment) {
                    8 -> samples[1][i] = samples[0][i] - samples[1][i]
                    9 -> samples[0][i] += samples[1][i]
                    10 -> {
                        val side = samples[1][i]
                        val mid = (samples[0][i] shl 1) or (side and 1)
                        samples[0][i] = (mid + side) shr 1
                        samples[1][i] = (mid - side) shr 1
                    }
                }
                for (channel in 0 until channels) {
                    val value = samples[channel][i]
                    if (value < -(1L shl (depth - 1)) || value >= (1L shl (depth - 1))) throw IOException("FLAC sample outside declared depth")
                    output[i * channels + channel] = (value shl (32 - depth)).toInt()
                }
            }
            frames += count
            return output
        }

        private fun readCodedNumber() {
            val first = bits.read(8).toInt()
            if (first and 128 == 0) return
            var count = 0
            var mask = 128
            while (first and mask != 0 && mask > 0) { count++; mask = mask ushr 1 }
            if (count !in 2..7) throw IOException("Invalid FLAC coded number")
            repeat(count - 1) { if (bits.read(8).toInt() and 0xc0 != 0x80) throw IOException("Invalid FLAC coded-number continuation") }
        }

        private fun subframe(count: Int, originalDepth: Int): LongArray {
            if (bits.read(1) != 0L) throw IOException("Invalid FLAC subframe padding")
            val type = bits.read(6).toInt()
            val wasted = if (bits.read(1) == 0L) 0 else bits.unary() + 1
            val width = originalDepth - wasted
            if (width !in 1..33) throw IOException("Invalid FLAC wasted bits")
            val out = LongArray(count)
            when {
                type == 0 -> out.fill(bits.signed(width))
                type == 1 -> for (i in out.indices) out[i] = bits.signed(width)
                type in 8..12 -> {
                    val order = type - 8
                    if (order > count) throw IOException("FLAC predictor exceeds block")
                    repeat(order) { out[it] = bits.signed(width) }
                    residual(out, order)
                    for (i in order until count) out[i] += when (order) {
                        0 -> 0L
                        1 -> out[i - 1]
                        2 -> 2 * out[i - 1] - out[i - 2]
                        3 -> 3 * (out[i - 1] - out[i - 2]) + out[i - 3]
                        else -> 4 * (out[i - 1] + out[i - 3]) - 6 * out[i - 2] - out[i - 4]
                    }
                }
                type in 32..63 -> {
                    val order = type - 31
                    if (order > count) throw IOException("FLAC LPC order exceeds block")
                    repeat(order) { out[it] = bits.signed(width) }
                    val precision = bits.read(4).toInt() + 1
                    if (precision == 16) throw IOException("Reserved FLAC LPC precision")
                    val shift = bits.signed(5).toInt()
                    val coefficients = LongArray(order) { bits.signed(precision) }
                    residual(out, order)
                    for (i in order until count) {
                        var sum = 0L
                        for (j in 0 until order) sum += coefficients[j] * out[i - j - 1]
                        out[i] += if (shift >= 0) sum shr shift else sum shl -shift
                    }
                }
                else -> throw IOException("Reserved FLAC subframe type")
            }
            if (wasted > 0) for (i in out.indices) out[i] = out[i] shl wasted
            return out
        }

        private fun residual(out: LongArray, order: Int) {
            val method = bits.read(2).toInt()
            val riceWidth = when (method) { 0 -> 4; 1 -> 5; else -> throw IOException("Reserved FLAC residual method") }
            val partitions = 1 shl bits.read(4).toInt()
            if (out.size % partitions != 0 || out.size / partitions < order) throw IOException("Invalid FLAC residual partition")
            val partitionSize = out.size / partitions
            var index = order
            repeat(partitions) { partition ->
                val parameter = bits.read(riceWidth).toInt()
                val count = partitionSize - if (partition == 0) order else 0
                if (parameter == (1 shl riceWidth) - 1) {
                    val raw = bits.read(5).toInt()
                    repeat(count) { out[index++] = if (raw == 0) 0 else bits.signed(raw) }
                } else repeat(count) {
                    val unsigned = (bits.unary().toLong() shl parameter) or bits.read(parameter)
                    out[index++] = (unsigned ushr 1) xor -(unsigned and 1)
                }
            }
        }
        override fun close() = source.close()
    }

    private class Bits(private val source: AudioBytes) {
        private val buffer = ByteArray(16384)
        private var start = 0L
        private var size = 0
        private var cursor = 0
        private var current = 0
        private var left = 0
        var crc8 = 0; private set
        var crc16 = 0; private set
        fun atEnd() = left == 0 && start + cursor >= source.size
        fun beginFrame() { check(left == 0); crc8 = 0; crc16 = 0 }
        private fun byte(): Int {
            if (cursor == size) {
                start += cursor
                cursor = 0
                size = source.readAt(start, buffer, 0, buffer.size)
                if (size <= 0) throw EOFException("Truncated FLAC bitstream")
            }
            val value = buffer[cursor++].toInt() and 255
            crc8 = crc8 xor value
            crc16 = crc16 xor (value shl 8)
            repeat(8) {
                crc8 = ((crc8 shl 1) xor if (crc8 and 128 != 0) 7 else 0) and 255
                crc16 = ((crc16 shl 1) xor if (crc16 and 32768 != 0) 0x8005 else 0) and 65535
            }
            return value
        }
        fun read(count: Int): Long {
            require(count in 0..63)
            var n = count
            var value = 0L
            while (n > 0) {
                if (left == 0) { current = byte(); left = 8 }
                val take = minOf(n, left)
                left -= take
                value = (value shl take) or ((current ushr left) and ((1 shl take) - 1)).toLong()
                n -= take
            }
            return value
        }
        fun signed(count: Int): Long = (read(count) shl (64 - count)) shr (64 - count)
        fun unary(): Int {
            var count = 0
            while (read(1) == 0L) {
                if (++count > 16_777_216) throw IOException("FLAC unary code exceeds decoder limit")
                if (count and 4095 == 0) checkDecodeCancellation()
            }
            return count
        }
        fun align() { if (left != 0 && read(left) != 0L) throw IOException("Nonzero FLAC alignment padding") }
        fun skipBytes(count: Int) {
            check(left == 0)
            val target = start + cursor + count
            if (target > source.size) throw EOFException("Truncated FLAC metadata")
            start = target; cursor = 0; size = 0
        }
    }
}
