package com.infinifolder.audio

import com.infinifolder.audio.guitarpro.GuitarPro3
import com.infinifolder.audio.midi.MidiTimeline
import com.infinifolder.audio.tracker.MidiSequencer
import com.infinifolder.audio.stream.probe
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** Independent voices/cursors, sharing a resident native SoundFont sample bank. */
internal object MidiPcmDecoder : PcmDecoder {
    override val name = "libVLC.FluidLite"
    @Volatile var soundFont: (() -> File)? = null
    override fun matches(input: AudioInput, probe: AudioProbe) = soundFont != null && (MidiSequencer.isMidi(probe.bytes) || GuitarPro3.matches(probe.bytes))
    override fun open(input: AudioInput): PcmStream {
        val guitarPro = GuitarPro3.matches(input.probe().bytes)
        val timeline = if (guitarPro) GuitarPro3.read(input).timeline else MidiTimeline.read(input)
        val font = soundFont?.invoke() ?: throw IOException("No MIDI SoundFont configured")
        if (!font.isFile || !font.canRead()) throw IOException("MIDI SoundFont is unreadable")
        return object : PcmStream {
            private var handle = FluidLiteNative.open(font.absolutePath)
            private var frame = 0L
            private var event = 0
            private var released = false
            private val buffer = ShortArray(8192)
            override val decoderName = "$name(${font.nameWithoutExtension})"
            override val sourceFormat = if (guitarPro) "Guitar Pro 3" else "MIDI"
            override val format = PcmFormat(MidiTimeline.SAMPLE_RATE, 2, timeline.totalFrames)
            @Synchronized override fun read(samples: IntArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && length <= samples.size-offset)
                if (handle == 0L || Thread.currentThread().isInterrupted) throw InterruptedIOException("MIDI stream closed or cancelled")
                if (length == 0) return 0
                require(length >= 2)
                if (frame == timeline.totalFrames) return -1
                while (event < timeline.messages.size && timeline.messages[event].frame <= frame)
                    FluidLiteNative.event(handle, timeline.messages[event++].bytes)
                val release = timeline.totalFrames - MidiTimeline.SAMPLE_RATE * 2L
                if (!released && frame >= release) {
                    for (ch in 0..15) {
                        FluidLiteNative.event(handle, byteArrayOf((176+ch).toByte(),64,0))
                        FluidLiteNative.event(handle, byteArrayOf((176+ch).toByte(),123,0))
                    }
                    released = true
                }
                var end = timeline.messages.getOrNull(event)?.frame ?: timeline.totalFrames
                if (!released) end = minOf(end, release)
                val frames = minOf((length/2).toLong(), (buffer.size/2).toLong(), end-frame).toInt()
                if (frames <= 0) throw IOException("MIDI timeline made no progress")
                FluidLiteNative.render(handle, buffer, frames)
                for (i in 0 until frames*2) samples[offset+i] = buffer[i].toInt() shl 16
                frame += frames
                return frames*2
            }
            @Synchronized override fun close() { if (handle != 0L) { FluidLiteNative.close(handle); handle=0L } }
        }
    }
}
internal object FluidLiteNative {
    init { LibVlcPcmNative.ensureLoaded() }
    external fun open(path: String): Long
    external fun event(handle: Long, message: ByteArray)
    external fun render(handle: Long, samples: ShortArray, frames: Int)
    external fun close(handle: Long)
    external fun bankLoads(): Int
}
