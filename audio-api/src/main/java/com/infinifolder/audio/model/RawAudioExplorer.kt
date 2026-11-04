package com.infinifolder.audio.model

/** Raw PCM interpretation shared by byte explorers; conversion uses the existing PCM model. */
object RawAudioExplorer {
    enum class Encoding(val title: String, val bytes: Int) {
        U8("Unsigned 8-bit", 1), S8("Signed 8-bit", 1), S16("Signed 16-bit", 2),
        S24("Signed 24-bit", 3), S32("Signed 32-bit", 4), F32("Float 32-bit", 4)
    }
    data class Config(val offset: Long = 0, val sampleRate: Int = 22050, val channels: Int = 1,
        val encoding: Encoding = Encoding.U8, val littleEndian: Boolean = true, val frames: Int = 16384,
        val byteLength: Long? = null) {
        val frameBytes get() = encoding.bytes * channels
        fun validate() {
            require(offset >= 0 && sampleRate in 1000..384000 && channels in 1..2 && frames in 1..262144 &&
                (byteLength == null || byteLength in 0..Long.MAX_VALUE-offset)) { "Invalid offset, rate, channels or frame window" }
        }
    }
    data class Envelope(val minimum: List<FloatArray>, val maximum: List<FloatArray>, val frames: Int)
    fun decode(bytes: ByteArray, config: Config): AudioPcmData {
        config.validate()
        require(bytes.size % config.frameBytes == 0) { "PCM data ends in a partial frame" }
        return when (config.encoding) {
            Encoding.U8 -> AudioPcmData.from8BitUnsigned(bytes, config.sampleRate, config.channels)
            Encoding.S8 -> AudioPcmData.from8BitSigned(bytes, config.sampleRate, config.channels)
            Encoding.S16 -> if (config.littleEndian) AudioPcmData.from16BitLittleEndian(bytes, config.sampleRate, config.channels)
                else AudioPcmData.from16BitBigEndian(bytes, config.sampleRate, config.channels)
            Encoding.S24 -> AudioPcmData.from24Bit(bytes, config.sampleRate, config.channels, !config.littleEndian)
            Encoding.F32 -> {
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(if (config.littleEndian) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
                while (buffer.remaining() >= 4) require(buffer.float.isFinite()) { "Non-finite float samples; choose a different PCM format" }
                AudioPcmData.from32BitFloat(bytes, config.sampleRate, config.channels, !config.littleEndian)
            }
            Encoding.S32 -> {
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(if (config.littleEndian) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
                AudioPcmData(config.sampleRate, config.channels, ShortArray(bytes.size / 4) { (buffer.int shr 16).toShort() })
            }
        }
    }
    fun envelope(pcm: AudioPcmData, columns: Int = 1024): Envelope {
        val frames = pcm.samples.size / pcm.channels
        require(frames > 0 && columns > 0) { "No complete sample frames" }
        val buckets = minOf(columns, frames)
        val lo = List(pcm.channels) { FloatArray(buckets) { 1f } }
        val hi = List(pcm.channels) { FloatArray(buckets) { -1f } }
        for (frame in 0 until frames) {
            val bucket = (frame.toLong() * buckets / frames).toInt()
            for (ch in 0 until pcm.channels) {
                val sample = pcm.samples[frame * pcm.channels + ch] / 32768f
                lo[ch][bucket] = minOf(lo[ch][bucket], sample); hi[ch][bucket] = maxOf(hi[ch][bucket], sample)
            }
        }
        return Envelope(lo, hi, frames)
    }
    data class Detection(val config: Config, val description: String, val certain: Boolean)
    fun detect(prefix: ByteArray, total: Long): List<Detection> {
        fun text(at: Int, length: Int) = if (at >= 0 && at + length <= prefix.size) String(prefix, at, length, Charsets.US_ASCII) else ""
        fun u16(at: Int) = (prefix[at].toInt() and 255) or ((prefix[at + 1].toInt() and 255) shl 8)
        fun u32(at: Int) = (0..3).fold(0L) { value, i -> value or ((prefix[at + i].toLong() and 255) shl (i * 8)) }
        if (prefix.size >= 12 && text(0,4) == "RIFF" && text(8,4) == "WAVE") {
            var at = 12L; var format: Config? = null
            while (at + 8 <= prefix.size) {
                val p = at.toInt(); val size = u32(p + 4); val body = p + 8
                if (text(p,4) == "fmt " && size >= 16 && body + 16 <= prefix.size) {
                    val tag = u16(body); val bits = u16(body + 14)
                    val encoding = if (tag == 3 && bits == 32) Encoding.F32 else if (tag == 1) when(bits) {
                        8 -> Encoding.U8; 16 -> Encoding.S16; 24 -> Encoding.S24; 32 -> Encoding.S32; else -> null
                    } else null
                    if (encoding != null) format = Config(sampleRate = u32(body + 4).toInt(), channels = u16(body + 2), encoding = encoding)
                }
                if (text(p,4) == "data" && format != null) {
                    val config = format.copy(offset = body.toLong(), byteLength = size)
                    if (runCatching { config.validate(); body <= total && size <= total - body }.getOrDefault(false))
                        return listOf(Detection(config, "WAVE header: ${config.encoding.title}, ${config.channels} channel(s), ${config.sampleRate} Hz", true))
                }
                at += 8 + size + (size and 1)
            }
        }
        if (total == 0L) return emptyList()
        return listOf(Config(), Config(sampleRate=44100, encoding=Encoding.S16), Config(sampleRate=44100, channels=2, encoding=Encoding.S16))
            .map { Detection(it, "Guess: ${it.encoding.title}, ${it.channels} channel(s), ${it.sampleRate} Hz", false) }
    }
}
