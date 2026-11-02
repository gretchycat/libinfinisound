package com.infinifolder.audio

import java.io.IOException
import java.io.InterruptedIOException

/** SMAF score/FM/sample playback; no Android output device or proprietary ROM. */
internal object SmafPcmDecoder : PcmDecoder {
    override val name = "SMAF.FM"
    fun hasSignature(bytes: ByteArray) = bytes.size >= 8 &&
        bytes[0] == 77.toByte() && bytes[1] == 77.toByte() && bytes[2] == 77.toByte() && bytes[3] == 68.toByte()
    override fun matches(input: AudioInput, probe: AudioProbe) = hasSignature(probe.bytes)
    override fun open(input: AudioInput): PcmStream {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SMAF opening cancelled")
        val data = input.readEncodedBytes(4 * 1024 * 1024,16)
        val handle = SmafNative.open(data)
        if (handle == 0L) throw IOException("SMAF decoder returned no stream")
        val frames = try { SmafNative.frames(handle) }
        catch (error: Throwable) { SmafNative.close(handle); throw error }
        return object : PcmStream {
            private var owner = handle
            override val format = PcmFormat(44100, 2, frames, bitsPerSample = 32)
            override val decoderName = name
            override val sourceFormat = "SMAF"
            @Synchronized override fun read(samples: IntArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && length <= samples.size-offset)
                if (owner == 0L) throw IOException("SMAF stream is closed")
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SMAF decoding cancelled")
                if (length == 0) return 0
                require(length >= 2)
                return SmafNative.read(owner, samples, offset, length)
            }
            @Synchronized override fun close() { if (owner != 0L) { SmafNative.close(owner); owner = 0L } }
        }
    }
    fun duration(input: AudioInput): Long = open(input).use { it.format.durationMs!! }
}

internal object SmafNative {
    init { System.loadLibrary("infinismaf") }
    external fun open(bytes: ByteArray): Long
    external fun frames(handle: Long): Long
    external fun read(handle: Long, samples: IntArray, offset: Int, length: Int): Int
    external fun close(handle: Long)
}
