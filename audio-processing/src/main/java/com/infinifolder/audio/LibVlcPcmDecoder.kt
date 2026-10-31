package com.infinifolder.audio

import com.infinifolder.audio.tracker.MidiSequencer
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** A real codec backend returning PCM only. libVLC never owns an audio output device. */
internal object LibVlcPcmDecoder : PcmDecoder {
    override val name = "libVLC.PCM"
    override fun matches(input: AudioInput, probe: AudioProbe): Boolean = probe.bytes.isNotEmpty() &&
        !MidiSequencer.isMidi(probe.bytes) && !GameMusicPcmDecoder.matches(input, probe) &&
        probe.normalizedExtension !in setOf("sid","ym","dro","imf") &&
        !(probe.bytes.size >= 4 && String(probe.bytes,0,4,Charsets.US_ASCII) in setOf("PSID","RSID"))
    override fun open(input: AudioInput): PcmStream = Stream(input, null)

    fun inspectDuration(input: AudioInput): Long? = Stream(input, null, 2_000_000_000L).use { it.duration() }

    private class Stream(input: AudioInput, font: File?, private val startupTimeout: Long = 15_000_000_000L) : PcmStream {
        private val closed = AtomicBoolean(false)
        private val lock = Any()
        private var handle = 0L
        private val buffer = ShortArray(32768)
        override val decoderName = if (font == null) name else "${MidiPcmDecoder.name}(${font.nameWithoutExtension})"
        override val sourceFormat: String? = if (font == null) null else "MIDI"
        override val format: PcmFormat
        init {
            try {
                handle = LibVlcPcmNative.open(input, font?.absolutePath)
                if (handle == 0L) throw IOException("libVLC returned no decoding session")
                val started = System.nanoTime()
                var encoded: Long
                do {
                    checkOpen()
                    encoded = synchronized(lock) { LibVlcPcmNative.format(handle) }
                    if (System.nanoTime() - started > startupTimeout) throw IOException("libVLC audio inspection timed out")
                } while (encoded == 0L)
                // Container duration is an estimate, not an exact PCM frame count.
                // Unknown totals use the existing explicit PCM preparation path.
                format = PcmFormat((encoded ushr 8).toInt(), (encoded and 255).toInt())
            } catch (error: Throwable) { close(); throw error }
        }
        fun duration(): Long? = synchronized(lock) { LibVlcPcmNative.duration(handle).takeIf { it > 0 } }
        private fun checkOpen() {
            if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("libVLC PCM decoding cancelled")
        }
        override fun read(samples: IntArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && length <= samples.size - offset)
            checkOpen()
            if (length == 0) return 0
            require(length >= format.channels) { "PCM read must fit a complete frame" }
            val wanted = minOf(length, buffer.size).let { it - it % format.channels }
            val started = System.nanoTime()
            while (true) {
                checkOpen()
                val count = synchronized(lock) {
                    checkOpen()
                    LibVlcPcmNative.read(handle, buffer, wanted)
                }
                if (count < 0) return -1
                if (count > 0) {
                    for (index in 0 until count) samples[offset + index] = buffer[index].toInt() shl 16
                    return count
                }
                if (System.nanoTime() - started > 15_000_000_000L) throw IOException("libVLC PCM decoding made no progress")
            }
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                if (handle != 0L) { LibVlcPcmNative.close(handle); handle = 0L }
            }
        }
    }
}

/** Exact JNI names are retained by consumer rules. */
internal object LibVlcPcmNative {
    init {
        System.loadLibrary("vlc")
        System.loadLibrary("infinivlcpcm")
    }
    fun ensureLoaded() {}
    external fun open(input: AudioInput, soundFont: String?): Long
    external fun format(handle: Long): Long
    external fun duration(handle: Long): Long
    external fun read(handle: Long, samples: ShortArray, length: Int): Int
    external fun close(handle: Long)
}
