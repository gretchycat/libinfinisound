package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Streams PCM to a host-owned file and patches the exact frame count on successful completion. */
object PcmWaveWriter {
    fun write(stream: PcmStream, destination: File, onSamples: (IntArray, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): PcmFormat {
        val format = stream.format
        val width = format.bitsPerSample / 8
        var sampleCount = 0L
        RandomAccessFile(destination, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(44))
            val samples = IntArray(4096 * format.channels)
            val bytes = ByteArray(samples.size * width)
            while (true) {
                checkDecodeCancellation()
                if (cancelled()) throw java.io.InterruptedIOException("Audio preparation cancelled")
                val count = stream.read(samples, 0, samples.size)
                if (count < 0) break
                if (count == 0 || count > samples.size || count % format.channels != 0)
                    throw IOException("Invalid PCM stream read: $count")
                if ((sampleCount + count) * width > 0xffffffffL - 36)
                    throw IOException("PCM exceeds RIFF WAVE size limit")
                for (i in 0 until count) {
                    val value = samples[i] ushr (32 - format.bitsPerSample)
                    for (j in 0 until width) bytes[i * width + j] = (value ushr (j * 8)).toByte()
                }
                onSamples(samples, count)
                out.write(bytes, 0, count * width)
                sampleCount += count
            }
            if (sampleCount == 0L) throw IOException("Decoder produced no PCM frames")
            val expected = format.totalFrames
            if (expected != null && sampleCount / format.channels != expected)
                throw IOException("Incomplete PCM: expected $expected frames, got ${sampleCount / format.channels}")
            out.seek(0)
            fun le16(value: Int) { out.write(value and 255); out.write(value ushr 8 and 255) }
            fun le32(value: Long) { repeat(4) { out.write((value ushr (it * 8)).toInt() and 255) } }
            out.writeBytes("RIFF"); le32(36 + sampleCount * width); out.writeBytes("WAVEfmt ")
            le32(16); le16(1); le16(format.channels); le32(format.sampleRate.toLong())
            le32(format.sampleRate.toLong() * format.channels * width); le16(format.channels * width); le16(format.bitsPerSample)
            out.writeBytes("data"); le32(sampleCount * width)
        }
        return format.copy(totalFrames = sampleCount / format.channels)
    }
}
