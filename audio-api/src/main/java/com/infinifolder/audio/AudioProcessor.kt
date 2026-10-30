package com.infinifolder.audio

import com.infinifolder.audio.model.AudioPcmData
import java.io.File

/** Bytes used for format detection. A caller can supply a header without decoding a track. */
data class AudioProbe(val bytes: ByteArray, val extension: String? = null) {
    val normalizedExtension: String get() = extension?.lowercase() ?: ""
}

/**
 * A buffered decode request. The caller owns [bytes] and the optional [file], including its
 * staging lease. The file supplies ALAC random access and the base directory for SFZ samples.
 * Existing render limits and synthesis defaults belong to each adapter.
 */
data class AudioDecodeRequest(val bytes: ByteArray, val extension: String? = null, val file: File? = null) {
    val probe: AudioProbe get() = AudioProbe(bytes, extension ?: file?.extension)
}

/** Only library-owned PCM and descriptive metadata cross a codec boundary. */
data class AudioDecodeResult(val pcm: AudioPcmData, val decoder: String, val sourceFormat: String? = null)

/** An adapter translates a parser, synthesizer or third-party codec into the common API. */
interface AudioDecoder {
    val name: String
    fun matches(probe: AudioProbe): Boolean
    fun decode(request: AudioDecodeRequest): AudioPcmData?
    fun sourceFormat(request: AudioDecodeRequest): String? = null
}

/** Per-request diagnostics; implementations must not throw or retain the source. */
interface AudioDecodeObserver {
    fun decoded(result: AudioDecodeResult) {}
    fun warning(decoder: String, error: Throwable) {}
    fun unsupported() {}
}

/** Parsing and processing only. This contract owns no player, output device or queue. */
interface AudioProcessor {
    fun isSupported(probe: AudioProbe): Boolean
    fun decode(request: AudioDecodeRequest, observer: AudioDecodeObserver? = null): AudioDecodeResult?
}

/** A cancellable analysis job, independent of the component that outputs the audio. */
interface AudioAnalysisSession {
    fun start()
    fun stop()
}
