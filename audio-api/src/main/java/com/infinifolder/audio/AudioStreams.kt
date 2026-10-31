package com.infinifolder.audio

/** Reopenable, random-access encoded input. Each opened handle is owned by its decoder. */
interface AudioInput {
    val name: String
    fun open(): AudioBytes
}

/** Read returns -1 only at end of input; zero is valid only for a zero-length request. */
interface AudioBytes : AutoCloseable {
    val size: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

data class PcmFormat(val sampleRate: Int, val channels: Int, val totalFrames: Long? = null, val bitsPerSample: Int = 16) {
    init { require(sampleRate > 0 && channels in 1..8); require(totalFrames == null || totalFrames >= 0); require(bitsPerSample in setOf(16, 24, 32)) }
    val durationMs: Long? get() = totalFrames?.let { it * 1000L / sampleRate }
}

/** Signed interleaved PCM, left-aligned in 32-bit integers; [PcmFormat.bitsPerSample] preserves source precision. Reads contain complete frames; silence is never EOF. */
interface PcmStream : AutoCloseable {
    val format: PcmFormat
    val decoderName: String? get() = null
    val sourceFormat: String? get() = null
    fun read(samples: IntArray, offset: Int, length: Int): Int
}

/** Optional exact output-frame positioning. Seeking changes only this decoder's cursor. */
interface FrameSeekablePcmStream : PcmStream {
    /** Subsequent reads start at this frame in the trimmed, decoded output timeline. */
    fun seekToFrame(frame: Long)
}

/** A platform or portable codec implementation; output and source staging belong to its host. */
interface PcmDecoder {
    val name: String
    fun matches(input: AudioInput, probe: AudioProbe): Boolean
    fun open(input: AudioInput): PcmStream
}

enum class DecoderPreference { PLATFORM_FIRST, PORTABLE_FIRST, PORTABLE_ONLY }

data class AudioPreparation(
    val format: PcmFormat,
    val decoder: String,
    val sourceFormat: String? = null,
    val signalMetrics: com.infinifolder.audio.diagnostics.AudioSignalMetrics? = null
)

interface AudioPreparationObserver {
    fun warning(decoder: String, error: Throwable) {}
    fun prepared(result: AudioPreparation) {}
}
