package com.infinifolder.audio.chiptune

import java.io.IOException

/** Optional NSFe time/fade chunks appended to an NSF v1/v2 program (NESdev NSF2 layout). */
data class NsfTrackTiming(val playMs: Int, val fadeMs: Int) {
    val durationMs: Long get() = playMs.toLong()+fadeMs

    companion object {
        fun read(bytes: ByteArray): NsfTrackTiming? {
            if (bytes.size < 128 || String(bytes,0,5,Charsets.ISO_8859_1) != "NESM\u001a") return null
            fun uint(at: Int, count: Int): Long {
                var value = 0L
                repeat(count) { value = value or ((bytes[at+it].toLong() and 255) shl (it*8)) }
                return value
            }
            val size = uint(125,3).toInt()
            if (size == 0) return null
            if (size > bytes.size-128) throw IOException("Truncated NSF program")
            var at = 128+size
            if (at == bytes.size) return null
            val track = (bytes[7].toInt() and 255)-1
            if (track !in 0 until (bytes[6].toInt() and 255)) throw IOException("Invalid NSF starting track")
            var time: Int? = null; var fade: Int? = null; var chunks = 0
            while (at < bytes.size) {
                if (++chunks > 4096 || bytes.size-at < 8) throw IOException("Invalid NSF metadata chunks")
                val length = uint(at,4)
                val id = String(bytes,at+4,4,Charsets.US_ASCII)
                at += 8
                if (length > bytes.size-at) throw IOException("Truncated NSF metadata")
                when (id) {
                    "time", "fade" -> if (length >= (track+1)*4L) {
                        val value = uint(at+track*4,4).toInt()
                        if (id == "time") time=value else fade=value
                    }
                    "NEND" -> {
                        val play = time?.takeIf { it >= 0 } ?: return null
                        val tail = fade?.takeIf { it >= 0 } ?: 8000
                        if (play.toLong()+tail !in 1..86_400_000) throw IOException("NSF timing exceeds limit")
                        return NsfTrackTiming(play,tail)
                    }
                    "INFO", "DATA", "BANK", "NSF2" -> throw IOException("Invalid NSF appended chunk: $id")
                    else -> if ((bytes[5].toInt() and 255) >= 2 && bytes[124].toInt() and 128 != 0 && id[0] in 'A'..'Z')
                        throw IOException("Unsupported mandatory NSF chunk: $id")
                }
                at += length.toInt()
            }
            throw IOException("Missing NSF metadata terminator")
        }
    }
}
