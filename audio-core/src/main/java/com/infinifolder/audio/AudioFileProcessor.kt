package com.infinifolder.audio

import com.infinifolder.audio.stream.*
import java.io.ByteArrayOutputStream
import com.infinifolder.audio.model.AudioPcmData
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** All codec selection and platform fallback belong here, independent of the audio output. */
class AudioFileProcessor(platformDecoders: List<PcmDecoder> = emptyList()) {
    private val platform = platformDecoders.toList()
    private fun candidates(input: AudioInput, preference: DecoderPreference): List<PcmDecoder> {
        val probe = input.probe()
        val portable = StandardAudioDecoders.decoders + LegacyPcmDecoder
        val customFirst = PortableAudioProcessing.prefersBufferedDecode(probe)
        val candidates = when (preference) {
            DecoderPreference.PORTABLE_ONLY -> portable
            DecoderPreference.PORTABLE_FIRST -> portable + platform
            DecoderPreference.PLATFORM_FIRST -> if (customFirst) listOf(LegacyPcmDecoder) + platform + portable else platform + portable
        }
        return candidates.distinct().filter { it.matches(input, probe) }
    }
    private fun openDecoder(decoder: PcmDecoder, input: AudioInput): PcmStream = try {
        decoder.open(input)
    } catch (error: LinkageError) {
        throw IOException("Codec backend ${decoder.name} is unavailable", error)
    }
    /** Explicit buffered compatibility API. Streaming hosts should use openPcm or prepare. */
    fun decodeBuffered(input: AudioInput, preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST,
        observer: AudioDecodeObserver? = null): AudioDecodeResult? {
        for (decoder in candidates(input, preference)) {
            checkDecodeCancellation()
            try {
                val result = openDecoder(decoder,input).use { stream ->
                    val output = ByteArrayOutputStream()
                    val samples = IntArray(4096 * stream.format.channels)
                    val bytes = ByteArray(samples.size * 2)
                    while (true) {
                        val count = stream.read(samples, 0, samples.size)
                        if (count < 0) break
                        if (count == 0 || count % stream.format.channels != 0) throw IOException("Invalid PCM read")
                        for (i in 0 until count) {
                            bytes[i * 2] = (samples[i] shr 16).toByte()
                            bytes[i * 2 + 1] = (samples[i] shr 24).toByte()
                        }
                        output.write(bytes, 0, count * 2)
                    }
                    if (output.size() == 0) throw IOException("No decoded PCM")
                    val expected = stream.format.totalFrames
                    if (expected != null && output.size().toLong() / 2 / stream.format.channels != expected)
                        throw IOException("Incomplete decoded PCM")
                    val pcm = AudioPcmData.from16BitLittleEndian(output.toByteArray(),stream.format.sampleRate,stream.format.channels)
                    AudioDecodeResult(pcm, stream.decoderName ?: decoder.name, stream.sourceFormat)
                }
                observer?.decoded(result)
                return result
            } catch (cancel: InterruptedIOException) { throw cancel }
            catch (error: Exception) { observer?.warning(decoder.name,error) }
        }
        observer?.unsupported()
        return null
    }

