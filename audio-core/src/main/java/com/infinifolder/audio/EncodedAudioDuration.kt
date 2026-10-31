package com.infinifolder.audio

import com.infinifolder.audio.stream.ByteReader
import com.infinifolder.audio.synth.SoundFontReader
import java.io.IOException
import java.io.InterruptedIOException

/** Header/frame inspection for formats without platform duration metadata. No PCM synthesis. */
internal object EncodedAudioDuration {
    fun read(input: AudioInput, probe: AudioProbe): Long? = input.open().use { source ->
        val r = ByteReader(source)
        when {
            probe.normalizedExtension == "cdda" -> (source.size / 4 * 1000 / 44100).takeIf { it > 0 }
            SoundFontReader.isSoundFont(probe.bytes) -> InstrumentPreview.DURATION_MS
            probe.bytes.size >= 16 && r.text(0,4) == "FRM8" && r.text(12,4) == "DSD " -> dff(r)
            probe.normalizedExtension in setOf("mlp", "thd", "truehd") -> mlp(r)
            else -> null
        }
    }

    private fun dff(r: ByteReader): Long? {
        val length = r.uint(4,8)
        if (length < 4 || length > r.source.size-12) return null
        var rate = 0L; var channels = 0L; var dataBytes = 0L; var compression = ""
        var chunks = 0
        fun walk(start: Long, end: Long, properties: Boolean) {
            var at = start
            while (at < end) {
                if (++chunks > 10000 || end-at < 12) throw IOException("Invalid DSDIFF chunks")
                val id = r.text(at,4); val size = r.uint(at+4,8); val body = at+12
                if (size > end-body) throw IOException("Truncated DSDIFF chunk")
                when {
                    !properties && id == "PROP" && size >= 4 && r.text(body,4) == "SND " -> walk(body+4,body+size,true)
                    properties && id == "FS  " && size == 4L -> rate = r.uint(body,4)
                    properties && id == "CHNL" && size >= 2 -> channels = r.uint(body,2)
                    properties && id == "CMPR" && size >= 4 -> compression = r.text(body,4)
                    !properties && id == "DSD " -> dataBytes += size
                }
                at = body+size
                // Standard even padding, plus compatibility with the original
                // unpadded fixture accepted by the existing portable decoder.
                if (size and 1L != 0L && at < end && r.uint(at,1) == 0L) at++
            }
        }
        walk(16,12+length,false)
        // This is the format supported by our existing DSD-to-PCM converter.
        if (rate != 2822400L || channels != 2L || compression != "DSD ") return null
        return (dataBytes/16*1000/44100).takeIf { it > 0 }
    }

    private fun mlp(r: ByteReader): Long? {
        var at = 0L; var units = 0L; var rate = 0L; var samplesPerUnit = 0L
        while (at < r.source.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio timing cancelled")
            if (units >= 10_000_000 || r.source.size-at < 4) return null
            val header = r.bytes(at,minOf(12L,r.source.size-at).toInt())
            fun byte(i: Int) = header[i].toInt() and 255
            val size = (((byte(0) shl 8) or byte(1)) and 0xfff)*2
            if (size < 4 || size > r.source.size-at) return null
            val major = header.size >= 10 && byte(4) == 0xf8 && byte(5) == 0x72 && byte(6) == 0x6f && byte(7) in 0xba..0xbb
            if (major) {
                if (size < 32) return null
                val bits = byte(if (byte(7) == 0xba) 8 else 9) ushr 4
                if (bits == 15) return null
                val nextRate = (if (bits and 8 != 0) 44100L else 48000L) shl (bits and 7)
                if (rate != 0L && rate != nextRate) return null
                rate = nextRate; samplesPerUnit = 40L shl (bits and 7)
            }
            if (rate == 0L) return null
            units++; at += size
        }
        if (rate == 0L || units == 0L) return null
        return (units*samplesPerUnit*1000/rate).takeIf { it > 0 }
    }
}

/** Explicit audition policy shared by instrument-bank rendering and metadata. */
object InstrumentPreview {
    const val DURATION_MS = 2500L
}
