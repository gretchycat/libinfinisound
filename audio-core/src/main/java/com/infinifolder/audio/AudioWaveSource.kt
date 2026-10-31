package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** PCM-only WAVE transport for output adapters. The host owns and removes the scratch file. */
class AudioWaveSource internal constructor(@Volatile private var source: ByteSource,
    preparation: AudioPreparation, progressive: Boolean,
    private val origin: StreamMetadata = StreamMetadata(),
    private val onClose: () -> Unit = {},
    private var recover: ((IOException) -> Pair<ByteSource, AudioPreparation>)? = null
) : ByteSource, PositionalReadable {
    @Volatile var preparation = preparation
        private set
    @Volatile var progressive = progressive
        private set
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var cursor = 0L
    override val metadata get() = if (progressive) source.metadata else pcmWaveMetadata(origin, preparation.format, false)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        requireRange(position, buffer, offset, length)
        if (closed.get()) throw IOException("Audio WAVE source is closed")
        try { (source as PositionalReadable).readAt(position, buffer, offset, length) }
        catch (cancel: InterruptedIOException) { throw cancel }
        catch (error: IOException) {
            if (closed.get()) throw error
            val retry = recover ?: throw error
            recover = null
            source.close()
            val (replacement, result) = retry(error)
            if (result.format != preparation.format) {
                replacement.close()
                throw IOException("Fallback decoder changed the negotiated PCM format", error)
            }
            source = replacement; preparation = result; progressive = false
            if (closed.get()) { replacement.close(); throw IOException("Audio WAVE source is closed") }
            (replacement as PositionalReadable).readAt(position, buffer, offset, length)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        readAt(cursor, buffer, offset, length).also { if (it > 0) cursor += it }
    }

    /** Reopenable borrowed views, for simultaneous output and analysis without another codec/cache. */
    fun borrowedResource(): SourceResource = object : SourceResource {
        override val metadata get() = this@AudioWaveSource.metadata.copy(ownership = Ownership.BORROWED)
        override fun open(): ByteSource = object : ByteSource, PositionalReadable {
            override val metadata get() = this@AudioWaveSource.metadata.copy(ownership = Ownership.BORROWED)
            private var position = 0L
            private var viewClosed = false
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (viewClosed) throw IOException("Borrowed PCM view is closed")
                return this@AudioWaveSource.readAt(position, buffer, offset, length)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                readAt(position, buffer, offset, length).also { if (it > 0) position += it }
            override fun close() { viewClosed = true }
        }
    }


    override fun close() {
        // PcmWaveSource sees cancellation immediately, before we wait for its current read.
        if (!closed.compareAndSet(false, true)) return
        onClose()
        val closing = source
        try { closing.close() } finally {
            synchronized(lock) { recover = null; if (source !== closing) source.close() }
        }
    }
}

internal fun pcmWaveMetadata(input: StreamMetadata, format: PcmFormat, progressive: Boolean): StreamMetadata = input.copy(
    content = ContentType(DataKind.ENCODED_AUDIO, "audio/wav", "wave-pcm", "signed-int${format.bitsPerSample}", "little"),
    name = "decoded.wav", length = 44 + format.totalFrames!! * format.channels * (format.bitsPerSample / 8),
    finiteness = Finiteness.FINITE,
    capabilities = setOf(Capability.READ, Capability.POSITIONAL_READ, Capability.KNOWN_LENGTH),
    ownership = Ownership.OWNED, concurrency = Concurrency.SERIAL,
    attributes = input.attributes + mapOf("sampleRate" to format.sampleRate.toString(), "channels" to format.channels.toString(),
        "bitsPerSample" to format.bitsPerSample.toString(), "totalFrames" to format.totalFrames.toString(),
        "durationMs" to format.durationMs.toString(), "access" to if (progressive) "decoded-prefix-disk-cache" else "prepared-file",
        "forwardSeek" to if (progressive) "decode-intervening-frames" else "cached-file"),
    provenance = input.provenance.copy(transformations = input.provenance.transformations + listOf("audio.decode", "audio.pcm-wave")),
    verification = Verification.PROVISIONAL)
