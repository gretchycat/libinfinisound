package com.infinifolder.audio

import java.io.IOException
import java.io.InterruptedIOException

/** GME emulates the actual source; looping formats get an explicit finite playback window. */
internal object GameMusicPcmDecoder : PcmDecoder {
    override val name = "libVLC.GME"
    override fun matches(input: AudioInput, probe: AudioProbe) = probe.normalizedExtension in
        setOf("nsf","nsfe","spc","gbs","hes","kss","gym","ay","vgm","vgz","sap","sgc") ||
        (probe.bytes.size >= 4 && String(probe.bytes,0,4,Charsets.US_ASCII) in setOf("NESM","NSFE","GBS\u0001","HESM","KSCC","KSSX","Vgm "))
    override fun open(input: AudioInput): PcmStream {
        val data = input.open().use { b ->
            if (b.size !in 4..(32*1024*1024).toLong()) throw IOException("Game music exceeds parser size limit")
            val data = ByteArray(b.size.toInt())
            var at = 0
            while (at < data.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Game music parsing cancelled")
                val count = b.readAt(at.toLong(),data,at,minOf(65536,data.size-at))
                if (count <= 0 || count > data.size-at) throw IOException("Truncated game music source")
                at += count
            }
            data
        }
        return object : PcmStream {
            private var handle = GameMusicNative.open(data)
            override val format = PcmFormat(44100,2,GameMusicNative.duration(handle)*44100L/1000)
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
                for (i in 0 until n) samples[offset+i]=buffer[i].toInt() shl 16
                frame += n/2
                return n
            }
            @Synchronized override fun close() { if (handle != 0L) { GameMusicNative.close(handle); handle=0L } }
        }
    }
}
internal object GameMusicNative {
    init { LibVlcPcmNative.ensureLoaded() }
    external fun open(bytes: ByteArray): Long
    external fun duration(handle: Long): Int
    external fun render(handle: Long, samples: ShortArray, length: Int)
    external fun close(handle: Long)
}
