package com.infinifolder.audio.stream

import com.infinifolder.audio.*
import io.github.gretchycat.infinistream.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit host-owned disk cache of decoded PCM. Seek-capable decoders fill independent
 * frame blocks; other decoders retain prefix decoding. Cached blocks are never decoded again.
 * No sparse/undecoded bytes are exposed as silence. Close releases the codec/cache handle;
 * the host retains ownership of the scratch file and the encoded resource's lease.
 */
class PcmWaveSource internal constructor(
    private val pcm: PcmStream,
    scratch: File,
    private val cancelled: () -> Boolean,
    inputMetadata: StreamMetadata,
    private val completed: (AudioPreparation) -> Unit
) : ByteSource, PositionalReadable {
    val format = pcm.format
    private val width = format.bitsPerSample / 8
    private val frames = format.totalFrames ?: throw IOException("An exact PCM length is required")
    private val dataLength: Long
    private val description: StreamMetadata
    @Volatile private var cachedBytes = 44L
    override val metadata get() = description.copy(attributes = description.attributes + mapOf(
        "cachedPcmBytes" to cachedBytes.toString(), "decoderFrameSeeks" to frameSeeks.toString()))
    @Volatile private var frameSeeks = 0L
    private val seeker = pcm as? FrameSeekablePcmStream
    private val blocks = java.util.BitSet()
    private var cachedBlocks = 0L
    private var decoderFrame = 0L
    private val blockFrames = 4096L
    private val file: RandomAccessFile
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val samples = IntArray(4096 * format.channels)
    private val bytes = ByteArray(samples.size * width)
    private val metrics = PcmSignalAccumulator()
    private var available = 44L
    private var cursor = 0L
    private var finished = false
    private var pcmClosed = false
    private var failure: IOException? = null

    init {
        if (frames <= 0 || frames > (0xffffffffL - 36) / (format.channels * width))
            throw IOException("PCM exceeds RIFF WAVE size limit or is empty")
        dataLength = frames * format.channels * width
        description = pcmWaveMetadata(inputMetadata, format, true).let { base ->
            if (seeker == null) base else base.copy(attributes = base.attributes + mapOf(
                "access" to "decoded-block-disk-cache", "forwardSeek" to "decoder-frame-seek",
                "cacheBlockFrames" to blockFrames.toString()))
        }
        file = RandomAccessFile(scratch, "rw")
        try {
            file.setLength(0)
            fun le16(n: Int) { repeat(2) { file.write(n ushr (it * 8) and 255) } }
            fun le32(n: Long) { repeat(4) { file.write((n ushr (it * 8)).toInt() and 255) } }
            file.writeBytes("RIFF"); le32(36 + dataLength); file.writeBytes("WAVEfmt ")
            le32(16); le16(1); le16(format.channels); le32(format.sampleRate.toLong())
            le32(format.sampleRate.toLong() * format.channels * width); le16(format.channels * width); le16(format.bitsPerSample)
            file.writeBytes("data"); le32(dataLength)
            // Validate the first decoded block before publishing a lazy source to its consumer.
            if (seeker == null) decodeThrough(minOf(44 + dataLength, 44 + bytes.size.toLong())) else decodeBlock(0)
        } catch (error: Throwable) { file.close(); throw error }
    }

    private fun checkOpen() {
        if (closed.get() || cancelled() || Thread.currentThread().isInterrupted)
            throw java.io.InterruptedIOException("PCM source closed or cancelled")
        failure?.let { throw it }
    }

    private fun decodeThrough(end: Long) {
        while (available < end) {
            checkOpen()
            val count = pcm.read(samples, 0, samples.size)
            checkOpen()
            if (count <= 0 || count > samples.size || count % format.channels != 0)
                throw IOException("Incomplete or invalid PCM read: $count")
            if (count.toLong() * width > 44 + dataLength - available)
                throw IOException("Decoder exceeded its exact PCM length")
            for (i in 0 until count) {
                val value = samples[i] ushr (32 - format.bitsPerSample)
                repeat(width) { bytes[i * width + it] = (value ushr (it * 8)).toByte() }
            }
            file.seek(available); file.write(bytes, 0, count * width)
            available += count * width
            cachedBytes = available
            metrics.add(samples, count)
        }
        if (!finished && available == 44 + dataLength) {
            if (pcm.read(samples, 0, samples.size) != -1) throw IOException("Decoder exceeded its exact PCM length")
            checkOpen()
            finished = true
            closePcm()
            completed(AudioPreparation(format, pcm.decoderName ?: "PCM", pcm.sourceFormat, metrics.finish(format)))
        }
    }

    private fun decodeBlock(index: Int) {
        if (blocks[index]) return
        checkOpen()
        val frame = index * blockFrames
        if (decoderFrame != frame) { frameSeeks++; seeker!!.seekToFrame(frame) }
        val wanted = minOf(blockFrames, frames - frame).toInt() * format.channels
        var count = 0
        while (count < wanted) {
            checkOpen()
            val n = pcm.read(samples, count, wanted - count)
            if (n <= 0 || n > wanted - count || n % format.channels != 0)
                throw IOException("Incomplete or invalid positioned PCM read: $n")
            count += n
        }
        checkOpen()
        decoderFrame = frame + wanted / format.channels
        if (decoderFrame == frames && pcm.read(IntArray(format.channels), 0, format.channels) != -1)
            throw IOException("Decoder exceeded its exact PCM length")
        for (i in 0 until count) {
            val value = samples[i] ushr (32 - format.bitsPerSample)
            repeat(width) { bytes[i * width + it] = (value ushr (it * 8)).toByte() }
        }
        file.seek(44 + frame * format.channels * width); file.write(bytes, 0, count * width)
        metrics.add(samples, count)
        blocks.set(index); cachedBytes += count * width; cachedBlocks++
        if (cachedBlocks == (frames + blockFrames - 1) / blockFrames) {
            finished = true; closePcm()
            completed(AudioPreparation(format, pcm.decoderName ?: "PCM", pcm.sourceFormat, metrics.finish(format)))
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        requireRange(position, buffer, offset, length); checkOpen()
        if (length == 0) return@synchronized 0
        if (position >= 44 + dataLength) return@synchronized -1
        // Short reads bound work per request; indexed reads fill only covered frame blocks.
        val count = minOf(length.toLong(), 64 * 1024L, 44 + dataLength - position).toInt()
        try {
            if (seeker == null) decodeThrough(position + count)
            else if (position + count > 44) {
                val blockBytes = blockFrames * format.channels * width
                val first = ((position - 44).coerceAtLeast(0) / blockBytes).toInt()
                val last = ((position + count - 45) / blockBytes).toInt()
                for (index in first..last) decodeBlock(index)
            }
            checkOpen(); file.seek(position); file.readFully(buffer, offset, count)
            count
        } catch (error: Exception) {
            val problem = error as? IOException ?: IOException("PCM backend read failed", error)
            failure = problem
            runCatching { closePcm() }
            throw problem
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        readAt(cursor, buffer, offset, length).also { if (it > 0) cursor += it }
    }

    /** Signal cancellation before waiting for an in-flight decoder read to finish. */
    private fun closePcm() { if (!pcmClosed) { pcmClosed = true; pcm.close() } }

    override fun close() {
        if (closed.compareAndSet(false, true)) synchronized(lock) {
            try { closePcm() } finally { file.close() }
        }
    }
}
