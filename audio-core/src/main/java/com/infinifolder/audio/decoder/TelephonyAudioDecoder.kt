package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

object TelephonyAudioDecoder {

    // AMR/GSM/G.722 are left to registered codecs; there is no substitute tone.
    fun canDecode(bytes: ByteArray, extension: String? = null): Boolean =
        bytes.size >= 36 && (bytes[0].toInt() and 0xFF) == 0x80 && bytes[1] == 0.toByte()

    fun decode(bytes: ByteArray, extension: String? = null): AudioPcmData? =
        if (canDecode(bytes, extension)) decodeAdx(bytes) else null

    private fun decodeAdx(bytes: ByteArray): AudioPcmData? {
        if (bytes.size < 36) return null
        val copyrightOffset = ByteBuffer.wrap(bytes, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        val channels = (bytes[7].toInt() and 0xFF).coerceIn(1, 2)
        val sampleRate = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        val totalSamples = ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.BIG_ENDIAN).int
        val cutoff = ByteBuffer.wrap(bytes, 16, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

        val sr = if (sampleRate > 0) sampleRate else 44100
        val highPass = if (cutoff in 100..4000) cutoff.toDouble() else 500.0

        // Compute 2nd-order AR filter coefficients
        val a = sqrt(2.0) - cos(2.0 * Math.PI * highPass / sr)
        val b = sqrt(2.0) - 1.0
        val c = (a - sqrt(((a + b) * (a - b)).coerceAtLeast(0.0))) / b
        val bits = 12
        val coeff0 = (c * 2.0 * (1 shl bits)).roundToInt()
        val coeff1 = (-(c * c) * (1 shl bits)).roundToInt()

        val dataStart = copyrightOffset + 4
        if (dataStart >= bytes.size) return null

        val sampleCount = if (totalSamples in 1..20000000) totalSamples else (bytes.size - dataStart) * 32 / (18 * channels)
        val outSamples = ShortArray(sampleCount * channels)

        var inPos = dataStart
        var outSampleIdx = 0

        val s1 = IntArray(channels)
        val s2 = IntArray(channels)

        // Stereo ADX arranges data as 18 bytes for Channel 0 followed by 18 bytes for Channel 1
        while (inPos + 18 * channels <= bytes.size && outSampleIdx < sampleCount) {
            for (ch in 0 until channels) {
                val blockOffset = inPos + ch * 18
                val scale = ((bytes[blockOffset].toInt() and 0xFF) shl 8) or (bytes[blockOffset + 1].toInt() and 0xFF)
                if ((scale and 0x8000) != 0) break // EOF packet

                for (sampleInBlock in 0 until 32) {
                    val byteIdx = blockOffset + 2 + (sampleInBlock / 2)
                    val rawByte = bytes[byteIdx].toInt()
                    val nibble = if ((sampleInBlock % 2) == 0) (rawByte shr 4) and 0x0F else rawByte and 0x0F
                    val signedNibble = if (nibble >= 8) nibble - 16 else nibble

                    val d = signedNibble * scale
                    val s0 = d + ((coeff0 * s1[ch] + coeff1 * s2[ch]) shr bits)
                    s2[ch] = s1[ch]
                    s1[ch] = s0.coerceIn(-32768, 32767)

                    val targetIdx = (outSampleIdx + sampleInBlock) * channels + ch
                    if (targetIdx < outSamples.size) {
                        outSamples[targetIdx] = s1[ch].toShort()
                    }
                }
            }

            inPos += 18 * channels
            outSampleIdx += 32
        }

        return AudioPcmData(sr, channels, outSamples, 16)
    }

}
