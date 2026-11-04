package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import com.infinifolder.audio.decoder.BuiltInAudioDecoders
import io.github.gretchycat.infinistream.*

/** Registration only. Each adapter pairs format facts with the decoder it actually invokes. */
class PortableSoundCapabilityProvider : CapabilityProvider {
    override val library = "sound"
    override fun handlers(): List<CapabilityHandler> = listOf(
        PcmCapabilityHandler(WavePcmDecoder, listOf(FormatSupport(setOf("audio/wav", "audio/x-wav"), setOf("wav", "wave"),
            magic = listOf(MagicSignature("52494646",description="RIFF; WAVE form and sample layout validated by decoder")))),
            limitations = listOf("RIFF PCM/IEEE float; supported extensible layouts only")),
        PcmCapabilityHandler(FlacPcmDecoder, listOf(FormatSupport(setOf("audio/flac"), setOf("flac"),
            magic = listOf(MagicSignature("664c6143")))), limitations = listOf("Native FLAC; decoder validates frame/header limits")),
        PcmCapabilityHandler(AacPcmDecoder, listOf(FormatSupport(setOf("audio/aac", "audio/mp4"), setOf("aac","m4a","m4b","m4r","3ga"))),
            limitations = listOf("ADTS or supported MP4 AAC; no DRM"),runtime={dependency("net.sourceforge.jaad.aac.Decoder")}),
        PcmCapabilityHandler(MpegPcmDecoder, listOf(FormatSupport(setOf("audio/mpeg"), setOf("mp3","mp2","mpa"),
            magic = listOf(MagicSignature("494433",description="ID3 tag; audio frames validated on open")))),runtime={dependency("javazoom.jl.decoder.Decoder")}),
        PcmCapabilityHandler(LegacyPcmDecoder, listOf(FormatSupport(extensions = BuiltInAudioDecoders.SUPPORTED_EXTENSIONS)),
            limitations = listOf("Bounded buffered compatibility decoders; companion-file formats need local context; optional native tracker/synth dependencies are checked on open"),
            runtime = { HandlerAvailability(AvailabilityState.CONDITIONAL,"Legacy families include optional native backends; successful parser/decoder open is required") })
    )
}

private fun dependency(name:String):HandlerAvailability {
    Class.forName(name,false,PortableSoundCapabilityProvider::class.java.classLoader)
    return HandlerAvailability(dependencies=mapOf(name to true))
}
