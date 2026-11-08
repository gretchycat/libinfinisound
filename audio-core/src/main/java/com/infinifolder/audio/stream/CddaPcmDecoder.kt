package com.infinifolder.audio.stream

import com.infinifolder.audio.AudioBytes
import com.infinifolder.audio.AudioInput
import com.infinifolder.audio.AudioProbe
import com.infinifolder.audio.PcmDecoder
import com.infinifolder.audio.PcmFormat
import com.infinifolder.audio.PcmStream
import com.infinifolder.audio.decoder.CddaDecoder
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Streaming Red Book CD-DA decoder for raw 44.1 kHz, 16-bit stereo PCM tracks. */
object CddaPcmDecoder : PcmDecoder {
    override val name = "Portable.CDDA"

    override fun matches(input: AudioInput, probe: AudioProbe): Boolean =
        probe.normalizedExtension.lowercase() in setOf("cdda", "cdda-be")

    override fun open(input: AudioInput): PcmStream {
        val source = input.open()
        try {
            if (source.size <= 0L || source.size % 4L != 0L)
                throw IOException("CD-DA data must contain complete stereo sample frames")
            val prefixLength = minOf(source.size, 512L).toInt()
            val prefix = ByteArray(prefixLength)
            readFully(source, 0L, prefix, 0, prefix.size)
            val forcedBigEndian = input.name.substringAfterLast('.', "").equals("cdda-be", true)
            val bigEndian = forcedBigEndian || detectBigEndian(prefix)
            return CddaStream(source, bigEndian)
        } catch (error: Throwable) {
            runCatching { source.close() }
            throw error
        }
    }

    private fun detectBigEndian(bytes: ByteArray): Boolean {
        val sampleCount = minOf(bytes.size / 2, 256)
        if (sampleCount < 2) return false
        var littleDifference = 0L
        var bigDifference = 0L
        var previousLittle = sample(bytes, 0, false)
        var previousBig = sample(bytes, 0, true)
        for (index in 1 until sampleCount) {
            val currentLittle = sample(bytes, index * 2, false)
            val currentBig = sample(bytes, index * 2, true)
            littleDifference += kotlin.math.abs(currentLittle - previousLittle).toLong()
            bigDifference += kotlin.math.abs(currentBig - previousBig).toLong()
            previousLittle = currentLittle
            previousBig = currentBig
        }
        return bigDifference * 2L < littleDifference
    }

    private fun sample(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int {
        val first = bytes[offset].toInt() and 0xff
        val second = bytes[offset + 1].toInt() and 0xff
        return if (bigEndian) ((first shl 8) or second).toShort().toInt()
        else ((second shl 8) or first).toShort().toInt()
    }

    private class CddaStream(private val source: AudioBytes, private val bigEndian: Boolean) : BlockPcmStream() {
        override val format = PcmFormat(CddaDecoder.SAMPLE_RATE, CddaDecoder.CHANNELS, source.size / 4L)
        override val decoderName = CddaPcmDecoder.name
        override val sourceFormat = "audio/x-cdda; endian=${if (bigEndian) "big" else "little"}"
        private var bytePosition = 0L
        private val closed = AtomicBoolean(false)

        override fun nextBlock(): IntArray? {
            check(!closed.get()) { "CD-DA decoder is closed" }
            checkDecodeCancellation()
            if (bytePosition >= source.size) return null
            val wanted = minOf(64L * 1024L, source.size - bytePosition).toInt() and -4
            if (wanted == 0) return null
            val bytes = ByteArray(wanted)
            readFully(source, bytePosition, bytes, 0, wanted)
            bytePosition += wanted
            return IntArray(wanted / 2) { index -> sample(bytes, index * 2, bigEndian) shl 16 }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) source.close()
        }
    }

    private fun readFully(source: AudioBytes, position: Long, bytes: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            checkDecodeCancellation()
            val count = source.readAt(position + total, bytes, offset + total, length - total)
            if (count < 0) throw IOException("Unexpected end of CD-DA data")
            if (count == 0) throw IOException("CD-DA source made no read progress")
            total += count
        }
    }
}
