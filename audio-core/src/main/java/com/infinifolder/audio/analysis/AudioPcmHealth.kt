package com.infinifolder.audio.analysis

import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.diagnostics.AudioHealthStatus
import com.infinifolder.audio.diagnostics.AudioSignalMetrics
import kotlin.math.log10
import kotlin.math.sqrt

object AudioPcmHealth {
    fun analyze(pcm: AudioPcmData): AudioSignalMetrics {
        val samples = pcm.samples
        val channels = pcm.channels.coerceAtLeast(1)
        val sampleRate = pcm.sampleRate.coerceAtLeast(1)
        val totalFrames = samples.size / channels
        val durationMs = pcm.durationMs

        if (samples.isEmpty() || totalFrames == 0) {
            return AudioSignalMetrics(
                sampleRate = sampleRate,
                channels = channels,
                durationMs = 0L,
                totalFrames = 0,
                minSample = 0,
                maxSample = 0,
                peakAmplitude = 0f,
                rmsDb = -100f,
                dcOffset = 0f,
                isSilent = true,
                isClipping = false,
                health = AudioHealthStatus.SILENT
            )
        }

        var min = 0
        var max = 0
        var sumSquares = 0.0
        var sumSamples = 0.0
        var clipCount = 0

        for (s in samples) {
            val v = s.toInt()
            if (v < min) min = v
            if (v > max) max = v
            sumSquares += (v * v).toDouble()
            sumSamples += v.toDouble()
            if (v <= -32760 || v >= 32760) {
                clipCount++
            }
        }

        val peakInt = Math.max(Math.abs(min), Math.abs(max))
        val peakAmplitude = (peakInt / 32768.0f).coerceIn(0f, 1f)

        val meanSquare = sumSquares / samples.size
        val rms = sqrt(meanSquare)
        val rmsDb = if (rms > 0.001) {
            (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-100f, 0f)
        } else {
            -100f
        }

        val dcOffset = (sumSamples / (samples.size * 32768.0)).toFloat()
        val isSilent = peakAmplitude < 0.005f || rmsDb < -60f
        val isClipping = (clipCount.toFloat() / samples.size) > 0.01f // more than 1% clipping

        val health = when {
            isSilent -> AudioHealthStatus.SILENT
            isClipping -> AudioHealthStatus.CLIPPING
            Math.abs(dcOffset) > 0.15f -> AudioHealthStatus.DC_OFFSET
            durationMs < 100L -> AudioHealthStatus.SUSPICIOUS_SHORT
            else -> AudioHealthStatus.HEALTHY
        }

        return AudioSignalMetrics(
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            totalFrames = totalFrames,
            minSample = min.toShort(),
            maxSample = max.toShort(),
            peakAmplitude = peakAmplitude,
            rmsDb = rmsDb,
            dcOffset = dcOffset,
            isSilent = isSilent,
            isClipping = isClipping,
            health = health
        )
    }

}
