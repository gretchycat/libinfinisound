package com.infinifolder.audio.stream

import com.infinifolder.audio.AudioInput
import java.io.IOException
import java.io.InterruptedIOException

data class AudioTrimInfo(val leadingFrames: Long, val totalFrames: Long?)

/** Shared container timing for native and portable decoding; never infer trim from silent samples. */
object AudioStreamTiming {
    fun read(input: AudioInput, outputRate: Int): AudioTrimInfo? {
        try {
            val header = input.open().use { source ->
                ByteReader(source).bytes(0,minOf(source.size,12L).toInt())
            }
            if (header.size >= 8 && String(header,4,4,Charsets.US_ASCII) in setOf("ftyp","moov","mdat","free","wide")) {
                val source = input.open()
                val packets = try { Mp4AacPackets(ByteReader(source)) }
                    catch (error: Throwable) { source.close(); throw error }
                packets.use {
                    return AudioTrimInfo(it.skipFrames*outputRate/it.timeScale,
                        it.totalFrames?.let { frames -> frames*outputRate/it.timeScale })
                }
            }
            val probe = input.probe(128 * 1024)
            val decoder = when {
                FlacPcmDecoder.matches(input,probe) -> FlacPcmDecoder
                WavePcmDecoder.matches(input,probe) -> WavePcmDecoder
                else -> null
            }
            if (decoder != null) decoder.open(input).use { stream ->
                return AudioTrimInfo(0,stream.format.totalFrames?.let { it*outputRate/stream.format.sampleRate })
            }
            if (MpegPcmDecoder.matches(input,probe)) MpegGapless.read(input)?.let { trim ->
                // Android's extractor omits the Xing/Info framing packet; its codec still has synthesis delay.
                return AudioTrimInfo((trim.skip-trim.headerSamples)*outputRate/trim.sampleRate,
                    trim.total?.let { it*outputRate/trim.sampleRate })
            }
        } catch (cancel: InterruptedIOException) { throw cancel }
        catch (_: IOException) { /* Optional timing unsupported by this parser; retain platform trim metadata. */ }
        return null
    }
}
