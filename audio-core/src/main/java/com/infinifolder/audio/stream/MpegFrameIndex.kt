package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import java.io.IOException
import java.util.TreeMap

/** Exact Layer III packet positions. Header-only scanning is lazy; at most 4096 anchors are retained. */
class MpegFrameIndex(private val input: AudioInput, private val sampleRate: Int) {
    data class Anchor(val frame: Long, val byteOffset: Long, val headerFrames: Long)
    private val anchors = TreeMap<Long, Long>()
    private var stride = 32L
    private var packets = 0L
    private var scannedFrame = 0L
    private var scannedByte = 0L
    private var headerFrames = 0L

    fun before(frame: Long): Anchor = input.open().use { source ->
        require(frame >= 0)
        val r = ByteReader(source)
        if (anchors.isEmpty()) {
            var start = 0L
            if (source.size >= 10 && r.text(0, 3) == "ID3") {
                val tag = r.bytes(0, 10)
                var size = 0L
                for (i in 6..9) {
                    if (tag[i].toInt() and 128 != 0) throw IOException("Invalid ID3 length")
                    size = (size shl 7) or (tag[i].toLong() and 127)
                }
                start = 10 + size + if (tag[5].toInt() and 16 != 0) 10 else 0
            }
            val limit = minOf(source.size - 4, start + 65536)
            while (start <= limit && header(r, start) == null) { checkDecodeCancellation(); start++ }
            val first = header(r, start) ?: throw IOException("No indexed MPEG audio frames")
            headerFrames = first.second.toLong()
            anchors[0] = start; scannedByte = start
        }
        // A small cached header window avoids a filesystem call for every packet.
        var windowStart = -1L
        var window = ByteArray(0)
        fun packet(position: Long): Pair<Int, Int> {
            if (position < windowStart || position + 4 > windowStart + window.size) {
                windowStart = position
                window = r.bytes(position, minOf(16384L, source.size - position).toInt())
            }
            val offset = (position - windowStart).toInt()
            return parse(window, offset) ?: throw IOException("MPEG frame index lost packet alignment")
        }
        while (scannedFrame <= frame) {
            checkDecodeCancellation()
            val h = packet(scannedByte)
            if (h.first > source.size - scannedByte) throw IOException("Truncated indexed MPEG packet")
            if (scannedFrame + h.second > frame) break
            if (scannedByte + h.first > source.size - 4) throw IOException("MPEG seek exceeds encoded frames")
            scannedByte += h.first; scannedFrame += h.second; packets++
            if (packets % stride == 0L) anchors[scannedFrame] = scannedByte
            if (anchors.size > 4096) {
                val keys = anchors.keys.toList()
                for (i in 1 until keys.size step 2) anchors.remove(keys[i])
                stride *= 2
            }
        }
        var position: Long
        var sample: Long
        if (frame >= scannedFrame) { position = scannedByte; sample = scannedFrame }
        else { val entry = anchors.floorEntry(frame); position = entry.value; sample = entry.key }
        while (true) {
            val h = packet(position)
            if (h.first > source.size - position) throw IOException("Truncated indexed MPEG packet")
            if (sample + h.second > frame) break
            checkDecodeCancellation(); sample += h.second; position += h.first
        }
        Anchor(sample, position, headerFrames)
    }

    private fun header(r: ByteReader, position: Long): Pair<Int, Int>? =
        if (position < 0 || position > r.source.size - 4) null else parse(r.bytes(position, 4), 0)

    private fun parse(b: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset < 0 || offset > b.size - 4) return null
        val h = IntArray(4) { b[offset + it].toInt() and 255 }
        val version = h[1] ushr 3 and 3; val code = h[2] ushr 4; val rateCode = h[2] ushr 2 and 3
        if (h[0] != 255 || h[1] and 0xe0 != 0xe0 || h[1] ushr 1 and 3 != 1 || version == 1 || code !in 1..14 || rateCode == 3) return null
        val rate = intArrayOf(44100, 48000, 32000)[rateCode] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        if (rate != sampleRate) throw IOException("MPEG seek changed sample rate")
        val bitrate = if (version == 3) intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320)[code]
            else intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160)[code]
        return ((if (version == 3) 144000 else 72000) * bitrate / rate + (h[2] ushr 1 and 1)) to if (version == 3) 1152 else 576
    }

    fun inputFrom(anchor: Anchor): AudioInput = object : AudioInput {
        override val name = input.name
        override fun open(): AudioBytes {
            val source = input.open()
            if (anchor.byteOffset !in 0..source.size) { source.close(); throw IOException("Invalid MPEG anchor") }
            return object : AudioBytes {
                override val size = source.size - anchor.byteOffset
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    io.github.gretchycat.infinistream.requireRange(position, buffer, offset, length)
                    if (length == 0) return 0
                    if (position >= size) return -1
                    return source.readAt(anchor.byteOffset + position, buffer, offset, minOf(length.toLong(), size - position).toInt())
                }
                override fun close() = source.close()
            }
        }
    }
}
