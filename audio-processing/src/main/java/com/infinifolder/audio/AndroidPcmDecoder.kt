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
import com.infinifolder.audio.stream.MpegFrameIndex
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared demuxer query used by playback and bounded file identity probing. */
internal fun MediaExtractor.firstAudioTrack(): Pair<Int, MediaFormat>? {
    for (index in 0 until trackCount) {
        val format = getTrackFormat(index)
        if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return index to format
    }
    return null
}

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
            val (track, format) = extractor.firstAudioTrack() ?: throw IOException("Android found no audio track")
            extractor.selectTrack(track)
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
            val flac = probe.size >= 26 && String(probe, 0, 4, Charsets.US_ASCII) == "fLaC"
            val flacDepth = if (flac)
                (((probe[20].toInt() and 1) shl 4) or ((probe[21].toInt() and 255) ushr 4)) + 1 else 16
            // Changing this MediaFormat cannot change PCM already produced by an extractor.
            // Request wider output only from a codec we configure; otherwise check its real precision.
            if (flacDepth > 16 && mime != "audio/raw") format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_32BIT)
            if (mime != "audio/raw") {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
            }
            val stream = AndroidStream(extractor, codec, dataSource, format, mime)
            if (stream.precisionBits < flacDepth) throw IOException("Android codec reduced FLAC sample precision")
            // Canonical FLAC output width follows source precision, even when a codec
            // supplies a wider integer container. This keeps late fallback format-stable.
            val pcm: PcmStream = if (flac) object : PcmStream {
                override val format = stream.format.copy(bitsPerSample = if (flacDepth <= 16) 16 else if (flacDepth <= 24) 24 else 32)
                override val decoderName get() = stream.decoderName
                override val sourceFormat get() = stream.sourceFormat
                override fun read(samples: IntArray, offset: Int, length: Int) = stream.read(samples, offset, length)
                override fun close() = stream.close()
            } else stream
            val timing = AudioStreamTiming.read(input,stream.format.sampleRate)
            val leading = if (hasDelay) delay.toLong() else timing?.leadingFrames ?: 0L
            val limit = timing?.totalFrames
            if (limit != null) return object : FrameSeekablePcmStream {
                private val mpeg = if (mime == "audio/mpeg") MpegFrameIndex(input, stream.format.sampleRate) else null
                private var output: PcmStream = TrimmedPcmStream(pcm, leading, padding, limit)
                override val format = output.format
                override val decoderName get() = stream.decoderName
                override val sourceFormat get() = stream.sourceFormat
                override fun read(samples: IntArray, offset: Int, length: Int) = output.read(samples, offset, length)
                override fun seekToFrame(frame: Long) {
                    require(frame in 0..limit)
                    if (mpeg == null) stream.seekToFrame(frame + leading)
                    else {
                        val anchor = mpeg.before((frame + leading - stream.format.sampleRate).coerceAtLeast(0L))
                        if (anchor.frame == 0L) stream.restartMpeg(input, frame + leading)
                        else stream.restartMpeg(mpeg.inputFrom(anchor), frame + leading + anchor.headerFrames - anchor.frame)
                    }
                    output = TrimmedPcmStream(pcm, paddingFrames = padding, totalFrames = limit - frame)
                }
                override fun close() = stream.close()
            }
            return if (leading != 0L || padding != 0 || limit != null)
                TrimmedPcmStream(pcm,leading,padding,limit) else pcm
        } catch (error: Throwable) {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }; runCatching { dataSource?.close() }
            throw error
        }
    }

    private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default

    private class AndroidStream(
        private var extractor: MediaExtractor,
        private val codec: MediaCodec?,
        private var source: MediaDataSource?,
        input: MediaFormat,
        override val sourceFormat: String
    ) : BlockPcmStream() {
        private var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private var encoding = input.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        private var inputEnded = false
        private var outputEnded = false
        private val info = MediaCodec.BufferInfo()
        private val raw = if (codec == null) ByteBuffer.allocateDirect(maxOf(262144, input.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0)).coerceAtMost(16 * 1024 * 1024)) else null
        private var initialized = false
        private var firstTimestampUs: Long? = null
        private var blockTimestampUs = 0L
        private var seekFrame: Long? = null
        private var pending: IntArray? = decodeBlock() ?: throw IOException("Android decoded no audio")
        override val format = PcmFormat(rate, channels, bitsPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 32
            else -> 16
        })
        val precisionBits: Int get() = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 24 else format.bitsPerSample
        override val decoderName = AndroidPcmDecoder.name
        override fun nextBlock(): IntArray? {
            while (true) {
                val block = pending?.also { pending = null } ?: decodeBlock() ?: return null
                val target = seekFrame ?: return block
                val start = Math.round((blockTimestampUs - firstTimestampUs!!) * rate / 1_000_000.0)
                if (start > target) throw IOException("Android seek skipped the requested PCM frame")
                val skip = target - start
                if (skip >= block.size / channels) continue
                seekFrame = null
                return block.copyOfRange(skip.toInt() * channels, block.size)
            }
        }
        fun seekToFrame(frame: Long) {
            require(frame >= 0)
            clearDecodedBlock(); pending = null
            // Preroll restores reservoir/filterbank/overlap state before requested output.
            val preroll = (frame - rate.toLong()).coerceAtLeast(0)
            val timeUs = firstTimestampUs!! + preroll * 1_000_000L / rate
            extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec?.flush()
            inputEnded = false; outputEnded = false; seekFrame = frame
        }
        fun restartMpeg(input: AudioInput, skipFrames: Long) {
            val bytes = input.open()
            val replacement = try { MediaExtractor() } catch (error: Throwable) { bytes.close(); throw error }
            val adapter = object : MediaDataSource() {
                override fun getSize() = bytes.size
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = bytes.readAt(position, buffer, offset, size)
                override fun close() = bytes.close()
            }
            try {
                replacement.setDataSource(adapter)
                val track = (0 until replacement.trackCount).firstOrNull {
                    replacement.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mpeg"
                } ?: throw IOException("No MPEG track at indexed anchor")
                replacement.selectTrack(track)
                extractor.release(); source?.close()
                extractor = replacement; source = adapter
                codec?.flush()
                clearDecodedBlock(); pending = null
                inputEnded = false; outputEnded = false
                firstTimestampUs = null; seekFrame = skipFrames
            } catch (error: Throwable) { replacement.release(); adapter.close(); throw error }
        }
        private fun recordTimestamp(timeUs: Long) {
            blockTimestampUs = timeUs
            if (firstTimestampUs == null) firstTimestampUs = timeUs
        }
        private fun decodeBlock(): IntArray? {
            if (outputEnded) return null
            val decoder = codec
            if (decoder == null) {
                val buffer = raw!!
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) { outputEnded = true; return null }
                recordTimestamp(extractor.sampleTime)
                buffer.position(0); buffer.limit(count)
                val result = convert(buffer.order(ByteOrder.LITTLE_ENDIAN))
                extractor.advance()
                return result
            }
            var progressAt = System.nanoTime()
            while (!outputEnded) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Android audio decode cancelled")
                if (System.nanoTime() - progressAt > 15_000_000_000L) throw IOException("Android audio decoder stalled")
                var queued = false
                // Feed available buffers without blocking behind output waiting to be drained.
                // Waiting for input while output is ready stalls bulk PCM reads after a seek.
                for (attempt in 0 until 8) {
                    if (inputEnded) break
                    val index = decoder.dequeueInputBuffer(0)
                    if (index < 0) break
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
                    queued = true
                }
                val output = decoder.dequeueOutputBuffer(info, if (queued) 0 else 10000)
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
                            recordTimestamp(info.presentationTimeUs)
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
            val count = buffer.remaining() / width
            // Bulk view reads avoid a JNI-backed DirectByteBuffer access for every sample.
            if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                val values = ShortArray(count); buffer.asShortBuffer().get(values)
                return IntArray(count) { values[it].toInt() shl 16 }
            }
            if (encoding == AudioFormat.ENCODING_PCM_32BIT) return IntArray(count).also { buffer.asIntBuffer().get(it) }
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val values = FloatArray(count); buffer.asFloatBuffer().get(values)
                return IntArray(count) { (values[it].toDouble().coerceIn(-1.0, 1.0) * 2147483648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() }
            }
            val values = ByteArray(buffer.remaining()); buffer.get(values)
            return IntArray(count) { i ->
                when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((values[i].toInt() and 255) - 128) shl 24
                    else -> {
                        val value = (values[i * 3].toInt() and 255) or ((values[i * 3 + 1].toInt() and 255) shl 8) or (values[i * 3 + 2].toInt() shl 16)
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
