package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SunAuDecoder {
    private const val AU_MAGIC = 0x2E736E64 // ".snd"

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 24) return false
        val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        return magic == AU_MAGIC
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bb.int // skip magic
        val dataOffset = bb.int
        val dataSize = bb.int
        val encoding = bb.int
        val sampleRate = bb.int
        val channels = bb.int

        if (sampleRate <= 0 || channels !in 1..8 || dataOffset !in 24 until bytes.size) return null
        val width = when (encoding) { 1, 2, 27 -> 1; 3 -> 2; 4 -> 3; 5, 6 -> 4; else -> return null }
        val availableDataLen = if (dataSize == -1) bytes.size - dataOffset else dataSize
        if (availableDataLen <= 0 || availableDataLen > bytes.size - dataOffset ||
            availableDataLen % (width * channels) != 0) return null

        return when (encoding) {
            1 -> { // 8-bit ISDN u-law
                AudioPcmData.decodeUlaw(bytes, sampleRate, channels, dataOffset, availableDataLen)
            }
            2 -> { // 8-bit linear PCM (signed)
                val sub = bytes.copyOfRange(dataOffset, dataOffset + availableDataLen)
                AudioPcmData.from8BitSigned(sub, sampleRate, channels)
            }
            3 -> { // 16-bit linear PCM (big-endian)
                AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, dataOffset, availableDataLen)
            }
            4 -> { // 24-bit linear PCM (big-endian)
                AudioPcmData.from24Bit(bytes, sampleRate, channels, bigEndian = true, offset = dataOffset, length = availableDataLen)
            }
            5 -> { // 32-bit linear PCM (big-endian)
                val sampleCount = availableDataLen / 4
                val samples = ShortArray(sampleCount)
                val sbb = ByteBuffer.wrap(bytes, dataOffset, availableDataLen).order(ByteOrder.BIG_ENDIAN)
                for (i in 0 until sampleCount) {
                    samples[i] = (sbb.int shr 16).toShort()
                }
                AudioPcmData(sampleRate, channels, samples, 16)
            }
            6 -> { // 32-bit IEEE float
                AudioPcmData.from32BitFloat(bytes, sampleRate, channels, bigEndian = true, offset = dataOffset, length = availableDataLen)
            }
            27 -> { // 8-bit ISDN A-law
                AudioPcmData.decodeAlaw(bytes, sampleRate, channels, dataOffset, availableDataLen)
            }
            else -> null
        }
    }
}
