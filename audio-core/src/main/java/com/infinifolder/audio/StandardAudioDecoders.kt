package com.infinifolder.audio

import com.infinifolder.audio.stream.*

/** Portable alternatives to platform decoding, shared by Android and desktop JVM hosts. */
object StandardAudioDecoders {
    val decoders: List<PcmDecoder> = listOf(WavePcmDecoder, FlacPcmDecoder, AacPcmDecoder, MpegPcmDecoder)
    fun matches(probe: AudioProbe): Boolean {
        val input = MemoryAudioInput(probe.bytes, "source.${probe.extension.orEmpty()}")
        return decoders.any { it.matches(input, probe) }
    }
}
