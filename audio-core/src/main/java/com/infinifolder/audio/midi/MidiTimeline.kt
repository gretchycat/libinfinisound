package com.infinifolder.audio.midi

import com.infinifolder.audio.AudioInput
import com.infinifolder.audio.stream.ByteReader
import java.io.IOException
import java.io.InterruptedIOException

/** Parsed timing and original MIDI messages; no synthesizer or sample bank is opened. */
data class MidiMessage(val frame: Long, val bytes: ByteArray)
data class MidiTimeline(val messages: List<MidiMessage>, val totalFrames: Long) {
    val durationMs: Long get() = totalFrames * 1000 / SAMPLE_RATE
    companion object {
        const val SAMPLE_RATE = 44100
        private const val MAX_BYTES = 16 * 1024 * 1024
        fun read(input: AudioInput): MidiTimeline {
            val encoded = input.open().use { b ->
                if (b.size !in 14..MAX_BYTES.toLong()) throw IOException("MIDI source exceeds parsing limits")
                ByteReader(b).bytes(0, b.size.toInt())
            }
            val data = if (String(encoded,0,4,Charsets.US_ASCII) == "RIFF") {
                if (String(encoded,8,4,Charsets.US_ASCII) != "RMID") throw IOException("Invalid RMID header")
                var pos = 12
                var smf: ByteArray? = null
                while (pos <= encoded.size - 8) {
                    val size = (0..3).fold(0L) { n,i -> n or ((encoded[pos+4+i].toLong() and 255) shl (i*8)) }
                    if (size > encoded.size - pos - 8) throw IOException("Truncated RMID chunk")
                    if (String(encoded,pos,4,Charsets.US_ASCII) == "data") { smf=encoded.copyOfRange(pos+8,pos+8+size.toInt()); break }
                    pos += 8 + size.toInt() + (size.toInt() and 1)
                }
                smf ?: throw IOException("Missing RMID MIDI data")
            } else encoded
            var at = 0
            fun fail(): Nothing = throw IOException("Invalid or unsupported MIDI timeline")
            fun byte(): Int = if (at < data.size) data[at++].toInt() and 255 else fail()
            fun number(n: Int): Long { var value = 0L; repeat(n) { value = (value shl 8) or byte().toLong() }; return value }
            fun magic(s: String) { s.forEach { if (byte() != it.code) fail() } }
            magic("MThd")
            val header = number(4)
            if (header < 6 || header > data.size - 8) fail()
            val kind = number(2).toInt(); val tracks = number(2).toInt(); val division = number(2).toInt()
            if (kind !in 0..1 || tracks == 0 || (kind == 0 && tracks != 1) || division == 0) fail()
            at = 8 + header.toInt()
            data class Event(val tick: Long, val message: ByteArray? = null, val tempo: Int = 0)
            val events = ArrayList<Event>()
            var endTick = 0L
            repeat(tracks) {
                magic("MTrk"); val size = number(4)
                if (size > data.size - at) fail()
                val end = at + size.toInt()
                var tick = 0L; var running = 0
                fun trackByte(): Int = if (at < end) byte() else fail()
                fun variable(): Int {
                    var value = 0
                    repeat(4) { value = (value shl 7) or (trackByte() and 127); if ((data[at-1].toInt() and 128) == 0) return value }
                    fail()
                }
                while (at < end) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("MIDI parsing cancelled")
                    if (events.size > 1_000_000) throw IOException("Too many MIDI events")
                    tick += variable(); if (tick > (1L shl 44)) fail()
                    if (at >= end) fail()
                    var status = data[at].toInt() and 255
                    if (status < 128) { if (running == 0) fail(); status = running } else { at++; if (status < 240) running = status }
                    if (status < 240) {
                        val n = if (status and 240 in setOf(192, 208)) 1 else 2
                        val msg = ByteArray(n + 1); msg[0] = status.toByte()
                        repeat(n) { val b = trackByte(); if (b >= 128) fail(); msg[it+1] = b.toByte() }
                        events.add(Event(tick, msg))
                    } else if (status == 255 || status == 240 || status == 247) {
                        running = 0
                        val type = if (status == 255) trackByte() else 0
                        val n = variable(); if (n > end - at) fail()
                        if (status == 255 && type == 81) {
                            if (n != 3) fail()
                            val tempo = ((data[at].toInt() and 255) shl 16) or ((data[at+1].toInt() and 255) shl 8) or (data[at+2].toInt() and 255)
                            if (tempo == 0) fail(); events.add(Event(tick, tempo = tempo))
                        } else if (status == 240) {
                            // FluidLite accepts the SysEx payload, without F0/F7 framing.
                            val length = n - if (n > 0 && data[at+n-1].toInt() and 255 == 247) 1 else 0
                            events.add(Event(tick, byteArrayOf(240.toByte()) + data.copyOfRange(at, at+length)))
                        }
                        at += n
                        if (status == 255 && type == 47) { if (n != 0) fail(); break }
                    } else fail()
                }
                endTick = maxOf(endTick, tick); at = end
            }
            val smpte = division and 32768 != 0
            val framesPerTick = if (smpte) {
                val fps = -(division shr 8).toByte().toInt(); val ticks = division and 255
                if (fps !in setOf(24,25,29,30) || ticks == 0) fail()
                SAMPLE_RATE.toDouble() / (if (fps == 29) 30000.0 / 1001 else fps.toDouble()) / ticks
            } else 0.0
            var tick = 0L; var frames = 0.0; var tempo = 500000
            val messages = ArrayList<MidiMessage>()
            for (event in events.sortedBy { it.tick } + Event(endTick)) {
                frames += (event.tick - tick) * if (smpte) framesPerTick else tempo.toDouble() * SAMPLE_RATE / 1_000_000 / division
                if (!frames.isFinite() || frames > SAMPLE_RATE * 24 * 3600.0) fail()
                tick = event.tick
                event.message?.let { messages.add(MidiMessage(frames.toLong(), it)) }
                if (event.tempo > 0) tempo = event.tempo
            }
            // A finite release tail is part of the rendered timeline, not an estimated container length.
            return MidiTimeline(messages, frames.toLong() + SAMPLE_RATE * 2L)
        }
    }
}
