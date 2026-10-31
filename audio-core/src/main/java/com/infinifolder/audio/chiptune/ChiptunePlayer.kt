package com.infinifolder.audio.chiptune

import com.infinifolder.audio.model.AudioPcmData

/** Compatibility entry point. A real decoder is required before reporting support. */
@Suppress("UNUSED_PARAMETER")
object ChiptunePlayer {
    fun canDecode(bytes: ByteArray): Boolean = false
    fun renderToPcm(rawBytes: ByteArray, maxDurationMs: Long = 3000L): AudioPcmData? = null
}
