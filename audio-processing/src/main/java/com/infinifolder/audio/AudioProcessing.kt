package com.infinifolder.audio

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.analysis.PlaybackPcmAnalyzer
import com.infinifolder.audio.decoder.AlacAndMpeg2Decoder
import com.infinifolder.audio.decoder.BuiltInAudioDecoders
import java.io.File

/** Default codec composition. It has no dependency on a viewer, storage runtime or audio output. */
object AudioProcessing : AudioProcessor {
    private val processor = AudioDecoderRegistry(BuiltInAudioDecoders.adapters) { probe ->
        BuiltInAudioDecoders.isSupported(probe.bytes, probe.extension)
    }
    val supportedExtensions: Set<String> get() = BuiltInAudioDecoders.SUPPORTED_EXTENSIONS
    val metadata: AudioMetadataReader = AndroidAudioMetadataReader

    override fun isSupported(probe: AudioProbe): Boolean = processor.isSupported(probe)
    override fun decode(request: AudioDecodeRequest, observer: AudioDecodeObserver?): AudioDecodeResult? =
        processor.decode(request, observer)

    /** Android URI setup is an edge adapter for providers that cannot supply a descriptor. */
    fun readContentTags(context: Context, uri: Uri): AudioTags =
        AndroidAudioMetadataReader.readContentTags(context, uri)

    /** Streams analysis blocks near an externally supplied playhead, without producing audio output. */
    fun createAnalyzer(
        path: String,
        playback: () -> Pair<Boolean, Long>,
        onFailure: (String, Throwable) -> Unit = { _, _ -> },
        onSamples: (FloatArray, Int, Int) -> Unit
    ): AudioAnalysisSession = PlaybackPcmAnalyzer(path, playback, onFailure, onSamples)

    fun prefersBufferedDecode(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in setOf(
            "mp2", "alac", "mod", "s3m", "xm", "it", "mid", "midi", "kar", "rmi",
            "sf2", "sfz", "pat", "dro", "imf", "vgm", "vgz", "ym", "nsf", "spc", "sid",
            "dsf", "dff", "tta", "wv", "ra", "ac3", "eac3", "mlp", "thd", "wma",
            "adx", "g722", "gsm", "caf", "voc", "au", "snd", "8svx", "svx", "16sv", "maud",
            "w64", "rf64", "avr", "ircam", "sph", "pvf", "wve", "htk"
        )) return true
        return ext == "m4a" && runCatching {
            val head = ByteArray(128 * 1024)
            val read = file.inputStream().use { it.read(head) }
            AlacAndMpeg2Decoder.canDecode(head.copyOf(read.coerceAtLeast(0)), ext)
        }.getOrDefault(false)
    }

}
