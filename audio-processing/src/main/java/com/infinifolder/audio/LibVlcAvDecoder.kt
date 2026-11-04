package com.infinifolder.audio

import java.io.InterruptedIOException

/** Unclocked pull decoding through the FFmpeg engine already bundled with libVLC. */
internal object LibVlcAvDecoder : PcmDecoder {
    override val name = "libVLC.FFmpeg"
    override fun matches(input: AudioInput, probe: AudioProbe) = probe.normalizedExtension in setOf(
        "wma","ra","ape","ac3","eac3","dts","tta","wv","mlp","thd","a52",
        "ogg","oga","opus","mka","weba","mp4","3gp","3gpp","3g2","mmf","smaf","amr","awb") || SmafPcmDecoder.hasSignature(probe.bytes)
    override fun open(input: AudioInput): PcmStream = Stream(input)
    fun inspectDuration(input: AudioInput): Long? = Stream(input).use { it.duration() }
    private class Stream(input: AudioInput) : PcmStream {
        private var handle = LibVlcAvNative.open(input)
        private val buffer = ShortArray(32768)
        override val format: PcmFormat
        override val decoderName: String
        override val sourceFormat: String
        init {
            try {
                val raw = LibVlcAvNative.format(handle)
                format = PcmFormat((raw ushr 8).toInt(),(raw and 255).toInt())
                sourceFormat = LibVlcAvNative.codec(handle)
                decoderName = "$name($sourceFormat)"
            } catch (error: Throwable) { close(); throw error }
        }
        fun duration() = LibVlcAvNative.duration(handle).takeIf { it > 0 }
        @Synchronized override fun read(samples: IntArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && length <= samples.size-offset)
            if (handle == 0L || Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio decode cancelled")
            if (length == 0) return 0
            require(length >= format.channels)
            val n = minOf(length,buffer.size).let { it-it%format.channels }
            val count = LibVlcAvNative.read(handle,buffer,n)
            for (i in 0 until maxOf(0,count)) samples[offset+i]=buffer[i].toInt() shl 16
            return count
        }
        @Synchronized override fun close() { if (handle != 0L) { LibVlcAvNative.close(handle); handle=0 } }
    }
}
internal object LibVlcAvNative {
    external fun available(): Boolean
    init { LibVlcPcmNative.ensureLoaded() }
    external fun open(input: AudioInput): Long
    external fun format(handle: Long): Long
    external fun codec(handle: Long): String
    external fun duration(handle: Long): Long
    external fun read(handle: Long, samples: ShortArray, length: Int): Int
    external fun close(handle: Long)
}
