package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData

/** Compatibility entry point. A real decoder is required before reporting support. */
@Suppress("UNUSED_PARAMETER")
object WmaDecoder {
    fun canDecode(bytes: ByteArray, extension: String? = null): Boolean = false
    fun decode(bytes: ByteArray): AudioPcmData? = null
}
