package com.infinifolder.audio.guitarpro

import app.tuxguitar.gm.GMChannelRoute
import app.tuxguitar.io.base.TGSongReaderHandle
import app.tuxguitar.io.gtp.GP3InputStream
import app.tuxguitar.io.gtp.GTPSettings
import app.tuxguitar.player.base.MidiSequenceHandler
import app.tuxguitar.player.base.MidiSequenceParser
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.song.managers.TGSongManager
import app.tuxguitar.song.models.TGDuration
import app.tuxguitar.song.models.TGTimeSignature
import com.infinifolder.audio.AudioInput
import com.infinifolder.audio.midi.MidiMessage
import com.infinifolder.audio.midi.MidiTimeline
import com.infinifolder.audio.readEncodedBytes
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

/** GP3 score interpretation only. Synthesis and the sample bank belong to Sound's MIDI backend. */
object GuitarPro3 {
    private const val VERSION = "FICHIER GUITAR PRO v3.00"
    data class Score(val timeline: MidiTimeline, val title: String?, val artist: String?)
    fun matches(bytes: ByteArray): Boolean {
        if (bytes.size < 31) return false
        val length = bytes[0].toInt() and 255
        return length == VERSION.length && String(bytes, 1, length, Charsets.US_ASCII) == VERSION
    }
    fun read(input: AudioInput): Score {
        val deadline = System.nanoTime() + 10_000_000_000L
        fun check() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Guitar Pro parsing cancelled")
            if (System.nanoTime() > deadline) throw IOException("Guitar Pro parsing timed out")
        }
        check()
        val data = input.readEncodedBytes(4 * 1024 * 1024,31)
        if (!matches(data)) throw IOException("Not a Guitar Pro 3 score")
        val factory = TGFactory()
        val stream = object : ByteArrayInputStream(data) {
            override fun read(): Int {
                check()
                return super.read().also { if (it < 0) throw EOFException("Truncated Guitar Pro score") }
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                check()
                if (length > available()) throw EOFException("Truncated Guitar Pro score")
                return super.read(bytes, offset, length)
            }
        }
        val handle = TGSongReaderHandle().apply { setFactory(factory); setInputStream(stream) }
        try { stream.use { GP3InputStream(GTPSettings().apply { charset = "windows-1252" }).read(handle) } }
        catch (error: Exception) { check(); throw IOException("Cannot parse Guitar Pro 3 score", error) }
        check()
        val song = handle.song ?: throw IOException("Empty Guitar Pro score")
        data class Event(val tick: Long, val bytes: ByteArray? = null, val tempo: Int = 0)
        val events = ArrayList<Event>()
        fun add(tick: Long, bytes: ByteArray? = null, tempo: Int = 0) {
            check()
            if (tick < 0 || tick > 100_000_000 || events.size >= 500_000) throw IOException("Guitar Pro event limit exceeded")
            events.add(Event(tick, bytes, tempo))
        }
        data class Route(val primary: Int, val secondary: Int)
        val routes = (0 until song.countChannels()).associate { i ->
            val channel = song.getChannel(i)
            fun number(key: String): Int? = (0 until channel.countParameters())
                .map { channel.getParameter(it) }.firstOrNull { it.key == key }?.value?.toIntOrNull()?.takeIf { it in 0..15 }
            val primary = if (channel.isPercussionChannel) 9 else number(GMChannelRoute.PARAMETER_GM_CHANNEL_1)
                ?: throw IOException("Guitar Pro channel is outside General MIDI range")
            channel.channelId to Route(primary, number(GMChannelRoute.PARAMETER_GM_CHANNEL_2) ?: primary)
        }
        fun route(channel: Int, bend: Boolean = false): Int {
            val r = routes[channel] ?: throw IOException("Unknown Guitar Pro channel")
            return if (bend) r.secondary else r.primary
        }
        fun message(tick: Long, status: Int, channel: Int, a: Int, b: Int? = null, bend: Boolean = false) {
            val bytes = if (b == null) byteArrayOf((status or route(channel,bend)).toByte(),a.coerceIn(0,127).toByte())
                else byteArrayOf((status or route(channel,bend)).toByte(),a.coerceIn(0,127).toByte(),b.coerceIn(0,127).toByte())
            add(tick, bytes)
        }
        val handler = object : MidiSequenceHandler(song.countTracks() + 2) {
            override fun addNoteOn(tick: Long, track: Int, channel: Int, note: Int, velocity: Int, voice: Int, bendMode: Boolean) =
                message(tick,0x90,channel,note,velocity,bendMode)
            override fun addNoteOff(tick: Long, track: Int, channel: Int, note: Int, velocity: Int, voice: Int, bendMode: Boolean) =
                message(tick,0x80,channel,note,velocity,bendMode)
            override fun addPitchBend(tick: Long, track: Int, channel: Int, value: Int, voice: Int, bendMode: Boolean) =
                message(tick,0xe0,channel,0,value,bendMode)
            override fun addControlChange(tick: Long, track: Int, channel: Int, controller: Int, value: Int) {
                message(tick,0xb0,channel,controller,value)
                if (route(channel) != route(channel,true)) message(tick,0xb0,channel,controller,value,true)
            }
            override fun addProgramChange(tick: Long, track: Int, channel: Int, instrument: Int) {
                message(tick,0xc0,channel,instrument)
                if (route(channel) != route(channel,true)) message(tick,0xc0,channel,instrument,bend=true)
            }
            override fun addTempoInUSQ(tick: Long, track: Int, usq: Int) {
                if (usq !in 10_000..60_000_000) throw IOException("Invalid Guitar Pro tempo")
                add(tick,tempo=usq)
            }
            override fun addTrackName(tick: Long, track: Int, name: String?) { check() }
            override fun addTimeSignature(tick: Long, track: Int, ts: TGTimeSignature?) { check() }
            override fun notifyFinish() { check() }
        }
        MidiSequenceParser(song,TGSongManager(factory),MidiSequenceParser.DEFAULT_EXPORT_FLAGS).parse(handler)
        var lastTick = 0L
        var frames = 0.0
        var tempo = 500_000
        val messages = ArrayList<MidiMessage>()
        for (event in events.sortedBy { it.tick }) {
            check()
            frames += (event.tick-lastTick).toDouble()*tempo*MidiTimeline.SAMPLE_RATE/TGDuration.QUARTER_TIME/1_000_000.0
            if (!frames.isFinite() || frames > 1800L*MidiTimeline.SAMPLE_RATE) throw IOException("Guitar Pro duration exceeds thirty minutes")
            lastTick = event.tick
            if (event.tempo != 0) tempo = event.tempo
            event.bytes?.let { messages.add(MidiMessage(frames.toLong(),it)) }
        }
        if (messages.none { it.bytes[0].toInt() and 0xf0 == 0x90 && it.bytes.last() != 0.toByte() })
            throw IOException("Guitar Pro score contains no playable notes")
        return Score(MidiTimeline(messages, frames.toLong()+MidiTimeline.SAMPLE_RATE*2L),song.name,song.artist)
    }
}
