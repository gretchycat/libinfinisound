package com.infinifolder.audio

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.analysis.PlaybackPcmAnalyzer
import com.infinifolder.audio.stream.FileAudioInput
import com.infinifolder.audio.stream.probe
import java.io.File
import io.github.gretchycat.infinistream.Operations

/** Default codec composition. It has no dependency on a viewer, storage runtime or audio output. */
object AudioProcessing : AudioProcessor {
    private val files = AudioFileProcessor()
    val supportedExtensions: Set<String> get() = SoundCapabilities.registry.query(Operations.DECODE).flatMap { it.descriptor.formats }.flatMap { it.extensions }.toSet()
    val metadata: AudioMetadataReader = AndroidAudioMetadataReader

    /** Configure a caller-owned MIDI SoundFont. MIDI otherwise uses the portable built-in synthesizer. */
    fun setMidiSoundFont(file: File) { MidiPcmDecoder.soundFont = { file }; SoundCapabilities.registry.refresh() }
    /** Loads the configured FluidLite bank into its process-wide resident cache without rendering a track. */
    fun preloadMidiSoundFont(): Boolean = MidiPcmDecoder.preloadSoundFont()
    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        MidiPcmDecoder.soundFont = null
        SoundCapabilities.registry.refresh()
    }

    override fun isSupported(probe: AudioProbe): Boolean = PortableAudioProcessing.isSupported(probe) || probe.normalizedExtension in supportedExtensions || SmafPcmDecoder.hasSignature(probe.bytes) || com.infinifolder.audio.guitarpro.GuitarPro3.matches(probe.bytes)
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

    /** PCM WAVE output with explicit packing; never opens an audio output device. */
    fun openWaveSource(source: File, scratch: File, output: PcmOutputOptions,
        cancelled: () -> Boolean = { false }): AudioWaveSource =
        files.openWaveSource(FileAudioInput(source),scratch,output,cancelled = cancelled,
            metadata = io.github.gretchycat.infinistream.FileSourceResource(source).metadata)

    fun openWaveSource(source: io.github.gretchycat.infinistream.SourceResource, scratch: File,
        output: PcmOutputOptions, cancelled: () -> Boolean = { false }): AudioWaveSource =
        files.openWaveSource(StreamAudioInput(source),scratch,output,cancelled = cancelled,metadata = source.metadata)

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
    fun requiresLocalFileForPlayback(source: io.github.gretchycat.infinistream.SourceResource): Boolean {
        val probe = StreamAudioInput(source).probe()
        if (MidiPcmDecoder.soundFont != null && com.infinifolder.audio.tracker.MidiSequencer.isMidi(probe.bytes)) return false
        return PortableAudioProcessing.prefersBufferedDecode(probe)
    }

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

    fun readTags(source: io.github.gretchycat.infinistream.SourceResource, includeArtwork: Boolean = false): AudioTags {
        val delegate = StreamAudioInput(source)
        val caller = Thread.currentThread()
        var deadline = System.nanoTime() + 3_000_000_000L
        val input = object : AudioInput {
            override val name = delegate.name
            override fun open(): AudioBytes {
                val bytes = delegate.open()
                return object : AudioBytes by bytes {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        if (caller.isInterrupted) throw java.io.InterruptedIOException("Audio metadata cancelled")
                        if (System.nanoTime() > deadline) throw java.io.IOException("Audio metadata inspection timed out")
                        return bytes.readAt(position,buffer,offset,length)
                    }
                }
            }
        }
        val probe = input.probe()
        // Android cannot inspect these event/emulation formats. Timing does not need PCM or a bank.
        if (com.infinifolder.audio.tracker.MidiSequencer.isMidi(probe.bytes))
            return AudioTags(null,null,null,com.infinifolder.audio.midi.MidiTimeline.read(input).durationMs.toString())
        if (com.infinifolder.audio.guitarpro.GuitarPro3.matches(probe.bytes)) {
            val score = com.infinifolder.audio.guitarpro.GuitarPro3.read(input)
            return AudioTags(score.title,score.artist,null,score.timeline.durationMs.toString())
        }
        if (SmafPcmDecoder.matches(input,probe))
            return AudioTags(null,null,null,SmafPcmDecoder.duration(input).toString())
        if (GameMusicPcmDecoder.matches(input,probe))
            return AudioTags(null,null,null,GameMusicPcmDecoder.inspectDuration(input)?.toString())
        val tags = runCatching { input.open().use { bytes ->
            metadata.readTags(AudioMetadataSource.Seekable(bytes.size, bytes::readAt), includeArtwork)
        } }.getOrElse { AudioTags(null,null,null,null) }
        if ((tags.duration?.toLongOrNull() ?: 0) > 0) return tags
        // A failed platform probe must not consume the portable/native fallback's
        // complete read budget. Each stage remains bounded independently.
        deadline = System.nanoTime() + 3_000_000_000L
        val duration = try { inspectDuration(input) }
            catch (cancel: java.io.InterruptedIOException) { throw cancel }
            catch (_: Exception) { null }
            catch (_: LinkageError) { null }
        return tags.copy(duration = duration?.toString())
    }

    private fun inspectDuration(input: AudioInput): Long? {
        PortableAudioDuration.read(input)?.let { return it }
        val probe = input.probe()
        if (com.infinifolder.audio.guitarpro.GuitarPro3.matches(probe.bytes)) {
            val score = com.infinifolder.audio.guitarpro.GuitarPro3.read(input)
            return score.timeline.durationMs
        }
        if (SmafPcmDecoder.matches(input,probe))
            return SmafPcmDecoder.duration(input)
        if (GameMusicPcmDecoder.matches(input,probe)) return GameMusicPcmDecoder.inspectDuration(input)
        // Many portable legacy containers (AIFF, CAF, AU, VOC, DSD, etc.) play
        // successfully but Android supplies no timing for them. Let the same
        // native demuxer inspect these too, without rendering an entire track.
        if (LibVlcPcmDecoder.matches(input,probe) &&
            probe.normalizedExtension !in setOf("sf2", "sfz", "pat")) {
            try { return LibVlcAvDecoder.inspectDuration(input) }
            catch (cancel: java.io.InterruptedIOException) { throw cancel }
            catch (_: Exception) { }
            catch (_: LinkageError) { }
            return LibVlcPcmDecoder.inspectDuration(input)
        }
        return null
    }

    fun readDuration(source: io.github.gretchycat.infinistream.SourceResource): Long =
        readTags(source).duration?.toLongOrNull() ?: 0L

    fun prepareForOutput(
        source: File,
        destination: File,
        preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST,
        observer: AudioPreparationObserver? = null,
        cancelled: () -> Boolean = { false }
    ): AudioPreparation = files.prepare(FileAudioInput(source), destination, preference, observer, cancelled)

    /** Metadata/timeline inspection only; never render a song to calculate its playlist length. */
    fun readDuration(file: File): Long = readDuration(io.github.gretchycat.infinistream.FileSourceResource(file))
}
