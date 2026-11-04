package com.infinifolder.audio

import java.io.IOException
import java.io.InterruptedIOException

/** GME emulates the actual source; looping formats get an explicit finite playback window. */
internal object GameMusicPcmDecoder : PcmDecoder {
    override val name = "libVLC.GME"
    override fun matches(input: AudioInput, probe: AudioProbe) = probe.normalizedExtension in
        setOf("nsf","nsfe","spc","gbs","hes","kss","gym","ay","vgm","vgz","sap","sgc") ||
        (probe.bytes.size >= 4 && String(probe.bytes,0,4,Charsets.US_ASCII) in setOf("NESM","NSFE","GBS\u0001","HESM","KSCC","KSSX","Vgm "))
    private fun readData(input: AudioInput): ByteArray = input.open().use { b ->
            if (b.size !in 4..(32*1024*1024).toLong()) throw IOException("Game music exceeds parser size limit")
            val data = ByteArray(b.size.toInt())
            var at = 0
            while (at < data.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Game music parsing cancelled")
                val count = b.readAt(at.toLong(),data,at,minOf(65536,data.size-at))
                if (count <= 0 || count > data.size-at) throw IOException("Truncated game music source")
                at += count
            }
            // Inflate here rather than depending on a particular GME build's
            // support for compressed memory readers. Bound the expanded input.
            if (data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                java.util.zip.GZIPInputStream(data.inputStream()).use { gzip ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Game music parsing cancelled")
                        val count = gzip.read(buffer)
                        if (count < 0) break
                        if (output.size() > 32*1024*1024-count) throw IOException("Expanded game music exceeds parser size limit")
                        output.write(buffer,0,count)
                    }
                    output.toByteArray()
                }
            } else data
        }
    /** An untimed loop's playback cap is not file metadata. Never publish it as a measured length. */
    fun inspectDuration(input: AudioInput): Long? {
        val data = readData(input)
        com.infinifolder.audio.chiptune.NsfTrackTiming.read(data)?.let { return it.durationMs }
        val handle = GameMusicNative.open(data, true)
        return try { GameMusicNative.metadataDuration(handle).toLong().takeIf { it > 0 } }
        finally { GameMusicNative.close(handle) }
    }
    override fun open(input: AudioInput): PcmStream {
        val data = readData(input)
        val timing = com.infinifolder.audio.chiptune.NsfTrackTiming.read(data)
        return object : PcmStream {
            private var handle = GameMusicNative.open(data, false)
            override val format = PcmFormat(44100,2,(timing?.durationMs ?: GameMusicNative.duration(handle).toLong())*44100/1000)
            private val fadeFrames = (timing?.fadeMs ?: GameMusicNative.fadeDuration(handle))*44100L/1000
            override val decoderName = name
            override val sourceFormat = "game-music"
            private var frame = 0L
            private val buffer = ShortArray(8192)
            @Synchronized override fun read(samples: IntArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && length <= samples.size-offset)
                if (handle == 0L || Thread.currentThread().isInterrupted) throw InterruptedIOException("Game music stream closed or cancelled")
                if (length == 0) return 0
                require(length >= 2)
                val n = minOf(length/2,buffer.size/2,(format.totalFrames!!-frame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())*2
                if (n == 0) return -1
                GameMusicNative.render(handle,buffer,n)
                for (i in 0 until n) {
                    val remaining = format.totalFrames!! - (frame+i/2)
                    val sample = if (fadeFrames > 0 && remaining <= fadeFrames)
                        (buffer[i].toLong()*remaining/fadeFrames).toInt() else buffer[i].toInt()
                    samples[offset+i]=sample shl 16
                }
                frame += n/2
                return n
            }
            @Synchronized override fun close() { if (handle != 0L) { GameMusicNative.close(handle); handle=0L } }
        }
    }
}
internal object GameMusicNative {
    external fun available(): Boolean
    init { LibVlcPcmNative.ensureLoaded() }
    external fun open(bytes: ByteArray, informationOnly: Boolean): Long
    external fun duration(handle: Long): Int
    external fun metadataDuration(handle: Long): Int
    external fun fadeDuration(handle: Long): Int
    external fun render(handle: Long, samples: ShortArray, length: Int)
    external fun close(handle: Long)
}
