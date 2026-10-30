package com.infinifolder.audio.stream

import com.infinifolder.audio.PcmFormat
import com.infinifolder.audio.diagnostics.*
import kotlin.math.*

/** Same health thresholds as buffered analysis, using constant memory. */
internal class PcmSignalAccumulator {
    private var count = 0L
    private var minimum = 0
    private var maximum = 0
    private var squares = 0.0
    private var sum = 0.0
    private var clipped = 0L
    fun add(samples: IntArray, size: Int) {
        for (i in 0 until size) {
            val value = samples[i] shr 16
            minimum = minOf(minimum, value); maximum = maxOf(maximum, value)
            squares += value.toDouble() * value; sum += value
            if (value <= -32760 || value >= 32760) clipped++
        }
        count += size
    }
    fun finish(format: PcmFormat): AudioSignalMetrics {
        val peak = (maxOf(abs(minimum), abs(maximum)) / 32768f).coerceIn(0f, 1f)
        val rms = if (count > 0) sqrt(squares / count) else 0.0
        val db = if (rms > 0.001) (20 * log10(rms / 32768)).toFloat().coerceIn(-100f, 0f) else -100f
        val dc = if (count > 0) (sum / (count * 32768.0)).toFloat() else 0f
        val silent = peak < 0.005f || db < -60f
        val clipping = count > 0 && clipped.toDouble() / count > 0.01
        val duration = count / format.channels * 1000 / format.sampleRate
        val health = when {
            silent -> AudioHealthStatus.SILENT
            clipping -> AudioHealthStatus.CLIPPING
            abs(dc) > 0.15f -> AudioHealthStatus.DC_OFFSET
            duration < 100 -> AudioHealthStatus.SUSPICIOUS_SHORT
            else -> AudioHealthStatus.HEALTHY
        }
        return AudioSignalMetrics(format.sampleRate, format.channels, duration,
            (count / format.channels).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), minimum.toShort(), maximum.toShort(), peak, db, dc, silent, clipping, health)
    }
}
