package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CafDecoder {
    private const val CAF_MAGIC = "caff"

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        return magic == CAF_MAGIC
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null
        var pos = 8 // Skip file header: caff (4) + version (2) + flags (2)

        var sampleRate = 44100
        var formatId = "lpcm"
        var formatFlags = 0
        var channels = 0
        var bitsPerChannel = 16

        var dataOffset = -1
        var dataSize = 0L

        while (pos + 12 <= bytes.size) {
            val chunkType = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 8).order(ByteOrder.BIG_ENDIAN).long
            val chunkDataPos = pos + 12
            if (chunkSize < 0 && !(chunkType == "data" && chunkSize == -1L)) return null
            if (chunkSize >= 0 && chunkSize > bytes.size - chunkDataPos) return null
            val nextPos = if (chunkSize >= 0) chunkDataPos + chunkSize.toInt() else bytes.size

            when (chunkType) {
                "desc" -> {
                    if (chunkSize >= 32) {
                        val bb = ByteBuffer.wrap(bytes, chunkDataPos, 32).order(ByteOrder.BIG_ENDIAN)
                        val srDbl = bb.double
                        if (!srDbl.isFinite() || srDbl <= 0 || srDbl > Int.MAX_VALUE) return null
                        sampleRate = srDbl.toInt()
                        val fmtBytes = ByteArray(4)
                        bb.get(fmtBytes)
                        formatId = String(fmtBytes, Charsets.US_ASCII)
                        formatFlags = bb.int
                        val bytesPerPacket = bb.int
                        val framesPerPacket = bb.int
                        channels = bb.int
                        bitsPerChannel = bb.int
                    }
                }
                "data" -> {
                    // First 4 bytes of data chunk is editCount
                    if (chunkSize == -1L || chunkSize >= 4) {
                        dataOffset = chunkDataPos + 4
                        dataSize = if (chunkSize >= 4) chunkSize - 4 else (bytes.size - dataOffset).toLong()
                    }
                    break
                }
            }

            pos = nextPos.coerceAtLeast(pos + 12)
        }

        if (dataOffset < 0 || dataOffset >= bytes.size || channels !in 1..8) return null
        if (formatId !in setOf("lpcm", "ulaw", "alaw")) return null
        if (formatId == "lpcm" && (bitsPerChannel !in setOf(8, 16, 24, 32) ||
                (formatFlags and 1 != 0 && bitsPerChannel != 32))) return null
        val width = if (formatId == "lpcm") bitsPerChannel / 8 else 1
        if (dataSize <= 0 || dataSize % (width * channels) != 0L) return null
        val len = (if (dataSize > 0) dataSize.toInt() else (bytes.size - dataOffset)).coerceAtMost(bytes.size - dataOffset)

        val isLittleEndian = (formatFlags and 0x02) != 0
        val isFloat = (formatFlags and 0x01) != 0

        if (formatId != "lpcm") {
            // Check if ulaw or alaw
            if (formatId == "ulaw") return AudioPcmData.decodeUlaw(bytes, sampleRate, channels, dataOffset, len)
            if (formatId == "alaw") return AudioPcmData.decodeAlaw(bytes, sampleRate, channels, dataOffset, len)
        }

        if (isFloat && bitsPerChannel == 32) {
            return AudioPcmData.from32BitFloat(bytes, sampleRate, channels, bigEndian = !isLittleEndian, offset = dataOffset, length = len)
        }

        return when (bitsPerChannel) {
            8 -> {
                val sub = bytes.copyOfRange(dataOffset, dataOffset + len)
                AudioPcmData.from8BitSigned(sub, sampleRate, channels)
            }
            16 -> {
                if (isLittleEndian) {
                    AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, len)
                } else {
                    AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, dataOffset, len)
                }
            }
            24 -> {
                AudioPcmData.from24Bit(bytes, sampleRate, channels, bigEndian = !isLittleEndian, offset = dataOffset, length = len)
            }
            32 -> {
                val sampleCount = len / 4
                val samples = ShortArray(sampleCount)
                val bb = ByteBuffer.wrap(bytes, dataOffset, len).order(if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                for (i in 0 until sampleCount) {
                    samples[i] = (bb.int shr 16).toShort()
                }
                AudioPcmData(sampleRate, channels, samples, 16)
            }
            else -> null
        }
    }
}
