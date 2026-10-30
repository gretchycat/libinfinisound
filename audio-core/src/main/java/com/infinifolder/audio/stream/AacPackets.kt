package com.infinifolder.audio.stream

import com.infinifolder.audio.AudioBytes
import java.io.EOFException
import java.io.IOException

/** Our bounded ADTS and ISO-BMFF sample-table parsers; AAC synthesis is provided by JAAD. */
internal interface AacPackets : AutoCloseable {
    val configuration: ByteArray
    val skipFrames: Long get() = 0
    val totalFrames: Long? get() = null
    val timeScale: Int get() = 0
    fun next(): ByteArray?
}

internal class ByteReader(val source: AudioBytes) {
    fun bytes(position: Long, size: Int): ByteArray {
        if (position < 0 || size < 0 || position > source.size - size) throw EOFException("Truncated audio structure")
        val out = ByteArray(size)
        var n = 0
        while (n < size) {
            val count = source.readAt(position + n, out, n, size - n)
            if (count <= 0) throw EOFException("Truncated audio structure")
            n += count
        }
        return out
    }
    fun uint(position: Long, count: Int): Long {
        var value = 0L
        for (b in bytes(position, count)) value = (value shl 8) or (b.toLong() and 255)
        if (value < 0) throw IOException("Audio structure exceeds supported range")
        return value
    }
    fun text(position: Long, size: Int) = String(bytes(position, size), Charsets.ISO_8859_1)
    fun close() = source.close()
}

internal class AdtsPackets(private val r: ByteReader) : AacPackets {
    private var position = 0L
    private var pending: ByteArray?
    override var configuration = ByteArray(0); private set
    init {
        if (r.source.size >= 10 && r.text(0, 3) == "ID3") {
            val tag = r.bytes(6, 4)
            var length = 0L
            for (v in tag) { if (v.toInt() and 128 != 0) throw IOException("Invalid ID3 size"); length = (length shl 7) or (v.toLong() and 127) }
            position = 10 + length
        }
        pending = packet() ?: throw IOException("No ADTS frames")
    }
    private fun packet(): ByteArray? {
        if (position == r.source.size) return null
        val h = r.bytes(position, 7).map { it.toInt() and 255 }
        if (h[0] != 255 || h[1] and 0xf6 != 0xf0 || h[6] and 3 != 0) throw IOException("Invalid or unsupported ADTS frame")
        val profile = (h[2] ushr 6) + 1
        val frequency = h[2] ushr 2 and 15
        val channels = ((h[2] and 1) shl 2) or (h[3] ushr 6)
        if (frequency > 12) throw IOException("Reserved ADTS sample rate")
        val config = byteArrayOf(((profile shl 3) or (frequency ushr 1)).toByte(), (((frequency and 1) shl 7) or (channels shl 3)).toByte())
        if (configuration.isEmpty()) configuration = config
        else if (!configuration.contentEquals(config)) throw IOException("ADTS format changes midstream")
        val header = if (h[1] and 1 == 1) 7 else 9
        val size = ((h[3] and 3) shl 11) or (h[4] shl 3) or (h[5] ushr 5)
        if (size <= header) throw IOException("Invalid ADTS frame length")
        val data = r.bytes(position + header, size - header)
        position += size
        return data
    }
    override fun next(): ByteArray? = pending?.also { pending = null } ?: packet()
    override fun close() = r.close()
}

