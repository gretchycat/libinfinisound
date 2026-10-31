package com.infinifolder.audio

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.analysis.PlaybackPcmAnalyzer
import com.infinifolder.audio.stream.FileAudioInput
import com.infinifolder.audio.stream.probe
import java.io.File

/** Default codec composition. It has no dependency on a viewer, storage runtime or audio output. */
object AudioProcessing : AudioProcessor {
    private val files = AudioFileProcessor(listOf(AndroidPcmDecoder))
    private val platformExtensions = setOf("ogg", "oga", "opus", "mka", "weba", "mp4", "amr", "awb")
    val supportedExtensions: Set<String> get() = PortableAudioProcessing.supportedExtensions + platformExtensions
    val metadata: AudioMetadataReader = AndroidAudioMetadataReader

    override fun isSupported(probe: AudioProbe): Boolean = PortableAudioProcessing.isSupported(probe) || probe.normalizedExtension in platformExtensions
    override fun decode(request: AudioDecodeRequest, observer: AudioDecodeObserver?): AudioDecodeResult? =
        files.decodeBuffered(com.infinifolder.audio.stream.MemoryAudioInput(request.bytes,
            "source.${request.probe.normalizedExtension}", request.file), observer = observer)

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

    fun createAnalyzer(source: io.github.gretchycat.infinistream.SourceResource,
        playback: () -> Pair<Boolean, Long>, onFailure: (String, Throwable) -> Unit = { _, _ -> },
        onSamples: (FloatArray, Int, Int) -> Unit): AudioAnalysisSession =
        PlaybackPcmAnalyzer("", playback, onFailure, onSamples, source)

    fun prefersBufferedDecode(file: File): Boolean = PortableAudioProcessing.prefersBufferedDecode(file)

    fun openPcm(input: AudioInput, preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST): PcmStream =
        files.openPcm(input, preference)

    fun openWaveSource(source: File, scratch: File,
        observer: AudioPreparationObserver? = null, cancelled: () -> Boolean = { false }): AudioWaveSource =
        files.openWaveSource(FileAudioInput(source), scratch, observer = observer, cancelled = cancelled,
            metadata = io.github.gretchycat.infinistream.FileSourceResource(source).metadata)

    /** Playback chooses the measured fast portable MPEG path, with Android fallback inside the library. */
    fun openPlaybackSource(source: File, scratch: File,
        observer: AudioPreparationObserver? = null, cancelled: () -> Boolean = { false }): AudioWaveSource {
        val input = FileAudioInput(source)
        val preference = if (StandardAudioDecoders.prefersPortablePlayback(input)) DecoderPreference.PORTABLE_FIRST
            else DecoderPreference.PLATFORM_FIRST
        return files.openWaveSource(input, scratch, preference, observer, cancelled,
            io.github.gretchycat.infinistream.FileSourceResource(source).metadata)
    }

    /** The host stages only when a legacy renderer requires a file/companion-file context. */
    fun requiresLocalFileForPlayback(source: io.github.gretchycat.infinistream.SourceResource): Boolean =
        PortableAudioProcessing.prefersBufferedDecode(com.infinifolder.audio.StreamAudioInput(source).probe())

    /** Borrowed reopenable input; no implicit encoded-source download or output device access. */
    fun openPlaybackSource(source: io.github.gretchycat.infinistream.SourceResource, scratch: File,
        observer: AudioPreparationObserver? = null, cancelled: () -> Boolean = { false }): AudioWaveSource {
        val delegate = StreamAudioInput(source)
        val input = object : AudioInput {
            override val name = delegate.name
            override fun open(): AudioBytes {
                if (cancelled()) throw java.io.InterruptedIOException("Audio input cancelled")
                val bytes = delegate.open()
                return object : AudioBytes by bytes {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        if (cancelled()) throw java.io.InterruptedIOException("Audio input cancelled")
                        return bytes.readAt(position, buffer, offset, length)
                    }
                }
            }
        }
        val preference = if (StandardAudioDecoders.prefersPortablePlayback(input)) DecoderPreference.PORTABLE_FIRST
            else DecoderPreference.PLATFORM_FIRST
        return files.openWaveSource(input, scratch, preference, observer, cancelled, source.metadata)
    }

    fun probeFormat(source: io.github.gretchycat.infinistream.SourceResource,
        fallbackName: String = "", durationMs: Long = 0L): AudioFormatInfo =
        AndroidAudioMetadataReader.probeFormat(source, fallbackName, durationMs)

    fun readTags(source: io.github.gretchycat.infinistream.SourceResource, includeArtwork: Boolean = false): AudioTags =
        StreamAudioInput(source).open().use { bytes ->
            metadata.readTags(AudioMetadataSource.Seekable(bytes.size, bytes::readAt), includeArtwork)
        }

    fun prepareForOutput(
        source: File,
        destination: File,
        preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST,
        observer: AudioPreparationObserver? = null,
        cancelled: () -> Boolean = { false }
    ): AudioPreparation = files.prepare(FileAudioInput(source), destination, preference, observer, cancelled)

    fun readDuration(file: File): Long {
        runCatching { metadata.readTags(AudioMetadataSource.LocalFile(file)).duration?.toLongOrNull() }
            .getOrNull()?.takeIf { it > 0 }?.let { return it }
        return files.durationMs(FileAudioInput(file))
    }
}
