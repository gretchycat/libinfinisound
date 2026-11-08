package com.infinifolder.audio

import com.infinifolder.audio.stream.*

/** Portable alternatives to platform decoding, shared by Android and desktop JVM hosts. */
object StandardAudioDecoders {
    /** Content/timing-based playback policy; all codec types remain behind the common interface. */
    fun prefersPortablePlayback(input: AudioInput): Boolean {
        // Raw CD-DA has no media-container header for Android's generic demuxer to
        // inspect. Its dedicated portable decoder knows the fixed PCM layout.
        if (input.name.substringAfterLast('.', "").lowercase() in setOf("cdda", "cdda-be")) return true
        val b = input.probe(12).bytes
        val signature = b.size >= 3 && (String(b, 0, 3, Charsets.US_ASCII) == "ID3" ||
            (b[0].toInt() and 255 == 255 && b[1].toInt() and 0xe0 == 0xe0))
        if (!signature) return false
        return try { MpegGapless.read(input)?.total != null }
        catch (cancel: java.io.InterruptedIOException) { throw cancel }
        catch (_: java.io.IOException) { false }
    }
    val decoders: List<PcmDecoder> get() = SoundCapabilities.decoders("portable").filter { it !== LegacyPcmDecoder }
    fun matches(probe: AudioProbe): Boolean {
        val input = MemoryAudioInput(probe.bytes, "source.${probe.extension.orEmpty()}")
        return decoders.any { it.matches(input, probe) }
    }
}
