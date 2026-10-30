package com.infinifolder.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.infinifolder.audio.stream.BlockPcmStream
import com.infinifolder.audio.stream.FileAudioInput
import com.infinifolder.audio.stream.TrimmedPcmStream
import com.infinifolder.audio.stream.AudioStreamTiming
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android's demuxer and codec are one registered decoding backend, never a player fallback. */
internal object AndroidPcmDecoder : PcmDecoder {
    override val name = "Android.MediaCodec"
    override fun matches(input: AudioInput, probe: AudioProbe) = true
    override fun open(input: AudioInput): PcmStream {
        val extractor = MediaExtractor()
        var dataSource: MediaDataSource? = null
        var codec: MediaCodec? = null
        try {
            if (input is FileAudioInput) extractor.setDataSource(input.file.absolutePath)
            else {
                val bytes = input.open()
                val adapter = object : MediaDataSource() {
                    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                    override fun getSize() = bytes.size
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = bytes.readAt(position, buffer, offset, size)
                    override fun close() { if (closed.compareAndSet(false, true)) bytes.close() }
                }
                dataSource = adapter
                extractor.setDataSource(adapter)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Android found no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing Android audio MIME")
            val hasDelay = format.containsKey(MediaFormat.KEY_ENCODER_DELAY)
            val delay = format.intOr(MediaFormat.KEY_ENCODER_DELAY, 0)
            val padding = format.intOr(MediaFormat.KEY_ENCODER_PADDING, 0)
            if (delay < 0 || padding < 0 || delay > 1_048_576 || padding > 1_048_576) throw IOException("Invalid encoder trim metadata")
            // This adapter applies trim once, to PCM, irrespective of which Android codec is selected.
            format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
            format.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
            val probe = input.open().use { source ->
                val bytes = ByteArray(minOf(source.size, 42L).toInt())
                var n = 0
                while (n < bytes.size) {
                    val count = source.readAt(n.toLong(), bytes, n, bytes.size - n)
                    if (count <= 0) break
                    n += count
                }
                bytes
            }
            val flacDepth = if (probe.size >= 26 && String(probe, 0, 4, Charsets.US_ASCII) == "fLaC")
                (((probe[20].toInt() and 1) shl 4) or ((probe[21].toInt() and 255) ushr 4)) + 1 else 16
            if (flacDepth > 16) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_32BIT)
            if (mime != "audio/raw") {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
            }
            val stream = AndroidStream(extractor, codec, dataSource, format)
            if (stream.precisionBits < flacDepth) throw IOException("Android codec reduced FLAC sample precision")
            val timing = AudioStreamTiming.read(input,stream.format.sampleRate)
            val leading = if (hasDelay) delay.toLong() else timing?.leadingFrames ?: 0L
            val limit = timing?.totalFrames
            return if (leading != 0L || padding != 0 || limit != null)
                TrimmedPcmStream(stream,leading,padding,limit) else stream
        } catch (error: Throwable) {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }; runCatching { dataSource?.close() }
            throw error
        }
    }

    private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default

    private class AndroidStream(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec?,
        private val source: MediaDataSource?,
        input: MediaFormat
    ) : BlockPcmStream() {
        private var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private var encoding = input.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        private var inputEnded = false
        private var outputEnded = false
        private val info = MediaCodec.BufferInfo()
        private val raw = if (codec == null) ByteBuffer.allocateDirect(maxOf(262144, input.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0)).coerceAtMost(16 * 1024 * 1024)) else null
        private var initialized = false
        private var pending: IntArray? = decodeBlock() ?: throw IOException("Android decoded no audio")
        override val format = PcmFormat(rate, channels, bitsPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 32
            else -> 16
        })
        val precisionBits: Int get() = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 24 else format.bitsPerSample
        override val decoderName = AndroidPcmDecoder.name
        override fun nextBlock(): IntArray? {
            val first = pending
            if (first != null) { pending = null; return first }
            return decodeBlock()
        }
        private fun decodeBlock(): IntArray? {
            if (outputEnded) return null
            val decoder = codec
            if (decoder == null) {
                val buffer = raw!!
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) { outputEnded = true; return null }
                buffer.position(0); buffer.limit(count)
                val result = convert(buffer.order(ByteOrder.LITTLE_ENDIAN))
                extractor.advance()
                return result
            }
            var progressAt = System.nanoTime()
            while (!outputEnded) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Android audio decode cancelled")
                if (System.nanoTime() - progressAt > 15_000_000_000L) throw IOException("Android audio decoder stalled")
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("Missing Android codec input buffer")
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressAt = System.nanoTime()
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    val newRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                    val newChannels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    if (initialized && (newRate != rate || newChannels != channels))
                        throw IOException("Android PCM format changed midstream")
                    rate = newRate; channels = newChannels
                    encoding = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    progressAt = System.nanoTime()
                } else if (output >= 0) {
                    try {
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = decoder.getOutputBuffer(output) ?: throw IOException("Missing Android codec output buffer")
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            initialized = true
                            return convert(buffer.slice().order(ByteOrder.LITTLE_ENDIAN))
                        }
                    } finally { decoder.releaseOutputBuffer(output, false) }
                    progressAt = System.nanoTime()
                }
            }
            return null
        }
        private fun convert(buffer: ByteBuffer): IntArray {
            val width = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                else -> throw IOException("Unsupported Android PCM encoding: $encoding")
            }
            if (channels !in 1..8 || buffer.remaining() % (width * channels) != 0) throw IOException("Incomplete Android PCM frame")
            return IntArray(buffer.remaining() / width) {
                when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) shl 24
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short.toInt() shl 16
                    AudioFormat.ENCODING_PCM_FLOAT -> (buffer.float.toDouble().coerceIn(-1.0, 1.0) * 2147483648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int
                    else -> {
                        val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                        value shl 8
                    }
                }
            }
        }
        override fun close() {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }; runCatching { source?.close() }
        }
    }
}
