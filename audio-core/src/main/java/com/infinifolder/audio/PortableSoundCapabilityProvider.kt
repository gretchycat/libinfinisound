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
        PcmCapabilityHandler(CddaPcmDecoder, listOf(FormatSupport(setOf("audio/x-cdda"), setOf("cdda", "cdda-be"))),
            limitations = listOf("Raw Red Book 44.1 kHz 16-bit stereo PCM; source must contain complete sample frames")),
        PcmCapabilityHandler(AacPcmDecoder, listOf(FormatSupport(setOf("audio/aac", "audio/mp4"), setOf("aac","m4a","m4b","m4r","3ga"),
            magic = listOf(0xf0, 0xf1, 0xf8, 0xf9).map { MagicSignature("ff%02x".format(it), description="ADTS sync; frame header validated by decoder") } +
                MagicSignature("66747970",4,description="ISO Base Media File Type box; AAC track validated by decoder"))),
            limitations = listOf("ADTS or supported MP4 AAC; no DRM"),runtime={dependency("net.sourceforge.jaad.aac.Decoder")}),
        PcmCapabilityHandler(MpegPcmDecoder, listOf(FormatSupport(setOf("audio/mpeg"), setOf("mp3","mp2","mpa"),
            magic = listOf(MagicSignature("494433",description="ID3 tag; audio frames validated by decoder")) +
                (0xe0..0xff).map { MagicSignature("ff%02x".format(it), description="MPEG frame sync; frame validated by decoder") })),
            runtime={dependency("javazoom.jl.decoder.Decoder")}),
        MagicAudioIdentityHandler(HandlerDescriptor("sound.portable.magic", "sound", "Portable audio signatures",
            legacyAudioFormats().filter { it.magic.isNotEmpty() }, setOf(Operations.IDENTIFY),
            limitations = listOf("Bounded exact signatures; playback parser validates content when opened"), priority = 20,
            attributes = mapOf("presentation" to "audio"))),
        PcmCapabilityHandler(LegacyPcmDecoder, legacyAudioFormats(), "buffered",
            limitations = listOf("Bounded buffered compatibility decoders; companion-file formats need local context; optional native tracker/synth dependencies are checked on open"),
            runtime = { HandlerAvailability(AvailabilityState.CONDITIONAL,"Legacy families include optional native backends; successful parser/decoder open is required") }),
        com.infinifolder.audio.playlist.PlaylistParser
    )
}

/** Format-specific identity facts for the bounded legacy parser adapter. */
private fun legacyAudioFormats(): List<FormatSupport> = listOf(
    FormatSupport(setOf("audio/basic"), setOf("au", "snd"), magic = listOf(MagicSignature("2e736e64"))),
    FormatSupport(setOf("audio/x-aiff"), setOf("aiff", "aif", "aifc"), magic = listOf(
        MagicSignature("41494646", 8), MagicSignature("41494643", 8))),
    FormatSupport(setOf("audio/x-voc"), setOf("voc"), magic = listOf(MagicSignature("437265617469766520566f6963652046696c651a"))),
    FormatSupport(setOf("audio/x-8svx"), setOf("8svx", "svx"), magic = listOf(MagicSignature("38535658", 8))),
    FormatSupport(setOf("audio/x-16sv"), setOf("16sv"), magic = listOf(MagicSignature("31365356", 8))),
    FormatSupport(setOf("audio/x-maud"), setOf("maud"), magic = listOf(MagicSignature("4d415544", 8))),
    FormatSupport(setOf("audio/x-wave64"), setOf("w64"), magic = listOf(MagicSignature("726966662e91"))),
    FormatSupport(setOf("audio/x-rf64"), setOf("rf64"), magic = listOf(MagicSignature("52463634"))),
    FormatSupport(setOf("audio/x-caf"), setOf("caf"), magic = listOf(MagicSignature("63616666"))),
    FormatSupport(setOf("audio/x-avr"), setOf("avr"), magic = listOf(MagicSignature("32424954"))),
    FormatSupport(setOf("audio/x-ircam"), setOf("ircam"), magic = listOf(
        MagicSignature("64a30100"), MagicSignature("0001a364"))),
    FormatSupport(setOf("audio/x-nist-sphere"), setOf("sph"), magic = listOf(MagicSignature("4e4953545f3141"))),
    FormatSupport(setOf("audio/x-pvf"), setOf("pvf"), magic = listOf(
        MagicSignature("505646310a"), MagicSignature("505646320a"))),
    FormatSupport(setOf("audio/x-wve"), setOf("wve"), magic = listOf(MagicSignature("414c6177536f756e6446696c652a2a"))),
    FormatSupport(setOf("audio/x-htk"), setOf("htk")),
    FormatSupport(setOf("audio/x-adx"), setOf("adx"), magic = listOf(MagicSignature("8000"))),
    FormatSupport(setOf("audio/mpeg"), setOf("mp2", "mpa")),
    FormatSupport(setOf("audio/alac"), setOf("alac")),
    FormatSupport(setOf("audio/midi"), setOf("mid", "midi", "kar", "rmi"), magic = listOf(MagicSignature("4d546864"))),
    FormatSupport(setOf("audio/x-soundfont"), setOf("sf2"), magic = listOf(MagicSignature("7366626b", 8))),
    FormatSupport(setOf("audio/x-sfz"), setOf("sfz")),
    FormatSupport(setOf("audio/x-gus-patch"), setOf("pat"), magic = listOf(MagicSignature("4746315041544348"))),
    FormatSupport(setOf("audio/x-mod"), setOf("mod"), magic = listOf(
        MagicSignature("4d2e4b2e", 1080), MagicSignature("4d214b21", 1080),
        MagicSignature("3443484e", 1080), MagicSignature("3643484e", 1080), MagicSignature("3843484e", 1080),
        MagicSignature("464c5434", 1080), MagicSignature("464c5438", 1080), MagicSignature("4f435441", 1080),
        MagicSignature("3136434e", 1080), MagicSignature("3332434e", 1080))),
    FormatSupport(setOf("audio/x-s3m"), setOf("s3m"), magic = listOf(MagicSignature("5343524d", 44))),
    FormatSupport(setOf("audio/x-xm"), setOf("xm"), magic = listOf(MagicSignature("457874656e646564204d6f64756c653a20"))),
    FormatSupport(setOf("audio/x-it"), setOf("it"), magic = listOf(MagicSignature("494d504d"))),
    FormatSupport(setOf("audio/x-dsf"), setOf("dsf"), magic = listOf(MagicSignature("44534420"))),
    FormatSupport(setOf("audio/x-dff"), setOf("dff"), magic = listOf(MagicSignature("46524d38"))),
    FormatSupport(extensions = BuiltInAudioDecoders.SUPPORTED_EXTENSIONS - setOf(
        "au", "snd", "aiff", "aif", "aifc", "voc", "8svx", "svx", "16sv", "maud", "w64", "rf64", "caf",
        "avr", "ircam", "sph", "pvf", "wve", "htk", "adx", "mp2", "alac", "mid", "midi", "kar", "rmi",
        "sf2", "sfz", "pat", "mod", "s3m", "xm", "it", "dsf", "dff", "cdda"))
)

private fun dependency(name:String):HandlerAvailability {
    Class.forName(name,false,PortableSoundCapabilityProvider::class.java.classLoader)
    return HandlerAvailability(dependencies=mapOf(name to true))
}
