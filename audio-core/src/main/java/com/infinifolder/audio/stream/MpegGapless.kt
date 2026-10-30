package com.infinifolder.audio.stream

import com.infinifolder.audio.AudioInput

/** Xing/Info frame counts and LAME-compatible delay/padding; all offsets are bounded to that frame. */
internal object MpegGapless {
    data class Trim(val skip: Long, val total: Long?, val sampleRate: Int, val headerSamples: Long)
    fun read(input: AudioInput): Trim? = input.open().use { source ->
        val r = ByteReader(source)
        if (source.size < 4) return null
        var position = 0L
        if (source.size >= 10 && r.text(0, 3) == "ID3") {
            val header = r.bytes(0, 10)
            var size = 0L
            for (i in 6..9) {
                if (header[i].toInt() and 128 != 0) return null
                size = (size shl 7) or (header[i].toLong() and 127)
            }
            position = 10 + size + if (header[5].toInt() and 16 != 0) 10 else 0
        }
        val limit = minOf(source.size - 4, position + 65536)
        while (position <= limit) {
            val h = r.bytes(position, 4).map { it.toInt() and 255 }
            val version = h[1] ushr 3 and 3
            val bitrateCode = h[2] ushr 4
            val rateCode = h[2] ushr 2 and 3
            if (h[0] == 255 && h[1] and 0xe0 == 0xe0 && h[1] ushr 1 and 3 == 1 &&
                version != 1 && bitrateCode in 1..14 && rateCode != 3) {
                val rate = intArrayOf(44100,48000,32000)[rateCode] / when(version) { 3 -> 1; 2 -> 2; else -> 4 }
                val kbps = if (version == 3) intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320)[bitrateCode]
                    else intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160)[bitrateCode]
                val frameSize = (if (version == 3) 144000 else 72000) * kbps / rate + (h[2] ushr 1 and 1)
                if (position + frameSize > source.size) return null
                val mono = h[3] ushr 6 == 3
                val side = if (version == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
                val tag = position + 4 + (if (h[1] and 1 == 0) 2 else 0) + side
                if (tag + 8 > position + frameSize || r.text(tag, 4) !in setOf("Xing","Info")) return null
                val flags = r.uint(tag + 4, 4).toInt()
                var cursor = tag + 8
                val frames = if (flags and 1 != 0) r.uint(cursor, 4).also { cursor += 4 } else null
                if (flags and 2 != 0) cursor += 4
                if (flags and 4 != 0) cursor += 100
                if (flags and 8 != 0) cursor += 4
                val samples = if (version == 3) 1152L else 576L
                if (frames == null || frames == 0L) return Trim(samples, null, rate, samples)
                if (cursor + 24 > position + frameSize || r.text(cursor, 4) !in setOf("LAME","Lavc","Lavf"))
                    return Trim(samples, frames * samples, rate, samples)
                val packed = r.uint(cursor + 21, 3).toInt()
                val delay = packed ushr 12
                val padding = packed and 4095
                val total = frames * samples - delay - padding
                if (total <= 0) return null
                // Layer III's synthesis/filterbank delay is 528 samples plus one overlap sample.
                // Cap the output by the tag's exact musical length; trailing padding is not inferred from silence.
                return Trim(samples + delay + 529L, total, rate, samples)
            }
            position++
        }
        null
    }
}
