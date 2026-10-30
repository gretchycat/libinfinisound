package com.infinifolder.audio

data class AudioFormatInfo(
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitrateKbps: Int = 0,
    val isBitrateRelevant: Boolean = true,
    val isSampleRateRelevant: Boolean = true
)

