package com.infinifolder.audio

import com.infinifolder.audio.midi.MidiTimeline
import com.infinifolder.audio.stream.*
import com.infinifolder.audio.tracker.MidiSequencer
import com.infinifolder.audio.tracker.OpenMpt

/** Timing inspection never consumes decoded PCM or invokes a buffered legacy renderer. */
object PortableAudioDuration {
    fun read(input: AudioInput): Long? {
        val probe = input.probe()
        if (MidiSequencer.isMidi(probe.bytes)) return MidiTimeline.read(input).durationMs
        try { EncodedAudioDuration.read(input,probe)?.let { return it } }
        catch (cancel: java.io.InterruptedIOException) { throw cancel }
        catch (_: java.io.IOException) { }
        for (decoder in StandardAudioDecoders.decoders) {
            if (decoder.matches(input,probe)) {
                try { decoder.open(input).use { it.format.durationMs?.let { ms -> return ms } } }
                catch (cancel: java.io.InterruptedIOException) { throw cancel }
                catch (_: Exception) { }
            }
        }
        if (probe.normalizedExtension in setOf("mod","xm","it","s3m","med","okt","mtm","669","far","amf","dsm","ult","psm","umx") && OpenMpt.isAvailable()) {
            val data = input.open().use { if (it.size !in 1..(32*1024*1024).toLong()) return null; ByteReader(it).bytes(0,it.size.toInt()) }
            return (OpenMpt.getDurationSeconds(data)*1000).toLong().takeIf { it > 0 }
        }
        return null
    }
}
