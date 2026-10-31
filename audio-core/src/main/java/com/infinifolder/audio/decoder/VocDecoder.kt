package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.io.ByteArrayOutputStream

object VocDecoder {
    private const val VOC_MAGIC = "Creative Voice File\u001A"

    fun canDecode(bytes: ByteArray): Boolean = bytes.size >= 26 &&
        String(bytes, 0, 20, Charsets.ISO_8859_1) == VOC_MAGIC

    private data class Format(val rate: Int, val channels: Int, val bits: Int)

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null
        fun u8(offset: Int) = bytes[offset].toInt() and 255
        fun u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
        val headerLen = u16(20)
        if (headerLen !in 26 until bytes.size) return null
        var pos = headerLen
        var extended: Format? = null
        var current: Format? = null
        var outputFormat: Format? = null
        val output = ByteArrayOutputStream()

        while (pos < bytes.size) {
            val type = u8(pos++)
            if (type == 0) break
            if (pos + 3 > bytes.size) return null
            val size = u16(pos) or (u8(pos + 2) shl 16)
            pos += 3
            if (size > bytes.size - pos) return null
            val end = pos + size
            when (type) {
                1 -> {
                    if (size < 2 || u8(pos + 1) != 0) return null // Only unpacked legacy PCM.
                    current = extended ?: Format(1_000_000 / (256 - u8(pos)), 1, 8)
                    extended = null
                    pos += 2
                }
                2 -> if (current == null) return null // Continuation requires a data format.
                8 -> {
                    if (size != 4 || u8(pos + 2) != 0 || u8(pos + 3) !in 0..1) return null
                    val channels = u8(pos + 3) + 1
                    extended = Format((256_000_000L / (channels * (65536 - u16(pos)))).toInt(), channels, 8)
                    pos = end
                    continue
                }
                9 -> {
                    if (size < 12) return null
                    val rate = u16(pos) or (u16(pos + 2) shl 16)
                    val bits = u8(pos + 4)
                    val channels = u8(pos + 5)
                    val codec = u16(pos + 6)
                    if (!(bits == 8 && codec == 0 || bits == 16 && codec == 4)) return null
                    if (rate <= 0 || channels !in 1..8) return null
                    current = Format(rate, channels, bits)
                    pos += 12
                }
                4, 5 -> { pos = end; continue } // Marker and text metadata.
                else -> return null // Silence, loops and packed codecs need actual implementations.
            }
            val format = current ?: return null
            if (outputFormat != null && outputFormat != format) return null
            outputFormat = format
            output.write(bytes, pos, end - pos)
            pos = end
        }
        if (extended != null) return null
        val format = outputFormat ?: return null
        val pcm = output.toByteArray()
        if (pcm.isEmpty() || pcm.size % (format.channels * (format.bits / 8)) != 0) return null
        return if (format.bits == 16) AudioPcmData.from16BitLittleEndian(pcm, format.rate, format.channels)
            else AudioPcmData.from8BitUnsigned(pcm, format.rate, format.channels)
    }
}