    fun openPcm(input: AudioInput, preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST): PcmStream {
        var failure: Exception? = null
        for (decoder in candidates(input, preference)) {
            checkDecodeCancellation()
            try { return openDecoder(decoder,input) }
            catch (cancel: InterruptedIOException) { throw cancel }
            catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        throw IOException("No decoder accepted ${input.name}", failure)
    }

    /** Known-length PCM is pulled lazily into an explicit disk cache; unknown lengths retain full preparation. */
    fun openWaveSource(input: AudioInput, scratch: File,
        preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST,
        observer: AudioPreparationObserver? = null, cancelled: () -> Boolean = { false },
        metadata: io.github.gretchycat.infinistream.StreamMetadata = io.github.gretchycat.infinistream.StreamMetadata()
    ): AudioWaveSource {
        require((input as? FileAudioInput)?.file?.canonicalFile != scratch.canonicalFile) { "PCM output must differ from source" }
        val sourceClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val isCancelled = { sourceClosed.get() || cancelled() }
        var failure: Exception? = null
        try {
            for (decoder in candidates(input, preference)) {
                checkDecodeCancellation()
                if (isCancelled()) throw InterruptedIOException("Audio preparation cancelled")
                var stream: PcmStream? = null
                try {
                    val pcm = openDecoder(decoder, input)
                    stream = pcm
                    if (pcm.format.totalFrames != null) {
                        val result = AudioPreparation(pcm.format, pcm.decoderName ?: decoder.name, pcm.sourceFormat)
                        val source = PcmWaveSource(pcm, scratch, isCancelled, metadata) { observer?.prepared(it) }
                        stream = null // Source owns the decoder from here onwards.
                        return AudioWaveSource(source, result, true, metadata, { sourceClosed.set(true) }) { error ->
                            observer?.warning(result.decoder, error)
                            // A decoder can fail after its initial block. Retain the full preparation
                            // fallback policy before returning bytes for the failing request.
                            val prepared = prepare(input, scratch, preference, observer, isCancelled)
                            io.github.gretchycat.infinistream.FileSourceResource(scratch).open() to prepared
                        }
                    }
                    val metrics = PcmSignalAccumulator()
                    val format = PcmWaveWriter.write(pcm, scratch, metrics::add, cancelled)
                    pcm.close(); stream = null
                    val result = AudioPreparation(format, pcm.decoderName ?: decoder.name, pcm.sourceFormat, metrics.finish(format))
                    observer?.prepared(result)
                    return AudioWaveSource(io.github.gretchycat.infinistream.FileSourceResource(scratch).open(), result, false, metadata)
                } catch (cancel: InterruptedIOException) { throw cancel }
                catch (error: Exception) {
                    observer?.warning(decoder.name, error)
                    if (failure == null) failure = error else failure.addSuppressed(error)
                } finally { stream?.close() }
            }
            throw IOException("Unable to decode ${input.name}", failure)
        } catch (error: Throwable) { scratch.delete(); throw error }
    }

    /** Writes only to the supplied host-owned PCM staging file; failed attempts are discarded. */
    fun prepare(
        input: AudioInput,
        destination: File,
        preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST,
        observer: AudioPreparationObserver? = null,
        cancelled: () -> Boolean = { false }
    ): AudioPreparation {
        require((input as? FileAudioInput)?.file?.canonicalFile != destination.canonicalFile) { "PCM output must differ from source" }
        try {
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            var failure: Exception? = null
            for (decoder in candidates(input, preference)) {
                checkDecodeCancellation()
                if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                try {
                    val result = openDecoder(decoder,input).use { stream ->
                        val metrics = PcmSignalAccumulator()
                        val format = PcmWaveWriter.write(stream, destination, metrics::add, cancelled)
                        AudioPreparation(format, stream.decoderName ?: decoder.name, stream.sourceFormat, metrics.finish(format))
                    }
                    if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                    observer?.prepared(result)
                    return result
                } catch (cancel: InterruptedIOException) {
                    destination.delete()
                    throw cancel
                } catch (error: Exception) {
                    destination.delete()
                    observer?.warning(decoder.name, error)
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            destination.delete()
            throw IOException("Unable to decode ${input.name}", failure)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun durationMs(input: AudioInput, preference: DecoderPreference = DecoderPreference.PLATFORM_FIRST): Long =
        openPcm(input, preference).use { stream ->
            stream.format.durationMs?.let { return it }
            val buffer = IntArray(4096 * stream.format.channels)
            var samples = 0L
            while (true) {
                val count = stream.read(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) throw IOException("PCM stream made no progress")
                samples += count
            }
            samples / stream.format.channels * 1000 / stream.format.sampleRate
        }
}