internal class Mp4AacPackets(private val r: ByteReader) : AacPackets {
    private data class Box(val type: String, val data: Long, val end: Long)
    override val configuration: ByteArray
    override val skipFrames: Long
    override val totalFrames: Long?
    override val timeScale: Int
    private val sampleSizes: IntArray
    private val chunks: LongArray
    private val firstChunks: IntArray
    private val perChunk: IntArray
    private var sample = 0
    private var chunk = 0
    private var run = 0
    private var inChunk = 0
    private var offset = 0L
    init {
        val moov = boxes(0, r.source.size).firstOrNull { it.type == "moov" } ?: throw IOException("Missing MP4 moov")
        val tracks = children(moov).filter { it.type == "trak" }
        val track = tracks.firstOrNull { t ->
            val mdia = children(t).firstOrNull { it.type == "mdia" }
            val handler = mdia?.let { children(it).firstOrNull { b -> b.type == "hdlr" } }
            handler != null && r.text(handler.data + 8, 4) == "soun"
        } ?: throw IOException("No MP4 audio track")
        val mdia = child(track, "mdia")
        val mdhd = child(mdia, "mdhd")
        val version = r.uint(mdhd.data, 1).toInt()
        timeScale = r.uint(mdhd.data + if (version == 1) 20 else 12, 4).toInt()
        if (timeScale <= 0) throw IOException("Invalid MP4 audio timescale")
        val stbl = child(child(mdia, "minf"), "stbl")
        val stsd = child(stbl, "stsd")
        val entry = boxes(stsd.data + 8, stsd.end).firstOrNull { it.type == "mp4a" }
            ?: throw IOException("MP4 audio is not unencrypted AAC")
        val entryStart = entry.data - 8
        val entryVersion = r.uint(entryStart + 16, 2).toInt()
        val extra = when (entryVersion) { 0 -> 0; 1 -> 16; 2 -> 36; else -> throw IOException("Unsupported MP4 audio sample entry") }
        val entryChildren = boxes(entryStart + 36 + extra, entry.end)
        val esds = entryChildren.firstOrNull { it.type == "esds" }
            ?: entryChildren.firstOrNull { it.type == "wave" }?.let { children(it).firstOrNull { b -> b.type == "esds" } }
            ?: throw IOException("Missing MP4 AAC decoder configuration")
        configuration = descriptorConfig(r.bytes(esds.data + 4, bounded(esds.end - esds.data - 4, 65536)))
            ?: throw IOException("Missing AAC AudioSpecificConfig")
        val stsz = child(stbl, "stsz")
        val fixed = r.uint(stsz.data + 4, 4)
        val count = bounded(r.uint(stsz.data + 8, 4), 20_000_000)
        if (count == 0) throw IOException("Empty MP4 audio sample table")
        if (fixed == 0L && stsz.data + 12 + count * 4L > stsz.end) throw EOFException("Truncated MP4 sample-size table")
        sampleSizes = IntArray(count) { i -> bounded(if (fixed != 0L) fixed else r.uint(stsz.data + 12 + i * 4L, 4), 16 * 1024 * 1024) }
        val offsets = children(stbl).firstOrNull { it.type == "stco" || it.type == "co64" } ?: throw IOException("Missing MP4 chunk offsets")
        val width = if (offsets.type == "co64") 8 else 4
        val chunkCount = bounded(r.uint(offsets.data + 4, 4), count)
        if (chunkCount == 0 || offsets.data + 8 + chunkCount.toLong() * width > offsets.end) throw IOException("Invalid MP4 chunk-offset table")
        chunks = LongArray(chunkCount) { r.uint(offsets.data + 8 + it.toLong() * width, width) }
        val stsc = child(stbl, "stsc")
        val runCount = bounded(r.uint(stsc.data + 4, 4), chunkCount)
        if (runCount == 0 || stsc.data + 8 + runCount * 12L > stsc.end) throw IOException("Invalid MP4 sample-to-chunk table")
        firstChunks = IntArray(runCount)
        perChunk = IntArray(runCount)
        for (i in 0 until runCount) {
            val pos = stsc.data + 8 + i * 12L
            firstChunks[i] = bounded(r.uint(pos, 4), chunkCount) - 1
            perChunk[i] = bounded(r.uint(pos + 4, 4), count)
            if (firstChunks[i] < 0 || perChunk[i] == 0 || (i == 0 && firstChunks[i] != 0) ||
                (i > 0 && firstChunks[i] <= firstChunks[i - 1]) || r.uint(pos + 8, 4) != 1L)
                throw IOException("Invalid MP4 sample-to-chunk run")
        }
        val stts = child(stbl, "stts")
        val durationRuns = bounded(r.uint(stts.data + 4, 4), count)
        if (stts.data + 8 + durationRuns * 8L > stts.end) throw IOException("Truncated MP4 timing table")
        var ticks = 0L; var timedSamples = 0L
        repeat(durationRuns) {
            val pos = stts.data + 8 + it * 8L
            val n = r.uint(pos, 4); val duration = r.uint(pos + 4, 4)
            if (n > count || duration > 0xffffffffL || ticks > Long.MAX_VALUE - n * duration) throw IOException("MP4 duration overflow")
            ticks += n * duration; timedSamples += n
        }
        if (timedSamples != count.toLong()) throw IOException("Inconsistent MP4 timing/sample table")
        var skip = 0L
        var target: Long? = ticks
        val edits = children(track).firstOrNull { it.type == "edts" }?.let { children(it).firstOrNull { b -> b.type == "elst" } }
        if (edits != null) {
            val editVersion = r.uint(edits.data, 1).toInt()
            val editCount = r.uint(edits.data + 4, 4)
            if (editCount == 1L) {
                val pos = edits.data + 8
                val duration = r.uint(pos, if (editVersion == 1) 8 else 4)
                val mediaPos = pos + if (editVersion == 1) 8 else 4
                val mediaTime = r.uint(mediaPos, if (editVersion == 1) 8 else 4)
                val mvhd = child(moov, "mvhd")
                val movieScale = r.uint(mvhd.data + if (r.uint(mvhd.data, 1) == 1L) 20 else 12, 4)
                if (movieScale <= 0) throw IOException("Invalid MP4 movie timescale")
                val rateOffset = mediaPos + if (editVersion == 1) 8 else 4
                if (r.uint(rateOffset, 4) != 0x10000L) throw IOException("Unsupported MP4 edit playback rate")
                skip = mediaTime
                target = minOf((ticks - skip).coerceAtLeast(0), duration * timeScale / movieScale)
            } else throw IOException("Complex MP4 edit list requires another decoder")
        }
        skipFrames = skip
        totalFrames = target
        offset = chunks[0]
    }
    override fun next(): ByteArray? {
        if (sample == sampleSizes.size) return null
        if (chunk >= chunks.size) throw IOException("MP4 chunk table is shorter than sample table")
        val data = r.bytes(offset, sampleSizes[sample++])
        offset += data.size
        inChunk++
        if (inChunk == perChunk[run]) {
            chunk++; inChunk = 0
            if (chunk < chunks.size) {
                if (run + 1 < firstChunks.size && chunk >= firstChunks[run + 1]) run++
                offset = chunks[chunk]
            }
        }
        return data
    }
    private fun child(box: Box, type: String) = children(box).firstOrNull { it.type == type } ?: throw IOException("Missing MP4 $type")
    private fun children(box: Box) = boxes(box.data, box.end)
    private fun boxes(start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (pos < end) {
            if (end - pos < 8) throw IOException("Truncated MP4 box")
            var size = r.uint(pos, 4)
            val type = r.text(pos + 4, 4)
            var header = 8L
            if (size == 1L) { size = r.uint(pos + 8, 8); header = 16 }
            else if (size == 0L) size = end - pos
            if (size < header || size > end - pos) throw IOException("Invalid MP4 box size")
            if (out.size >= 1_000_000) throw IOException("MP4 box count exceeds decoder limit")
            out += Box(type, pos + header, pos + size)
            pos += size
        }
        return out
    }
    private fun bounded(n: Long, limit: Int): Int {
        if (n !in 0..limit.toLong()) throw IOException("MP4 table exceeds decoder limit")
        return n.toInt()
    }
    private fun descriptorConfig(bytes: ByteArray): ByteArray? {
        fun find(start: Int, end: Int): ByteArray? {
            var pos = start
            while (pos < end) {
                val tag = bytes[pos++].toInt() and 255
                var size = 0
                var done = false
                repeat(4) {
                    if (!done) {
                        if (pos >= end) throw IOException("Truncated MP4 descriptor")
                        val n = bytes[pos++].toInt() and 255
                        size = (size shl 7) or (n and 127)
                        done = n and 128 == 0
                    }
                }
                if (!done || size > end - pos) throw IOException("Invalid MP4 descriptor size")
                if (tag == 5) return bytes.copyOfRange(pos, pos + size)
                var nested = pos
                if (tag == 3) {
                    if (size < 3) throw IOException("Invalid ES descriptor")
                    val flags = bytes[pos + 2].toInt() and 255
                    nested += 3
                    if (flags and 128 != 0) nested += 2
                    if (flags and 64 != 0) {
                        if (nested >= pos + size) throw IOException("Truncated ES URL")
                        nested += 1 + (bytes[nested].toInt() and 255)
                    }
                    if (flags and 32 != 0) nested += 2
                } else if (tag == 4) {
                    if (size < 13 || bytes[pos].toInt() and 255 !in setOf(0x40, 0x66, 0x67, 0x68)) throw IOException("MP4 sample entry is not AAC")
                    nested += 13
                }
                if ((tag == 3 || tag == 4) && nested < pos + size) find(nested, pos + size)?.let { return it }
                pos += size
            }
            return null
        }
        return find(0, bytes.size)
    }
    override fun close() = r.close()
}
