package com.infinifolder.audio.diagnostics

enum class AudioHealthStatus {
    HEALTHY,
    SILENT,
    CLIPPING,
    DC_OFFSET,
    SUSPICIOUS_SHORT,
    DECODE_FAILED,
    PLAYBACK_ERROR
}

data class AudioSignalMetrics(
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val totalFrames: Int,
    val minSample: Short,
    val maxSample: Short,
    val peakAmplitude: Float,
    val rmsDb: Float,
    val dcOffset: Float,
    val isSilent: Boolean,
    val isClipping: Boolean,
    val health: AudioHealthStatus
) {
    override fun toString(): String {
        return "AudioSignalMetrics(sr=${sampleRate}Hz, ch=$channels, dur=${durationMs}ms, peak=${"%.2f".format(peakAmplitude)}, rms=${"%.1f".format(rmsDb)}dB, silent=$isSilent, clip=$isClipping, health=$health)"
    }
}

