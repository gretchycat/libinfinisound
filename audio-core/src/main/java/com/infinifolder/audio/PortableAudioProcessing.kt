package com.infinifolder.audio

import com.infinifolder.audio.decoder.BuiltInAudioDecoders
import com.infinifolder.audio.decoder.AlacAndMpeg2Decoder
import java.io.File
import com.infinifolder.audio.stream.probe

/** Portable JVM composition; contains no Android API or output device. */
object PortableAudioProcessing : AudioProcessor {
    private val legacy = AudioDecoderRegistry(BuiltInAudioDecoders.adapters) { probe ->
        BuiltInAudioDecoders.isSupported(probe.bytes, probe.extension)
    }
    val supportedExtensions: Set<String> get() = BuiltInAudioDecoders.SUPPORTED_EXTENSIONS +
        setOf("wav", "mp3", "aac", "m4a", "m4b", "m4r", "3ga", "flac")
    override fun isSupported(probe: AudioProbe): Boolean =
        probe.normalizedExtension in supportedExtensions || legacy.isSupported(probe) || StandardAudioDecoders.matches(probe)
    override fun decode(request: AudioDecodeRequest, observer: AudioDecodeObserver?): AudioDecodeResult? =
        AudioFileProcessor().decodeBuffered(com.infinifolder.audio.stream.MemoryAudioInput(request.bytes,
            "source.${request.probe.normalizedExtension}",request.file),DecoderPreference.PORTABLE_ONLY,observer)
    fun decodeLegacy(request: AudioDecodeRequest, observer: AudioDecodeObserver? = null): AudioDecodeResult? =
        legacy.decode(request, observer)
    fun prefersBufferedDecode(file: File): Boolean = prefersBufferedDecode(com.infinifolder.audio.stream.FileAudioInput(file).probe())

    fun prefersBufferedDecode(probe: AudioProbe): Boolean {
        val ext = probe.normalizedExtension
        if (ext in setOf(
            "mp2", "alac", "mod", "s3m", "xm", "it", "mid", "midi", "kar", "rmi",
            "sf2", "sfz", "pat",
            "dsf", "dff",
            "adx", "caf", "voc", "au", "snd", "8svx", "svx", "16sv", "maud",
            "w64", "rf64", "avr", "ircam", "sph", "pvf", "wve", "htk"
        )) return true
        return ext == "m4a" && AlacAndMpeg2Decoder.canDecode(probe.bytes, ext)
    }
}
